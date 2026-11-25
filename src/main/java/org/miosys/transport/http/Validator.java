package org.miosys.transport.http;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;
import java.util.regex.Pattern;
import org.miosys.transport.http.ApiException.BadRequest;

/** Field rules from the spec validation reference. Stops at the first error. */
public final class Validator {

    private static final Pattern DATE_SHAPE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final DateTimeFormatter STRICT_DATE =
            DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT);
    private static final BigDecimal MAX_MONEY = new BigDecimal("1000000.00");

    private Validator() {}

    private static JsonNode field(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n == null || n.isNull() ? null : n;
    }

    private static String asText(String field, JsonNode n) {
        if (!n.isTextual()) {
            throw new BadRequest(field + " must be a string");
        }
        return n.asText();
    }

    // ---- strings ----

    public static String reqString(JsonNode body, String field, int min, int max) {
        JsonNode n = field(body, field);
        if (n == null) {
            throw new BadRequest(field + " is required");
        }
        String v = asText(field, n).strip();
        if (v.isEmpty()) {
            throw new BadRequest(field + " is required");
        }
        return checkedString(field, v, min, max, false);
    }

    public static Optional<String> optString(JsonNode body, String field, int min, int max) {
        return optString(body, field, min, max, false);
    }

    public static Optional<String> optString(
            JsonNode body, String field, int min, int max, boolean allowTabNewline) {
        JsonNode n = field(body, field);
        if (n == null) {
            return Optional.empty();
        }
        String v = asText(field, n).strip();
        if (v.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(checkedString(field, v, min, max, allowTabNewline));
    }

    /** Trims, rejects control characters, and enforces length. */
    public static String checkedString(
            String field, String value, int min, int max, boolean allowTabNewline) {
        String v = value.strip();
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if ((ch < 0x20 || ch == 0x7F) && !(allowTabNewline && (ch == '\t' || ch == '\n'))) {
                throw new BadRequest(field + " must not contain control characters");
            }
        }
        if (v.length() < min || v.length() > max) {
            throw new BadRequest(field + " must be " + min + "-" + max + " characters");
        }
        return v;
    }

    /** Path segment: same string rules, reported under the parameter name. */
    public static String pathString(String param, String raw, int min, int max) {
        return checkedString(param, raw.strip(), min, max, false);
    }

    // ---- dates ----

    public static LocalDate reqDate(JsonNode body, String field) {
        JsonNode n = field(body, field);
        if (n == null) {
            throw new BadRequest(field + " is required");
        }
        return parseDate(field, asText(field, n));
    }

    public static Optional<LocalDate> optDate(JsonNode body, String field) {
        JsonNode n = field(body, field);
        if (n == null) {
            return Optional.empty();
        }
        String v = asText(field, n).strip();
        if (v.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(parseDate(field, v));
    }

    public static LocalDate parseDate(String field, String value) {
        String v = value.strip();
        if (!DATE_SHAPE.matcher(v).matches()) {
            throw new BadRequest(field + " must be a date (YYYY-MM-DD)");
        }
        try {
            return LocalDate.parse(v, STRICT_DATE);
        } catch (DateTimeParseException e) {
            throw new BadRequest(field + " must be a date (YYYY-MM-DD)");
        }
    }

    // ---- instants ----

    public static Optional<Instant> optInstant(JsonNode body, String field) {
        JsonNode n = field(body, field);
        if (n == null) {
            return Optional.empty();
        }
        String v = asText(field, n).strip();
        if (v.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(parseInstant(field, v));
    }

    public static Instant parseInstant(String field, String value) {
        String v = value.strip();
        if (!v.endsWith("Z")) {
            throw new BadRequest(field + " must be an ISO-8601 UTC instant (…Z)");
        }
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException e) {
            throw new BadRequest(field + " must be an ISO-8601 UTC instant (…Z)");
        }
    }

    // ---- money (integer cents) ----

    public static long reqCents(JsonNode body, String field) {
        JsonNode n = field(body, field);
        if (n == null) {
            throw new BadRequest(field + " is required");
        }
        return parseCents(field, n);
    }

    public static Optional<Long> optCents(JsonNode body, String field) {
        JsonNode n = field(body, field);
        if (n == null) {
            return Optional.empty();
        }
        return Optional.of(parseCents(field, n));
    }

    public static long parseCents(String field, JsonNode n) {
        if (!n.isNumber()) {
            throw new BadRequest(field + " must be a number");
        }
        BigDecimal bd = n.decimalValue();
        if (bd.scale() > 2 || bd.signum() < 0 || bd.compareTo(MAX_MONEY) > 0) {
            throw new BadRequest(field + " must be a non-negative amount with at most 2 decimals");
        }
        return bd.movePointRight(2).longValueExact();
    }

    // ---- enums, booleans ----

    public static String reqEnum(JsonNode body, String field, String... allowed) {
        JsonNode n = field(body, field);
        if (n == null) {
            throw new BadRequest(field + " is required");
        }
        return parseEnum(field, asText(field, n).strip(), allowed);
    }

    public static Optional<String> optEnum(JsonNode body, String field, String... allowed) {
        JsonNode n = field(body, field);
        if (n == null) {
            return Optional.empty();
        }
        String v = asText(field, n).strip();
        if (v.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(parseEnum(field, v, allowed));
    }

    public static String parseEnum(String field, String value, String... allowed) {
        for (String a : allowed) {
            if (a.equals(value)) {
                return value;
            }
        }
        throw new BadRequest(field + " must be one of: " + String.join(", ", allowed));
    }

    public static boolean reqBool(JsonNode body, String field) {
        JsonNode n = field(body, field);
        if (n == null) {
            throw new BadRequest(field + " is required");
        }
        if (!n.isBoolean()) {
            throw new BadRequest(field + " must be a boolean");
        }
        return n.asBoolean();
    }

    public static Optional<Boolean> optBool(JsonNode body, String field) {
        JsonNode n = field(body, field);
        if (n == null) {
            return Optional.empty();
        }
        if (!n.isBoolean()) {
            throw new BadRequest(field + " must be a boolean");
        }
        return Optional.of(n.asBoolean());
    }

    // ---- currency, last4, time zone ----

    public static Optional<String> optCurrency(JsonNode body, String field) {
        JsonNode n = field(body, field);
        if (n == null) {
            return Optional.empty();
        }
        String v = asText(field, n).strip();
        if (v.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(parseCurrency(field, v));
    }

    public static String parseCurrency(String field, String value) {
        String v = value.strip();
        if (v.length() == 3 && v.chars().allMatch(ch -> ch >= 'A' && ch <= 'Z')) {
            return v;
        }
        throw new BadRequest(field + " must be a 3-letter uppercase currency code");
    }

    public static Optional<String> optLast4(JsonNode body) {
        JsonNode n = field(body, "paymentLast4");
        if (n == null) {
            return Optional.empty();
        }
        String v = asText("paymentLast4", n).strip();
        if (v.isEmpty()) {
            return Optional.empty();
        }
        if (v.length() != 4 || !v.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            throw new BadRequest("paymentLast4 must be 4 digits");
        }
        return Optional.of(v);
    }

    public static Optional<String> optTimeZone(JsonNode body, String field) {
        JsonNode n = field(body, field);
        if (n == null) {
            return Optional.empty();
        }
        String v = asText(field, n).strip();
        if (v.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(parseTimeZone(field, v));
    }

    public static String parseTimeZone(String field, String value) {
        String v = value.strip();
        try {
            ZoneId.of(v);
        } catch (java.time.DateTimeException e) {
            throw new BadRequest(field + " must be a valid IANA time zone");
        }
        return v;
    }

    // ---- paging ----

    public static int queryPage(Query query) {
        Optional<String> raw = query.opt("page");
        if (raw.isEmpty()) {
            return 0;
        }
        try {
            int page = Integer.parseInt(raw.get());
            if (page < 0) {
                throw new BadRequest("page must be >= 0");
            }
            return page;
        } catch (NumberFormatException e) {
            throw new BadRequest("page must be >= 0");
        }
    }

    public static int querySize(Query query) {
        Optional<String> raw = query.opt("size");
        if (raw.isEmpty()) {
            return 50;
        }
        try {
            int size = Integer.parseInt(raw.get());
            if (size < 1 || size > 200) {
                throw new BadRequest("size must be 1-200");
            }
            return size;
        } catch (NumberFormatException e) {
            throw new BadRequest("size must be 1-200");
        }
    }
}
