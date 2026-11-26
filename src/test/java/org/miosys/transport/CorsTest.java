package org.miosys.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.miosys.transport.http.ApiTestBase;

/** Browser cross-origin support, mirroring the grocery API contract. */
class CorsTest extends ApiTestBase {

    private static final String ORIGIN = "http://localhost:5173";

    private Map<String, String> originHeaders(String origin) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", origin);
        return headers;
    }

    private Map<String, String> authedOrigin(String key, String origin) {
        Map<String, String> headers = originHeaders(origin);
        headers.put("Authorization", "Bearer " + key);
        return headers;
    }

    @Test
    void preflightSucceedsWithoutAuth() throws Exception {
        enableCors(ORIGIN);
        Map<String, String> headers = originHeaders(ORIGIN);
        headers.put("Access-Control-Request-Method", "GET");
        var r = client.requestFull("OPTIONS", "/api/rides", headers, null);
        assertEquals(200, r.status());
        assertEquals(ORIGIN, r.headers().getFirst("Access-Control-Allow-Origin"));
        assertEquals("Origin", r.headers().getFirst("Vary"));
        assertEquals(
                "GET, PUT, PATCH, DELETE, POST, OPTIONS",
                r.headers().getFirst("Access-Control-Allow-Methods"));
        assertEquals(
                "Authorization, Content-Type", r.headers().getFirst("Access-Control-Allow-Headers"));
        assertEquals("3600", r.headers().getFirst("Access-Control-Max-Age"));
    }

    @Test
    void preflightUnknownPathStillShortCircuits() throws Exception {
        enableCors(ORIGIN);
        Map<String, String> headers = originHeaders(ORIGIN);
        headers.put("Access-Control-Request-Method", "GET");
        var r = client.requestFull("OPTIONS", "/api/nope", headers, null);
        assertEquals(200, r.status());
        assertEquals(ORIGIN, r.headers().getFirst("Access-Control-Allow-Origin"));
    }

    @Test
    void authedResponseCarriesCorsHeaders() throws Exception {
        enableCors(ORIGIN);
        String key = bootstrapKey("Owner");
        var r = client.requestFull("GET", "/api/rides", authedOrigin(key, ORIGIN), null);
        assertEquals(200, r.status());
        assertEquals(ORIGIN, r.headers().getFirst("Access-Control-Allow-Origin"));
        assertEquals("Origin", r.headers().getFirst("Vary"));
    }

    @Test
    void errorResponsesCarryCorsHeaders() throws Exception {
        enableCors(ORIGIN);
        String key = bootstrapKey("Owner");
        var denied = client.requestFull("GET", "/api/rides", originHeaders(ORIGIN), null);
        assertEquals(401, denied.status());
        assertEquals(ORIGIN, denied.headers().getFirst("Access-Control-Allow-Origin"));

        var missing = client.requestFull("GET", "/api/nope", authedOrigin(key, ORIGIN), null);
        assertEquals(404, missing.status());
        assertEquals(ORIGIN, missing.headers().getFirst("Access-Control-Allow-Origin"));

        Map<String, String> bad = authedOrigin(key, ORIGIN);
        var invalid = client.requestFull("GET", "/api/rides?size=999", bad, null);
        assertEquals(400, invalid.status());
        assertEquals(ORIGIN, invalid.headers().getFirst("Access-Control-Allow-Origin"));
    }

    @Test
    void disallowedOriginIsIgnored() throws Exception {
        enableCors(ORIGIN);
        String key = bootstrapKey("Owner");
        var r = client.requestFull("GET", "/api/rides", authedOrigin(key, "http://evil.example"), null);
        assertEquals(200, r.status());
        assertNull(r.headers().getFirst("Access-Control-Allow-Origin"));

        Map<String, String> preflight = originHeaders("http://evil.example");
        preflight.put("Access-Control-Request-Method", "GET");
        var blocked = client.requestFull("OPTIONS", "/api/rides", preflight, null);
        assertEquals(401, blocked.status(), "falls through to the door without CORS headers");
        assertNull(blocked.headers().getFirst("Access-Control-Allow-Origin"));
    }

    @Test
    void noOriginMeansNoHeaders() throws Exception {
        enableCors(ORIGIN);
        String key = bootstrapKey("Owner");
        var r = client.req("GET", "/api/rides", key, null);
        assertEquals(200, r.status());
        assertNull(r.headers().getFirst("Access-Control-Allow-Origin"));
    }

    @Test
    void corsOffByDefault() throws Exception {
        String key = bootstrapKey("Owner");
        var r = client.requestFull("GET", "/api/rides", authedOrigin(key, ORIGIN), null);
        assertEquals(200, r.status());
        assertNull(r.headers().getFirst("Access-Control-Allow-Origin"));

        Map<String, String> preflight = originHeaders(ORIGIN);
        preflight.put("Access-Control-Request-Method", "GET");
        var options = client.requestFull("OPTIONS", "/api/rides", preflight, null);
        assertEquals(401, options.status(), "no short-circuit when CORS is off");
    }

    @Test
    void nonApiPathsHaveNoCorsHeaders() throws Exception {
        enableCors(ORIGIN);
        var r = client.requestFull("GET", "/health", originHeaders(ORIGIN), null);
        assertEquals(200, r.status());
        assertNull(r.headers().getFirst("Access-Control-Allow-Origin"));
    }

    @Test
    void originListParsing() throws Exception {
        enableCors("  http://a.example ,, http://b.example  ");
        String key = bootstrapKey("Owner");
        var a = client.requestFull("GET", "/api/rides", authedOrigin(key, "http://a.example"), null);
        assertEquals("http://a.example", a.headers().getFirst("Access-Control-Allow-Origin"));
        var b = client.requestFull("GET", "/api/rides", authedOrigin(key, "http://b.example"), null);
        assertEquals("http://b.example", b.headers().getFirst("Access-Control-Allow-Origin"));
    }
}
