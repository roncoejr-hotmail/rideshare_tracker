package org.miosys.transport.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.miosys.transport.auth.Door;
import org.miosys.transport.auth.KeyRepository;
import org.miosys.transport.auth.Principal;
import org.miosys.transport.auth.RequestContext;
import org.miosys.transport.auth.TenantRepository;
import org.miosys.transport.db.Database;
import org.miosys.transport.db.Migrations;
import org.miosys.transport.db.SqlScripts;
import org.miosys.transport.store.KeySelfService;
import org.miosys.transport.store.SqliteKeySelfService;
import org.miosys.transport.store.SqliteTenantAdmin;
import org.miosys.transport.store.SqliteTransportStore;
import org.miosys.transport.store.TenantAdmin;
import org.miosys.transport.store.TransportStore;

/**
 * Acceptance-test harness. Each test gets a fresh migrated database and drives the
 * production ApiHandler in-process through a fake exchange, since this sandbox
 * cannot listen on sockets. Every line of request handling is the shipped code.
 */
public abstract class ApiTestBase {

    protected static final String BUILD_DIR = System.getProperty("transport.buildDir", "target");

    protected String dbPath;
    protected Database database;
    protected TransportStore store;
    protected TenantAdmin admin;
    protected Router router;
    protected Door door;
    protected TestClient client;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(Paths.get(BUILD_DIR, "test-dbs"));
        Files.createDirectories(Paths.get(System.getProperty("java.io.tmpdir")));
        dbPath = Paths.get(BUILD_DIR, "test-dbs", UUID.randomUUID() + ".db").toString();
        database = new Database(dbPath);
        Migrations.run(database);
        store = new SqliteTransportStore(database);
        admin = new SqliteTenantAdmin(database);
        KeySelfService self = new SqliteKeySelfService(database);
        router = new Router();
        Routes.register(router, store, admin, self);
        door = new Door(new KeyRepository(database), new TenantRepository(database));
        client = new TestClient(new FakeHandler(router, door, Cors.disabled()));
    }

    protected void enableCors(String csv) {
        client =
                new TestClient(
                        new FakeHandler(
                                router,
                                door,
                                new Cors(org.miosys.transport.config.Config.corsOrigins(csv))));
    }

    // ---- fake transport ----

    static final class FakeHandler {
        final ThreadLocal<ApiHandler.RawTarget> next = new ThreadLocal<>();
        final ApiHandler handler;

        FakeHandler(Router router, Door door, Cors cors) {
            this.handler = new ApiHandler(router, door, ex -> next.get(), cors);
        }
    }

    static final class FakeExchange extends HttpExchange {
        private final String method;
        private final Headers reqHeaders = new Headers();
        private final Headers respHeaders = new Headers();
        private final InputStream reqBody;
        private final ByteArrayOutputStream respBody = new ByteArrayOutputStream();
        private int status = -1;
        private boolean closed;

        FakeExchange(String method, Map<String, String> headers, byte[] body) {
            this.method = method;
            headers.forEach((k, v) -> reqHeaders.add(k, v));
            this.reqBody = new ByteArrayInputStream(body);
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public URI getRequestURI() {
            return URI.create("/");
        }

        @Override
        public Headers getRequestHeaders() {
            return reqHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return respHeaders;
        }

        @Override
        public InputStream getRequestBody() {
            return reqBody;
        }

        @Override
        public OutputStream getResponseBody() {
            if (closed) {
                return new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        throw new IOException("stream is closed");
                    }
                };
            }
            return respBody;
        }

        @Override
        public void sendResponseHeaders(int code, long length) throws IOException {
            if (closed) {
                throw new IOException("stream is closed");
            }
            status = code;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {}

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public void setStreams(InputStream i, OutputStream o) {}

        @Override
        public int getResponseCode() {
            return status;
        }

        int status() {
            return status;
        }

        byte[] responseBytes() {
            return respBody.toByteArray();
        }
    }

    public static final class TestClient {
        public record Resp(int status, Headers headers, byte[] raw, JsonNode json) {
            public String error() {
                return json.has("error") ? json.get("error").asText() : null;
            }

            public String rawText() {
                return new String(raw, StandardCharsets.UTF_8);
            }
        }

        private final FakeHandler handler;

        TestClient(FakeHandler handler) {
            this.handler = handler;
        }

        /** Full header value (e.g. "Bearer abc"), or null for no header. */
        public Resp request(String method, String target, String authHeader, String body)
                throws Exception {
            Map<String, String> headers = new LinkedHashMap<>();
            if (authHeader != null) {
                headers.put("Authorization", authHeader);
            }
            return requestFull(method, target, headers, body);
        }

        /** Arbitrary request headers (for Origin / preflight tests). */
        public Resp requestFull(String method, String target, Map<String, String> headers, String body)
                throws Exception {
            String rawPath = target;
            String rawQuery = null;
            int q = target.indexOf('?');
            if (q >= 0) {
                rawPath = target.substring(0, q);
                rawQuery = target.substring(q + 1);
            }
            byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            FakeExchange ex = new FakeExchange(method, headers, bodyBytes);
            handler.next.set(new ApiHandler.RawTarget(rawPath, rawQuery));
            try {
                handler.handler.handle(ex);
            } finally {
                handler.next.remove();
            }
            byte[] raw = ex.responseBytes();
            JsonNode json = raw.length == 0 ? NullNode.getInstance() : Json.mapper().readTree(raw);
            return new Resp(ex.status(), ex.getResponseHeaders(), raw, json);
        }

        /** Convenience: apiKey is wrapped as "Bearer <key>"; null means no header. */
        public Resp req(String method, String target, String apiKey, String body) throws Exception {
            return request(method, target, apiKey == null ? null : "Bearer " + apiKey, body);
        }
    }

    // ---- helpers ----

    protected TestClient.Resp bootstrap(String name) throws Exception {
        return client.req("POST", "/api/bootstrap", null, "{\"name\":" + quote(name) + "}");
    }

    protected String bootstrapKey(String name) throws Exception {
        TestClient.Resp r = bootstrap(name);
        if (r.status() != 201) {
            throw new IllegalStateException("bootstrap failed: " + r.status() + " " + r.rawText());
        }
        return r.json().get("apiKey").asText();
    }

    protected String createTenantKey(String operatorKey, String name) throws Exception {
        TestClient.Resp r =
                client.req(
                        "POST", "/api/admin/tenants", operatorKey, "{\"name\":" + quote(name) + "}");
        if (r.status() != 201) {
            throw new IllegalStateException("createTenant failed: " + r.status() + " " + r.rawText());
        }
        return r.json().get("apiKey").asText();
    }

    protected void loadSql(String resource) throws Exception {
        String sql;
        try (var in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource: " + resource);
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection c = database.open();
                Statement st = c.createStatement()) {
            for (String stmt : SqlScripts.split(sql)) {
                st.execute(stmt);
            }
        }
    }

    protected <T> T as(long tenantId, long keyId, boolean operator, Callable<T> work) throws Exception {
        return ScopedValue.where(RequestContext.PRINCIPAL, new Principal(tenantId, keyId, operator))
                .call(() -> work.call());
    }

    protected static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Asserts a money field's decimal value. Wire format (exactly 2 places) is covered by raw-text checks. */
    protected static void assertMoney(JsonNode parent, String field, String expected) {
        assertMoney(parent, field, expected, field);
    }

    protected static void assertMoney(JsonNode parent, String field, String expected, String what) {
        JsonNode n = parent.get(field);
        org.junit.jupiter.api.Assertions.assertTrue(
                n != null && n.isBigDecimal(), what + ": expected decimal money, got " + n);
        org.junit.jupiter.api.Assertions.assertEquals(
                0, new java.math.BigDecimal(expected).compareTo(n.decimalValue()), what);
    }
}
