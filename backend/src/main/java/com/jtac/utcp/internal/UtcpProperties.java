package com.jtac.utcp.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for the native Universal Tool Calling Protocol (UTCP) integration.
 *
 * @param enabled whether UTCP endpoints and execution are enabled globally on this server
 * @param requestsPerMinute per-connection or per-user rate limit for UTCP tool calls
 * @param experimental whether the experimental badge/flags are advertised (defaults to true)
 */
@ConfigurationProperties("jtac.utcp")
record UtcpProperties(boolean enabled, int requestsPerMinute, boolean experimental) {

    UtcpProperties {
        if (requestsPerMinute <= 0) {
            requestsPerMinute = 240;
        }
    }

    UtcpProperties() {
        this(true, 240, true);
    }
}

