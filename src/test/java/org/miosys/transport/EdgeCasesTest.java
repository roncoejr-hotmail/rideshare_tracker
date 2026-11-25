package org.miosys.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.miosys.transport.http.ApiTestBase;

/** Beyond section 13: list filters, merged-patch errors, settings rules, negative savings. */
class EdgeCasesTest extends ApiTestBase {

    private static final String RIDE =
            "{\"provider\":\"Uber\",\"rideDate\":\"2026-11-24\",\"region\":\"local\","
                + "\"total\":66.45,\"tip\":15.50,\"emailSubject\":\"[Personal] trip\"}";

    @Test
    void rideListFilters() throws Exception {
        String key = bootstrapKey("Owner");
        assertEquals(201, client.req("PUT", "/api/rides/m-1", key, RIDE).status());
        String travel =
                RIDE.replace("\"region\":\"local\"", "\"region\":\"travel\"")
                        .replace("Uber", "Lyft")
                        .replace("2026-11-24", "2026-11-20");
        assertEquals(201, client.req("PUT", "/api/rides/m-2", key, travel).status());
        String cad =
                RIDE.replaceFirst("\\}$", ",\"currency\":\"CAD\"}").replace("2026-11-24", "2026-11-21");
        assertEquals(201, client.req("PUT", "/api/rides/m-3", key, cad).status());

        assertEquals(1, client.req("GET", "/api/rides?region=travel", key, null).json().get("total").asInt());
        assertEquals(1, client.req("GET", "/api/rides?currency=CAD", key, null).json().get("total").asInt());
        assertEquals(1, client.req("GET", "/api/rides?provider=lyft", key, null).json().get("total").asInt());
        assertEquals(
                2,
                client.req("GET", "/api/rides?from=2026-11-21&to=2026-11-24", key, null)
                        .json()
                        .get("total")
                        .asInt());

        var badRegion = client.req("GET", "/api/rides?region=far", key, null);
        assertEquals(400, badRegion.status());
        assertEquals("region must be one of: local, travel", badRegion.error());
        var badCurrency = client.req("GET", "/api/rides?currency=US", key, null);
        assertEquals(400, badCurrency.status());
        assertEquals("currency must be a 3-letter uppercase currency code", badCurrency.error());
        var badFrom = client.req("GET", "/api/rides?from=tomorrow", key, null);
        assertEquals(400, badFrom.status());
        assertEquals("from must be a date (YYYY-MM-DD)", badFrom.error());
        var badPage = client.req("GET", "/api/rides?page=-1", key, null);
        assertEquals(400, badPage.status());
        assertEquals("page must be >= 0", badPage.error());
    }

    @Test
    void rentalListFilters() throws Exception {
        String key = bootstrapKey("Owner");
        String personal =
                "{\"startDate\":\"2026-09-29\",\"endDate\":\"2026-10-02\",\"region\":\"local\","
                    + "\"gross\":100.00}";
        String business =
                "{\"startDate\":\"2026-10-05\",\"endDate\":\"2026-10-06\",\"region\":\"local\","
                    + "\"purpose\":\"business\",\"gross\":50.00}";
        assertEquals(201, client.req("PUT", "/api/rentals/Avis/R-1", key, personal).status());
        assertEquals(201, client.req("PUT", "/api/rentals/Hertz/R-2", key, business).status());

        assertEquals(
                1,
                client.req("GET", "/api/rentals?purpose=business", key, null).json().get("total").asInt());
        assertEquals(
                1, client.req("GET", "/api/rentals?company=hertz", key, null).json().get("total").asInt());
        assertEquals(
                1,
                client.req("GET", "/api/rentals?from=2026-10-01&to=2026-10-31", key, null)
                        .json()
                        .get("total")
                        .asInt());
        var badPurpose = client.req("GET", "/api/rentals?purpose=fun", key, null);
        assertEquals(400, badPurpose.status());
        assertEquals("purpose must be one of: personal, business", badPurpose.error());
    }

    @Test
    void recurringListFilters() throws Exception {
        String key = bootstrapKey("Owner");
        assertEquals(
                201,
                client.req(
                                "PUT",
                                "/api/recurring-costs/Lease/2026-07-26",
                                key,
                                "{\"side\":\"baseline\",\"period\":\"monthly\",\"amount\":645.00}")
                        .status());
        assertEquals(
                201,
                client.req(
                                "PUT",
                                "/api/recurring-costs/EV/2026-07-26",
                                key,
                                "{\"side\":\"current\",\"period\":\"weekly\",\"amount\":8.00}")
                        .status());
        var baseline = client.req("GET", "/api/recurring-costs?side=baseline", key, null);
        assertEquals(200, baseline.status());
        assertEquals(1, baseline.json().size());
        var badSide = client.req("GET", "/api/recurring-costs?side=old", key, null);
        assertEquals(400, badSide.status());
        assertEquals("side must be one of: baseline, current", badSide.error());
        var badActiveOn = client.req("GET", "/api/recurring-costs?activeOn=never", key, null);
        assertEquals(400, badActiveOn.status());
        assertEquals("activeOn must be a date (YYYY-MM-DD)", badActiveOn.error());
    }

