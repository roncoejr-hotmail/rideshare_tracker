package org.miosys.transport.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Parsed query string. First value wins; unknown parameters are ignored by callers. */
public record Query(Map<String, String> params) {

    public static Query parse(String rawQuery) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            for (String pair : rawQuery.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                map.putIfAbsent(decode(key), decode(value));
            }
        }
        return new Query(map);
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ApiException.BadRequest("invalid query encoding");
        }
    }

    /** Trimmed value, or empty when absent or blank. */
    public Optional<String> opt(String name) {
        String v = params.get(name);
        if (v == null || v.strip().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(v.strip());
    }
}
