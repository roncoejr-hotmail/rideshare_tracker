package org.miosys.transport.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.miosys.transport.domain.Adjustment;
import org.miosys.transport.domain.Enums.Period;
import org.miosys.transport.domain.Enums.Purpose;
import org.miosys.transport.domain.Enums.Region;
import org.miosys.transport.domain.Enums.Side;
import org.miosys.transport.domain.Money;
import org.miosys.transport.domain.RecurringCost;
import org.miosys.transport.domain.Rental;
import org.miosys.transport.domain.Ride;
import org.miosys.transport.domain.TenantSettings;
import org.miosys.transport.http.ApiException.BadRequest;

/** Builds domain objects from validated PUT bodies. PATCH merges reuse these via stored rows. */
public final class Inputs {

    private static final Set<String> RIDE_FIELDS =
            Set.of(
                    "provider", "rideDate", "region", "total", "tip", "emailSubject", "currency",
                    "pickupUtc", "dropoffUtc", "pickupAddr", "dropoffAddr", "paymentLast4", "rider",
                    "emailReceivedUtc", "notes");
    private static final Set<String> RENTAL_FIELDS =
            Set.of(
                    "startDate", "endDate", "region", "gross", "purpose", "currency",
                    "bookingChannel", "location", "paymentLast4", "emailMessageId", "emailSubject",
                    "notes", "adjustments");
    private static final Set<String> COST_FIELDS =
            Set.of("side", "period", "amount", "effectiveTo", "notes");

    private Inputs() {}

    // ---- rides ----

    public static Ride rideFromPut(
            JsonNode body, String emailMessageId, String defaultCurrency, String personalTag,
            boolean checkTag) {
        String provider = Validator.reqString(body, "provider", 1, 40);
        LocalDate rideDate = Validator.reqDate(body, "rideDate");
        Region region = Region.parse(Validator.reqEnum(body, "region", "local", "travel"));
        long totalCents = Validator.reqCents(body, "total");
        long tipCents = Validator.optCents(body, "tip").orElse(0L);
        if (tipCents > totalCents) {
            throw new BadRequest("tip must not exceed total");
        }
        String emailSubject = Validator.reqString(body, "emailSubject", 1, 300);
        if (checkTag && !emailSubject.toLowerCase(Locale.ROOT).contains(personalTag.toLowerCase(Locale.ROOT))) {
            throw new BadRequest("emailSubject must contain the personal tag");
        }
        String currency = Validator.optCurrency(body, "currency").orElse(defaultCurrency);
        var pickupUtc = Validator.optInstant(body, "pickupUtc");
        var dropoffUtc = Validator.optInstant(body, "dropoffUtc");
        if (pickupUtc.isPresent() && dropoffUtc.isPresent() && dropoffUtc.get().isBefore(pickupUtc.get())) {
            throw new BadRequest("dropoffUtc must not be before pickupUtc");
        }
        return new Ride(
                emailMessageId,
                provider,
                region,
                rideDate,
                pickupUtc,
                dropoffUtc,
                Validator.optString(body, "pickupAddr", 1, 200),
                Validator.optString(body, "dropoffAddr", 1, 200),
                new Money(totalCents - tipCents, currency),
                new Money(tipCents, currency),
                Validator.optLast4(body),
                Validator.optString(body, "rider", 1, 80),
                emailSubject,
                Validator.optInstant(body, "emailReceivedUtc"),
                Validator.optString(body, "notes", 1, 2000, true),
                "",
                "");
    }

    public static ObjectNode mergedRide(Ride stored, JsonNode patch) {
        ObjectNode m = Json.mapper().createObjectNode();
        m.put("provider", stored.provider());
        m.put("rideDate", stored.rideDate().toString());
        m.put("region", stored.region().json());
        m.put("total", stored.total().toDecimal());
        m.put("tip", stored.tip().toDecimal());
        m.put("emailSubject", stored.emailSubject());
        m.put("currency", stored.total().currency());
        stored.pickupUtc().ifPresent(i -> m.put("pickupUtc", i.toString()));
        stored.dropoffUtc().ifPresent(i -> m.put("dropoffUtc", i.toString()));
        stored.pickupAddr().ifPresent(v -> m.put("pickupAddr", v));
        stored.dropoffAddr().ifPresent(v -> m.put("dropoffAddr", v));
        stored.paymentLast4().ifPresent(v -> m.put("paymentLast4", v));
        stored.rider().ifPresent(v -> m.put("rider", v));
        stored.emailReceivedUtc().ifPresent(i -> m.put("emailReceivedUtc", i.toString()));
        stored.notes().ifPresent(v -> m.put("notes", v));
        overlay(m, patch, RIDE_FIELDS);
        return m;
    }

    // ---- rentals ----

