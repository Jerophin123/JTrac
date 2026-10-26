package com.jtac.identity;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read access to user accounts for other modules. Authorization is the caller's job. */
public interface UserDirectory {

    Optional<UserSummary> find(UUID id);

    Map<UUID, UserSummary> findAll(Collection<UUID> ids);

    Optional<UserSummary> findByEmail(String email);

    Map<String, UserSummary> findByHandles(Collection<String> handles);

    /**
     * Creates a verified account for someone who proved ownership of {@code email} (e.g. by
     * accepting an invitation sent to it). Fails if the email is already registered.
     */
    UUID createVerifiedAccount(String email, String displayName, String rawPassword);

    /** Like {@link #createVerifiedAccount}, for the account that administers the server (first-run setup). */
    UUID createServerAdministrator(String email, String displayName, String rawPassword);

    /**
     * Records that the user accepted the Terms of Service and Privacy Notice in force. Callers
     * check {@link Consent#require} before creating the account.
     */
    void recordConsent(UUID userId);

    /** Whether the account is active and administers the server, which allows creating organizations. */
    boolean isServerAdministrator(UUID id);

    /** Whether any account exists at all (false only on a fresh installation, before first-run setup). */
    boolean anyAccountExists();
}
