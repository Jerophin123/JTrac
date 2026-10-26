package com.jtac.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretBoxTest {

    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private static IntegrationProperties props(String key, Path file, boolean require) {
        return new IntegrationProperties(key, file, require, false, false, 8, Duration.ofSeconds(10), 20);
    }

    @Test
    void roundTripsAndBindsCiphertextToItsRow(@TempDir Path dir) {
        SecretBox box = new SecretBox(props(KEY, dir.resolve("k"), true));
        String sealed = box.encrypt("whsec_abc", "row-1");
        assertThat(sealed).startsWith("v1:").doesNotContain("whsec_abc");
        assertThat(box.decrypt(sealed, "row-1")).isEqualTo("whsec_abc");
        assertThat(box.encrypt("whsec_abc", "row-1")).as("random nonce").isNotEqualTo(sealed);
        assertThatThrownBy(() -> box.decrypt(sealed, "row-2")).isInstanceOf(IllegalStateException.class);
        String tampered = sealed.substring(0, sealed.length() - 4) + (sealed.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertThatThrownBy(() -> box.decrypt(tampered, "row-1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionRequiresAnExplicitKey(@TempDir Path dir) {
        assertThatThrownBy(() -> new SecretBox(props(null, dir.resolve("k"), true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JTAC_SECRET_KEY");
        assertThatThrownBy(() -> new SecretBox(props("c2hvcnQ=", dir.resolve("k"), true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }

    @Test
    void developmentGeneratesAndReusesAKeyFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data/secret.key");
        String sealed = new SecretBox(props(null, file, false)).encrypt("value", "id");
        assertThat(Files.exists(file)).isTrue();
        assertThat(new SecretBox(props(null, file, false)).decrypt(sealed, "id")).isEqualTo("value");
    }
}