    @Test
    void patchMergeErrors() throws Exception {
        String key = bootstrapKey("Owner");
        assertEquals(201, client.req("PUT", "/api/rides/m-1", key, RIDE).status());
        var tip = client.req("PATCH", "/api/rides/m-1", key, "{\"tip\":70.00}");
        assertEquals(400, tip.status());
        assertEquals("tip must not exceed total", tip.error());

        String rental =
                "{\"startDate\":\"2026-09-29\",\"endDate\":\"2026-10-02\",\"region\":\"local\","
                    + "\"gross\":100.00,\"adjustments\":[{\"lineNo\":1,\"kind\":\"rebate\","
                    + "\"amount\":20.00,\"effectiveDate\":\"2026-10-05\"}]}";
        assertEquals(201, client.req("PUT", "/api/rentals/Avis/R-1", key, rental).status());
        var gross = client.req("PATCH", "/api/rentals/Avis/R-1", key, "{\"gross\":10.00}");
        assertEquals(400, gross.status());
        assertEquals("adjustments exceed gross", gross.error());
        var shape = client.req("PATCH", "/api/rentals/Avis/R-1", key, "{\"adjustments\":{}}");
        assertEquals(400, shape.status());
        assertEquals("adjustments must be an array", shape.error());
    }

    @Test
    void patchCostOverlapRechecked() throws Exception {
        String key = bootstrapKey("Owner");
        String cost = "{\"side\":\"baseline\",\"period\":\"monthly\",\"amount\":645.00}";
        assertEquals(201, client.req("PUT", "/api/recurring-costs/Lease/2026-07-26", key, cost).status());
        assertEquals(
                200,
                client.req(
                                "PATCH",
                                "/api/recurring-costs/Lease/2026-07-26",
                                key,
                                "{\"effectiveTo\":\"2026-07-31\"}")
                        .status());
        assertEquals(201, client.req("PUT", "/api/recurring-costs/Lease/2026-08-01", key, cost).status());
        var overlap =
                client.req(
                        "PATCH",
                        "/api/recurring-costs/Lease/2026-07-26",
                        key,
                        "{\"effectiveTo\":\"2026-08-15\"}");
        assertEquals(409, overlap.status());
        assertEquals("recurring cost overlaps an existing range for this name", overlap.error());
    }

    @Test
    void settingsValidationAndReplace() throws Exception {
        String key = bootstrapKey("Owner");
        var badTz =
                client.req(
                        "PUT",
                        "/api/settings",
                        key,
                        "{\"comparisonStart\":\"2026-07-26\",\"homeRegion\":\"H\","
                                + "\"timeZone\":\"Mars/Olympus\"}");
        assertEquals(400, badTz.status());
        assertEquals("timeZone must be a valid IANA time zone", badTz.error());
        var badCurrency =
                client.req(
                        "PUT",
                        "/api/settings",
                        key,
                        "{\"comparisonStart\":\"2026-07-26\",\"homeRegion\":\"H\",\"currency\":\"usd\"}");
        assertEquals(400, badCurrency.status());
        assertEquals("currency must be a 3-letter uppercase currency code", badCurrency.error());

        String good = "{\"comparisonStart\":\"2026-07-26\",\"homeRegion\":\"H\"}";
        assertEquals(201, client.req("PUT", "/api/settings", key, good).status());
        var again = client.req("PUT", "/api/settings", key, good);
        assertEquals(200, again.status());
        assertEquals("USD", again.json().get("currency").asText());
        assertEquals("[Personal]", again.json().get("personalTag").asText());
        assertEquals("UTC", again.json().get("timeZone").asText());
    }

    @Test
    void rideCurrencyValidation() throws Exception {
        String key = bootstrapKey("Owner");
        var bad = client.req("PUT", "/api/rides/m-1", key, RIDE.replaceFirst("\\}$", ",\"currency\":\"US\"}"));
        assertEquals(400, bad.status());
        assertEquals("currency must be a 3-letter uppercase currency code", bad.error());
        var negative = client.req("PUT", "/api/rides/m-2", key, RIDE.replace("66.45", "-5.00"));
        assertEquals(400, negative.status());
        assertEquals("total must be a non-negative amount with at most 2 decimals", negative.error());
    }

