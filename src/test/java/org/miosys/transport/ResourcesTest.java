package org.miosys.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.miosys.transport.http.ApiTestBase;

/** Spec section 13.6: resources and validation. */
class ResourcesTest extends ApiTestBase {

    private static final String RIDE =
            "{\"provider\":\"Uber\",\"rideDate\":\"2026-11-24\",\"region\":\"local\","
                + "\"total\":66.45,\"tip\":15.50,\"emailSubject\":\"[Personal] trip\"}";

    @Test
    void ridePutIsIdempotentAndComputesFare() throws Exception {
        String key = bootstrapKey("Owner");
        assertEquals(201, client.req("PUT", "/api/rides/m-1", key, RIDE).status());
        var again = client.req("PUT", "/api/rides/m-1", key, RIDE);
        assertEquals(200, again.status());
        assertMoney(again.json(), "fare", "50.95");
        assertMoney(again.json(), "total", "66.45");
        assertTrue(again.rawText().contains("\"fare\":50.95"));
        assertTrue(again.rawText().contains("\"tip\":15.50"));
        assertTrue(again.rawText().contains("\"total\":66.45"));
        var list = client.req("GET", "/api/rides", key, null);
        assertEquals(1, list.json().get("total").asInt());
    }

    @Test
    void rideTagIsCaseInsensitive() throws Exception {
        String key = bootstrapKey("Owner");
        String body = RIDE.replace("[Personal]", "[personal]");
        assertEquals(201, client.req("PUT", "/api/rides/m-1", key, body).status());
    }

    @Test
    void rideWithoutTagIsRejectedWithNoBypass() throws Exception {
        String key = bootstrapKey("Owner");
        String untagged = RIDE.replace("[Personal] trip", "Your Tuesday trip");
        var plain = client.req("PUT", "/api/rides/m-1", key, untagged);
        assertEquals(400, plain.status());
        assertEquals("emailSubject must contain the personal tag", plain.error());

        String personal = untagged.replaceFirst("\\}$", ",\"purpose\":\"personal\"}");
        var withPersonal = client.req("PUT", "/api/rides/m-2", key, personal);
        assertEquals(400, withPersonal.status());
        assertEquals("emailSubject must contain the personal tag", withPersonal.error());

        String business = untagged.replaceFirst("\\}$", ",\"purpose\":\"business\"}");
        var withBusiness = client.req("PUT", "/api/rides/m-3", key, business);
        assertEquals(400, withBusiness.status());
        assertEquals("emailSubject must contain the personal tag", withBusiness.error());

        var missing =
                client.req(
                        "PUT",
                        "/api/rides/m-4",
                        key,
                        "{\"provider\":\"Uber\",\"rideDate\":\"2026-11-24\",\"region\":\"local\",\"total\":10.00}");
        assertEquals(400, missing.status());
        assertEquals("emailSubject is required", missing.error());

        var ok = client.req("PUT", "/api/rides/m-5", key, RIDE);
        assertEquals(201, ok.status());
        assertTrue(!ok.json().has("purpose"), "ride responses never carry purpose");
    }

    @Test
    void customPersonalTag() throws Exception {
        String op = bootstrapKey("Owner");
        String t2 = createTenantKey(op, "Two");
        assertEquals(
                201,
                client.req(
                                "PUT",
                                "/api/settings",
                                t2,
                                "{\"comparisonStart\":\"2026-07-26\",\"homeRegion\":\"H\","
                                    + "\"personalTag\":\"[Home]\"}")
                        .status());
        String home = RIDE.replace("[Personal]", "[Home]");
        assertEquals(201, client.req("PUT", "/api/rides/h-1", t2, home).status());
        assertEquals(400, client.req("PUT", "/api/rides/h-2", t2, RIDE).status());

        var badPatch = client.req("PATCH", "/api/rides/h-1", t2, "{\"emailSubject\":\"no tag here\"}");
        assertEquals(400, badPatch.status());
        assertEquals("emailSubject must contain the personal tag", badPatch.error());

        var notesOnly = client.req("PATCH", "/api/rides/h-1", t2, "{\"notes\":\"hello\"}");
        assertEquals(200, notesOnly.status());
        assertEquals("hello", notesOnly.json().get("notes").asText());
    }

