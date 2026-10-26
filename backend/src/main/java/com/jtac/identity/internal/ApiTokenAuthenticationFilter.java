package com.jtac.identity.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.shared.web.ProblemSecurityHandlers;
import com.jtac.shared.web.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer jtac_...} requests. A request with a bearer
 * token never uses the session cookie (it is exempt from CSRF only because of this).
 * Read-only tokens may only perform safe methods. Not a servlet filter: it is added to the
 * security chain only (see {@link SecurityConfiguration}).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final ApiTokenService tokens;
    private final ProblemSecurityHandlers problems;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    /** Per-user API budget for token-authenticated requests. */
    private final RateLimiter perUser = new RateLimiter("API", 600, Duration.ofMinutes(1), 100_000);

    ApiTokenAuthenticationFilter(ApiTokenService tokens, ProblemSecurityHandlers problems) {
        this.tokens = tokens;
        this.problems = problems;
    }

    static boolean hasBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!hasBearerToken(request)) {
            chain.doFilter(request, response);
            return;
        }
        String secret = request.getHeader("Authorization").substring(7).strip();
        Optional<CurrentUser> user = tokens.authenticate(secret);
        if (user.isEmpty()) {
            contextHolder.clearContext();
            problems.commence(request, response, new BadCredentialsException("Invalid API token"));
            return;
        }
        Optional<java.time.Duration> wait = perUser.tryAcquire(user.get().id().toString());
        if (wait.isPresent()) {
            problems.respond(request, response, new RateLimiter.RateLimited("API", wait.get()));
            return;
        }
        if (user.get().readOnly() && !SAFE_METHODS.contains(request.getMethod())) {
            problems.handle(request, response, new AccessDeniedException("This API token is read-only."));
            return;
        }
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(new TokenAuthentication(user.get()));
        contextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    static final class TokenAuthentication extends AbstractAuthenticationToken {
        private static final long serialVersionUID = 1L;
        private final transient CurrentUser user; // request-scoped; token authentication is never stored in a session

        TokenAuthentication(CurrentUser user) {
            super(List.of());
            this.user = user;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return user;
        }

        @Override
        public String getName() {
            return user.email();
        }
    }
}
