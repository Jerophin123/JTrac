package com.jtac.identity.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session authentication and account recovery API ({@code /api/v1/auth}). Policies live in
 * {@link SignInService} and {@link AccountRecoveryService}; this controller maps HTTP and
 * establishes the session. Logout is Spring Security's filter at {@code POST /api/v1/auth/logout}.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/auth")
class AuthController {

    private final SignInService signIn;
    private final AccountRecoveryService recovery;
    private final UserAccountService accounts;
    private final ConsentService consents;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    AuthController(SignInService signIn, AccountRecoveryService recovery, UserAccountService accounts, ConsentService consents,
            SessionAuthenticationStrategy sessionAuthenticationStrategy, SecurityContextRepository securityContextRepository) {
        this.signIn = signIn;
        this.recovery = recovery;
        this.accounts = accounts;
        this.consents = consents;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
    }

    /** Issues (or refreshes) the {@code XSRF-TOKEN} cookie. Call on start-up and after login/logout. */
    @GetMapping("/csrf")
    ResponseEntity<Void> csrf(CsrfToken token) {
        token.getToken(); // resolving the deferred token writes the cookie
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/login")
    ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest login, HttpServletRequest request,
            HttpServletResponse response) {
        Authentication authentication = signIn.signIn(login.email(), login.password(), request.getRemoteAddr());

        // New session id (fixation protection) and a fresh CSRF token, then persist the context.
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        JtacUserDetails principal = (JtacUserDetails) authentication.getPrincipal();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SessionResponse.of(principal.toCurrentUser(), accounts.isServerAdministrator(principal.id()),
                        !consents.hasCurrentConsent(principal.id())));
    }

    /** Current session; profile data is read fresh so renames show up without signing in again. */
    @GetMapping("/session")
    ResponseEntity<SessionResponse> session(@AuthenticationPrincipal JtacUserDetails principal) {
        UserAccount account = accounts.findAccount(principal.id()).orElseThrow();
        CurrentUser user = new CurrentUser(account.id(), account.email(), account.displayName(), account.handle());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SessionResponse.of(user, account.isActive() && account.isServerAdmin(), !consents.hasCurrentConsent(account.id())));
    }

    @PostMapping("/password-reset")
    ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        recovery.requestPasswordReset(body.email(), request.getRemoteAddr());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password-reset/confirm")
    ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody ResetRequest body) {
        recovery.completePasswordReset(body.token(), body.password());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/register")
    ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        recovery.register(body.email(), body.displayName(), body.password(), body.acceptTerms(), request.getRemoteAddr());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/verify-email")
    ResponseEntity<Void> verifyEmail(@Valid @RequestBody TokenRequest body) {
        recovery.verifyEmail(body.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify-email/resend")
    ResponseEntity<Void> resendVerification(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        recovery.resendVerification(body.email(), request.getRemoteAddr());
        return ResponseEntity.accepted().build();
    }

    record LoginRequest(
            @NotBlank(message = "Email is required.") @Email(message = "Enter a valid email address.")
            @Size(max = 320, message = "Email must be at most 320 characters.") String email,
            @NotBlank(message = "Password is required.")
            @Size(max = 1024, message = "Password is too long.") String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=****]";
        }
    }

    record EmailRequest(@NotBlank(message = "Email is required.") @Size(max = 320) String email) {
    }

    record TokenRequest(@NotBlank(message = "Token is required.") @Size(max = 200) String token) {
    }

    record ResetRequest(@NotBlank @Size(max = 200) String token, @NotBlank @Size(max = 1024) String password) {
        @Override
        public String toString() {
            return "ResetRequest[****]";
        }
    }

    record RegisterRequest(@NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 120) String displayName,
            @NotBlank @Size(max = 1024) String password, Boolean acceptTerms) {
        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + ", password=****, acceptTerms=" + acceptTerms + "]";
        }
    }

    record UserResponse(UUID id, String email, String displayName, String handle, boolean serverAdmin) {
        static UserResponse of(CurrentUser user, boolean serverAdmin) {
            return new UserResponse(user.id(), user.email(), user.displayName(), user.handle(), serverAdmin);
        }
    }

    /** @param consentRequired the current Terms of Service and Privacy Notice still need to be accepted */
    record SessionResponse(UserResponse user, boolean consentRequired) {
        static SessionResponse of(CurrentUser user, boolean serverAdmin, boolean consentRequired) {
            return new SessionResponse(UserResponse.of(user, serverAdmin), consentRequired);
        }
    }
}
