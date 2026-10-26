package com.jtrac.serverconfig;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Converts between the configuration model and the property files the server reads.
 *
 * <p>The files use the server's own property names (Spring Boot keys and the historical
 * {@code jtac.*} prefix), so the server imports them directly. Keys under {@code jtrac.*}
 * are read only by the launcher. Environment variables override file values (see
 * docs/configuration-reference.md).
 */
public final class ConfigMapper {

    // HTTP
    public static final String BIND_ADDRESS = "server.address";
    public static final String PORT = "server.port";
    public static final String PUBLIC_URL = "jtac.public-url";
    public static final String FORWARD_HEADERS = "server.forward-headers-strategy";
    public static final String SECURE_COOKIES = "server.servlet.session.cookie.secure";
    public static final String SESSION_TIMEOUT = "server.servlet.session.timeout";
    // Database
    public static final String DB_MODE = "jtrac.database.mode";
    public static final String DB_HOST = "jtrac.database.host";
    public static final String DB_PORT = "jtrac.database.port";
    public static final String DB_NAME = "jtrac.database.name";
    public static final String DB_OWNER = "jtrac.database.owner-user";
    public static final String DB_SSL_MODE = "jtrac.database.ssl-mode";
    public static final String DATASOURCE_URL = "spring.datasource.url";
    public static final String DATASOURCE_USER = "spring.datasource.username";
    public static final String APP_ROLE = "jtac.database.app-role";
    // Mail
    public static final String MAIL_TRANSPORT = "jtac.mail.transport";
    public static final String MAIL_HOST = "jtac.mail.smtp.host";
    public static final String MAIL_PORT = "jtac.mail.smtp.port";
    public static final String MAIL_AUTH = "jtac.mail.smtp.auth";
    public static final String MAIL_USERNAME = "jtac.mail.smtp.username";
    public static final String MAIL_TLS = "jtac.mail.smtp.tls";
    public static final String MAIL_TIMEOUT = "jtac.mail.smtp.timeout-seconds";
    public static final String MAIL_TRUSTED_CERTS = "jtac.mail.smtp.trusted-certificates";
    public static final String MAIL_FROM = "jtac.mail.from";
    public static final String MAIL_FROM_NAME = "jtac.mail.from-name";
    public static final String MAIL_REPLY_TO = "jtac.mail.reply-to";
    public static final String MAIL_MAX_ATTEMPTS = "jtac.mail.max-attempts";
    // Security
    public static final String REGISTRATION_MODE = "jtac.registration.mode";
    // Storage
    public static final String ATTACHMENTS_DIR = "jtac.attachments.directory";
    public static final String BACKUP_DIR = "jtrac.storage.backup-dir";
    public static final String LOG_DIR = "jtrac.storage.log-dir";
    public static final String LOG_FILE = "logging.file.name";
    // Legal
    public static final String LEGAL_OPERATOR_NAME = "jtac.legal.operator-name";
    public static final String LEGAL_OPERATOR_ADDRESS = "jtac.legal.operator-address";
    public static final String LEGAL_CONTACT_EMAIL = "jtac.legal.contact-email";
    public static final String LEGAL_GRIEVANCE_NAME = "jtac.legal.grievance-officer-name";
    public static final String LEGAL_GRIEVANCE_EMAIL = "jtac.legal.grievance-officer-email";
    public static final String LEGAL_JURISDICTION = "jtac.legal.jurisdiction";
    // Secrets (secrets.properties)
    public static final String DATASOURCE_PASSWORD = "spring.datasource.password";
    public static final String MAIL_PASSWORD = "jtac.mail.smtp.password";
    public static final String INTEGRATION_KEY = "jtac.integrations.secret-key";
    // Administration secrets (admin-secrets.properties, launcher only)
    public static final String DB_OWNER_PASSWORD = "jtrac.database.owner-password";
    public static final String DB_SUPERUSER_PASSWORD = "jtrac.database.superuser-password";

    private ConfigMapper() {
    }

