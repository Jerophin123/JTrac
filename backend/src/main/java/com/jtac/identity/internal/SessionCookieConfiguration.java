package com.jtac.identity.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * The session cookie written by Spring Session. Declared explicitly (rather than derived
 * from the embedded server's settings, which Spring Boot only does when an embedded server
 * runs) so that the name and security attributes are identical in every environment,
 * including tests. Values come from {@code server.servlet.session.cookie.*}.
 */
@Configuration(proxyBeanMethods = false)
class SessionCookieConfiguration {

    @Bean
    CookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.name:JTRAC_SESSION}") String name,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure,
            @Value("${server.servlet.session.cookie.same-site:lax}") String sameSite) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(name);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(secure);
        serializer.setSameSite(Character.toUpperCase(sameSite.charAt(0)) + sameSite.substring(1).toLowerCase());
        serializer.setCookiePath("/");
        // The session id is opaque; no base64 wrapping needed for interoperability.
        serializer.setUseBase64Encoding(false);
        return serializer;
    }
}
