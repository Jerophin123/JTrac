package com.jtac.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Re-checks on every authenticated request that the session's account still exists and
 * is active. A disabled or deleted account loses its session immediately instead of at
 * session expiry. (API tokens are checked when they are resolved.) Registered only inside
 * the security filter chain (not a servlet filter).
 */
final class ActiveAccountFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ActiveAccountFilter.class);

    private final UserAccountService accounts;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    ActiveAccountFilter(UserAccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = contextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof JtacUserDetails principal) {
            UUID id = principal.id();
            if (!accounts.isActive(id)) {
                log.info("Ending session: account no longer active userId={}", id);
                contextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
            }
        }
        chain.doFilter(request, response);
    }
}