    public static Map<String, String> toProperties(ServerConfig config) {
        Map<String, String> out = new LinkedHashMap<>();
        ServerConfig.Http http = config.http();
        out.put(BIND_ADDRESS, http.bindAddress());
        out.put(PORT, Integer.toString(http.port()));
        out.put(PUBLIC_URL, http.publicUrl());
        out.put(FORWARD_HEADERS, http.behindProxy() ? "native" : "none");
        out.put(SECURE_COOKIES, Boolean.toString(http.secureCookies()));
        out.put(SESSION_TIMEOUT, config.security().sessionTimeoutHours() + "h");

        ServerConfig.Database db = config.database();
        out.put(DB_MODE, db.mode().name());
        out.put(DB_HOST, db.host());
        out.put(DB_PORT, Integer.toString(db.port()));
        out.put(DB_NAME, db.name());
        out.put(DB_OWNER, db.ownerUser());
        out.put(DB_SSL_MODE, db.sslMode());
        out.put(DATASOURCE_URL, db.jdbcUrl());
        out.put(DATASOURCE_USER, db.appUser());
        out.put(APP_ROLE, db.appUser());

        SmtpSettings smtp = config.smtp();
        out.put(MAIL_TRANSPORT, smtp.enabled() ? "smtp" : "disabled");
        out.put(MAIL_HOST, smtp.host());
        out.put(MAIL_PORT, Integer.toString(smtp.port()));
        out.put(MAIL_AUTH, Boolean.toString(smtp.auth()));
        out.put(MAIL_USERNAME, smtp.username());
        out.put(MAIL_TLS, smtp.tls().name());
        out.put(MAIL_TIMEOUT, Integer.toString(smtp.timeoutSeconds()));
        out.put(MAIL_TRUSTED_CERTS, smtp.trustedCertificates());
        out.put(MAIL_FROM, smtp.fromAddress());
        out.put(MAIL_FROM_NAME, smtp.fromName());
        out.put(MAIL_REPLY_TO, smtp.replyTo());
        out.put(MAIL_MAX_ATTEMPTS, Integer.toString(smtp.maxAttempts()));

        out.put(REGISTRATION_MODE, config.security().registrationMode().name());

        ServerConfig.Storage storage = config.storage();
        out.put(ATTACHMENTS_DIR, storage.attachmentsDir());
        out.put(BACKUP_DIR, storage.backupDir());
        out.put(LOG_DIR, storage.logDir());
        out.put(LOG_FILE, storage.logDir().isBlank() ? "" : storage.logDir().replace('\\', '/') + "/jtrac.log");

        ServerConfig.Legal legal = config.legal();
        out.put(LEGAL_OPERATOR_NAME, legal.operatorName());
        out.put(LEGAL_OPERATOR_ADDRESS, legal.operatorAddress());
        out.put(LEGAL_CONTACT_EMAIL, legal.contactEmail());
        out.put(LEGAL_GRIEVANCE_NAME, legal.grievanceOfficerName());
        out.put(LEGAL_GRIEVANCE_EMAIL, legal.grievanceOfficerEmail());
        out.put(LEGAL_JURISDICTION, legal.jurisdiction());
        // Production hardening, always on for installed servers (development uses application.yaml).
        out.put("jtac.integrations.require-secret-key", "true");
        out.put("jtac.integrations.allow-private-networks", "false");
        out.put("jtac.integrations.allow-http", "false");
        out.put("server.error.include-stacktrace", "never");
        out.replaceAll((k, v) -> v == null ? "" : v);
        return out;
    }

