package com.jtrac.launcher;

import com.jtrac.serverconfig.AdminSecrets;
import com.jtrac.serverconfig.ConfigValidator;
import com.jtrac.serverconfig.ConfigStore;
import com.jtrac.serverconfig.DatabaseMode;
import com.jtrac.serverconfig.PortCheck;
import com.jtrac.serverconfig.Problem;
import com.jtrac.serverconfig.SecretGenerator;
import com.jtrac.serverconfig.Secrets;
import com.jtrac.serverconfig.ServerConfig;
import com.jtrac.serverconfig.SmtpSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * First-run setup: collects, validates and saves the configuration, provisions a new empty
 * database, applies migrations, creates the first administrator, starts JTRAC and checks its
 * health. Re-running it never overwrites settings, rotates secrets or touches existing data:
 * finished steps are recorded and skipped, and a configured installation is left as it is.
 */
final class SetupWizard {

    private static final int STAGES = 13;
    private static final int MANAGED_DB_PORT = 54329;

    private final Prompter p;
    private Installation installation;

    SetupWizard(Prompter prompter, Installation installation) {
        this.p = prompter;
        this.installation = installation;
    }

    int run(boolean startWhenDone) throws IOException {
        Out.title("JTRAC server setup");
        Platform platform = Platform.current();

        // 1. Welcome and installation mode
        Out.step(1, STAGES, "Welcome");
        Out.info("This sets up a JTRAC server: configuration, a new empty database, the first administrator,");
        Out.info("outgoing email, and then starts it. Nothing is sent anywhere except your own SMTP test email.");
        if (!platform.supported()) {
            throw new SetupException("This CPU architecture (" + System.getProperty("os.arch") + ") is not supported.");
        }
        Out.ok("Platform " + platform.id() + ", JTRAC " + installation.version());
        if (installation.setupComplete()) {
            Out.ok("JTRAC is already set up (data in " + installation.data() + ").");
            Out.info("Nothing was changed. Use `jtrac configure` to change settings, or `jtrac start` to start it.");
            return 0;
        }
        boolean bundled = installation.hasBundledPostgres();
        if (bundled && runningAsRoot()) {
            Out.warn("You are running setup as root. The managed PostgreSQL refuses to run as root, and JTRAC never");
            Out.warn("needs root: create a dedicated account (e.g. `sudo useradd -m jtrac`), give it the package folder,");
            Out.warn("and run setup as that user. Only the external-database mode is available as root.");
            bundled = false;
        }
        String mode = bundled ? p.askChoice("database.mode", "Database", List.of(
                new String[] {"MANAGED", "Managed: JTRAC runs its bundled PostgreSQL 18 on this computer (recommended)"},
                new String[] {"EXTERNAL", "External: use a PostgreSQL 18 server you provide"}), "MANAGED")
                : "EXTERNAL";
        if (!bundled) {
            Out.warn("This package has no bundled PostgreSQL for " + platform.id() + ": an external PostgreSQL 18 server is required.");
        }

        // 2. Directories and runtime
        Out.step(2, STAGES, "Directories and runtime");
        String dataDir = p.ask("data.dir", "Data directory (configuration, database, attachments, backups, logs)",
                installation.data().toString(), v -> {
                    try {
                        return Path.of(v).isAbsolute() ? Optional.empty() : Optional.of("Use an absolute path.");
                    } catch (java.nio.file.InvalidPathException e) {
                        return Optional.of("Not a valid path.");
                    }
                });
        installation = installation.withData(Path.of(dataDir));
        Files.createDirectories(installation.data());
        Files.createDirectories(installation.runDir());
        if (!Files.isWritable(installation.data())) {
            throw new SetupException("The data directory " + installation.data() + " is not writable by this account.");
        }
        Out.ok("Data directory " + installation.data());
        Out.ok("Java runtime " + Runtime.version() + " (" + System.getProperty("java.home") + ")");
        if (!Files.isRegularFile(installation.serverJar())) {
            throw new SetupException("The server (" + installation.serverJar() + ") is missing; the package is incomplete.");
        }
        ConfigStore store = installation.store();
        boolean resuming = store.exists();
        ServerConfig config = resuming ? store.loadConfig() : defaults(installation.data());
        Secrets secrets = resuming ? store.loadSecrets() : Secrets.EMPTY;
        AdminSecrets adminSecrets = resuming ? store.loadAdminSecrets() : AdminSecrets.EMPTY;
        if (resuming) {
            Out.warn("A previous setup was not finished; continuing with the saved settings.");
        }

        // 3. Database
        Out.step(3, STAGES, "Database");
        ExternalAdmin externalAdmin = null;
        if (!installation.stepDone("database")) {
            if (mode.equals("MANAGED")) {
                ManagedPostgres pg = new ManagedPostgres(installation, MANAGED_DB_PORT);
                Out.ok("Bundled " + pg.version());
                int port = config.database().mode() == DatabaseMode.MANAGED && resuming ? config.database().port()
                        : p.askInt("database.port", "Port for the managed database (loopback only)",
                                PortCheck.firstFree("127.0.0.1", MANAGED_DB_PORT), 1024, 65535);
                config = config.withDatabase(new ServerConfig.Database(DatabaseMode.MANAGED, "127.0.0.1", port, "jtrac",
                        "jtrac_app", "jtrac_owner", "disable"));
                if (secrets.databasePassword().isBlank()) {
                    secrets = new Secrets(SecretGenerator.password(32), secrets.smtpPassword(), secrets.integrationSecretKey());
                    adminSecrets = new AdminSecrets(SecretGenerator.password(32), SecretGenerator.password(32));
                }
                Out.ok("Database jtrac with roles jtrac_owner (migrations) and jtrac_app (application); random passwords "
                        + "are stored in owner-only files.");
            } else {
                Out.info("JTRAC needs PostgreSQL 18 with two roles: a schema owner (runs migrations) and an application");
                Out.info("role with read/write access only.");
                String host = p.ask("database.host", "Database host", config.database().mode() == DatabaseMode.EXTERNAL
                        ? config.database().host() : "localhost", v -> ConfigValidator.isHost(v) ? Optional.empty()
                        : Optional.of("Enter a host name or IP address."));
                int port = p.askInt("database.port", "Database port", 5432, 1, 65535);
                String name = p.ask("database.name", "Database name", "jtrac", null);
                String owner = p.ask("database.owner-user", "Schema owner role", "jtrac_owner", null);
                String app = p.ask("database.app-user", "Application role", "jtrac_app", null);
                String ssl = p.ask("database.ssl-mode", "sslmode (disable, require, verify-full)",
                        ConfigValidator.isLoopbackHost(host) ? "disable" : "verify-full", null);
                config = config.withDatabase(new ServerConfig.Database(DatabaseMode.EXTERNAL, host, port, name, app, owner, ssl));
                String ownerPassword = p.askSecret("database.owner-password", "JTRAC_DB_OWNER_PASSWORD", "Password of " + owner, false, false, null);
                String appPassword = p.askSecret("database.app-password", "JTRAC_DB_APP_PASSWORD", "Password of " + app, false, false, null);
                secrets = new Secrets(appPassword, secrets.smtpPassword(), secrets.integrationSecretKey());
                adminSecrets = new AdminSecrets(ownerPassword, "");
                if (p.askYesNo("database.provision", "Create the roles, database and schema now with an administrator account", false)) {
                    String adminUser = p.ask("database.admin-user", "Administrator role (used once, not stored)", "postgres", null);
                    String adminPassword = p.askSecret("database.admin-password", "JTRAC_DB_ADMIN_PASSWORD",
                            "Password of " + adminUser + " (not stored)", false, false, null);
                    externalAdmin = new ExternalAdmin(adminUser, adminPassword);
                } else {
                    Out.info("If the database isn't provisioned yet, have a DBA run this script (passwords replaced):");
                    Out.blank();
                    Out.line(DatabaseSetup.provisioningScript(config.database()));
                }
            }
        } else {
            Out.ok("Database already set up (" + config.database().mode() + ", " + config.database().host() + ":"
                    + config.database().port() + ").");
        }
        if (secrets.integrationSecretKey().isBlank()) {
            secrets = new Secrets(secrets.databasePassword(), secrets.smtpPassword(), SecretGenerator.key256());
        }

        // 4. Initial administrator
        Out.step(4, STAGES, "First administrator");
        Admin admin = null;
        if (!installation.stepDone("administrator")) {
            Out.info("This account administers the server: it can create organizations and change server settings.");
            String name = p.ask("admin.name", "Name", null, v -> v.isBlank() || v.length() > 120
                    ? Optional.of("Enter 1 to 120 characters.") : Optional.empty());
            String email = p.ask("admin.email", "Email (used to sign in)", null,
                    v -> ConfigValidator.email("admin.email", v, true).stream().findFirst().map(Problem::message));
            String password = p.askSecret("admin.password", "JTRAC_ADMIN_PASSWORD", "Password (12 to 128 characters)", true, false,
                    v -> v.length() < 12 || v.length() > 128 || v.isBlank() ? Optional.of("Use 12 to 128 characters.") : Optional.empty());
            admin = new Admin(name, email, password);
        } else {
            Out.ok("Administrator already created.");
        }

        // 5 + 6. HTTP port, bind address, public URL and proxy
        Out.step(5, STAGES, "Web server address and port");
        ServerConfig.Http http = Sections.http(p, config.http(), true);
        Out.step(6, STAGES, "Public address and reverse proxy");
        Out.ok("People will open JTRAC at " + http.publicUrl() + (http.behindProxy() ? " (forwarded headers trusted)" : ""));
        if (http.secureCookies()) {
            Out.ok("HTTPS address: session cookies are marked Secure.");
        }
        config = config.withHttp(http);

        // 7. SMTP
        Out.step(7, STAGES, "Outgoing email (SMTP)");
        Sections.Smtp smtp = Sections.smtp(p, config.smtp(), secrets.smtpPassword(), admin == null ? "" : admin.email());
        config = config.withSmtp(smtp.settings());
        secrets = secrets.withSmtpPassword(smtp.password() == null ? "" : smtp.password());

        // 8. Security
        Out.step(8, STAGES, "Accounts and sessions");
        config = config.withSecurity(Sections.security(p, config.security()));

        // 9. Storage (and optional legal details)
        Out.step(9, STAGES, "Attachments, backups and logs");
        config = config.withStorage(Sections.storage(p, config.storage(), installation.data()));
        if (p.askYesNo("legal.configure", "Enter the operator details for the legal pages now", false)) {
            config = config.withLegal(Sections.legal(p, config.legal()));
        }

        // 10. Review
        Out.step(10, STAGES, "Review");
        printSummary(config, admin);
        List<Problem> problems = ConfigValidator.validate(config, secrets);
        problems.stream().filter(Problem::isError).forEach(e -> Out.fail(e.field() + ": " + e.message()));
        if (ConfigValidator.hasErrors(problems)) {
            throw new SetupException("Fix the settings above and run setup again; nothing was applied.");
        }
        if (!p.askYesNo("confirm", "Apply these settings", true)) {
            Out.info("Nothing was applied.");
            return 1;
        }

        // 11. Save, provision, migrate, create the administrator
        Out.step(11, STAGES, "Database and secure bootstrap");
        for (String dir : List.of(config.storage().attachmentsDir(), config.storage().backupDir(), config.storage().logDir())) {
            Files.createDirectories(Path.of(dir));
        }
        store.saveConfig(config);
        store.saveSecrets(secrets);
        store.saveAdminSecrets(adminSecrets);
        installation.writePointer();
        Out.ok("Settings saved to " + store.directory() + " (secrets in owner-only files).");
        bootstrapDatabase(config, secrets, adminSecrets, externalAdmin);
        if (admin != null) {
            long accounts = accountsOrFail(config, secrets);
            if (accounts > 0) {
                throw new SetupException("The database already contains " + accounts + " account(s). Setup never adds data to an "
                        + "existing JTRAC database; use an empty database.");
            }
            DatabaseSetup.createAdministrator(installation, admin.name(), admin.email(), admin.password());
            installation.markStep("administrator");
            Out.ok("Administrator " + admin.email() + " created.");
        }
        installation.markStep("complete");

        // 12. Start and health check
        Out.step(12, STAGES, "Start JTRAC");
        boolean start = startWhenDone && p.askYesNo("start", "Start JTRAC now", true);
        if (start) {
            int code = new Lifecycle(installation).start(Duration.ofMinutes(3));
            if (code != 0) {
                return code;
            }
        }

        // 13. Done
        Out.step(13, STAGES, "Done");
        Out.ok("JTRAC is set up. Open " + config.http().publicUrl() + " and sign in" + (admin == null ? "." : " as " + admin.email() + "."));
        if (!config.smtp().enabled()) {
            Out.warn("Email is pending: set it up later with `jtrac configure smtp` or in Server settings.");
        }
        Out.info("Commands: jtrac status | stop | start | logs | backup | configure | test-email");
        Out.info("Start automatically: jtrac service install");
        List<String> unused = p.unusedAnswers();
        if (!unused.isEmpty()) {
            Out.warn("Unused answers (typos?): " + String.join(", ", unused));
        }
        return 0;
    }

