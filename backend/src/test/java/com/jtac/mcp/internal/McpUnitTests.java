package com.jtac.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** Security-relevant logic of the MCP module that needs no database. */
class McpUnitTests {

    private static McpProperties properties(McpProperties.HttpsRedirects policy) {
        return new McpProperties(true, Duration.ofHours(1), Duration.ofDays(30), Duration.ofMinutes(10), Duration.ofSeconds(60),
                20, 240, 60, policy, List.of("antigravity.google", "www.cursor.com"), DataSize.ofMegabytes(2),
                DataSize.ofMegabytes(4), DataSize.ofMegabytes(1), Duration.ofDays(90));
    }

    @Nested
    class Pkce {
        @Test
        void s256MatchesTheRfc7636TestVector() {
            assertThat(OAuthService.s256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                    .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
        }
    }

    @Nested
    class Redirects {
        private final RedirectUris any = new RedirectUris(properties(McpProperties.HttpsRedirects.ANY));
        private final RedirectUris allowlist = new RedirectUris(properties(McpProperties.HttpsRedirects.ALLOWLIST));
        private final RedirectUris none = new RedirectUris(properties(McpProperties.HttpsRedirects.NONE));

        @Test
        void loopbackCallbacksOfLocalAgentsAreAllowed() {
            for (String uri : List.of("http://localhost:43123/callback", "http://127.0.0.1:19876/mcp/oauth/callback",
                    "http://[::1]:8080/cb", "http://localhost/oauth/callback")) {
                assertThat(none.classify(uri)).as(uri).contains(RedirectUris.Kind.LOOPBACK);
            }
        }

        @Test
        void plainHttpToOtherHostsCustomSchemesFragmentsAndUserInfoAreRejected() {
            for (String uri : List.of("http://evil.example/cb", "http://localhost.evil.example/cb", "http://127.0.0.1.nip.io/cb",
                    "cursor://anysphere.cursor-mcp/oauth/callback", "javascript:alert(1)", "https://good.example/cb#frag",
                    "https://user@good.example/cb", "/relative/cb", "", "http://10.0.0.1/cb")) {
                assertThat(any.classify(uri)).as(uri).isEmpty();
            }
        }

        @Test
        void httpsCallbacksFollowThePolicy() {
            assertThat(any.classify("https://antigravity.google/oauth-callback")).contains(RedirectUris.Kind.RECOGNISED);
            assertThat(any.classify("https://agent.example/callback")).contains(RedirectUris.Kind.UNRECOGNISED);
            assertThat(allowlist.classify("https://antigravity.google/oauth-callback")).contains(RedirectUris.Kind.RECOGNISED);
            assertThat(allowlist.classify("https://agent.example/callback")).isEmpty();
            assertThat(none.classify("https://antigravity.google/oauth-callback")).isEmpty();
        }

        @Test
        void matchingIsExactExceptTheLoopbackPort() {
            List<String> registered = List.of("http://127.0.0.1:5000/callback", "https://antigravity.google/oauth-callback");
            assertThat(any.matches("http://127.0.0.1:61234/callback", registered)).isTrue();
            assertThat(any.matches("http://127.0.0.1:61234/other", registered)).isFalse();
            assertThat(any.matches("http://localhost:5000/callback", registered)).isFalse();
            assertThat(any.matches("https://antigravity.google/oauth-callback", registered)).isTrue();
            assertThat(any.matches("https://antigravity.google:8443/oauth-callback", registered)).isFalse();
            assertThat(any.matches("https://antigravity.google/oauth-callback?x=1", registered)).isFalse();
        }
    }

    @Nested
    class Urls {
        private final McpUrls urls = new McpUrls("https://Tracker.Example.com/");

        @Test
        void resourceUrlsAreCanonicalAndPerConnection() {
            String id = ConnectionService.newPublicId();
            assertThat(urls.issuer()).isEqualTo("https://tracker.example.com");
            assertThat(urls.resource(id)).isEqualTo("https://tracker.example.com/mcp/" + id);
            assertThat(urls.publicIdOf("HTTPS://TRACKER.example.com:443/mcp/" + id + "/")).contains(id);
            assertThat(urls.publicIdOf("https://tracker.example.com/mcp/" + id + "?x=1")).isEmpty();
            assertThat(urls.publicIdOf("https://other.example.com/mcp/" + id)).isEmpty();
            assertThat(urls.publicIdOf("https://tracker.example.com/mcp/c_short")).isEmpty();
            assertThat(urls.secure()).isTrue();
        }

        @Test
        void plainHttpIsOnlySecureOnLoopback() {
            assertThat(new McpUrls("http://localhost:5173").secure()).isTrue();
            assertThat(new McpUrls("http://127.0.0.1:8080").secure()).isTrue();
            assertThat(new McpUrls("http://tracker.example.com").secure()).isFalse();
        }

        @Test
        void publicIdsAreRandomAndCarryNoIdentity() {
            String a = ConnectionService.newPublicId();
            String b = ConnectionService.newPublicId();
            assertThat(a).matches(McpUrls.PUBLIC_ID).isNotEqualTo(b);
        }
    }

