package org.miosys.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.miosys.transport.http.ApiException;
import org.miosys.transport.http.ApiTestBase;

/** Spec section 13.3: the operator boundary. */
class TenantAdminTest extends ApiTestBase {

    @Test
    void nonOperatorGets403BeforeValidation() throws Exception {
        String op = bootstrapKey("Owner");
        String t2 = createTenantKey(op, "Two");
        var badBody = client.req("POST", "/api/admin/tenants", t2, "{oops");
        assertEquals(403, badBody.status());
        assertEquals("forbidden", badBody.error());
        var list = client.req("GET", "/api/admin/tenants", t2, null);
        assertEquals(403, list.status());
        var patch = client.req("PATCH", "/api/admin/tenants/1", t2, "{\"name\":\"x\"}");
        assertEquals(403, patch.status());
        var issue = client.req("POST", "/api/admin/tenants/1/keys", t2, "{\"label\":\"x\"}");
        assertEquals(403, issue.status());
        var disable = client.req("PATCH", "/api/admin/keys/1", t2, "{\"isEnabled\":false}");
        assertEquals(403, disable.status());
    }

    @Test
    void createTenantAndListHidesSecrets() throws Exception {
        String op = bootstrapKey("Owner");
        var created =
                client.req("POST", "/api/admin/tenants", op, "{\"name\":\"Two\",\"keyLabel\":\"agent\"}");
        assertEquals(201, created.status());
        assertEquals(2, created.json().get("tenantId").asInt());
        assertEquals(false, created.json().get("isOperator").asBoolean());
        assertEquals("agent", created.json().get("keyLabel").asText());
        assertEquals(64, created.json().get("apiKey").asText().length());

        var list = client.req("GET", "/api/admin/tenants", op, null);
        assertEquals(200, list.status());
        assertEquals(2, list.json().size());
        assertEquals(1, list.json().get(0).get("tenantId").asInt());
        assertEquals(2, list.json().get(1).get("tenantId").asInt());
        assertTrue(list.json().get(0).get("keys").size() >= 1);
        String raw = list.rawText();
        assertTrue(!raw.contains("apiKey"), "list must not leak secrets");
        assertTrue(!raw.contains("key_hash") && !raw.contains("keyHash"), "list must not leak hashes");
    }

    @Test
    void cannotDisableOwnTenant() throws Exception {
        String op = bootstrapKey("Owner");
        var r = client.req("PATCH", "/api/admin/tenants/1", op, "{\"isEnabled\":false}");
        assertEquals(409, r.status());
        assertEquals("cannot disable your own tenant", r.error());
    }

    @Test
    void cannotDisableKeyInUse() throws Exception {
        String op = bootstrapKey("Owner");
        var r = client.req("PATCH", "/api/admin/keys/1", op, "{\"isEnabled\":false}");
        assertEquals(409, r.status());
        assertEquals("cannot disable the key in use", r.error());
    }

    @Test
    void twoKeyDisableDance() throws Exception {
        String a = bootstrapKey("Owner");
        var issued = client.req("POST", "/api/admin/tenants/1/keys", a, "{\"label\":\"b\"}");
        String b = issued.json().get("apiKey").asText();
        int bId = issued.json().get("keyId").asInt();
        // Presenting A: disable B, cannot disable A, re-enable B.
        assertEquals(200, client.req("PATCH", "/api/admin/keys/" + bId, a, "{\"isEnabled\":false}").status());
        var inUse = client.req("PATCH", "/api/admin/keys/1", a, "{\"isEnabled\":false}");
        assertEquals(409, inUse.status());
        assertEquals("cannot disable the key in use", inUse.error());
        assertEquals(200, client.req("PATCH", "/api/admin/keys/" + bId, a, "{\"isEnabled\":true}").status());
        // Presenting B: disable A.
        var disableA = client.req("PATCH", "/api/admin/keys/1", b, "{\"isEnabled\":false}");
        assertEquals(200, disableA.status());
        assertEquals(false, disableA.json().get("isEnabled").asBoolean());
        // A is dead, B works.
        assertEquals(401, client.req("GET", "/api/settings", a, null).status());
        assertEquals(404, client.req("GET", "/api/settings", b, null).status());
    }

    @Test
    void lastOperatorKeyGuardAtStoreLevel() throws Exception {
        bootstrapKey("Owner");
        try {
            as(1, 999, true, () -> admin.setKeyEnabled(1, false));
            throw new IllegalStateException("expected Conflict");
        } catch (ApiException.Conflict e) {
            assertEquals("cannot disable the operator's last key", e.getMessage());
        }
    }

    @Test
    void disablingTenantLocksItsKeysUntilReenabled() throws Exception {
        String op = bootstrapKey("Owner");
        String t2 = createTenantKey(op, "Two");
        assertEquals(404, client.req("GET", "/api/settings", t2, null).status());
        assertEquals(
                200,
                client.req("PATCH", "/api/admin/tenants/2", op, "{\"isEnabled\":false}").status());
        assertEquals(401, client.req("GET", "/api/settings", t2, null).status());
        assertEquals(
                200, client.req("PATCH", "/api/admin/tenants/2", op, "{\"isEnabled\":true}").status());
        assertEquals(404, client.req("GET", "/api/settings", t2, null).status());
    }

    @Test
    void deleteTenantIs405() throws Exception {
        String op = bootstrapKey("Owner");
        createTenantKey(op, "Two");
        var r = client.req("DELETE", "/api/admin/tenants/2", op, null);
        assertEquals(405, r.status());
        assertEquals("method not allowed", r.error());
        assertEquals("PATCH", r.headers().getFirst("Allow"));
    }

    @Test
    void patchTenantNameAndUnknownIds() throws Exception {
        String op = bootstrapKey("Owner");
        var renamed = client.req("PATCH", "/api/admin/tenants/1", op, "{\"name\":\"Renamed\"}");
        assertEquals(200, renamed.status());
        assertEquals("Renamed", renamed.json().get("name").asText());
        assertTrue(renamed.json().has("keys"));

        assertEquals(404, client.req("PATCH", "/api/admin/tenants/99", op, "{\"name\":\"x\"}").status());
        assertEquals(404, client.req("POST", "/api/admin/tenants/99/keys", op, "{\"label\":\"x\"}").status());
        assertEquals(404, client.req("PATCH", "/api/admin/keys/99", op, "{\"isEnabled\":false}").status());
        assertEquals(404, client.req("PATCH", "/api/admin/tenants/abc", op, "{\"name\":\"x\"}").status());
    }

    @Test
    void issueKeyNeedsLabel() throws Exception {
        String op = bootstrapKey("Owner");
        var r = client.req("POST", "/api/admin/tenants/1/keys", op, "{}");
        assertEquals(400, r.status());
        assertEquals("label is required", r.error());
    }
}