    private void bootstrapDatabase(ServerConfig config, Secrets secrets, AdminSecrets adminSecrets, ExternalAdmin external)
            throws IOException {
        ServerConfig.Database db = config.database();
        if (!installation.stepDone("database")) {
            try {
                if (db.mode() == DatabaseMode.MANAGED) {
                    ManagedPostgres pg = new ManagedPostgres(installation, db.port());
                    if (!pg.initialized()) {
                        pg.initialize(adminSecrets.superuserPassword());
                        Out.ok("Initialized a new, empty PostgreSQL cluster in " + installation.pgData());
                    }
                    pg.start();
                    Out.ok("Managed PostgreSQL listening on 127.0.0.1:" + db.port());
                    DatabaseSetup.provision(db, ManagedPostgres.SUPERUSER, adminSecrets.superuserPassword(), "postgres",
                            adminSecrets.ownerPassword(), secrets.databasePassword());
                } else if (external != null) {
                    DatabaseSetup.provision(db, external.user(), external.password(), "postgres", adminSecrets.ownerPassword(),
                            secrets.databasePassword());
                }
            } catch (SQLException e) {
                throw new SetupException("Database provisioning failed: " + e.getMessage(), e);
            }
            List<String> access = DatabaseSetup.verifyAccess(db, adminSecrets.ownerPassword(), secrets.databasePassword());
            if (!access.isEmpty()) {
                access.forEach(Out::fail);
                throw new SetupException("The database is not ready for JTRAC (see above).");
            }
            Out.ok("Roles verified: the application role can read and write data but cannot change the schema.");
            installation.markStep("database");
        } else if (db.mode() == DatabaseMode.MANAGED) {
            new ManagedPostgres(installation, db.port()).start();
        }
        DatabaseSetup.migrate(installation, db, adminSecrets.ownerPassword());
        installation.markStep("migrations");
    }

