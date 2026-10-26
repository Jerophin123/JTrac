package com.jtac.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void generatesVersion7WithIetfVariant() {
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheTimestampInTheMostSignificantBits() {
        Instant instant = Instant.parse("2026-10-24T12:00:00.123Z");
        UUID id = Ids.newId(Clock.fixed(instant, ZoneOffset.UTC));
        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(instant.toEpochMilli());
    }

    @Test
    void laterTimestampsSortAfterEarlierOnes() {
        UUID earlier = Ids.newId(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        UUID later = Ids.newId(Clock.fixed(Instant.parse("2026-01-01T00:00:00.001Z"), ZoneOffset.UTC));
        assertThat(Long.compareUnsigned(later.getMostSignificantBits(), earlier.getMostSignificantBits())).isPositive();
    }

    @Test
    void identifiersAreUnique() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.newId());
        }
        assertThat(ids).hasSize(10_000);
    }
}
