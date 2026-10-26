package com.jtac.integration.internal;

import com.jtac.shared.web.ApiErrors;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Server-side request forgery guard for webhook URLs. Targets must be https (unless
 * {@code allow-http}) and every address the host resolves to must be public (unless
 * {@code allow-private-networks}). The check runs when a webhook is saved and again before
 * every delivery, so a DNS change to an internal address is caught at send time.
 */
@Component
class WebhookTargets {

    private final IntegrationProperties properties;

    WebhookTargets(IntegrationProperties properties) {
        this.properties = properties;
    }

    /** Validates a URL for saving; throws a validation problem for field {@code url}. */
    URI validate(String url) {
        try {
            return check(url);
        } catch (Rejected e) {
            throw ApiErrors.invalid("url", e.getMessage());
        }
    }

    /** Re-checks a stored URL before delivery. */
    URI checkForDelivery(String url) throws Rejected {
        return check(url);
    }

    private URI check(String url) throws Rejected {
        URI uri;
        try {
            uri = new URI(url == null ? "" : url.strip());
        } catch (URISyntaxException e) {
            throw new Rejected("Enter a valid URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && properties.allowHttp())) {
            throw new Rejected(properties.allowHttp() ? "Use an http or https URL." : "Use an https URL.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new Rejected("The URL needs a host name.");
        }
        if (uri.getUserInfo() != null) {
            throw new Rejected("Credentials in the URL are not allowed; authenticate with the signature instead.");
        }
        if (uri.getFragment() != null) {
            throw new Rejected("Remove the #fragment from the URL.");
        }
        if (!properties.allowPrivateNetworks()) {
            InetAddress[] addresses;
            try {
                addresses = InetAddress.getAllByName(uri.getHost());
            } catch (UnknownHostException e) {
                throw new Rejected("The host name does not resolve.");
            }
            for (InetAddress address : addresses) {
                if (!isPublic(address)) {
                    throw new Rejected("The host resolves to a private or reserved network address.");
                }
            }
        }
        return uri;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return !(first == 0 // "this" network
                    || (first == 100 && second >= 64 && second <= 127) // carrier-grade NAT
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 0) // IETF protocol assignments
                    || (first == 198 && (second == 18 || second == 19)) // benchmarking
                    || first >= 240); // reserved and broadcast
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) {
                return false; // unique local fc00::/7
            }
            if (first == 0x20 && (b[1] & 0xff) == 0x02) {
                return isPublicV4(b[2], b[3], b[4], b[5]); // 6to4 2002::/16
            }
            if (first == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0) {
                return false; // Teredo 2001::/32 tunnels to arbitrary IPv4 addresses
            }
            if (first == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) {
                return isPublicV4(b[12], b[13], b[14], b[15]); // NAT64 64:ff9b::/96
            }
            boolean mappedV4 = true;
            for (int i = 0; i < 10; i++) {
                mappedV4 &= b[i] == 0;
            }
            if (mappedV4 && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                return isPublicV4(b[12], b[13], b[14], b[15]);
            }
        }
        return true;
    }

    private static boolean isPublicV4(byte a, byte b, byte c, byte d) {
        try {
            return isPublic(InetAddress.getByAddress(new byte[] {a, b, c, d}));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    static final class Rejected extends Exception {
        private static final long serialVersionUID = 1L;

        Rejected(String message) {
            super(message);
        }
    }
}
