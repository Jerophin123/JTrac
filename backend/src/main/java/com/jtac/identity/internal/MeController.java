package com.jtac.identity.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.identity.CurrentUser;
import com.jtac.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own profile and password. */
@RestController
@RequestMapping(ApiPaths.V1 + "/me")
class MeController {

    private final UserAccountService accounts;
    private final SessionTerminator sessions;
    private final AuditLog audit;

    MeController(UserAccountService accounts, SessionTerminator sessions, AuditLog audit) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.audit = audit;
    }

    @GetMapping
    ResponseEntity<Profile> me(CurrentUser user) {
        UserAccount account = accounts.findAccount(user.id()).orElseThrow();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Profile.of(account));
    }

    @PatchMapping
    @Transactional
    Profile update(CurrentUser user, @Valid @RequestBody ProfileUpdate body) {
        return Profile.of(accounts.updateProfile(user.id(), body.displayName(), body.handle()));
    }

    /** Changes the password and signs out every other session of this user. */
    @PostMapping("/password")
    @Transactional
    ResponseEntity<Void> changePassword(CurrentUser user, @Valid @RequestBody PasswordChange body,
            HttpServletRequest request) {
        UserAccount account = accounts.changePassword(user.id(), body.currentPassword(), body.newPassword());
        HttpSession current = request.getSession(false);
        int ended = sessions.endSessions(account.email(), current == null ? null : current.getId());
        audit.record(AuditEntry.of(null, user.id(), "account.password_changed").target("user", user.id())
                .detail("otherSessionsEnded", ended));
        return ResponseEntity.noContent().build();
    }

    record Profile(UUID id, String email, String displayName, String handle, boolean emailVerified) {
        static Profile of(UserAccount account) {
            return new Profile(account.id(), account.email(), account.displayName(), account.handle(), account.isVerified());
        }
    }

    record ProfileUpdate(@NotBlank @Size(max = 120) String displayName, @Size(max = 32) String handle) {
    }

    record PasswordChange(@NotBlank @Size(max = 1024) String currentPassword, @NotBlank @Size(max = 1024) String newPassword) {
        @Override
        public String toString() {
            return "PasswordChange[****]";
        }
    }
}
