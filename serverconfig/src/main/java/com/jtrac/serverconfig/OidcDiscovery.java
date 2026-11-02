package com.jtrac.serverconfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks an OpenID Connect issuer the way the server will use it: JTRAC reads
 * {@code <issuer>/.well-known/openid-configuration} at startup and refuses to start unless it
 * loads and names exactly the same issuer. Checking during setup turns that into a clear
 * message instead of a server that doesn't start.
 */
public final class OidcDiscovery {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_DOCUMENT = 256 * 1024;

    /** @param ok whether the server will accept this issuer; {@code message} says why not */
    public record Result(boolean ok, String message) {
    }

    private OidcDiscovery() {
    }

    public static Result check(String issuerUri) {
        String issuer = issuerUri == null ? "" : issuerUri.strip();
        URI location;
        try {
            location = URI.create(issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration");
        } catch (IllegalArgumentException e) {
            return new Result(false, "The issuer URL is not valid.");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<String> response;
        try {
            response = client.send(HttpRequest.newBuilder(location).timeout(TIMEOUT)
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return new Result(false, "Could not reach " + location + " (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "). Check the address, DNS, the proxy or firewall,"
                    + " and that this computer trusts the provider's certificate.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "Interrupted while contacting the identity provider.");
        } finally {
            client.close();
        }
        if (response.statusCode() != 200) {
            return new Result(false, location + " answered HTTP " + response.statusCode() + ". Is the issuer URL right?");
        }
        String body = response.body();
        if (body.length() > MAX_DOCUMENT) {
            return new Result(false, "The discovery document at " + location + " is unexpectedly large.");
        }
        return evaluate(issuer, body);
    }

    /** Checks a discovery document (JSON) against the configured issuer. */
    static Result evaluate(String issuer, String document) {
        Optional<String> published = stringField(document, "issuer");
        if (published.isEmpty()) {
            return new Result(false, "The response is not an OpenID Connect discovery document (no issuer).");
        }
        if (!published.get().equals(issuer)) {
            return new Result(false, "The provider calls itself \"" + published.get() + "\", not \"" + issuer
                    + "\". Enter the issuer exactly as published (including any trailing slash).");
        }
        for (String field : new String[] {"authorization_endpoint", "token_endpoint", "jwks_uri"}) {
            if (stringField(document, field).isEmpty()) {
                return new Result(false, "The discovery document has no " + field + ".");
            }
        }
        return new Result(true, "Identity provider found: " + issuer);
    }

    private static Optional<String> stringField(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? Optional.of(m.group(1).replace("\\/", "/").replace("\\\\", "\\")) : Optional.empty();
    }
}