    @Test
    void messageIdWithSpecialCharsRoundTrips() throws Exception {
        String key = bootstrapKey("Owner");
        String encoded = "%3CCAF%2Bx9%40mail.example.com%3E";
        assertEquals(201, client.req("PUT", "/api/rides/" + encoded, key, RIDE).status());
        var got = client.req("GET", "/api/rides/" + encoded, key, null);
        assertEquals(200, got.status());
        assertEquals("<CAF+x9@mail.example.com>", got.json().get("emailMessageId").asText());
    }

    @Test
    void malformedPathEncodingIs400() throws Exception {
        String key = bootstrapKey("Owner");
        var bad = client.req("GET", "/api/rides/%ZZ", key, null);
        assertEquals(400, bad.status());
        assertEquals("invalid path encoding", bad.error());
        var truncated = client.req("GET", "/api/rides/%4", key, null);
        assertEquals(400, truncated.status());
        assertEquals("invalid path encoding", truncated.error());
    }

    @Test
    void rideFieldValidation() throws Exception {
        String key = bootstrapKey("Owner");
        var badDate = client.req("PUT", "/api/rides/m-1", key, RIDE.replace("2026-11-24", "2026-02-30"));
        assertEquals(400, badDate.status());
        assertEquals("rideDate must be a date (YYYY-MM-DD)", badDate.error());

        var stringTotal =
                client.req("PUT", "/api/rides/m-1", key, RIDE.replace("66.45", "\"66.45\""));
        assertEquals(400, stringTotal.status());
        assertEquals("total must be a number", stringTotal.error());

        var threeDecimals =
                client.req("PUT", "/api/rides/m-1", key, RIDE.replace("66.45", "12.345"));
        assertEquals(400, threeDecimals.status());
        assertEquals("total must be a non-negative amount with at most 2 decimals", threeDecimals.error());

        var bigTip = client.req("PUT", "/api/rides/m-1", key, RIDE.replace("15.50", "70.00"));
        assertEquals(400, bigTip.status());
        assertEquals("tip must not exceed total", bigTip.error());

        var badRegion = client.req("PUT", "/api/rides/m-1", key, RIDE.replace("local", "Local"));
        assertEquals(400, badRegion.status());
        assertEquals("region must be one of: local, travel", badRegion.error());

        var badInstant =
                client.req(
                        "PUT",
                        "/api/rides/m-1",
                        key,
                        RIDE.replaceFirst("\\}$", ",\"pickupUtc\":\"yesterday\"}"));
        assertEquals(400, badInstant.status());
        assertEquals("pickupUtc must be an ISO-8601 UTC instant (…Z)", badInstant.error());
    }

    @Test
    void rentalAdjustmentsReplaceAndClear() throws Exception {
        String key = bootstrapKey("Owner");
        String two =
                "{\"startDate\":\"2026-09-29\",\"endDate\":\"2026-10-02\",\"region\":\"local\","
                    + "\"gross\":100.00,\"adjustments\":[{\"lineNo\":1,\"kind\":\"rebate\","
                    + "\"amount\":20.00,\"effectiveDate\":\"2026-10-05\"},{\"lineNo\":2,"
                    + "\"kind\":\"hold\",\"amount\":50.00,\"effectiveDate\":\"2026-09-29\","
                    + "\"holdReleased\":false}]}";
        var created = client.req("PUT", "/api/rentals/Avis/R-1", key, two);
        assertEquals(201, created.status());
        assertMoney(created.json(), "net", "80.00");
        assertTrue(created.rawText().contains("\"gross\":100.00"));
        assertTrue(created.rawText().contains("\"net\":80.00"));
        assertTrue(created.rawText().contains("\"amount\":20.00"));

        var cleared = client.req("PATCH", "/api/rentals/Avis/R-1", key, "{\"adjustments\":[]}");
        assertEquals(200, cleared.status());
        assertEquals(0, cleared.json().get("adjustments").size());
        assertMoney(cleared.json(), "net", "100.00");
        assertTrue(cleared.rawText().contains("\"net\":100.00"));
    }