    public static Rental rentalFromPut(
            JsonNode body, String company, String confirmationNo, String defaultCurrency) {
        LocalDate startDate = Validator.reqDate(body, "startDate");
        LocalDate endDate = Validator.reqDate(body, "endDate");
        if (endDate.isBefore(startDate)) {
            throw new BadRequest("endDate must not be before startDate");
        }
        Region region = Region.parse(Validator.reqEnum(body, "region", "local", "travel"));
        long grossCents = Validator.reqCents(body, "gross");
        Purpose purpose =
                Purpose.parse(Validator.optEnum(body, "purpose", "personal", "business").orElse("personal"));
        String currency = Validator.optCurrency(body, "currency").orElse(defaultCurrency);
        List<Adjustment> adjustments = parseAdjustments(body.get("adjustments"), grossCents);
        return new Rental(
                company,
                confirmationNo,
                Validator.optString(body, "bookingChannel", 1, 60),
                startDate,
                endDate,
                Validator.optString(body, "location", 1, 200),
                purpose,
                region,
                new Money(grossCents, currency),
                adjustments,
                Validator.optLast4(body),
                Validator.optString(body, "emailMessageId", 1, 512),
                Validator.optString(body, "emailSubject", 1, 300),
                Validator.optString(body, "notes", 1, 2000, true),
                "",
                "");
    }

