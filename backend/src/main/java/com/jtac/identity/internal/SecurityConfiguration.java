package com.jtac.identity.internal;

import com.jtac.shared.web.ApiPaths;
import com.jtac.shared.web.ProblemSecurityHandlers;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * HTTP security for a same-origin SPA plus bearer API tokens:
 * <ul>
 *   <li>server-side session persisted in PostgreSQL (Spring Session JDBC), cookie
 *       {@code JTAC_SESSION} (HttpOnly, SameSite=Lax; Secure in production);</li>
 *   <li>no tokens in browser storage;</li>
 *   <li>CSRF protection with the Spring Security SPA configuration for cookie-authenticated
 *       requests; bearer-token requests carry no ambient credentials and are exempt;</li>
 *   <li>every {@code /api/**} endpoint requires authentication unless explicitly public;</li>
 *   <li>the session's account is re-checked on every request ({@link ActiveAccountFilter});</li>
 *   <li>passwords: Argon2id; legacy bcrypt hashes verify and are upgraded on sign-in;</li>
 *   <li>actuator: health probes are public, Prometheus metrics only from loopback.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String SESSION_COOKIE = "JTRAC_SESSION";

    /**
     * Argon2id with OWASP's minimum recommended parameters (m=19 MiB, t=2, p=1). Existing
     * {@code {bcrypt}} hashes still verify and are rehashed after the next successful sign-in.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
        return new DelegatingPasswordEncoder("argon2", Map.of("argon2", argon2, "bcrypt", new SafeBcryptPasswordEncoder()));
    }

    @Bean
    AuthenticationManager authenticationManager(JtacUserDetailsService users, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setUserDetailsPasswordService(users);
        ProviderManager manager = new ProviderManager(provider);
        manager.setEraseCredentialsAfterAuthentication(true);
        return manager;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    /** Applied on login: new session id (fixation protection) and a fresh CSRF token. */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebSecurity {

        /** The token filter runs only inside the security chain, never as a plain servlet filter. */
        @Bean
        FilterRegistrationBean<ApiTokenAuthenticationFilter> apiTokenFilterRegistration(ApiTokenAuthenticationFilter filter) {
            FilterRegistrationBean<ApiTokenAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
            registration.setEnabled(false);
            return registration;
        }

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemSecurityHandlers problems,
                SecurityContextRepository securityContextRepository, CsrfTokenRepository csrfTokenRepository,
                UserAccountService accounts, ApiTokenAuthenticationFilter apiTokens) throws Exception {
            String auth = ApiPaths.V1 + "/auth";
            http
                    .securityContext(context -> context.securityContextRepository(securityContextRepository))
                    .addFilterBefore(apiTokens, AnonymousAuthenticationFilter.class)
                    .addFilterBefore(new ActiveAccountFilter(accounts), AnonymousAuthenticationFilter.class)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                    .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository)
                            .ignoringRequestMatchers(ApiTokenAuthenticationFilter::hasBearerToken))
                    .authorizeHttpRequests(authorize -> authorize
                            .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
                                    "/actuator/health/readiness").permitAll()
                            .requestMatchers(HttpMethod.GET, "/actuator/prometheus").access(loopbackOnly())
                            .requestMatchers("/actuator/**").denyAll()
                            .requestMatchers(HttpMethod.GET, auth + "/csrf", ApiPaths.V1 + "/config").permitAll()
                            .requestMatchers(HttpMethod.POST, auth + "/login", auth + "/register", auth + "/verify-email",
                                    auth + "/verify-email/resend", auth + "/password-reset",
                                    auth + "/password-reset/confirm").permitAll()
                            .requestMatchers(ApiPaths.V1 + "/public/**").permitAll()
                            .requestMatchers("/api/**").authenticated()
                            // Everything else is the static single-page application.
                            .anyRequest().permitAll())
                    .exceptionHandling(exceptions -> exceptions
                            .authenticationEntryPoint(problems)
                            .accessDeniedHandler(problems))
                    .logout(logout -> logout
                            .logoutUrl(auth + "/logout")
                            .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                            .deleteCookies(SESSION_COOKIE))
                    .formLogin(AbstractHttpConfigurer::disable)
                    .httpBasic(AbstractHttpConfigurer::disable)
                    .requestCache(cache -> cache.disable())
                    .headers(headers -> headers
                            .contentSecurityPolicy(csp -> csp.policyDirectives(String.join("; ",
                                    "default-src 'self'", "script-src 'self'", "style-src 'self'",
                                    "img-src 'self' data: blob:", "font-src 'self'", "connect-src 'self'",
                                    "object-src 'none'", "base-uri 'self'", "form-action 'self'",
                                    "frame-ancestors 'none'")))
                            .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                            .frameOptions(frame -> frame.deny()));
            return http.build();
        }

        /** Metrics are for local scrapers/sidecars only (see docs/operations.md). */
        private static AuthorizationManager<RequestAuthorizationContext> loopbackOnly() {
            return (authentication, context) -> {
                try {
                    return new AuthorizationDecision(InetAddress.getByName(context.getRequest().getRemoteAddr()).isLoopbackAddress());
                } catch (UnknownHostException e) {
                    return new AuthorizationDecision(false);
                }
            };
        }
    }

    /**
     * Verifies legacy bcrypt hashes only. BCrypt ignores input beyond 72 bytes, so longer
     * inputs never match instead of being silently truncated.
     */
    static final class SafeBcryptPasswordEncoder implements PasswordEncoder {
        private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder();

        @Override
        public String encode(CharSequence rawPassword) {
            throw new UnsupportedOperationException("bcrypt is only used to verify legacy hashes");
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            if (rawPassword.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
                return false;
            }
            return delegate.matches(rawPassword, encodedPassword);
        }

        @Override
        public boolean upgradeEncoding(String encodedPassword) {
            return true;
        }
    }
}
