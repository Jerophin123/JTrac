package com.jtac.identity.internal;

import com.jtac.identity.CurrentUser;
import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Security principal stored in the HTTP session. The password hash is erased after
 * authentication, so it is never kept in (or serialized with) the session.
 */
final class JtacUserDetails implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 2L;

    private final UUID id;
    private final String email;
    private final String displayName;
    private final String handle;
    private final boolean enabled;
    private final boolean verified;
    private String passwordHash;

    JtacUserDetails(UUID id, String email, String displayName, String handle, String passwordHash, boolean enabled,
            boolean verified) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.handle = handle;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.verified = verified;
    }

    static JtacUserDetails from(UserAccount account) {
        return new JtacUserDetails(account.id(), account.email(), account.displayName(), account.handle(),
                account.passwordHash(), account.isActive(), account.isVerified());
    }

    CurrentUser toCurrentUser() {
        return new CurrentUser(id, email, displayName, handle);
    }

    UUID id() {
        return id;
    }

    boolean verified() {
        return verified;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Authorization is resource-scoped (organization/project roles), not global authorities.
        return List.of();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
