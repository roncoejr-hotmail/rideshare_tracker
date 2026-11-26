package org.miosys.transport.http;

import java.util.List;
import java.util.Set;

/**
 * Browser cross-origin support for dashboards. Mirrors the grocery API: exact-match
 * allow-list from TRANSPORT_CORS_ORIGINS (empty = CORS fully off), scoped to /api/**.
 * Unlike Spring's filter, a disallowed origin is ignored rather than rejected, so
 * non-browser clients that send Origin see no behavior change; browsers enforce
 * the policy client-side from the missing headers.
 */
public final class Cors {

    static final String ALLOW_METHODS = "GET, PUT, PATCH, DELETE, POST, OPTIONS";
    static final String ALLOW_HEADERS = "Authorization, Content-Type";
    static final String MAX_AGE = "3600";

    private final Set<String> allowed;

    public Cors(List<String> origins) {
        this.allowed = Set.copyOf(origins);
    }

    public static Cors disabled() {
        return new Cors(List.of());
    }

    public boolean enabled() {
        return !allowed.isEmpty();
    }

    public boolean isAllowed(String origin) {
        return origin != null && allowed.contains(origin);
    }
}
