package org.miosys.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.miosys.transport.http.ApiTestBase;

/** Spec section 13.2: the door rejects uniformly with 401. */
class DoorTest extends ApiTestBase {

    private static final String DOOR = "{\"error\":\"unauthorized - missing or invalid API key\"}";

    @Test
    void allRejectionsAreByteIdentical401() throws Exception {
        String op = bootstrapKey("Owner");
        // Second key, then disabled.
        var issued =
                client.req("POST", "/api/admin/tenants/1/keys", op, "{\"label\":\"temp\"}");
        assertEquals(201, issued.status());
        String spare = issued.json().get("apiKey").asText();
        int spareId = issued.json().get("keyId").asInt();
        var disabled =
                client.req("PATCH", "/api/admin/keys/" + spareId, op, "{\"isEnabled\":false}");
        assertEquals(200, disabled.status());
        // Disabled tenant.
        String tenant2 = createTenantKey(op, "Two");
        var off =
                client.req("PATCH", "/api/admin/tenants/2", op, "{\"isEnabled\":false}");
        assertEquals(200, off.status());

        List<String> bodies = new ArrayList<>();
        bodies.add(check("GET", "/api/settings", null).rawText());
        bodies.add(check("GET", "/api/settings", "Basic abc").rawText());
        bodies.add(check("GET", "/api/settings", "Bearer").rawText());
        bodies.add(check("GET", "/api/settings", "Bearer ").rawText());
        bodies.add(check("GET", "/api/settings", "Bearer    ").rawText());
        bodies.add(check("GET", "/api/settings", "Bearer " + "z".repeat(64)).rawText());
        bodies.add(check("GET", "/api/settings", "Bearer " + spare).rawText());
        bodies.add(check("GET", "/api/settings", "Bearer " + tenant2).rawText());
        bodies.add(check("GET", "/api/does-not-exist", null).rawText());
        bodies.add(check("PUT", "/api/settings", null).rawText());
        for (String body : bodies) {
            assertEquals(DOOR, body);
        }
    }

    private TestClient.Resp check(String method, String target, String authHeader) throws Exception {
        var r = client.request(method, target, authHeader, null);
        assertEquals(401, r.status(), method + " " + target + " with header <" + authHeader + ">");
        return r;
    }

    @Test
    void unknownPathWithKeyIs404() throws Exception {
        String op = bootstrapKey("Owner");
        var r = client.req("GET", "/api/does-not-exist", op, null);
        assertEquals(404, r.status());
        assertEquals("not found", r.error());
    }

    @Test
    void nonApiUnknownPathSkipsDoor() throws Exception {
        var r = client.req("GET", "/nope", null, null);
        assertEquals(404, r.status());
        assertEquals("not found", r.error());
    }

    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        String op = bootstrapKey("Owner");
        var r = client.request("GET", "/api/settings", "bearer " + op, null);
        assertEquals(404, r.status(), "lowercase scheme opens the door, then 404 for missing settings");
    }
}
