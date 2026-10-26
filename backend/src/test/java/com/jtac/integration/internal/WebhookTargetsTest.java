package com.jtac.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jtac.shared.web.ApiErrors;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SSRF guard for webhook URLs, with production settings (https only, public addresses only). */
class WebhookTargetsTest {

    private final WebhookTargets production = new WebhookTargets(new IntegrationProperties(null, Path.of("unused"), true,
            false, false, 8, Duration.ofSeconds(10), 20));

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/hook", "https://10.1.2.3/hook", "https://172.16.0.1/", "https://192.168.1.10/",
            "https://169.254.169.254/latest/meta-data", "https://100.64.0.1/", "https://0.0.0.0/", "https://[::1]/",
            "https://[fd00::1]/", "https://[fe80::1]/", "https://[::ffff:127.0.0.1]/", "https://[64:ff9b::a00:1]/",
            "https://[2002:a00:1::]/", "https://[2001:0:4136:e378:8000:63bf:3fff:fdd2]/", "https://localhost/"})
    void privateAndReservedTargetsAreRejected(String url) {
        assertThatThrownBy(() -> production.validate(url)).isInstanceOf(ApiErrors.ValidationFailed.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://93.184.215.14/hook", "ftp://93.184.215.14/", "https://user:pw@93.184.215.14/",
            "https://93.184.215.14/#frag", "not a url", ""})
    void insecureOrMalformedUrlsAreRejected(String url) {
        assertThatThrownBy(() -> production.validate(url)).isInstanceOf(ApiErrors.ValidationFailed.class);
    }

    @Test
    void publicHttpsTargetsAreAccepted() {
        assertThat(production.validate("https://93.184.215.14/hooks/jtac?x=1").getHost()).isEqualTo("93.184.215.14");
        assertThat(production.validate("https://[2606:2800:21f:cb07:6820:80da:af6b:8b2c]/").getHost()).isNotBlank();
    }

    @Test
    void addressClassification() throws Exception {
        assertThat(WebhookTargets.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(WebhookTargets.isPublic(InetAddress.getByName("198.18.0.1"))).isFalse();
        assertThat(WebhookTargets.isPublic(InetAddress.getByName("240.0.0.1"))).isFalse();
    }
}
