package org.miosys.transport.auth;

import java.security.SecureRandom;
import java.util.Base64;

/** Generates API secrets: 48 random bytes, base64url without padding (64 chars). */
public final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {}

    public static String generate() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