    @Test
    void rentalAdjustmentValidation() throws Exception {
        String key = bootstrapKey("Owner");
        String base =
                "{\"startDate\":\"2026-09-29\",\"endDate\":\"2026-10-02\",\"region\":\"local\","
                    + "\"gross\":100.00,\"adjustments\":[%s]}";
        var holdMissing =
                client.req(
                        "PUT",
                        "/api/rentals/Avis/R-1",
                        key,
                        base.formatted(
                                "{\"lineNo\":1,\"kind\":\"hold\",\"amount\":50.00,"
                                        + "\"effectiveDate\":\"2026-09-29\"}"));
        assertEquals(400, holdMissing.status());
        assertEquals("adjustments[0].holdReleased is required for holds", holdMissing.error());

        var rebateFlag =
                client.req(
                        "PUT",
                        "/api/rentals/Avis/R-2",
                        key,
                        base.formatted(
                                "{\"lineNo\":1,\"kind\":\"rebate\",\"amount\":20.00,"
                                        + "\"effectiveDate\":\"2026-10-05\",\"holdReleased\":true}"));
        assertEquals(400, rebateFlag.status());
        assertEquals("adjustments[0].holdReleased is only allowed for holds", rebateFlag.error());

        var dup =
                client.req(
                        "PUT",
                        "/api/rentals/Avis/R-3",
                        key,
                        base.formatted(
                                "{\"lineNo\":1,\"kind\":\"rebate\",\"amount\":5.00,"
                                        + "\"effectiveDate\":\"2026-10-05\"},{\"lineNo\":1,\"kind\":\"refund\","
                                        + "\"amount\":5.00,\"effectiveDate\":\"2026-10-05\"}"));
        assertEquals(400, dup.status());
        assertEquals("adjustments[1].lineNo is duplicated", dup.error());

        var over =
                client.req(
                        "PUT",
                        "/api/rentals/Avis/R-4",
                        key,
                        base.formatted(
                                "{\"lineNo\":1,\"kind\":\"rebate\",\"amount\":90.00,"
                                        + "\"effectiveDate\":\"2026-10-05\"},{\"lineNo\":2,\"kind\":\"refund\","
                                        + "\"amount\":20.00,\"effectiveDate\":\"2026-10-05\"}"));
        assertEquals(400, over.status());
        assertEquals("adjustments exceed gross", over.error());

        var ignoredFlag =
                client.req(
                        "PUT",
                        "/api/rentals/Avis/R-5",
                        key,
                        base.formatted(
                                "{\"lineNo\":1,\"kind\":\"rebate\",\"amount\":20.00,"
                                        + "\"effectiveDate\":\"2026-10-05\",\"affectsNet\":false}"));
        assertEquals(201, ignoredFlag.status(), "client affectsNet is ignored");
        assertMoney(ignoredFlag.json(), "net", "80.00");
        assertTrue(ignoredFlag.rawText().contains("\"net\":80.00"));
        assertEquals(true, ignoredFlag.json().get("adjustments").get(0).get("affectsNet").asBoolean());
    }

    @Test
    void rentalCompanyKeyIsCaseInsensitive() throws Exception {
        String key = bootstrapKey("Owner");
        String body =
                "{\"startDate\":\"2026-09-29\",\"endDate\":\"2026-10-02\",\"region\":\"local\",\"gross\":10.00}";
        assertEquals(201, client.req("PUT", "/api/rentals/Avis/R-1", key, body).status());
        var again = client.req("PUT", "/api/rentals/avis/R-1", key, body);
        assertEquals(200, again.status());
        assertEquals("Avis", again.json().get("company").asText());
    }

    @Test
    void recurringCostOverlapAndAdjacency() throws Exception {
        String key = bootstrapKey("Owner");
        String cost = "{\"side\":\"baseline\",\"period\":\"monthly\",\"amount\":645.00}";
        assertEquals(201, client.req("PUT", "/api/recurring-costs/Lease/2026-07-26", key, cost).status());
        var overlap =
                client.req("PUT", "/api/recurring-costs/Lease/2026-08-01", key, cost);
        assertEquals(409, overlap.status());
        assertEquals("recurring cost overlaps an existing range for this name", overlap.error());

        var endOld =
                client.req(
                        "PATCH", "/api/recurring-costs/Lease/2026-07-26", key, "{\"effectiveTo\":\"2026-07-31\"}");
        assertEquals(200, endOld.status());
        assertEquals(201, client.req("PUT", "/api/recurring-costs/Lease/2026-08-01", key, cost).status());

        var replaceSameKey =
                client.req(
                        "PUT",
                        "/api/recurring-costs/Lease/2026-07-26",
                        key,
                        cost.replaceFirst("\\}$", ",\"effectiveTo\":\"2026-07-31\"}"));
        assertEquals(200, replaceSameKey.status());
    }

