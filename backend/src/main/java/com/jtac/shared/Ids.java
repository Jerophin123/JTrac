package com.jtac.shared;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Generates time-ordered UUID version 7 identifiers (RFC 9562, section 5.7).
 * Time ordering keeps B-tree indexes compact; the random part makes identifiers
 * unguessable, but authorization never relies on that.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        return newId(Clock.systemUTC());
    }

    static UUID newId(Clock clock) {
        long millis = clock.millis() & 0xFFFF_FFFF_FFFFL;     // 48-bit Unix epoch milliseconds
        long randA = RANDOM.nextInt(1 << 12);                  // 12 random bits
        long msb = (millis << 16) | (0x7L << 12) | randA;      // version 7
        long randB = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL;
        long lsb = randB | 0x8000_0000_0000_0000L;             // IETF variant (10xx)
        return new UUID(msb, lsb);
    }
}
