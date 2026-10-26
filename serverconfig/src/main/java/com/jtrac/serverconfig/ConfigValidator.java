package com.jtrac.serverconfig;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The validation rules for every setting. The setup wizard, {@code jtrac configure} and the
 * server's administration pages all call these, so a value accepted in one place is accepted
 * everywhere.
 */
public final class ConfigValidator {

    private static final Pattern HOST_NAME = Pattern.compile(
            "^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*\\.?$");
    private static final Pattern IPV4 = Pattern.compile("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$");
    private static final Pattern IPV6 = Pattern.compile("^\\[?[0-9a-fA-F:.]+(%[\\w.]+)?]?$");
    private static final Pattern ROLE = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");
    private static final Pattern DATABASE = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,62}$");

    private ConfigValidator() {
    }

    public static List<Problem> validate(ServerConfig config, Secrets secrets) {
        List<Problem> problems = new ArrayList<>();
        problems.addAll(validateHttp(config.http()));
        problems.addAll(validateDatabase(config.database(), secrets.databasePassword()));
        problems.addAll(validateSmtp(config.smtp(), secrets.smtpPassword()));
        problems.addAll(validateSecurity(config.security()));
        problems.addAll(validateStorage(config.storage()));
        problems.addAll(validateLegal(config.legal()));
        if (secrets.integrationSecretKey() == null || secrets.integrationSecretKey().isBlank()) {
            problems.add(Problem.error("security.integrationSecretKey", "An encryption key for integration secrets is required."));
        }
        return problems;
    }

    public static boolean hasErrors(List<Problem> problems) {
        return problems.stream().anyMatch(Problem::isError);
    }

    public static List<Problem> validateHttp(ServerConfig.Http http) {
        List<Problem> problems = new ArrayList<>();
        if (!isHost(http.bindAddress())) {
            problems.add(Problem.error("http.bindAddress", "Enter an IP address such as 127.0.0.1 or 0.0.0.0, or a host name."));
        } else if (isAllInterfaces(http.bindAddress())) {
            problems.add(Problem.warning("http.bindAddress", "JTRAC will accept connections from other computers. Put it behind "
                    + "HTTPS (a reverse proxy) and a firewall before exposing it."));
        }
        if (http.port() < 1 || http.port() > 65535) {
            problems.add(Problem.error("http.port", "Use a port between 1 and 65535."));
        } else if (http.port() < 1024) {
            problems.add(Problem.warning("http.port", "Ports below 1024 need administrator rights on Linux and macOS. "
                    + "Prefer a port such as 8080 behind a reverse proxy."));
        }
        if (http.publicUrl() == null || http.publicUrl().isBlank()) {
            problems.add(Problem.error("http.publicUrl", "Enter the address people use to open JTRAC, e.g. https://jtrac.example.com."));
        } else {
            URI uri = parseUri(http.publicUrl());
            String scheme = uri == null || uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (uri == null || !(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                problems.add(Problem.error("http.publicUrl", "Use an absolute http:// or https:// address."));
            } else if (uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null) {
                problems.add(Problem.error("http.publicUrl", "The address must not contain a user name, query or fragment."));
            } else if (scheme.equals("http") && !isLoopbackHost(uri.getHost())) {
                problems.add(Problem.warning("http.publicUrl", "Plain http:// sends passwords unencrypted. Use https:// "
                        + "(a reverse proxy with a certificate) for anything beyond local testing."));
            }
        }
        if (http.behindProxy() && isAllInterfaces(http.bindAddress())) {
            problems.add(Problem.warning("http.behindProxy", "With forwarded headers trusted, bind JTRAC to 127.0.0.1 so only "
                    + "the reverse proxy can reach it; otherwise clients could spoof their address."));
        }
        return problems;
    }

    public static List<Problem> validateDatabase(ServerConfig.Database db, String appPassword) {
        List<Problem> problems = new ArrayList<>();
        if (!isHost(db.host())) {
            problems.add(Problem.error("database.host", "Enter the database host name or IP address."));
        } else if (db.mode() == DatabaseMode.MANAGED && !isLoopbackHost(db.host())) {
            problems.add(Problem.error("database.host", "The managed database only listens on the loopback interface (127.0.0.1)."));
        }
        if (db.port() < 1 || db.port() > 65535) {
            problems.add(Problem.error("database.port", "Use a port between 1 and 65535."));
        }
        if (!DATABASE.matcher(nullToEmpty(db.name())).matches()) {
            problems.add(Problem.error("database.name", "Use letters, digits and underscores, starting with a letter."));
        }
        if (!ROLE.matcher(nullToEmpty(db.appUser())).matches()) {
            problems.add(Problem.error("database.appUser", "Use lowercase letters, digits and underscores."));
        }
        if (!ROLE.matcher(nullToEmpty(db.ownerUser())).matches()) {
            problems.add(Problem.error("database.ownerUser", "Use lowercase letters, digits and underscores."));
        } else if (db.ownerUser().equals(db.appUser())) {
            problems.add(Problem.error("database.appUser", "The application role must differ from the schema owner (least privilege)."));
        }
        if (appPassword == null || appPassword.isBlank()) {
            problems.add(Problem.error("database.appPassword", "Enter the password of the application role."));
        }
        String ssl = nullToEmpty(db.sslMode());
        if (!ssl.isEmpty() && !List.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full").contains(ssl)) {
            problems.add(Problem.error("database.sslMode", "Use disable, prefer, require, verify-ca or verify-full."));
        }
        if (db.mode() == DatabaseMode.EXTERNAL && !isLoopbackHost(db.host()) && !ssl.startsWith("verify")) {
            problems.add(Problem.warning("database.sslMode", "Use sslmode=verify-full for a database on another machine, so the "
                    + "connection is encrypted and the server is authenticated."));
        }
        return problems;
    }

    public static List<Problem> validateSmtp(SmtpSettings smtp, String password) {
        List<Problem> problems = new ArrayList<>();
        if (!smtp.enabled()) {
            problems.add(Problem.warning("smtp.enabled", "Email is off: invitations, password resets and notifications are queued "
                    + "but not sent until SMTP is configured."));
            return problems;
        }
        if (!isHost(smtp.host())) {
            problems.add(Problem.error("smtp.host", "Enter the SMTP server's host name, e.g. the one your email provider lists for SMTP."));
        }
        if (smtp.port() < 1 || smtp.port() > 65535) {
            problems.add(Problem.error("smtp.port", "Use a port between 1 and 65535."));
        } else {
            if (smtp.port() == 465 && smtp.tls() == TlsMode.STARTTLS) {
                problems.add(Problem.warning("smtp.tls", "Port 465 normally uses implicit TLS, not STARTTLS."));
            }
            if (smtp.port() == 587 && smtp.tls() == TlsMode.IMPLICIT) {
                problems.add(Problem.warning("smtp.tls", "Port 587 normally uses STARTTLS, not implicit TLS."));
            }
            if (smtp.port() == 25 && smtp.tls() != TlsMode.NONE) {
                problems.add(Problem.warning("smtp.port", "Port 25 is meant for relays between mail servers and is often blocked "
                        + "by ISPs and cloud providers; providers usually offer 587 or 465 for sending."));
            }
        }
        if (smtp.auth()) {
            if (smtp.username() == null || smtp.username().isBlank()) {
                problems.add(Problem.error("smtp.username", "Enter the SMTP user name."));
            }
            if (password == null || password.isEmpty()) {
                problems.add(Problem.error("smtp.password", "Enter the SMTP password (or the app password your provider issued)."));
            }
            if (smtp.tls() == TlsMode.NONE) {
                problems.add(Problem.error("smtp.tls", "Signing in over an unencrypted connection would expose the password. "
                        + "Use STARTTLS or implicit TLS."));
            }
        } else {
            problems.add(Problem.warning("smtp.auth", "Sending without signing in only works through a trusted relay that accepts "
                    + "mail from this server's address. Don't use it with public email providers."));
        }
        if (smtp.tls() == TlsMode.NONE) {
            problems.add(Problem.warning("smtp.tls", "Email will travel unencrypted to the relay. Use this only on a private network."));
        }
        problems.addAll(email("smtp.fromAddress", smtp.fromAddress(), true));
        problems.addAll(email("smtp.replyTo", smtp.replyTo(), false));
        if (smtp.fromName() != null && smtp.fromName().length() > 100) {
            problems.add(Problem.error("smtp.fromName", "Use at most 100 characters."));
        }
        if (smtp.timeoutSeconds() < 1 || smtp.timeoutSeconds() > 300) {
            problems.add(Problem.error("smtp.timeoutSeconds", "Use a timeout between 1 and 300 seconds."));
        }
        if (smtp.maxAttempts() < 1 || smtp.maxAttempts() > 50) {
            problems.add(Problem.error("smtp.maxAttempts", "Use between 1 and 50 attempts."));
        }
        if (smtp.trustedCertificates() != null && !smtp.trustedCertificates().isBlank()) {
            problems.addAll(absolutePath("smtp.trustedCertificates", smtp.trustedCertificates()));
        }
        return problems;
    }

    public static List<Problem> validateSecurity(ServerConfig.Security security) {
        List<Problem> problems = new ArrayList<>();
        if (security.sessionTimeoutHours() < 1 || security.sessionTimeoutHours() > 720) {
            problems.add(Problem.error("security.sessionTimeoutHours", "Use between 1 and 720 hours."));
        }
        if (security.registrationMode() == ServerConfig.RegistrationMode.OPEN) {
            problems.add(Problem.warning("security.registrationMode", "Anyone who can reach JTRAC can create an account. "
                    + "They can't create organizations unless a server administrator allows it."));
        }
        return problems;
    }

    public static List<Problem> validateStorage(ServerConfig.Storage storage) {
        List<Problem> problems = new ArrayList<>();
        problems.addAll(absolutePath("storage.attachmentsDir", storage.attachmentsDir()));
        problems.addAll(absolutePath("storage.backupDir", storage.backupDir()));
        problems.addAll(absolutePath("storage.logDir", storage.logDir()));
        return problems;
    }

    public static List<Problem> validateLegal(ServerConfig.Legal legal) {
        List<Problem> problems = new ArrayList<>();
        problems.addAll(email("legal.contactEmail", legal.contactEmail(), false));
        problems.addAll(email("legal.grievanceOfficerEmail", legal.grievanceOfficerEmail(), false));
        if (legal.operatorName().isBlank() || legal.grievanceOfficerEmail().isBlank()) {
            problems.add(Problem.warning("legal.operatorName", "The Terms of Service and Privacy Notice will show placeholders until "
                    + "the operator name and grievance officer email are set."));
        }
        return problems;
    }

    /** Validates one email address; blank is allowed unless required. */
    public static List<Problem> email(String field, String value, boolean required) {
        if (value == null || value.isBlank()) {
            return required ? List.of(Problem.error(field, "Enter an email address.")) : List.of();
        }
        try {
            InternetAddress address = new InternetAddress(value.strip(), true);
            String text = address.getAddress();
            if (address.getPersonal() != null || !text.contains("@") || text.startsWith("@") || text.endsWith("@")
                    || text.length() > 320) {
                return List.of(Problem.error(field, "Enter just the address, e.g. jtrac@example.com."));
            }
            return List.of();
        } catch (AddressException e) {
            return List.of(Problem.error(field, "Enter a valid email address, e.g. jtrac@example.com."));
        }
    }

    public static boolean isHost(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.strip();
        return IPV4.matcher(v).matches() || (v.contains(":") && IPV6.matcher(v).matches()) || HOST_NAME.matcher(v).matches();
    }

    public static boolean isLoopbackHost(String host) {
        String h = host == null ? "" : host.strip().toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        return h.equals("localhost") || h.startsWith("127.") || h.equals("::1");
    }

    private static boolean isAllInterfaces(String host) {
        String h = host == null ? "" : host.strip();
        return h.equals("0.0.0.0") || h.equals("::") || h.equals("[::]");
    }

    private static List<Problem> absolutePath(String field, String value) {
        if (value == null || value.isBlank()) {
            return List.of(Problem.error(field, "Enter a directory."));
        }
        try {
            if (!Path.of(value).isAbsolute()) {
                return List.of(Problem.error(field, "Use an absolute path."));
            }
            return List.of();
        } catch (InvalidPathException e) {
            return List.of(Problem.error(field, "This is not a valid path on this system."));
        }
    }

    private static URI parseUri(String value) {
        try {
            return new URI(value.strip());
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