    private long accountsOrFail(ServerConfig config, Secrets secrets) {
        try {
            return DatabaseSetup.accountCount(config.database(), secrets.databasePassword());
        } catch (SQLException e) {
            throw new SetupException("Could not read the database: " + e.getMessage(), e);
        }
    }

    /** PostgreSQL's initdb refuses root on Linux and macOS. */
    static boolean runningAsRoot() {
        if (Platform.current().os() == Platform.Os.WINDOWS) {
            return false;
        }
        return Processes.run(List.of("id", "-u"), java.util.Map.of(), null, Duration.ofSeconds(10)).output().strip().equals("0");
    }

    static ServerConfig defaults(Path data) {
        return new ServerConfig(
                new ServerConfig.Http("127.0.0.1", 8080, "", false),
                new ServerConfig.Database(DatabaseMode.MANAGED, "127.0.0.1", MANAGED_DB_PORT, "jtrac", "jtrac_app", "jtrac_owner", "disable"),
                SmtpSettings.disabled(),
                new ServerConfig.Security(ServerConfig.RegistrationMode.INVITE_ONLY, 8),
                new ServerConfig.Storage(data.resolve("attachments").toString(), data.resolve("backups").toString(),
                        data.resolve("logs").toString()),
                ServerConfig.Legal.EMPTY);
    }

