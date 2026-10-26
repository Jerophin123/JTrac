package com.jtac.organization.internal;

import com.jtac.identity.Consent;
import com.jtac.identity.CurrentUser;
import com.jtac.identity.UserDirectory;
import com.jtac.identity.UserSummary;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.RateLimiter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * First-run setup of a fresh installation: while no account exists, the person who installed
 * JTRAC creates the first administrator account and the first organization (as its owner).
 * That account is the server administrator, the only kind of account that may create
 * further organizations.
 *
 * <p>There is no default password. To keep a stranger from claiming a freshly deployed server
 * first, setup also requires a one-time code that only the operator can read: it is written to
 * a file in the server's data folder (never to the log) and deleted once setup completes.
 * Concurrent attempts are serialized by a transaction-scoped advisory lock, and "no account
 * exists" is re-checked under that lock, so setup can succeed at most once.
 */
@Service
class FirstRunSetup {

    private static final Logger log = LoggerFactory.getLogger(FirstRunSetup.class);
    /** Advisory lock key reserved for first-run setup. */
    private static final long LOCK_KEY = 0x4A54524143L; // "JTRAC"
    /** Unambiguous characters (no 0/O, 1/I/L) so the code is easy to copy by hand. */
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserDirectory users;
    private final OrganizationService organizations;
    private final JdbcClient jdbc;
    private final Path codeFile;
    private final boolean adminCommand;
    private final RateLimiter attempts = new RateLimiter("first-run setup", 10, Duration.ofMinutes(15), 10_000);
    private volatile String setupCode;

    FirstRunSetup(UserDirectory users, OrganizationService organizations, JdbcClient jdbc,
            @Value("${jtac.setup.code-file:./data/setup-code.txt}") Path codeFile,
            @Value("${jtac.admin.user-action:}") String adminCommand) {
        this.users = users;
        this.organizations = organizations;
        this.jdbc = jdbc;
        this.codeFile = codeFile;
        this.adminCommand = !adminCommand.isBlank();
    }

    /** On a fresh installation, prepares the setup code as soon as the server is up. */
    @EventListener(ApplicationReadyEvent.class)
    void announceIfRequired() {
        if (!adminCommand && !users.anyAccountExists()) {
            ensureCode();
        }
    }

    /** Whether setup is still open, decided by the database (not by cached state). */
    boolean required() {
        boolean required = !users.anyAccountExists();
        if (required && !adminCommand) {
            ensureCode();
        }
        return required;
    }

    @Transactional
    String complete(String code, String displayName, String email, String password, String organizationName,
            String organizationSlug, Boolean acceptTerms, String clientAddress) {
        attempts.acquireOrThrow(clientAddress == null ? "" : clientAddress);
        Consent.require(acceptTerms);
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", LOCK_KEY).query().singleRow();
        if (users.anyAccountExists()) {
            throw ApiErrors.conflict("JTRAC is already set up. Sign in instead.");
        }
        String expected = setupCode;
        if (expected == null || !matches(expected, code)) {
            throw ApiErrors.invalid("setupCode",
                    "This setup code is not correct. Copy it from setup-code.txt in the server's data folder.");
        }
        UUID userId = users.createServerAdministrator(email, displayName, password);
        users.recordConsent(userId);
        UserSummary admin = users.find(userId).orElseThrow();
        OrganizationService.OrgSummary org = organizations.create(
                new CurrentUser(admin.id(), admin.email(), admin.displayName(), admin.handle()), organizationName, organizationSlug);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    finish();
                }
            });
        } else {
            finish();
        }
        return org.slug();
    }

    private synchronized void ensureCode() {
        if (setupCode != null) {
            return;
        }
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) {
                code.append('-');
            }
            code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        try {
            Path parent = codeFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(codeFile, code + System.lineSeparator(), StandardCharsets.UTF_8);
            PosixFileAttributeView posix = Files.getFileAttributeView(codeFile, PosixFileAttributeView.class);
            if (posix != null) {
                posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write the first-run setup code file " + codeFile.toAbsolutePath(), e);
        }
        setupCode = code.toString();
        log.warn("JTRAC has no accounts yet. Open the app in a browser to run first-time setup; "
                + "the one-time setup code is in {}", codeFile.toAbsolutePath());
    }

    private synchronized void finish() {
        setupCode = null;
        try {
            Files.deleteIfExists(codeFile);
        } catch (IOException e) {
            log.warn("First-run setup completed, but {} could not be deleted; remove it manually", codeFile.toAbsolutePath());
        }
        log.info("First-run setup completed");
    }

    /** Compares in constant time, ignoring case, spaces and dashes (people retype codes). */
    private static boolean matches(String expected, String given) {
        if (given == null) {
            return false;
        }
        byte[] a = normalize(expected).getBytes(StandardCharsets.UTF_8);
        byte[] b = normalize(given).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static String normalize(String code) {
        return code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }
}
