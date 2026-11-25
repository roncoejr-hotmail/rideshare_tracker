package org.miosys.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.miosys.transport.http.ApiTestBase;

/** Spec section 13.8: the synthetic fixture, built through the endpoints. */
class ComparisonSyntheticTest extends ApiTestBase {

    private String tenant2() throws Exception {
        String op = bootstrapKey("Owner");
        String t2 = createTenantKey(op, "Fixture");
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
        assertEquals(
                201,
                client.req(
                                "PUT",
                                "/api/recurring-costs/Fuel%20avoided/2026-09-15",
                                t2,
                                "{\"side\":\"baseline\",\"period\":\"weekly\",\"amount\":35.00,"
                                    + "\"effectiveTo\":\"2026-10-05\"}")
                        .status());
        assertEquals(
                201,
                client.req(
                                "PUT",
                                "/api/recurring-costs/EV%20charging/2026-09-20",
                                t2,
                                "{\"side\":\"current\",\"period\":\"weekly\",\"amount\":7.00}")
                        .status());
        ride(t2, "fx-1", "2026-09-20", "local", "USD", 12.00, 2.00);
        ride(t2, "fx-2", "2026-09-10", "local", "USD", 50.00, 0.00);
        ride(t2, "fx-3", "2026-09-21", "travel", "USD", 40.00, 0.00);
        ride(t2, "fx-5", "2026-10-01", "local", "CAD", 25.00, 0.00);
        ride(t2, "fx-6", "2026-10-09", "local", "USD", 18.50, 3.00);
        ride(t2, "fx-7", "2026-10-11", "local", "USD", 99.99, 0.00);
        assertEquals(
                201,
                client.req(
                                "PUT",
                                "/api/rentals/Avis/FX-R1",
                                t2,
                                "{\"startDate\":\"2026-09-29\",\"endDate\":\"2026-10-02\","
                                    + "\"region\":\"local\",\"gross\":100.00,\"adjustments\":["
                                    + "{\"lineNo\":1,\"kind\":\"rebate\",\"amount\":20.00,"
                                    + "\"effectiveDate\":\"2026-10-05\"},"
                                    + "{\"lineNo\":2,\"kind\":\"reimbursement\",\"amount\":5.00,"
                                    + "\"effectiveDate\":\"2026-10-05\"},"
                                    + "{\"lineNo\":3,\"kind\":\"hold\",\"amount\":50.00,"
                                    + "\"effectiveDate\":\"2026-09-29\",\"holdReleased\":true}]}")
                        .status());
        return t2;
    }

    private void ride(
            String key, String id, String date, String region, String currency, double total, double tip)
            throws Exception {
        String body =
                "{\"provider\":\"Uber\",\"rideDate\":\"" + date + "\",\"region\":\"" + region + "\","
                    + "\"currency\":\"" + currency + "\",\"total\":" + total + ",\"tip\":" + tip + ","
                    + "\"emailSubject\":\"[Personal] " + id + "\"}";
        assertEquals(201, client.req("PUT", "/api/rides/" + id, key, body).status(), "ride " + id);
    }

    @Test
    void tenant2PartialMonths() throws Exception {
        String t2 = tenant2();
        var r = client.req("GET", "/api/comparison?asOf=2026-10-10", t2, null);
        assertEquals(200, r.status());
        JsonNode months = r.json().get("months");
        assertEquals(2, months.size());

        JsonNode sep = months.get(0);
        assertEquals("2026-09", sep.get("month").asText());
        assertEquals("2026-09-15", sep.get("windowFrom").asText());
        assertEquals("2026-09-30", sep.get("windowTo").asText());
        assertEquals(16, sep.get("days").asInt());
        assertMoney(sep, "oldCost", "1680.00");
        assertMoney(sep, "rides", "12.00");
        assertEquals(1, sep.get("rideCount").asInt());
        assertMoney(sep, "rentals", "80.00");
        assertEquals(1, sep.get("rentalCount").asInt());
        assertMoney(sep, "recurringNew", "11.00");
        assertMoney(sep, "newCost", "103.00");
        assertMoney(sep, "saved", "1577.00");
        assertEquals(1, sep.get("excludedRides").asInt());

        JsonNode oct = months.get(1);
        assertEquals("2026-10", oct.get("month").asText());
        assertEquals("2026-10-01", oct.get("windowFrom").asText());
        assertEquals("2026-10-10", oct.get("windowTo").asText());
        assertEquals(10, oct.get("days").asInt());
        assertMoney(oct, "oldCost", "992.74");
        assertMoney(oct, "rides", "18.50");
        assertEquals(1, oct.get("rideCount").asInt());
        assertMoney(oct, "rentals", "0.00");
        assertMoney(oct, "recurringNew", "10.00");
        assertMoney(oct, "newCost", "28.50");
        assertMoney(oct, "saved", "964.24");
        assertEquals(1, oct.get("excludedRides").asInt());

        JsonNode totals = r.json().get("totals");
        assertMoney(totals, "oldCost", "2672.74");
        assertMoney(totals, "newCost", "131.50");
        assertMoney(totals, "saved", "2541.24");
        assertTrue(r.rawText().contains("\"oldCost\":1680.00"));
        assertTrue(r.rawText().contains("\"saved\":964.24"));
    }

    @Test
    void tenant3HalfEvenTie() throws Exception {
        String op = bootstrapKey("Owner");
        String t3 = createTenantKey(op, "Tie");
        assertEquals(
                201,
                client.req(
                                "PUT",
                                "/api/settings",
                                t3,
                                "{\"comparisonStart\":\"2026-09-30\",\"homeRegion\":\"Testville\"}")
                        .status());
        assertEquals(
                201,
                client.req(
                                "PUT",
                                "/api/recurring-costs/Tiny/2026-09-01",
                                t3,
                                "{\"side\":\"baseline\",\"period\":\"monthly\",\"amount\":0.75}")
                        .status());
        assertEquals(
                201,
                client.req(
                                "PUT",
                                "/api/recurring-costs/Tiny2/2026-09-01",
                                t3,
                                "{\"side\":\"current\",\"period\":\"monthly\",\"amount\":0.45}")
                        .status());
        var r = client.req("GET", "/api/comparison?asOf=2026-09-30", t3, null);
        assertEquals(200, r.status());
        JsonNode sep = r.json().get("months").get(0);
        assertMoney(sep, "oldCost", "0.02");
        assertMoney(sep, "recurringNew", "0.02");
        assertMoney(sep, "saved", "0.00");
        assertTrue(r.rawText().contains("\"oldCost\":0.02"));
    }
}
