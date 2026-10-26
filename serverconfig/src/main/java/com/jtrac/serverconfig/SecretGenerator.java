package com.jtrac.serverconfig;

import java.security.SecureRandom;
import java.util.Base64;

/** Random secrets for database roles and encryption keys. */
public final class SecretGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();

    private SecretGenerator() {
    }

    /** A password of letters and digits only, so it needs no quoting in any config format. */
    public static String password(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return out.toString();
    }

    /** A base64-encoded 256-bit key. */
    public static String key256() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