    /** Only the email keys, for saving SMTP changes without touching other settings. */
    public static Map<String, String> toSmtpProperties(SmtpSettings smtp) {
        Map<String, String> all = toProperties(new ServerConfig(
                new ServerConfig.Http("127.0.0.1", 1, "", false),
                new ServerConfig.Database(DatabaseMode.MANAGED, "127.0.0.1", 1, "x", "a", "o", ""),
                smtp, new ServerConfig.Security(ServerConfig.RegistrationMode.INVITE_ONLY, 8),
                new ServerConfig.Storage("", "", ""), ServerConfig.Legal.EMPTY));
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : SMTP_KEYS) {
            out.put(key, all.get(key));
        }
        return out;
    }

    /** The email settings in a property map (other keys ignored). */
    public static SmtpSettings smtpFromProperties(Map<String, String> p) {
        return fromProperties(p).smtp();
    }

    /** Every key that belongs to the email settings (password excluded). */
    public static final java.util.List<String> SMTP_KEYS = java.util.List.of(MAIL_TRANSPORT, MAIL_HOST, MAIL_PORT, MAIL_AUTH,
            MAIL_USERNAME, MAIL_TLS, MAIL_TIMEOUT, MAIL_TRUSTED_CERTS, MAIL_FROM, MAIL_FROM_NAME, MAIL_REPLY_TO, MAIL_MAX_ATTEMPTS);

    public static ServerConfig fromProperties(Map<String, String> p) {
        ServerConfig.Http http = new ServerConfig.Http(
                get(p, BIND_ADDRESS, "127.0.0.1"),
                integer(p, PORT, 8080),
                get(p, PUBLIC_URL, ""),
                "native".equalsIgnoreCase(get(p, FORWARD_HEADERS, "none")));
        ServerConfig.Database db = new ServerConfig.Database(
                enumValue(DatabaseMode.class, get(p, DB_MODE, "MANAGED"), DatabaseMode.MANAGED),
                get(p, DB_HOST, "127.0.0.1"),
                integer(p, DB_PORT, 54329),
                get(p, DB_NAME, "jtrac"),
                get(p, DATASOURCE_USER, "jtrac_app"),
                get(p, DB_OWNER, "jtrac_owner"),
                get(p, DB_SSL_MODE, ""));
        int port = integer(p, MAIL_PORT, 587);
        SmtpSettings smtp = new SmtpSettings(
                "smtp".equalsIgnoreCase(get(p, MAIL_TRANSPORT, "disabled")),
                get(p, MAIL_HOST, ""),
                port,
                Boolean.parseBoolean(get(p, MAIL_AUTH, "true")),
                get(p, MAIL_USERNAME, ""),
                enumValue(TlsMode.class, get(p, MAIL_TLS, ""), SmtpSettings.defaultTls(port)),
                get(p, MAIL_FROM, ""),
                get(p, MAIL_FROM_NAME, "JTRAC"),
                get(p, MAIL_REPLY_TO, ""),
                integer(p, MAIL_TIMEOUT, SmtpSettings.DEFAULT_TIMEOUT_SECONDS),
                integer(p, MAIL_MAX_ATTEMPTS, SmtpSettings.DEFAULT_MAX_ATTEMPTS),
                get(p, MAIL_TRUSTED_CERTS, ""));
        String timeout = get(p, SESSION_TIMEOUT, "8h").toLowerCase(Locale.ROOT).replace("h", "");
        ServerConfig.Security security = new ServerConfig.Security(
                enumValue(ServerConfig.RegistrationMode.class, get(p, REGISTRATION_MODE, "INVITE_ONLY"),
                        ServerConfig.RegistrationMode.INVITE_ONLY),
                parseInt(timeout, 8));
        ServerConfig.Storage storage = new ServerConfig.Storage(
                get(p, ATTACHMENTS_DIR, ""), get(p, BACKUP_DIR, ""), get(p, LOG_DIR, ""));
        ServerConfig.Legal legal = new ServerConfig.Legal(
                get(p, LEGAL_OPERATOR_NAME, ""), get(p, LEGAL_OPERATOR_ADDRESS, ""), get(p, LEGAL_CONTACT_EMAIL, ""),
                get(p, LEGAL_GRIEVANCE_NAME, ""), get(p, LEGAL_GRIEVANCE_EMAIL, ""), get(p, LEGAL_JURISDICTION, ""));
        return new ServerConfig(http, db, smtp, security, storage, legal);
    }

    public static Map<String, String> toProperties(Secrets secrets) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(DATASOURCE_PASSWORD, nullToEmpty(secrets.databasePassword()));
        out.put(MAIL_PASSWORD, nullToEmpty(secrets.smtpPassword()));
        out.put(INTEGRATION_KEY, nullToEmpty(secrets.integrationSecretKey()));
        return out;
    }

    public static Secrets secretsFromProperties(Map<String, String> p) {
        return new Secrets(get(p, DATASOURCE_PASSWORD, ""), get(p, MAIL_PASSWORD, ""), get(p, INTEGRATION_KEY, ""));
    }

    public static Map<String, String> toProperties(AdminSecrets secrets) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(DB_OWNER_PASSWORD, nullToEmpty(secrets.ownerPassword()));
        out.put(DB_SUPERUSER_PASSWORD, nullToEmpty(secrets.superuserPassword()));
        return out;
    }

    public static AdminSecrets adminSecretsFromProperties(Map<String, String> p) {
        return new AdminSecrets(get(p, DB_OWNER_PASSWORD, ""), get(p, DB_SUPERUSER_PASSWORD, ""));
    }

    private static String get(Map<String, String> p, String key, String fallback) {
        String value = p.get(key);
        return value == null ? fallback : value.strip();
    }

    private static int integer(Map<String, String> p, String key, int fallback) {
        return parseInt(get(p, key, ""), fallback);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
