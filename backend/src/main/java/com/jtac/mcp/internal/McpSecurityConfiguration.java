package com.jtac.mcp.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * Security for the agent-facing endpoints ({@code /mcp/**}, {@code /oauth/**} and the OAuth
 * metadata under {@code /.well-known/}). They never use the browser session: the MCP endpoint
 * authenticates every request with an access token ({@link McpEndpoint}), and the OAuth
 * endpoints are public by design (the user approves in the signed-in web app, which keeps
 * its session and CSRF protection). Without ambient credentials there is nothing for CSRF to
 * protect here, so it is off; responses are JSON or redirects and get a deny-all CSP.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class McpSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain agentEndpoints(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/mcp/**", "/oauth/**", "/.well-known/oauth-authorization-server",
                        "/.well-known/oauth-protected-resource/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.requireExplicitSave(true))
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .frameOptions(frame -> frame.deny()));
        return http.build();
    }
}