    private static void printSummary(ServerConfig c, Admin admin) {
        Out.info("Web:        " + c.http().bindAddress() + ":" + c.http().port() + "  ->  " + c.http().publicUrl()
                + (c.http().behindProxy() ? "  (behind proxy)" : ""));
        Out.info("Database:   " + c.database().mode() + " " + c.database().host() + ":" + c.database().port() + "/"
                + c.database().name() + "  (owner " + c.database().ownerUser() + ", app " + c.database().appUser() + ")");
        Out.info("Email:      " + (c.smtp().enabled() ? c.smtp().host() + ":" + c.smtp().port() + " " + c.smtp().tls()
                + (c.smtp().auth() ? " as " + c.smtp().username() : " (no sign-in)") + ", from " + c.smtp().fromAddress()
                : "pending (not configured)"));
        Out.info("Accounts:   " + c.security().registrationMode() + ", sessions " + c.security().sessionTimeoutHours() + " h");
        Out.info("Storage:    attachments " + c.storage().attachmentsDir());
        Out.info("            backups " + c.storage().backupDir() + ", logs " + c.storage().logDir());
        if (admin != null) {
            Out.info("Admin:      " + admin.name() + " <" + admin.email() + ">");
        }
        Out.info("Secrets:    stored separately, readable only by this account (never shown)");
    }

    private record Admin(String name, String email, String password) {
        @Override
        public String toString() {
            return "Admin[" + email + "]";
        }
    }

    private record ExternalAdmin(String user, String password) {
        @Override
        public String toString() {
            return "ExternalAdmin[" + user + "]";
        }
    }
}