    @Test
    void costEffectiveToOrder() throws Exception {
        String key = bootstrapKey("Owner");
        var r =
                client.req(
                        "PUT",
                        "/api/recurring-costs/Lease/2026-08-01",
                        key,
                        "{\"side\":\"baseline\",\"period\":\"monthly\",\"amount\":1.00,"
                                + "\"effectiveTo\":\"2026-07-31\"}");
        assertEquals(400, r.status());
        assertEquals("effectiveTo must not be before effectiveFrom", r.error());
    }

    @Test
    void tagChangeDoesNotRecheckStoredRides() throws Exception {
        String key = bootstrapKey("Owner");
        assertEquals(201, client.req("PUT", "/api/rides/m-1", key, RIDE).status());
        assertEquals(
                201,
                client.req(
                                "PUT",
                                "/api/settings",
                                key,
                                "{\"comparisonStart\":\"2026-07-26\",\"homeRegion\":\"H\","
                                    + "\"personalTag\":\"[Home]\"}")
                        .status());
        var patched = client.req("PATCH", "/api/rides/m-1", key, "{\"notes\":\"still fine\"}");
        assertEquals(200, patched.status());
        var retagged = client.req("PATCH", "/api/rides/m-1", key, "{\"emailSubject\":\"[Home] now\"}");
        assertEquals(200, retagged.status());
    }

    @Test
    void rentalAndCostDeleteRoundTrips() throws Exception {
        String key = bootstrapKey("Owner");
        String rental =
                "{\"startDate\":\"2026-09-29\",\"endDate\":\"2026-10-02\",\"region\":\"local\","
                    + "\"gross\":100.00,\"adjustments\":[{\"lineNo\":1,\"kind\":\"rebate\","
                    + "\"amount\":20.00,\"effectiveDate\":\"2026-10-05\"}]}";
        assertEquals(201, client.req("PUT", "/api/rentals/Avis/R-1", key, rental).status());
        assertEquals(204, client.req("DELETE", "/api/rentals/Avis/R-1", key, null).status());
        assertEquals(404, client.req("GET", "/api/rentals/Avis/R-1", key, null).status());
        try (var c = database.open();
                var ps = c.prepareStatement("SELECT COUNT(*) FROM rental_adjustment");
                var rs = ps.executeQuery()) {
            rs.next();
            assertEquals(0, rs.getInt(1), "adjustments cascade with the rental");
        }

        String cost = "{\"side\":\"baseline\",\"period\":\"monthly\",\"amount\":645.00}";
        assertEquals(201, client.req("PUT", "/api/recurring-costs/Lease/2026-07-26", key, cost).status());
        assertEquals(204, client.req("DELETE", "/api/recurring-costs/Lease/2026-07-26", key, null).status());
        assertEquals(404, client.req("GET", "/api/recurring-costs/Lease/2026-07-26", key, null).status());
    }

    @Test
    void negativeSavingsRender() throws Exception {
        String key = bootstrapKey("Owner");
        assertEquals(
                201,
                client.req(
                                "PUT",
                                "/api/settings",
                                key,
                                "{\"comparisonStart\":\"2026-11-01\",\"homeRegion\":\"H\"}")
                        .status());
        String big = RIDE.replace("66.45", "500.00").replace("15.50", "0.00").replace("2026-11-24", "2026-11-10");
        assertEquals(201, client.req("PUT", "/api/rides/m-1", key, big).status());
        var r = client.req("GET", "/api/comparison?asOf=2026-11-10", key, null);
        assertEquals(200, r.status());
        assertMoney(r.json().get("months").get(0), "saved", "-500.00");
        assertTrue(r.rawText().contains("\"saved\":-500.00"));
    }

    @Test
    void businessAndTravelRentalsAreExcluded() throws Exception {
        String key = bootstrapKey("Owner");
        assertEquals(
                201,
                client.req(
                                "PUT",
                                "/api/settings",
                                key,
                                "{\"comparisonStart\":\"2026-09-01\",\"homeRegion\":\"H\"}")
                        .status());
        String business =
                "{\"startDate\":\"2026-09-05\",\"endDate\":\"2026-09-06\",\"region\":\"local\","
                    + "\"purpose\":\"business\",\"gross\":50.00}";
        String travel =
                "{\"startDate\":\"2026-09-07\",\"endDate\":\"2026-09-08\",\"region\":\"travel\","
                    + "\"gross\":60.00}";
        assertEquals(201, client.req("PUT", "/api/rentals/Avis/B-1", key, business).status());
        assertEquals(201, client.req("PUT", "/api/rentals/Avis/T-1", key, travel).status());
        var r = client.req("GET", "/api/comparison?asOf=2026-09-30", key, null);
        assertEquals(200, r.status());
        assertEquals(0, r.json().get("months").get(0).get("rentalCount").asInt());
        assertEquals(2, r.json().get("months").get(0).get("excludedRentals").asInt());
        assertMoney(r.json().get("months").get(0), "rentals", "0.00");
    }
}
