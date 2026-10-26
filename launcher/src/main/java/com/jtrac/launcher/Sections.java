package com.jtrac.launcher;

import com.jtrac.serverconfig.ConfigValidator;
import com.jtrac.serverconfig.PortCheck;
import com.jtrac.serverconfig.Problem;
import com.jtrac.serverconfig.ServerConfig;
import com.jtrac.serverconfig.SmtpSettings;
import com.jtrac.serverconfig.SmtpTestResult;
import com.jtrac.serverconfig.SmtpTester;
import com.jtrac.serverconfig.TlsMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The questions for each group of settings, shared by {@code jtrac setup} and
 * {@code jtrac configure}. Every answer is checked with the shared {@link ConfigValidator}.
 */
final class Sections {

    private Sections() {
    }

    /** Result of the SMTP section: the settings and the (possibly unchanged) password. */
    record Smtp(SmtpSettings settings, String password) {
    }

    static ServerConfig.Http http(Prompter p, ServerConfig.Http current, boolean checkPort) {
        Out.info("Bind address: 127.0.0.1 accepts connections from this computer only (recommended, also behind a");
        Out.info("reverse proxy). 0.0.0.0 accepts connections from the network.");
        String bind = p.ask("http.bind", "Bind address", current.bindAddress(),
                v -> ConfigValidator.isHost(v) ? Optional.empty() : Optional.of("Enter an IP address or host name."));
        int suggested = checkPort && PortCheck.problem(bind, current.port()).isPresent()
                ? PortCheck.firstFree(bind, current.port()) : current.port();
        if (suggested != current.port()) {
            Out.warn("Port " + current.port() + " is in use; suggesting " + suggested + ".");
        }
        int port;
        while (true) {
            port = p.askInt("http.port", "HTTP port", suggested, 1, 65535);
            Optional<String> problem = checkPort ? PortCheck.problem(bind, port) : Optional.empty();
            if (problem.isEmpty()) {
                break;
            }
            if (p.unattended()) {
                throw new SetupException(problem.get());
            }
            Out.warn(problem.get());
            suggested = PortCheck.firstFree(bind, port + 1);
        }
        String host = bind.equals("0.0.0.0") || bind.equals("::") ? "localhost" : bind;
        String defaultUrl = current.publicUrl() == null || current.publicUrl().isBlank()
                ? "http://" + host + ":" + port : current.publicUrl();
        Out.info("Public address: what people type in the browser, used for links in emails.");
        String url = p.ask("http.public-url", "Public address (URL)", defaultUrl, v -> errors(
                ConfigValidator.validateHttp(new ServerConfig.Http("127.0.0.1", 8080, v, false)), "http.publicUrl"));
        boolean proxy = p.askYesNo("http.behind-proxy", "Is JTRAC behind a reverse proxy that sets X-Forwarded-* headers",
                current.behindProxy());
        ServerConfig.Http http = new ServerConfig.Http(bind, port, url, proxy);
        showWarnings(ConfigValidator.validateHttp(http));
        return http;
    }

