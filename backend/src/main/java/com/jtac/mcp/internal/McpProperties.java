package com.jtac.mcp.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * AI agent connections (MCP) settings.
 *
 * @param enabled               turns the MCP endpoint, OAuth endpoints and connection management on or off
 * @param accessTokenTtl        lifetime of access tokens (short; refreshed by the agent)
 * @param refreshTokenTtl       lifetime of each refresh token (rotated on every use)
 * @param authorizationTtl      how long a consent screen stays valid
 * @param codeTtl               how long an authorization code stays valid
 * @param maxConnectionsPerUser active (not revoked) connections per user
 * @param requestsPerMinute     MCP requests per connection and minute
 * @param registrationsPerHour  anonymous OAuth client registrations per IP address and hour
 * @param httpsRedirects        which HTTPS OAuth callback URLs agents may register: {@code ANY}
 *                              (shown on the consent screen, with a warning when not in
 *                              {@code trustedRedirectHosts}), {@code ALLOWLIST} (only those hosts)
 *                              or {@code NONE} (local agents with loopback callbacks only)
 * @param trustedRedirectHosts  hosts of hosted agents' OAuth callbacks that are recognised
 * @param maxAttachmentRead     bytes read from one attachment for text extraction
 * @param maxImageSize          largest image returned to an agent as image content
 * @param maxBlobSize           largest non-text file returned base64-encoded on request
 * @param activityRetention     how long connection activity is kept
 */
@ConfigurationProperties("jtac.mcp")
record McpProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1h") Duration accessTokenTtl,
        @DefaultValue("30d") Duration refreshTokenTtl,
        @DefaultValue("10m") Duration authorizationTtl,
        @DefaultValue("60s") Duration codeTtl,
        @DefaultValue("20") int maxConnectionsPerUser,
        @DefaultValue("240") int requestsPerMinute,
        @DefaultValue("60") int registrationsPerHour,
        @DefaultValue("ANY") HttpsRedirects httpsRedirects,
        @DefaultValue({"antigravity.google", "www.cursor.com"}) List<String> trustedRedirectHosts,
        @DefaultValue("2MB") DataSize maxAttachmentRead,
        @DefaultValue("4MB") DataSize maxImageSize,
        @DefaultValue("1MB") DataSize maxBlobSize,
        @DefaultValue("90d") Duration activityRetention) {

    enum HttpsRedirects {
        ANY, ALLOWLIST, NONE
    }
}