    @Nested
    class Scopes {
        @Test
        void unknownScopesAreIgnoredAndProfilesAreRecognised() {
            assertThat(Scope.parseParameter("issues:read admin:all  attachments:read"))
                    .containsExactlyInAnyOrder(Scope.ISSUES_READ, Scope.ATTACHMENTS_READ);
            assertThat(Scope.Profile.matching(Scope.Profile.CODING_ASSISTANT.scopes())).contains(Scope.Profile.CODING_ASSISTANT);
            assertThat(Scope.Profile.matching(List.of(Scope.ISSUES_READ))).isEmpty();
        }

        @Test
        void theDefaultProfileIsReadOnly() {
            assertThat(Scope.Profile.DEFAULT.scopes()).noneMatch(Scope::write);
            assertThat(Scope.Profile.ISSUE_CONTRIBUTOR.scopes()).anyMatch(Scope::write);
        }
    }

    @Nested
    class Arguments {
        @Test
        void issueKeysAndNumbersAreValidated() {
            assertThat(new Args(Map.of("k", "web-12")).issueKey("k")).isEqualTo("WEB-12");
            assertThatThrownBy(() -> new Args(Map.of("k", "WEB-12; DROP")).issueKey("k")).isInstanceOf(Args.Invalid.class);
            assertThatThrownBy(() -> new Args(Map.of("n", 1.5)).integer("n", 0, 0, 10)).isInstanceOf(Args.Invalid.class);
            assertThatThrownBy(() -> new Args(Map.of("n", 99)).integer("n", 0, 0, 10)).isInstanceOf(Args.Invalid.class);
            assertThatThrownBy(() -> new Args(Map.of("id", "x")).uuid("id")).isInstanceOf(Args.Invalid.class);
        }
    }

    @Nested
    class EvidenceExtraction {
        @Test
        void descriptionSectionsAreFoundByHeading() {
            String description = """
                    Login breaks after the upgrade.

                    ## Steps to reproduce
                    1. Open /login
                    2. Submit valid credentials

                    **Expected behaviour**
                    The dashboard opens.

                    Actual:
                    A 500 error page.

                    ### Environment
                    Chrome 140, server 3.0.4
                    """;
            Map<String, String> sections = Evidence.sections(description);
            assertThat(sections.get("reproductionSteps")).contains("Open /login").contains("Submit valid credentials");
            assertThat(sections.get("expectedBehavior")).isEqualTo("The dashboard opens.");
            assertThat(sections.get("actualBehavior")).isEqualTo("A 500 error page.");
            assertThat(sections.get("environment")).contains("Chrome 140");
            assertThat(sections).doesNotContainKey("acceptanceCriteria");
        }

        @Test
        void logsAreSummarisedWithExceptionsAndStackTraces() {
            String log = """
                    2026-10-01T10:00:00Z INFO Starting
                    2026-10-01T10:00:01Z WARN Slow query
                    2026-10-01T10:00:02Z ERROR Request failed
                    java.lang.IllegalStateException: boom
                        at com.example.Foo.bar(Foo.java:42)
                        at com.example.Main.main(Main.java:7)
                    Caused by: java.io.IOException: disk full
                        at com.example.Disk.write(Disk.java:9)
                    2026-10-01T10:00:03Z INFO Done
                    """;
            Map<String, Object> analysis = Evidence.analyzeLog(log);
            assertThat(analysis.get("levels")).isEqualTo(Map.of("ERROR", 1, "INFO", 2, "WARN", 1));
            @SuppressWarnings("unchecked")
            Map<String, Integer> exceptions = (Map<String, Integer>) analysis.get("exceptionTypes");
            assertThat(exceptions).containsKeys("java.lang.IllegalStateException", "java.io.IOException");
            assertThat(analysis.get("firstTimestamp")).isEqualTo("2026-10-01T10:00:00Z");
            assertThat(analysis.get("lastTimestamp")).isEqualTo("2026-10-01T10:00:03Z");
            assertThat((List<?>) analysis.get("stackTraces")).hasSize(1);
        }

        @Test
        void binaryAndMalformedDataIsNotTreatedAsText() {
            assertThat(Evidence.decodeText(new byte[] {'a', 0, 'b'}, false)).isEmpty();
            assertThat(Evidence.decodeText(new byte[] {(byte) 0xC3, (byte) 0x28}, false)).isEmpty();
            assertThat(Evidence.decodeText("héllo".getBytes(StandardCharsets.UTF_8), false)).contains("héllo");
            byte[] cut = "aé".getBytes(StandardCharsets.UTF_8);
            byte[] truncated = new byte[] {cut[0], cut[1]}; // the read stopped inside "é"
            assertThat(Evidence.decodeText(truncated, true)).isEqualTo(Optional.of("a"));
        }

        @Test
        void hugeLinesDoNotSlowMatchingDown() {
            String line = "x".repeat(5_000_000) + "Exception";
            long started = System.nanoTime();
            Evidence.errorSignals(line, 10);
            Evidence.analyzeLog(line);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Nested
    class Keywords {
        @Test
        void similarIssueKeywordsDropStopWordsAndNumbers() {
            assertThat(IssueTools.keywords("Checkout fails when coupon applied after 2024 upgrade"))
                    .contains("checkout", "coupon", "applied", "upgrade").doesNotContain("when", "after", "2024", "fails");
        }
    }
}