    @Test
    void patchNullsAndUnknownFieldsAreIgnored() throws Exception {
        String key = bootstrapKey("Owner");
        String withNotes = RIDE.replaceFirst("\\}$", ",\"notes\":\"keep\"}");
        assertEquals(201, client.req("PUT", "/api/rides/m-1", key, withNotes).status());
        var patched = client.req("PATCH", "/api/rides/m-1", key, "{\"notes\":null,\"bogus\":1}");
        assertEquals(200, patched.status());
        assertEquals("keep", patched.json().get("notes").asText());
        assertTrue(!patched.json().has("bogus"));
    }

    @Test
    void listPagingAndRanges() throws Exception {
        String key = bootstrapKey("Owner");
        for (int i = 1; i <= 3; i++) {
            String body = RIDE.replace("2026-11-24", "2026-11-2" + i);
            assertEquals(201, client.req("PUT", "/api/rides/m-" + i, key, body).status());
        }
        assertEquals(400, client.req("GET", "/api/rides?size=201", key, null).status());
        var p0 = client.req("GET", "/api/rides?size=2&page=0", key, null);
        assertEquals(2, p0.json().get("content").size());
        assertEquals(3, p0.json().get("total").asInt());
        var p1 = client.req("GET", "/api/rides?size=2&page=1", key, null);
        assertEquals(1, p1.json().get("content").size());
        var p2 = client.req("GET", "/api/rides?size=2&page=2", key, null);
        assertEquals(0, p2.json().get("content").size());
        assertEquals("2026-11-21", p0.json().get("content").get(0).get("rideDate").asText());

        var badRange = client.req("GET", "/api/rides?from=2026-11-03&to=2026-11-01", key, null);
        assertEquals(400, badRange.status());
        assertEquals("from must not be after to", badRange.error());

        var badSize = client.req("GET", "/api/rides?size=0", key, null);
        assertEquals(400, badSize.status());
        assertEquals("size must be 1-200", badSize.error());

        var ignored = client.req("GET", "/api/rides?bogus=1", key, null);
        assertEquals(200, ignored.status());
    }

    @Test
    void bodyOver1MiBIs413() throws Exception {
        String key = bootstrapKey("Owner");
        String big = RIDE.replaceFirst("\\}$", ",\"notes\":\"" + "n".repeat(1024 * 1024) + "\"}");
        var r = client.req("PUT", "/api/rides/m-1", key, big);
        assertEquals(413, r.status());
        assertEquals("request body too large", r.error());
    }

    @Test
    void nonJsonBodyIs400() throws Exception {
        String key = bootstrapKey("Owner");
        var r = client.req("PUT", "/api/rides/m-1", key, "not json");
        assertEquals(400, r.status());
        assertEquals("request body must be a JSON object", r.error());
    }

    @Test
    void dateOrderRules() throws Exception {
        String key = bootstrapKey("Owner");
        var rental =
                client.req(
                        "PUT",
                        "/api/rentals/Avis/R-1",
                        key,
                        "{\"startDate\":\"2026-10-02\",\"endDate\":\"2026-09-29\","
                                + "\"region\":\"local\",\"gross\":10.00}");
        assertEquals(400, rental.status());
        assertEquals("endDate must not be before startDate", rental.error());

        var ride =
                client.req(
                        "PUT",
                        "/api/rides/m-1",
                        key,
                        RIDE.replaceFirst(
                                "\\}$",
                                ",\"pickupUtc\":\"2026-11-24T16:53:00Z\","
                                        + "\"dropoffUtc\":\"2026-11-24T16:24:00Z\"}"));
        assertEquals(400, ride.status());
        assertEquals("dropoffUtc must not be before pickupUtc", ride.error());
    }

    @Test
    void deleteRoundTrip() throws Exception {
        String key = bootstrapKey("Owner");
        assertEquals(201, client.req("PUT", "/api/rides/m-1", key, RIDE).status());
        assertEquals(204, client.req("DELETE", "/api/rides/m-1", key, null).status());
        assertEquals(404, client.req("GET", "/api/rides/m-1", key, null).status());
        assertEquals(404, client.req("DELETE", "/api/rides/m-1", key, null).status());
    }
}
