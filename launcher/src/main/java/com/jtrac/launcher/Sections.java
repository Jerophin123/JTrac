package com.jtrac.launcher;

import com.jtrac.serverconfig.ConfigValidator;
import com.jtrac.serverconfig.OidcDiscovery;
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

    /** Result of the single sign-on section: the settings and the (possibly unchanged) client secret. */
    record Sso(ServerConfig.Sso settings, String clientSecret) {
        @Override
        public String toString() {
            return "Sso[" + settings + ", ****]";
        }
    }

    /** A well-known identity provider: suggested id and label, issuer (exact or an example), and setup hints. */
    private record IdpPreset(String id, String label, String issuer, boolean exactIssuer, boolean trustEmail, String note) {
    }

    private static final List<IdpPreset> IDP_PRESETS = List.of(
            new IdpPreset("entra", "Microsoft Entra ID", "https://login.microsoftonline.com/<tenant id>/v2.0", false, true,
                    "Use your tenant id (not common/organizations). Entra usually omits email_verified, so its email claim is "
                            + "trusted; add the optional 'email' claim under Token configuration."),
            new IdpPreset("okta", "Okta", "https://<your org>.okta.com", false, false,
                    "Create an OIDC Web Application; use the org URL or an authorization server such as .../oauth2/default."),
            new IdpPreset("google", "Google", "https://accounts.google.com", true, false,
                    "Create an OAuth client (Web application). Limit the email domains to your Workspace domains."),
            new IdpPreset("keycloak", "Keycloak", "https://<host>/realms/<realm>", false, false,
                    "Create a confidential OpenID Connect client with the standard flow enabled."),
            new IdpPreset("oidc", "Single sign-on", "https://<identity provider>", false, false,
                    "Any OpenID Connect provider (Ping, Auth0, AD FS at https://<host>/adfs, ...). Register a web application."));

    /**
     * Single sign-on with one OpenID Connect provider (docs/sso.md). The issuer is checked the
     * way the server reads it at startup, so a wrong value can't keep JTRAC from starting.
     */
    static Sso sso(Prompter p, ServerConfig.Sso current, String currentSecret, String publicUrl, String suggestedDomain) {
        Out.info("Single sign-on lets people sign in with your organization's identity provider (Microsoft Entra ID, Okta,");
        Out.info("Google Workspace, Keycloak or any OpenID Connect provider). Password sign-in stays available.");
        if (!p.askYesNo("sso.enabled", "Set up single sign-on now", current.enabled())) {
            Out.info("No single sign-on. Add it later with `jtrac configure sso`.");
            return new Sso(ServerConfig.Sso.DISABLED, "");
        }
        while (true) {
            ServerConfig.Sso was = current;
            String presetDefault = IDP_PRESETS.stream().anyMatch(pr -> pr.id().equals(was.providerId()))
                    ? was.providerId() : was.enabled() ? "oidc" : "entra";
            String presetId = p.askChoice("sso.provider", "Identity provider", IDP_PRESETS.stream()
                    .map(pr -> new String[] {pr.id(), pr.label()}).toList(), presetDefault);
            IdpPreset preset = IDP_PRESETS.stream().filter(pr -> pr.id().equals(presetId)).findFirst().orElseThrow();
            Out.info(preset.note());
            String id = p.ask("sso.provider-id", "Short id for this provider (part of the redirect URI)",
                    was.enabled() && !IDP_PRESETS.stream().anyMatch(pr -> pr.id().equals(was.providerId()))
                            ? was.providerId() : preset.id(),
                    v -> ssoError(new ServerConfig.Sso(true, v, "x", "https://x.example", "x", false, "", false), "sso.providerId"));
            boolean same = was.enabled() && was.providerId().equals(id);
            String label = p.ask("sso.name", "Sign-in button label (Sign in with ...)", same ? was.providerName() : preset.label(),
                    v -> ssoError(new ServerConfig.Sso(true, id, v, "https://x.example", "x", false, "", false), "sso.providerName"));
            Out.ok("Register this redirect URI at the identity provider: "
                    + new ServerConfig.Sso(true, id, label, "", "", false, "", false).redirectUri(publicUrl));
            String issuer = p.ask("sso.issuer-uri", "Issuer URL (e.g. " + preset.issuer() + ")",
                    same ? was.issuerUri() : preset.exactIssuer() ? preset.issuer() : null,
                    v -> ssoError(new ServerConfig.Sso(true, id, label, v, "x", false, "", false), "sso.issuerUri"));
            String clientId = p.ask("sso.client-id", "Client (application) id", same ? was.clientId() : null,
                    v -> ssoError(new ServerConfig.Sso(true, id, label, issuer, v, false, "", false), "sso.clientId"));
            String secret = currentSecret;
            boolean keep = currentSecret != null && !currentSecret.isEmpty() && !p.unattended()
                    && p.askYesNo("sso.keep-secret", "Keep the saved client secret", true);
            if (!keep) {
                secret = p.askSecret("sso.client-secret", "JTRAC_SSO_CLIENT_SECRET", "Client secret", false, false, null);
            }
            Out.info("People who already have an account (matched by verified email) can always use single sign-on.");
            boolean autoProvision = p.askYesNo("sso.auto-provision",
                    "Also create an account for anyone the provider signs in", same && was.autoProvision());
            String domains = p.ask("sso.allowed-domains", "Allowed email domains (comma-separated, blank for any)",
                    same ? was.allowedDomains() : suggestedDomain == null ? "" : suggestedDomain,
                    v -> ssoError(new ServerConfig.Sso(true, id, label, issuer, clientId, false, v, false), "sso.allowedDomains"));
            boolean trust = p.askYesNo("sso.trust-unverified-email",
                    "Trust the provider's email addresses without an email_verified claim",
                    same ? was.trustUnverifiedEmail() : preset.trustEmail());
            ServerConfig.Sso settings = new ServerConfig.Sso(true, id, label, issuer, clientId, autoProvision, domains.strip(), trust);
            List<Problem> problems = ConfigValidator.validateSso(settings, secret, publicUrl);
            if (ConfigValidator.hasErrors(problems)) {
                problems.stream().filter(Problem::isError).forEach(e -> Out.fail(e.message()));
                if (p.unattended()) {
                    throw new SetupException("The single sign-on settings are invalid.");
                }
                current = settings;
                currentSecret = secret;
                continue;
            }
            showWarnings(problems);
            if (p.askYesNo("sso.check", "Check the identity provider now (JTRAC can't start if it can't load it)", true)) {
                OidcDiscovery.Result result = OidcDiscovery.check(issuer);
                if (result.ok()) {
                    Out.ok(result.message());
                } else {
                    Out.fail(result.message());
                    if (p.unattended()) {
                        throw new SetupException("The identity provider check failed: " + result.message());
                    }
                    String next = p.askChoice("sso.after-failure", "What now", List.of(
                            new String[] {"retry", "Change the single sign-on settings"},
                            new String[] {"keep", "Save these settings anyway (JTRAC won't start until the provider is reachable)"},
                            new String[] {"skip", "Skip single sign-on for now"}), "retry");
                    if (next.equals("retry")) {
                        current = settings;
                        currentSecret = secret;
                        continue;
                    }
                    if (next.equals("skip")) {
                        return new Sso(ServerConfig.Sso.DISABLED, "");
                    }
                }
            }
            return new Sso(settings, secret);
        }
    }

    /** The first error the shared rules report for {@code field} of a (partly filled) SSO setting. */
    private static Optional<String> ssoError(ServerConfig.Sso candidate, String field) {
        return errors(ConfigValidator.validateSso(candidate, "x", null), field);
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
