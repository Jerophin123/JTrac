package com.jtac.identity.internal;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads accounts for authentication and upgrades legacy password hashes (bcrypt) to the
 * current encoder (Argon2id) transparently on the next successful sign-in.
 */
@Component
class JtacUserDetailsService implements UserDetailsService, UserDetailsPasswordService {

    private final UserAccountRepository accounts;

    JtacUserDetailsService(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        return accounts.findByEmail(email == null ? "" : email.strip())
                .map(JtacUserDetails::from)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
    }

    @Override
    @Transactional
    public UserDetails updatePassword(UserDetails user, String newPassword) {
        accounts.upgradePasswordHash(user.getUsername(), newPassword);
        return accounts.findByEmail(user.getUsername()).map(JtacUserDetails::from).orElseThrow();
    }
}
