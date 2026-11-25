package org.miosys.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.miosys.transport.auth.Sha256;
import org.miosys.transport.db.Database;
import org.miosys.transport.db.Migrations;
import org.miosys.transport.http.ApiTestBase;

/** Spec section 13.1: bootstrap and health. */
class BootstrapTest extends ApiTestBase {

    @Test
    void healthNeedsNoAuth() throws Exception {
        var r = client.req("GET", "/health", null, null);
        assertEquals(200, r.status());
        assertEquals("ok", r.json().get("status").asText());
    }

    @Test
    void healthIgnoresBadAuth() throws Exception {
        var r = client.request("GET", "/health", "Bearer garbage", null);
        assertEquals(200, r.status());
    }

    @Test
    void bootstrapHappyPath() throws Exception {
        var r =
                client.req(
                        "POST",
                        "/api/bootstrap",
                        null,
                        "{\"name\":\"Ron\",\"keyLabel\":\"desktop\"}");
        assertEquals(201, r.status());
        assertEquals(1, r.json().get("tenantId").asInt());
        assertEquals("Ron", r.json().get("name").asText());
        assertEquals(true, r.json().get("isOperator").asBoolean());
        assertEquals(1, r.json().get("keyId").asInt());
        assertEquals("desktop", r.json().get("keyLabel").asText());
        String key = r.json().get("apiKey").asText();
        assertEquals(64, key.length());
        assertTrue(key.matches("[A-Za-z0-9_-]{64}"), "base64url without padding");

        var settings = client.req("GET", "/api/settings", key, null);
        assertEquals(404, settings.status());
        assertEquals("not found", settings.error());
    }

    @Test
    void bootstrapDefaultsKeyLabel() throws Exception {
        var r = client.req("POST", "/api/bootstrap", null, "{\"name\":\"Ron\"}");
        assertEquals(201, r.status());
        assertEquals("default", r.json().get("keyLabel").asText());
    }

    @Test
    void secondBootstrapIs404() throws Exception {
        assertEquals(201, bootstrap("Ron").status());
        var r = bootstrap("SomeoneElse");
        assertEquals(404, r.status());
        assertEquals("not found", r.error());
    }

    @Test
    void concurrentBootstrapYieldsExactlyOne201() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> job =
                    () -> client.req("POST", "/api/bootstrap", null, "{\"name\":\"Racer\"}").status();
            List<Future<Integer>> results = new ArrayList<>(pool.invokeAll(List.of(job, job)));
            long created = 0;
            long notFound = 0;
            for (Future<Integer> f : results) {
                int status = f.get();
                if (status == 201) {
                    created++;
                } else if (status == 404) {
                    notFound++;
                } else {
                    throw new IllegalStateException("unexpected status " + status);
                }
            }
            assertEquals(1, created);
            assertEquals(1, notFound);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void secretIsHashedNeverStored() throws Exception {
        String key = bootstrapKey("Ron");
        StringBuilder bytes = new StringBuilder();
        for (String suffix : List.of("", "-wal", "-shm")) {
            var path = Paths.get(dbPath + suffix);
            if (Files.exists(path)) {
                bytes.append(new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1));
            }
        }
        assertTrue(!bytes.toString().contains(key), "database bytes must not contain the secret");
        try (Connection c = database.open();
                var ps = c.prepareStatement("SELECT key_hash FROM tenant_key");
                var rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(Sha256.hex(key), rs.getString(1));
        }
    }

    @Test
    void bootstrapValidation() throws Exception {
        var missing = client.req("POST", "/api/bootstrap", null, "{}");
        assertEquals(400, missing.status());
        assertEquals("name is required", missing.error());

        var longName = client.req("POST", "/api/bootstrap", null, "{\"name\":\"" + "x".repeat(81) + "\"}");
        assertEquals(400, longName.status());
        assertEquals("name must be 1-80 characters", longName.error());

        var array = client.req("POST", "/api/bootstrap", null, "[1,2]");
        assertEquals(400, array.status());
        assertEquals("request body must be a JSON object", array.error());

        var empty = client.req("POST", "/api/bootstrap", null, "");
        assertEquals(400, empty.status());
        assertEquals("request body must be a JSON object", empty.error());
    }

    @Test
    void newerSchemaAbortsStartup() throws Exception {
        String path = BUILD_DIR + "/test-dbs/newer-" + System.nanoTime() + ".db";
        Database db = new Database(path);
        Migrations.run(db);
        try (Connection c = db.open();
                Statement st = c.createStatement()) {
            st.execute("PRAGMA user_version = 99");
        }
        assertThrows(IllegalStateException.class, () -> Migrations.run(db));
    }
}
