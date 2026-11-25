package org.miosys.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.miosys.transport.http.ApiTestBase;

/** Spec section 13.5: tenants never see each other's rows. */
class IsolationTest extends ApiTestBase {

    private static final String RIDE =
            "{\"provider\":\"Uber\",\"rideDate\":\"2026-11-24\",\"region\":\"local\","
                + "\"total\":66.45,\"tip\":15.50,\"emailSubject\":\"[Personal] trip\"}";

    @Test
    void rideCrossTenantIs404AndUnchanged() throws Exception {
        String t1 = bootstrapKey("One");
        String t2 = createTenantKey(t1, "Two");
        assertEquals(201, client.req("PUT", "/api/rides/m-1", t1, RIDE).status());

        assertEquals(404, client.req("GET", "/api/rides/m-1", t2, null).status());
        assertEquals(404, client.req("PATCH", "/api/rides/m-1", t2, "{\"notes\":\"x\"}").status());
        assertEquals(404, client.req("DELETE", "/api/rides/m-1", t2, null).status());

        var kept = client.req("GET", "/api/rides/m-1", t1, null);
        assertEquals(200, kept.status());
        assertEquals("Uber", kept.json().get("provider").asText());
    }

    @Test
    void sameRideKeyIsSeparatePerTenant() throws Exception {
        String t1 = bootstrapKey("One");
        String t2 = createTenantKey(t1, "Two");
        assertEquals(201, client.req("PUT", "/api/rides/m-1", t1, RIDE).status());
        String other = RIDE.replace("Uber", "Lyft");
        assertEquals(201, client.req("PUT", "/api/rides/m-1", t2, other).status());

        var l1 = client.req("GET", "/api/rides", t1, null);
        var l2 = client.req("GET", "/api/rides", t2, null);
        assertEquals(1, l1.json().get("total").asInt());
        assertEquals(1, l2.json().get("total").asInt());
        assertEquals("Uber", l1.json().get("content").get(0).get("provider").asText());
        assertEquals("Lyft", l2.json().get("content").get(0).get("provider").asText());
    }

    @Test
    void rentalCrossTenantIs404() throws Exception {
        String t1 = bootstrapKey("One");
        String t2 = createTenantKey(t1, "Two");
        String rental =
                "{\"startDate\":\"2026-09-29\",\"endDate\":\"2026-10-02\",\"region\":\"local\","
                    + "\"gross\":100.00,\"adjustments\":[{\"lineNo\":1,\"kind\":\"rebate\","
                    + "\"amount\":20.00,\"effectiveDate\":\"2026-10-05\"}]}";
        assertEquals(201, client.req("PUT", "/api/rentals/Avis/R-1", t1, rental).status());

        assertEquals(404, client.req("GET", "/api/rentals/Avis/R-1", t2, null).status());
        assertEquals(404, client.req("PATCH", "/api/rentals/Avis/R-1", t2, "{\"notes\":\"x\"}").status());
        assertEquals(404, client.req("DELETE", "/api/rentals/Avis/R-1", t2, null).status());
        assertEquals(201, client.req("PUT", "/api/rentals/Avis/R-1", t2, rental).status());

        var kept = client.req("GET", "/api/rentals/Avis/R-1", t1, null);
        assertEquals(1, kept.json().get("adjustments").size());
        assertMoney(kept.json(), "net", "80.00");
        assertTrue(kept.rawText().contains("\"net\":80.00"));
    }

    @Test
    void costCrossTenantIs404() throws Exception {
        String t1 = bootstrapKey("One");
        String t2 = createTenantKey(t1, "Two");
        String cost = "{\"side\":\"baseline\",\"period\":\"monthly\",\"amount\":645.00}";
        assertEquals(201, client.req("PUT", "/api/recurring-costs/Lease/2026-07-26", t1, cost).status());

        assertEquals(404, client.req("GET", "/api/recurring-costs/Lease/2026-07-26", t2, null).status());
        assertEquals(
                404,
                client.req("PATCH", "/api/recurring-costs/Lease/2026-07-26", t2, "{\"amount\":1.00}")
                        .status());
        assertEquals(404, client.req("DELETE", "/api/recurring-costs/Lease/2026-07-26", t2, null).status());
        assertEquals(201, client.req("PUT", "/api/recurring-costs/Lease/2026-07-26", t2, cost).status());
    }

    @Test
    void settingsArePerTenant() throws Exception {
        String t1 = bootstrapKey("One");
        String t2 = createTenantKey(t1, "Two");
        assertEquals(
                201,
                client.req(
                                "PUT",
                                "/api/settings",
                                t1,
                                "{\"comparisonStart\":\"2026-07-26\",\"homeRegion\":\"DC\"}")
                        .status());
        assertEquals(404, client.req("GET", "/api/settings", t2, null).status());
    }

    @Test
    void comparisonSeesOnlyOwnRows() throws Exception {
        String t1 = bootstrapKey("One");
        loadSql("seed/seed.sql");
        String t2 = createTenantKey(t1, "Two");
        assertEquals(
                201,
                client.req(
                                "PUT",
                                "/api/settings",
                                t2,
                                "{\"comparisonStart\":\"2026-09-15\",\"homeRegion\":\"Testville\"}")
                        .status());
        assertEquals(
                201,
                client.req(
                                "PUT",
                                "/api/recurring-costs/Lease/2026-09-15",
                                t2,
                                "{\"side\":\"baseline\",\"period\":\"monthly\",\"amount\":3000.00}")
                        .status());
        var report = client.req("GET", "/api/comparison?asOf=2026-09-30", t2, null);
        assertEquals(200, report.status());
        assertEquals(1, report.json().get("months").size());
        assertMoney(report.json().get("months").get(0), "oldCost", "1600.00");
        assertTrue(report.rawText().contains("\"oldCost\":1600.00"));
        assertEquals(0, report.json().get("totals").get("rideCount").asInt());
        assertEquals(0, report.json().get("totals").get("rentalCount").asInt());
    }
}