    static Smtp smtp(Prompter p, SmtpSettings current, String currentPassword, String defaultRecipient) {
        Out.info("Outgoing email delivers invitations, password resets and notifications. Any standard SMTP provider");
        Out.info("works (for example Zoho Mail, Microsoft 365, Google Workspace, or your organization's relay); use the");
        Out.info("host name, port and credentials your provider gives you.");
        boolean enable = p.askYesNo("smtp.enabled", "Configure outgoing email now", current.enabled() || !current.host().isBlank());
        if (!enable) {
            Out.warn("Email pending: invitations, password resets and notifications will wait in the queue until you run");
            Out.warn("`jtrac configure smtp` or set up email in Server settings.");
            return new Smtp(new SmtpSettings(false, current.host(), current.port(), current.auth(), current.username(),
                    current.tls(), current.fromAddress(), current.fromName(), current.replyTo(), current.timeoutSeconds(),
                    current.maxAttempts(), current.trustedCertificates()), currentPassword);
        }
        while (true) {
            String host = p.ask("smtp.host", "SMTP server host name", current.host(),
                    v -> ConfigValidator.isHost(v) ? Optional.empty() : Optional.of("Enter the SMTP host name from your provider."));
            String tlsKey = p.askChoice("smtp.tls", "Encryption", List.of(
                    new String[] {"STARTTLS", "STARTTLS - usually port 587 (recommended)"},
                    new String[] {"IMPLICIT", "Implicit TLS / SSL - usually port 465"},
                    new String[] {"NONE", "None - only for a trusted relay on a private network"}), current.tls().name());
            TlsMode tls = TlsMode.valueOf(tlsKey);
            int defaultPort = current.tls() == tls && current.port() > 0 ? current.port() : SmtpSettings.defaultPort(tls);
            int port = p.askInt("smtp.port", "SMTP port", defaultPort, 1, 65535);
            boolean auth = p.askYesNo("smtp.auth", "Sign in to the SMTP server (needed for providers)", tls != TlsMode.NONE && current.auth());
            String username = current.username();
            String password = currentPassword;
            if (auth) {
                username = p.ask("smtp.username", "SMTP user name", username.isBlank() ? null : username,
                        v -> v.isBlank() ? Optional.of("Enter the user name.") : Optional.empty());
                boolean keep = currentPassword != null && !currentPassword.isEmpty() && !p.unattended()
                        && p.askYesNo("smtp.keep-password", "Keep the saved SMTP password", true);
                if (!keep) {
                    password = p.askSecret("smtp.password", "JTRAC_SMTP_PASSWORD", "SMTP password (or app password)", false, false, null);
                }
            }
            String from = p.ask("smtp.from", "Sender (From) address", current.fromAddress().isBlank() && auth && username.contains("@")
                    ? username : current.fromAddress(), v -> errors(ConfigValidator.email("smtp.fromAddress", v, true), "smtp.fromAddress"));
            String fromName = p.ask("smtp.from-name", "Sender display name", current.fromName().isBlank() ? "JTRAC" : current.fromName(), null);
            String replyTo = p.ask("smtp.reply-to", "Reply-to address (optional, blank for none)", current.replyTo(),
                    v -> errors(ConfigValidator.email("smtp.replyTo", v, false), "smtp.replyTo"));
            int timeout = p.askInt("smtp.timeout", "Connection timeout in seconds", current.timeoutSeconds(), 1, 300);
            String trusted = "";
            if (tls != TlsMode.NONE && p.askYesNo("smtp.private-ca",
                    "Does the SMTP server use a certificate from a private (internal) CA", !current.trustedCertificates().isBlank())) {
                Out.info("Certificates are always verified. Give a PEM file with your CA certificate(s) to trust in addition.");
                trusted = p.ask("smtp.trusted-certificates", "CA certificate file (PEM, absolute path)", current.trustedCertificates(),
                        v -> {
                            if (v.isBlank()) {
                                return Optional.of("Enter the path of the PEM file.");
                            }
                            Optional<String> path = absoluteDir(v);
                            return path.isPresent() ? path : java.nio.file.Files.isRegularFile(Path.of(v)) ? Optional.empty()
                                    : Optional.of("File not found.");
                        });
            }
            SmtpSettings settings = new SmtpSettings(true, host, port, auth, auth ? username : "", tls, from, fromName, replyTo,
                    timeout, current.maxAttempts(), trusted);
            List<Problem> problems = ConfigValidator.validateSmtp(settings, password);
            if (ConfigValidator.hasErrors(problems)) {
                problems.stream().filter(Problem::isError).forEach(e -> Out.fail(e.message()));
                if (p.unattended()) {
                    throw new SetupException("The SMTP settings are invalid.");
                }
                continue;
            }
            showWarnings(problems);
            if (!auth && !p.unattended()
                    && !p.askYesNo("smtp.relay-confirmed", "Is this a trusted relay that accepts mail from this server", false)) {
                continue;
            }
            if (p.askYesNo("smtp.test", "Send a test email now", true)) {
                String to = p.ask("smtp.test.recipient", "Send the test to", defaultRecipient,
                        v -> errors(ConfigValidator.email("recipient", v, true), "recipient"));
                SmtpTestResult result = SmtpTester.test(settings, password, to);
                printTest(result);
                if (!result.accepted()) {
                    if (p.unattended()) {
                        throw new SetupException("The test email failed: " + result.summary());
                    }
                    String next = p.askChoice("smtp.after-failure", "What now", List.of(
                            new String[] {"retry", "Change the email settings"},
                            new String[] {"keep", "Save these settings anyway (fix later)"},
                            new String[] {"pending", "Skip email for now (email pending)"}), "retry");
                    if (next.equals("retry")) {
                        current = settings;
                        currentPassword = password;
                        continue;
                    }
                    if (next.equals("pending")) {
                        return new Smtp(new SmtpSettings(false, host, port, auth, settings.username(), tls, from, fromName, replyTo,
                                timeout, settings.maxAttempts(), settings.trustedCertificates()), password);
                    }
                }
            }
            return new Smtp(settings, password);
        }
    }

