package com.jtac.identity.internal;

import com.jtac.identity.UserDirectory;
import com.jtac.identity.UserSummary;
import com.jtac.shared.Ids;
import com.jtac.shared.web.ApiErrors;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account lifecycle rules: creation (administrator, registration, invitation), profile,
 * passwords and email verification. Also the module's public {@link UserDirectory}.
 */
@Service
class UserAccountService implements UserDirectory {

    static final int MIN_PASSWORD_LENGTH = 12;
    static final int MAX_PASSWORD_LENGTH = 128;
    static final Pattern HANDLE = Pattern.compile("^[a-z0-9][a-z0-9_.-]{1,31}$");

    /** The same Bean Validation rules as the sign-in request, so every created account can sign in. */
    record EmailAddress(@NotBlank @Email @Size(max = 320) String value) {
    }

    private final UserAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;
    private final Clock clock;
    private final ConsentService consents;

    UserAccountService(UserAccountRepository accounts, PasswordEncoder passwordEncoder, Validator validator, Clock clock,
            ConsentService consents) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.validator = validator;
        this.clock = clock;
        this.consents = consents;
    }

    /** Key used for throttling only; lookups are normalized by the database (see the repository). */
    static String throttleKey(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    static boolean exceedsPasswordLimit(String rawPassword) {
        return rawPassword.length() > MAX_PASSWORD_LENGTH;
    }

    static void requireAcceptablePassword(String field, String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH || rawPassword.length() > MAX_PASSWORD_LENGTH) {
            throw ApiErrors.invalid(field, "Use " + MIN_PASSWORD_LENGTH + " to " + MAX_PASSWORD_LENGTH + " characters.");
        }
        if (rawPassword.isBlank()) {
            throw ApiErrors.invalid(field, "The password must not be only spaces.");
        }
    }

    String requireValidEmail(String field, String email) {
        String address = email == null ? "" : email.strip();
        if (!validator.validate(new EmailAddress(address)).isEmpty()) {
            throw ApiErrors.invalid(field, "Enter a valid email address.");
        }
        return address;
    }

    static String requireDisplayName(String field, String displayName) {
        String name = displayName == null ? "" : displayName.strip();
        if (name.isEmpty() || name.length() > 120) {
            throw ApiErrors.invalid(field, "Use 1 to 120 characters.");
        }
        return name;
    }

    @Transactional
    UUID create(String email, String displayName, String rawPassword, boolean verified) {
        String address = requireValidEmail("email", email);
        String name = requireDisplayName("displayName", displayName);
        requireAcceptablePassword("password", rawPassword);
        if (accounts.findByEmail(address).isPresent()) {
            throw ApiErrors.conflict("An account with this email address already exists.");
        }
        UserAccount account = new UserAccount(Ids.newId(), address, name, uniqueHandleFor(address),
                passwordEncoder.encode(rawPassword), clock.instant(), verified);
        accounts.saveAndFlush(account);
        return account.id();
    }

    @Override
    @Transactional
    public UUID createVerifiedAccount(String email, String displayName, String rawPassword) {
        return create(email, displayName, rawPassword, true);
    }

    @Override
    @Transactional
    public UUID createServerAdministrator(String email, String displayName, String rawPassword) {
        UUID id = create(email, displayName, rawPassword, true);
        accounts.findById(id).orElseThrow().setServerAdmin(true, clock.instant());
        return id;
    }

    @Override
    public void recordConsent(UUID userId) {
        consents.record(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isServerAdministrator(UUID id) {
        return accounts.findById(id).map(account -> account.isActive() && account.isServerAdmin()).orElse(false);
    }

    /** Grants or revokes server administration (operator command); false if there is no such account. */
    @Transactional
    boolean setServerAdministrator(String email, boolean serverAdmin) {
        return accounts.findByEmail(email == null ? "" : email.strip())
                .map(account -> {
                    account.setServerAdmin(serverAdmin, clock.instant());
                    return true;
                })
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean anyAccountExists() {
        return accounts.count() > 0;
    }

    private String uniqueHandleFor(String email) {
        String base = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "")
                .replaceAll("^[^a-z0-9]+", "");
        if (base.length() < 2) {
            base = "user" + base;
        }
        base = base.length() > 24 ? base.substring(0, 24) : base;
        String candidate = base;
        for (int i = 2; accounts.existsByHandle(candidate); i++) {
            candidate = base + i;
        }
        return candidate;
    }

    @Transactional
    UserAccount updateProfile(UUID id, String displayName, String handle) {
        UserAccount account = accounts.findById(id).orElseThrow(() -> ApiErrors.notFound("Account"));
        String name = requireDisplayName("displayName", displayName);
        String newHandle = handle == null ? account.handle() : handle.strip().toLowerCase(Locale.ROOT);
        if (!HANDLE.matcher(newHandle).matches()) {
            throw ApiErrors.invalid("handle", "Use 2–32 lowercase letters, digits, '.', '_' or '-', starting with a letter or digit.");
        }
        if (!newHandle.equals(account.handle()) && accounts.existsByHandle(newHandle)) {
            throw ApiErrors.invalid("handle", "This handle is already taken.");
        }
        account.rename(name, newHandle, clock.instant());
        return account;
    }

    /** Verifies the current password, then sets a new one. */
    @Transactional
    UserAccount changePassword(UUID id, String currentPassword, String newPassword) {
        UserAccount account = accounts.findById(id).orElseThrow(() -> ApiErrors.notFound("Account"));
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, account.passwordHash())) {
            throw ApiErrors.invalid("currentPassword", "The current password is incorrect.");
        }
        requireAcceptablePassword("newPassword", newPassword);
        account.changePassword(passwordEncoder.encode(newPassword), clock.instant());
        return account;
    }

    /** Sets a new password after a verified reset; proving mailbox access also verifies the email. */
    @Transactional
    UserAccount resetPassword(UUID id, String newPassword) {
        requireAcceptablePassword("password", newPassword);
        UserAccount account = accounts.findById(id).orElseThrow(() -> ApiErrors.notFound("Account"));
        account.changePassword(passwordEncoder.encode(newPassword), clock.instant());
        account.markVerified(clock.instant());
        return account;
    }

    @Transactional
    void markVerified(UUID id) {
        accounts.findById(id).ifPresent(account -> account.markVerified(clock.instant()));
    }

    @Transactional(readOnly = true)
    Optional<UserAccount> findAccount(UUID id) {
        return accounts.findById(id);
    }

    @Transactional(readOnly = true)
    Optional<UserAccount> findAccountByEmail(String email) {
        return email == null ? Optional.empty() : accounts.findByEmail(email.strip());
    }

    @Transactional
    boolean deleteByEmail(String email) {
        return accounts.findByEmail(email == null ? "" : email.strip())
                .map(account -> {
                    accounts.delete(account);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    void recordLogin(UUID id) {
        accounts.recordLogin(id, clock.instant());
    }

    @Transactional(readOnly = true)
    boolean isActive(UUID id) {
        return accounts.existsByIdAndStatus(id, UserAccount.Status.ACTIVE);
    }

    // --- UserDirectory -------------------------------------------------------------------

    static UserSummary summary(UserAccount account) {
        return new UserSummary(account.id(), account.email(), account.displayName(), account.handle(), account.isActive());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> find(UUID id) {
        return accounts.findById(id).map(UserAccountService::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UserSummary> findAll(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UserSummary> result = new LinkedHashMap<>();
        accounts.findAllById(ids).forEach(a -> result.put(a.id(), summary(a)));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findByEmail(String email) {
        return findAccountByEmail(email).map(UserAccountService::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, UserSummary> findByHandles(Collection<String> handles) {
        if (handles.isEmpty()) {
            return Map.of();
        }
        return accounts.findByHandleIn(handles.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList()).stream()
                .collect(Collectors.toMap(UserAccount::handle, UserAccountService::summary));
    }
}
