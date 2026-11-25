package org.miosys.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.miosys.transport.http.ApiTestBase;

/** Spec section 13.7: the real tenant-1 seed through 2026-11-25. */
class ComparisonSeedTest extends ApiTestBase {

    private String seedTenant() throws Exception {
        String key = bootstrapKey("Owner");
        loadSql("seed/seed.sql");
        loadSql("seed/seed.sql"); // idempotent
        return key;
    }

    @Test
    void fullMonthTable() throws Exception {
        String key = seedTenant();
        var r = client.req("GET", "/api/comparison?asOf=2026-11-25", key, null);
        assertEquals(200, r.status());
        assertEquals("USD", r.json().get("currency").asText());
        assertEquals("2026-07-26", r.json().get("comparisonStart").asText());
        assertEquals("2026-11-25", r.json().get("asOf").asText());

        String[][] expected = {
            // month, from, to, days, old, rides, ride#, rentals, rental#, ev, new, saved, cumSaved, exclRides
            {"2026-07", "2026-07-26", "2026-07-31", "6", "301.73", "73.73", "1", "0.00", "0",
                "6.86", "80.59", "221.14", "221.14", "0"},
            {"2026-08", "2026-08-01", "2026-08-31", "31", "1558.92", "225.55", "3", "0.00", "0",
                "35.43", "260.98", "1297.94", "1519.08", "6"},
            {"2026-09", "2026-09-01", "2026-09-30", "30", "1552.92", "175.00", "4", "957.54", "2",
                "34.29", "1166.83", "386.09", "1905.17", "1"},
            {"2026-10", "2026-10-01", "2026-10-31", "31", "1558.92", "110.45", "2", "0.00", "0",
                "35.43", "145.88", "1413.04", "3318.21", "3"},
            {"2026-11", "2026-11-01", "2026-11-25", "25", "1294.10", "242.66", "4", "418.93", "1",
                "28.57", "690.16", "603.94", "3922.15", "2"},
        };
        JsonNode months = r.json().get("months");
        assertEquals(5, months.size());
        for (int i = 0; i < expected.length; i++) {
            JsonNode m = months.get(i);
            String[] e = expected[i];
            assertEquals(e[0], m.get("month").asText(), "month " + i);
            assertEquals(e[1], m.get("windowFrom").asText(), "from " + e[0]);
            assertEquals(e[2], m.get("windowTo").asText(), "to " + e[0]);
            assertEquals(Integer.parseInt(e[3]), m.get("days").asInt(), "days " + e[0]);
            assertMoney(m, "oldCost", e[4], "old " + e[0]);
            assertMoney(m, "rides", e[5], "rides " + e[0]);
            assertEquals(Integer.parseInt(e[6]), m.get("rideCount").asInt(), "ride# " + e[0]);
            assertMoney(m, "rentals", e[7], "rentals " + e[0]);
            assertEquals(Integer.parseInt(e[8]), m.get("rentalCount").asInt(), "rental# " + e[0]);
            assertMoney(m, "recurringNew", e[9], "ev " + e[0]);
            assertMoney(m, "newCost", e[10], "new " + e[0]);
            assertMoney(m, "saved", e[11], "saved " + e[0]);
            assertMoney(m, "cumulativeSaved", e[12], "cumSaved " + e[0]);
            assertEquals(Integer.parseInt(e[13]), m.get("excludedRides").asInt(), "excl " + e[0]);
            assertEquals(0, m.get("excludedRentals").asInt(), "exclRentals " + e[0]);
        }
        // July baseline is round-half-even(6,547,464 / 217 cents).
        assertMoney(months.get(0), "oldCost", "301.73");

        JsonNode totals = r.json().get("totals");
        assertEquals(123, totals.get("days").asInt());
        assertMoney(totals, "oldCost", "6266.59");
        assertMoney(totals, "newCost", "2344.44");
        assertMoney(totals, "saved", "3922.15");
        assertEquals(14, totals.get("rideCount").asInt());
        assertEquals(3, totals.get("rentalCount").asInt());
        assertEquals(12, totals.get("excludedRides").asInt());
        assertEquals(0, totals.get("excludedRentals").asInt());
        assertEquals(
                0,
                totals.get("saved")
                        .decimalValue()
                        .compareTo(months.get(4).get("cumulativeSaved").decimalValue()));
        assertTrue(r.rawText().contains("\"oldCost\":301.73"));
        assertTrue(r.rawText().contains("\"rentals\":0.00"));
        assertTrue(r.rawText().contains("\"cumulativeSaved\":3922.15"));
        assertTrue(r.rawText().contains("\"newCost\":2344.44"));
    }

    @Test
    void asOfBeforeStartYieldsEmptyTable() throws Exception {
        String key = seedTenant();
        var r = client.req("GET", "/api/comparison?asOf=2026-07-25", key, null);
        assertEquals(200, r.status());
        assertEquals(0, r.json().get("months").size());
        JsonNode totals = r.json().get("totals");
        assertEquals(0, totals.get("days").asInt());
        assertMoney(totals, "oldCost", "0.00");
        assertMoney(totals, "newCost", "0.00");
        assertMoney(totals, "saved", "0.00");
        assertEquals(0, totals.get("rideCount").asInt());
        assertEquals(0, totals.get("rentalCount").asInt());
    }

    @Test
    void comparisonNeedsSettings() throws Exception {
        String op = bootstrapKey("Owner");
        String t2 = createTenantKey(op, "Two");
        var r = client.req("GET", "/api/comparison?asOf=2026-11-25", t2, null);
        assertEquals(409, r.status());
        assertEquals("settings not configured", r.error());
    }

    @Test
    void badAsOfIs400() throws Exception {
        String key = seedTenant();
        var r = client.req("GET", "/api/comparison?asOf=11-25-2026", key, null);
        assertEquals(400, r.status());
        assertEquals("asOf must be a date (YYYY-MM-DD)", r.error());
    }

    @Test
    void defaultAsOfIsTodayInTenantZone() throws Exception {
        String op = bootstrapKey("Owner");
        String t2 = createTenantKey(op, "Two");
        assertEquals(
                201,
                client.req(
                                "PUT",
                                "/api/settings",
                                t2,
                                "{\"comparisonStart\":\"2026-07-26\",\"homeRegion\":\"H\","
                                    + "\"timeZone\":\"UTC\"}")
                        .status());
        String today = LocalDate.now(ZoneId.of("UTC")).toString();
        var r = client.req("GET", "/api/comparison", t2, null);
        assertEquals(200, r.status());
        assertEquals(today, r.json().get("asOf").asText());
    }
}
