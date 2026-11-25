package org.miosys.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.miosys.transport.http.ApiTestBase;

/** Spec section 13.4: own-key rotation. */
class KeyRotationTest extends ApiTestBase {

    @Test
    void rotateSwapsOnlyThePresentingKey() throws Exception {
        String op = bootstrapKey("Owner");
        String a = createTenantKey(op, "Two");
        var issued = client.req("POST", "/api/admin/tenants/2/keys", op, "{\"label\":\"second\"}");
        String b = issued.json().get("apiKey").asText();
        int aId = 2; // bootstrap key 1, tenant-2 first key 2

        String replacement = "r".repeat(48);
        var rotated = client.req("PATCH", "/api/admin/tenant/key", a, "{\"apiKey\":\"" + replacement + "\"}");
        assertEquals(200, rotated.status());
        assertEquals(aId, rotated.json().get("keyId").asInt());
        assertTrue(!rotated.rawText().contains("apiKey"), "rotation must not echo the secret");

        assertEquals(401, client.req("GET", "/api/settings", a, null).status());
        assertEquals(404, client.req("GET", "/api/settings", replacement, null).status());
        assertEquals(404, client.req("GET", "/api/settings", b, null).status());
    }

    @Test
    void rotateRejectsShortSecrets() throws Exception {
        String op = bootstrapKey("Owner");
        var r = client.req("PATCH", "/api/admin/tenant/key", op, "{\"apiKey\":\"" + "s".repeat(31) + "\"}");
        assertEquals(400, r.status());
        assertEquals("apiKey must be 32-512 printable characters with no whitespace", r.error());
    }

    @Test
    void rotateRejectsSecretsAlreadyInUse() throws Exception {
        String op = bootstrapKey("Owner");
        String a = createTenantKey(op, "Two");
        var issued = client.req("POST", "/api/admin/tenants/2/keys", op, "{\"label\":\"second\"}");
        String b = issued.json().get("apiKey").asText();

        var reuse = client.req("PATCH", "/api/admin/tenant/key", a, "{\"apiKey\":\"" + b + "\"}");
        assertEquals(409, reuse.status());
        assertEquals("apiKey already in use", reuse.error());

        var same = client.req("PATCH", "/api/admin/tenant/key", a, "{\"apiKey\":\"" + a + "\"}");
        assertEquals(409, same.status());
        assertEquals("apiKey already in use", same.error());
    }

    @Test
    void rotateRejectsBadShapes() throws Exception {
        String op = bootstrapKey("Owner");
        String message = "apiKey must be 32-512 printable characters with no whitespace";
        assertEquals(message, client.req("PATCH", "/api/admin/tenant/key", op, "{}").error());
        assertEquals(400, client.req("PATCH", "/api/admin/tenant/key", op, "{}").status());
        var number = client.req("PATCH", "/api/admin/tenant/key", op, "{\"apiKey\":123}");
        assertEquals(400, number.status());
        assertEquals(message, number.error());
        var space =
                client.req(
                        "PATCH", "/api/admin/tenant/key", op, "{\"apiKey\":\"" + "x".repeat(31) + " \"}");
        assertEquals(400, space.status());
        assertEquals(message, space.error());
    }

    @Test
    void operatorCanRotateOwnKey() throws Exception {
        String op = bootstrapKey("Owner");
        String replacement = "o".repeat(40);
        var r = client.req("PATCH", "/api/admin/tenant/key", op, "{\"apiKey\":\"" + replacement + "\"}");
        assertEquals(200, r.status());
        assertEquals(1, r.json().get("keyId").asInt());
        assertEquals(401, client.req("GET", "/api/settings", op, null).status());
        assertEquals(404, client.req("GET", "/api/settings", replacement, null).status());
    }
}
