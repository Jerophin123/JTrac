package com.jtrac.serverconfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OidcDiscoveryTests {

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        serve("/realms/good/.well-known/openid-configuration", 200, document(base + "/realms/good"));
        // Published issuer differs from the one requested (trailing slash): the server would refuse it.
        serve("/realms/slash/.well-known/openid-configuration", 200, document(base + "/realms/slash/"));
        serve("/realms/html/.well-known/openid-configuration", 200, "<html>login</html>");
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void acceptsAMatchingDiscoveryDocument() {
        OidcDiscovery.Result result = OidcDiscovery.check(base + "/realms/good");
        assertThat(result.ok()).as(result.message()).isTrue();
    }

    @Test
    void refusesWhatTheServerWouldRefuseAtStartup() {
        assertThat(OidcDiscovery.check(base + "/realms/slash").message()).contains("calls itself");
        assertThat(OidcDiscovery.check(base + "/realms/html").message()).contains("not an OpenID Connect discovery document");
        assertThat(OidcDiscovery.check(base + "/realms/missing").message()).contains("HTTP 404");
        assertThat(OidcDiscovery.check("http://127.0.0.1:1/realms/x").ok()).isFalse();
    }

    @Test
    void readsEscapedJsonStrings() {
        String json = "{\"issuer\":\"https:\\/\\/idp.example.com\",\"authorization_endpoint\":\"https:\\/\\/idp.example.com\\/a\","
                + "\"token_endpoint\":\"https://idp.example.com/t\",\"jwks_uri\":\"https://idp.example.com/k\"}";
        assertThat(OidcDiscovery.evaluate("https://idp.example.com", json).ok()).isTrue();
        assertThat(OidcDiscovery.evaluate("https://idp.example.com", json.replace(",\"jwks_uri\":\"https://idp.example.com/k\"", ""))
                .message()).contains("jwks_uri");
    }

    private void serve(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private static String document(String issuer) {
        return "{\"issuer\":\"" + issuer + "\",\"authorization_endpoint\":\"" + issuer + "/auth\",\"token_endpoint\":\""
                + issuer + "/token\",\"jwks_uri\":\"" + issuer + "/certs\"}";
    }
}