    public static List<Adjustment> parseAdjustments(JsonNode node, long grossCents) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new BadRequest("adjustments must be an array");
        }
        List<Adjustment> out = new ArrayList<>();
        Set<Integer> seenLineNo = new HashSet<>();
        int index = 0;
        for (JsonNode el : node) {
            String f = "adjustments[" + index + "]";
            if (!el.isObject()) {
                throw new BadRequest(f + " must be an object");
            }
            int lineNo = adjustmentLineNo(el, f);
            if (!seenLineNo.add(lineNo)) {
                throw new BadRequest(f + ".lineNo is duplicated");
            }
            String kind =
                    Validator.parseEnum(
                            f + ".kind",
                            needText(el, "kind", f + ".kind").strip(),
                            "rebate",
                            "refund",
                            "reimbursement",
                            "hold");
            long amountCents = adjustmentAmount(el, f);
            LocalDate effectiveDate =
                    Validator.parseDate(f + ".effectiveDate", needText(el, "effectiveDate", f + ".effectiveDate"));
            var emailMessageId = optAdjText(el, "emailMessageId", f + ".emailMessageId", 1, 512, false);
            var notes = optAdjText(el, "notes", f + ".notes", 1, 2000, true);
            Adjustment a =
                    switch (kind) {
                        case "rebate" -> {
                            rejectHoldFlag(el, f);
                            yield new Adjustment.Rebate(lineNo, amountCents, effectiveDate, emailMessageId, notes);
                        }
                        case "refund" -> {
                            rejectHoldFlag(el, f);
                            yield new Adjustment.Refund(lineNo, amountCents, effectiveDate, emailMessageId, notes);
                        }
                        case "reimbursement" -> {
                            rejectHoldFlag(el, f);
                            yield new Adjustment.Reimbursement(
                                    lineNo, amountCents, effectiveDate, emailMessageId, notes);
                        }
                        default -> {
                            JsonNode hr = el.get("holdReleased");
                            if (hr == null || hr.isNull()) {
                                throw new BadRequest(f + ".holdReleased is required for holds");
                            }
                            if (!hr.isBoolean()) {
                                throw new BadRequest(f + ".holdReleased must be a boolean");
                            }
                            yield new Adjustment.Hold(
                                    lineNo, amountCents, effectiveDate, hr.asBoolean(), emailMessageId, notes);
                        }
                    };
            out.add(a);
            index++;
        }
        long netAffecting =
                out.stream().filter(Adjustment::affectsNet).mapToLong(Adjustment::amountCents).sum();
        if (netAffecting > grossCents) {
            throw new BadRequest("adjustments exceed gross");
        }
        return out;
    }

    private static int adjustmentLineNo(JsonNode el, String f) {
        JsonNode n = el.get("lineNo");
        if (n == null || n.isNull()) {
            throw new BadRequest(f + ".lineNo is required");
        }
        if (!n.isNumber()) {
            throw new BadRequest(f + ".lineNo must be a number");
        }
        if (!n.isIntegralNumber() || n.longValue() < 1 || n.longValue() > Integer.MAX_VALUE) {
            throw new BadRequest(f + ".lineNo must be an integer >= 1");
        }
        return n.intValue();
    }

    private static long adjustmentAmount(JsonNode el, String f) {
        JsonNode n = el.get("amount");
        if (n == null || n.isNull()) {
            throw new BadRequest(f + ".amount is required");
        }
        long cents = Validator.parseCents(f + ".amount", n);
        if (cents == 0) {
            throw new BadRequest(f + ".amount must be greater than 0");
        }
        return cents;
    }

    /** Array elements use short keys; errors are reported under the indexed display name. */
    private static JsonNode need(JsonNode el, String key, String display) {
        JsonNode n = el.get(key);
        if (n == null || n.isNull()) {
            throw new BadRequest(display + " is required");
        }
        return n;
    }

    private static String needText(JsonNode el, String key, String display) {
        JsonNode n = need(el, key, display);
        if (!n.isTextual()) {
            throw new BadRequest(display + " must be a string");
        }
        return n.asText();
    }

    private static java.util.Optional<String> optAdjText(
            JsonNode el, String key, String display, int min, int max, boolean allowTabNewline) {
        JsonNode n = el.get(key);
        if (n == null || n.isNull()) {
            return java.util.Optional.empty();
        }
        if (!n.isTextual()) {
            throw new BadRequest(display + " must be a string");
        }
        String v = n.asText().strip();
        if (v.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Validator.checkedString(display, v, min, max, allowTabNewline));
    }

    private static void rejectHoldFlag(JsonNode el, String f) {
        JsonNode hr = el.get("holdReleased");
        if (hr != null && !hr.isNull()) {
            throw new BadRequest(f + ".holdReleased is only allowed for holds");
        }
    }

    public static ObjectNode mergedRental(Rental stored, JsonNode patch) {
        ObjectNode m = Json.mapper().createObjectNode();
        m.put("startDate", stored.startDate().toString());
        m.put("endDate", stored.endDate().toString());
        m.put("region", stored.region().json());
        m.put("gross", stored.gross().toDecimal());
        m.put("purpose", stored.purpose().json());
        m.put("currency", stored.gross().currency());
        stored.bookingChannel().ifPresent(v -> m.put("bookingChannel", v));
        stored.location().ifPresent(v -> m.put("location", v));
        stored.paymentLast4().ifPresent(v -> m.put("paymentLast4", v));
        stored.emailMessageId().ifPresent(v -> m.put("emailMessageId", v));
        stored.emailSubject().ifPresent(v -> m.put("emailSubject", v));
        stored.notes().ifPresent(v -> m.put("notes", v));
        ArrayNode arr = Json.mapper().createArrayNode();
        for (Adjustment a : stored.adjustments()) {
            ObjectNode o = Json.mapper().createObjectNode();
            o.put("lineNo", a.lineNo());
            o.put("kind", a.kind());
            o.put("amount", BigDecimal.valueOf(a.amountCents(), 2));
            o.put("effectiveDate", a.effectiveDate().toString());
            if (a instanceof Adjustment.Hold h) {
                o.put("holdReleased", h.released());
            }
            a.emailMessageId().ifPresent(v -> o.put("emailMessageId", v));
            a.notes().ifPresent(v -> o.put("notes", v));
            arr.add(o);
        }
        m.set("adjustments", arr);
        overlay(m, patch, RENTAL_FIELDS);
        return m;
    }

    // ---- recurring costs ----

    public static RecurringCost costFromPut(JsonNode body, String name, LocalDate effectiveFrom) {
        Side side = Side.parse(Validator.reqEnum(body, "side", "baseline", "current"));
        Period period = Period.parse(Validator.reqEnum(body, "period", "weekly", "monthly"));
        long amountCents = Validator.reqCents(body, "amount");
        var effectiveTo = Validator.optDate(body, "effectiveTo");
        if (effectiveTo.isPresent() && effectiveTo.get().isBefore(effectiveFrom)) {
            throw new BadRequest("effectiveTo must not be before effectiveFrom");
        }
        return new RecurringCost(
                name,
                effectiveFrom,
                effectiveTo,
                side,
                period,
                amountCents,
                Validator.optString(body, "notes", 1, 2000, true),
                "",
                "");
    }

    public static ObjectNode mergedCost(RecurringCost stored, JsonNode patch) {
        ObjectNode m = Json.mapper().createObjectNode();
        m.put("side", stored.side().json());
        m.put("period", stored.period().json());
        m.put("amount", BigDecimal.valueOf(stored.amountCents(), 2));
        stored.effectiveTo().ifPresent(d -> m.put("effectiveTo", d.toString()));
        stored.notes().ifPresent(v -> m.put("notes", v));
        overlay(m, patch, COST_FIELDS);
        return m;
    }

    // ---- settings ----

    public static TenantSettings settingsFromPut(JsonNode body) {
        return new TenantSettings(
                Validator.reqDate(body, "comparisonStart"),
                Validator.optCurrency(body, "currency").orElse("USD"),
                Validator.reqString(body, "homeRegion", 1, 80),
                Validator.optString(body, "personalTag", 1, 40).orElse("[Personal]"),
                Validator.optTimeZone(body, "timeZone").orElse("UTC"),
                "",
                "");
    }

    private static void overlay(ObjectNode merged, JsonNode patch, Set<String> fields) {
        for (String f : fields) {
            JsonNode v = patch.get(f);
            if (v != null && !v.isNull()) {
                merged.set(f, v);
            }
        }
    }
}