    static ServerConfig.Security security(Prompter p, ServerConfig.Security current) {
        String mode = p.askChoice("security.registration", "Who can create accounts", List.of(
                new String[] {"INVITE_ONLY", "Invited people only (recommended)"},
                new String[] {"OPEN", "Anyone who can open JTRAC (self sign-up with email confirmation)"}),
                current.registrationMode().name());
        int hours = p.askInt("security.session-hours", "Sign-in session length in hours", current.sessionTimeoutHours(), 1, 720);
        ServerConfig.Security security = new ServerConfig.Security(ServerConfig.RegistrationMode.valueOf(mode), hours);
        showWarnings(ConfigValidator.validateSecurity(security));
        return security;
    }

    static ServerConfig.Storage storage(Prompter p, ServerConfig.Storage current, Path data) {
        String attachments = p.ask("storage.attachments", "Attachments directory",
                orDefault(current.attachmentsDir(), data.resolve("attachments")), Sections::absoluteDir);
        String backups = p.ask("storage.backups", "Backups directory", orDefault(current.backupDir(), data.resolve("backups")),
                Sections::absoluteDir);
        String logs = p.ask("storage.logs", "Logs directory", orDefault(current.logDir(), data.resolve("logs")), Sections::absoluteDir);
        return new ServerConfig.Storage(attachments, backups, logs);
    }

    static ServerConfig.Legal legal(Prompter p, ServerConfig.Legal current) {
        Out.info("Optional: who operates this server, shown in the Terms of Service, Privacy Notice and grievance page");
        Out.info("(India's DPDP Act asks for a grievance officer). Leave blank to fill in later.");
        String name = p.ask("legal.operator-name", "Organization operating JTRAC", current.operatorName(), null);
        String address = p.ask("legal.operator-address", "Postal address", current.operatorAddress(), null);
        String contact = p.ask("legal.contact-email", "Privacy contact email", current.contactEmail(),
                v -> errors(ConfigValidator.email("legal.contactEmail", v, false), "legal.contactEmail"));
        String officer = p.ask("legal.grievance-officer-name", "Grievance officer name", current.grievanceOfficerName(), null);
        String officerEmail = p.ask("legal.grievance-officer-email", "Grievance officer email", current.grievanceOfficerEmail(),
                v -> errors(ConfigValidator.email("legal.grievanceOfficerEmail", v, false), "legal.grievanceOfficerEmail"));
        String jurisdiction = p.ask("legal.jurisdiction", "Courts for disputes (e.g. Chennai, Tamil Nadu)", current.jurisdiction(), null);
        return new ServerConfig.Legal(name, address, contact, officer, officerEmail, jurisdiction);
    }

    static void printTest(SmtpTestResult result) {
        for (SmtpTestResult.Step step : result.steps()) {
            String line = step.name() + ": " + step.detail();
            switch (step.status()) {
                case OK -> Out.ok(line);
                case WARNING, SKIPPED -> Out.warn(line);
                case FAILED -> Out.fail(line);
            }
        }
        if (result.accepted()) {
            Out.ok(result.summary());
        } else {
            Out.fail(result.summary());
        }
    }

    static void showWarnings(List<Problem> problems) {
        problems.stream().filter(pr -> !pr.isError()).forEach(pr -> Out.warn(pr.message()));
    }

    private static Optional<String> errors(List<Problem> problems, String field) {
        return problems.stream().filter(Problem::isError).filter(pr -> pr.field().equals(field)).map(Problem::message).findFirst();
    }

    private static Optional<String> absoluteDir(String value) {
        try {
            return Path.of(value).isAbsolute() ? Optional.empty() : Optional.of("Use an absolute path.");
        } catch (java.nio.file.InvalidPathException e) {
            return Optional.of("Not a valid path.");
        }
    }

    private static String orDefault(String value, Path fallback) {
        return value == null || value.isBlank() ? fallback.toString() : value;
    }

    static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
