package org.miosys.transport.auth;

import java.util.Optional;

/** Parses the Authorization header. Same shape as the grocery service door. */
public final class Bearer {

    private Bearer() {}

    public static Optional<String> parse(String header) {
        if (header == null) {
            return Optional.empty();
        }
        int space = header.indexOf(' ');
        if (space < 0) {
            return Optional.empty();
        }
        if (!header.substring(0, space).equalsIgnoreCase("Bearer")) {
            return Optional.empty();
        }
        String token = header.substring(space + 1).strip();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
