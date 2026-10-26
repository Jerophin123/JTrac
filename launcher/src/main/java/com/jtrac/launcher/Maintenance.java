package com.jtrac.launcher;

import com.jtrac.serverconfig.ConfigStore;
import com.jtrac.serverconfig.ConfigValidator;
import com.jtrac.serverconfig.DatabaseMode;
import com.jtrac.serverconfig.Problem;
import com.jtrac.serverconfig.Secrets;
import com.jtrac.serverconfig.ServerConfig;
import com.jtrac.serverconfig.SmtpTestResult;
import com.jtrac.serverconfig.SmtpTester;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** configure, test-email, migrate, update and uninstall. */
final class Maintenance {

    private final Installation installation;
    private final Prompter prompter;

    Maintenance(Installation installation, Prompter prompter) {
        this.installation = installation;
        this.prompter = prompter;
    }

    /** Changes one group of settings (or all) with the same questions and checks as setup. */
    int configure(String section) throws IOException {
        ConfigStore store = installation.store();
        if (!store.exists()) {
            throw new SetupException("JTRAC is not set up yet. Run `jtrac setup` first.");
        }
        ServerConfig config = store.loadConfig();
        Secrets secrets = store.loadSecrets();
        String which = section == null ? prompter.askChoice("section", "What do you want to change", List.of(
                new String[] {"http", "Web address, port and reverse proxy"},
                new String[] {"smtp", "Outgoing email (SMTP)"},
                new String[] {"security", "Account registration and session length"},
                new String[] {"storage", "Attachment, backup and log directories"},
                new String[] {"legal", "Operator details for the legal pages"}), "smtp") : section;
        boolean running = new ServerProcess(installation).running().isPresent();
        switch (which) {
            case "http" -> config = config.withHttp(Sections.http(prompter, config.http(), !running));
            case "smtp" -> {
                Sections.Smtp smtp = Sections.smtp(prompter, config.smtp(), secrets.smtpPassword(), "");
                config = config.withSmtp(smtp.settings());
                secrets = secrets.withSmtpPassword(smtp.password() == null ? "" : smtp.password());
            }
            case "security" -> config = config.withSecurity(Sections.security(prompter, config.security()));
            case "storage" -> config = config.withStorage(Sections.storage(prompter, config.storage(), installation.data()));
            case "legal" -> config = config.withLegal(Sections.legal(prompter, config.legal()));
            default -> throw new SetupException("Unknown section '" + which + "'. Use http, smtp, security, storage or legal.");
        }
        List<Problem> problems = ConfigValidator.validate(config, secrets);
        problems.stream().filter(Problem::isError).forEach(e -> Out.fail(e.field() + ": " + e.message()));
        if (ConfigValidator.hasErrors(problems)) {
            throw new SetupException("Nothing was saved.");
        }
        store.saveConfig(config);
        store.saveSecrets(secrets);
        Out.ok("Saved to " + store.directory() + ".");
        if (running) {
            if (prompter.askYesNo("restart", "Restart JTRAC now to apply the changes", true)) {
                return new Lifecycle(installation).restart();
            }
            Out.info("Restart JTRAC (`jtrac restart`) to apply the changes.");
        }
        return 0;
    }

    int testEmail(String recipient) throws IOException {
        ServerConfig config = new Lifecycle(installation).config();
        Secrets secrets = installation.store().loadSecrets();
        if (!config.smtp().enabled() && config.smtp().host().isBlank()) {
            Out.fail("Email is not configured. Run `jtrac configure smtp`.");
            return 1;
        }
        if (!config.smtp().enabled()) {
            Out.warn("Email is turned off (pending); testing the saved settings anyway.");
        }
        String to = recipient != null ? recipient : prompter.ask("to", "Send the test email to", null,
                v -> ConfigValidator.email("recipient", v, true).stream().findFirst().map(Problem::message));
        SmtpTestResult result = SmtpTester.test(config.smtp(), secrets.smtpPassword(), to);
        Sections.printTest(result);
        return result.accepted() ? 0 : 1;
    }

    int migrate() throws IOException {
        ServerConfig config = new Lifecycle(installation).config();
        if (config.database().mode() == DatabaseMode.MANAGED) {
            new ManagedPostgres(installation, config.database().port()).start();
        }
        DatabaseSetup.migrate(installation, config.database(), installation.store().loadAdminSecrets().ownerPassword());
        return 0;
    }

    /**
     * Moves an installation to this (newer) release: stops the old server, makes a verified
     * backup, applies migrations with this release, and starts it. The data directory, settings
     * and secrets are kept as they are.
     */
    int update(Path oldHome) throws IOException {
        if (!installation.store().exists()) {
            throw new SetupException("No JTRAC installation found in " + installation.data()
                    + ". Pass --data-dir or --from <old installation folder>.");
        }
        Out.title("JTRAC update to " + installation.version());
        Out.info("Data directory: " + installation.data() + (oldHome != null ? " (from " + oldHome + ")" : ""));
        ServerConfig config = installation.store().loadConfig();
        ServerProcess server = new ServerProcess(installation);
        if (server.running().isPresent()) {
            server.stop(Duration.ofSeconds(60));
            Out.ok("Stopped the running JTRAC.");
        }
        String before = DatabaseSetup.schemaVersion(config.database(), installation.store().loadSecrets().databasePassword(),
                config.database().appUser());
        if (config.database().mode() == DatabaseMode.MANAGED) {
            new ManagedPostgres(installation, config.database().port()).start();
            before = DatabaseSetup.schemaVersion(config.database(), installation.store().loadSecrets().databasePassword(),
                    config.database().appUser());
        }
        Out.info("Making a verified backup before changing anything...");
        Path backup = new Backups(installation).backup(null, true);
        Out.ok("Pre-update backup: " + backup);
        DatabaseSetup.migrate(installation, config.database(), installation.store().loadAdminSecrets().ownerPassword());
        installation.writePointer();
        if (oldHome != null) {
            Files.deleteIfExists(oldHome.resolve(Installation.POINTER_FILE));
        }
        Out.ok("Schema " + before + " -> " + DatabaseSetup.schemaVersion(config.database(),
                installation.store().loadSecrets().databasePassword(), config.database().appUser()));
        if (new Services(installation).installed()) {
            Out.warn("A service is registered for the old folder. Run `jtrac service install` from this folder to update it.");
        }
        int code = new Lifecycle(installation).start(Duration.ofMinutes(3));
        Out.info("Rollback: stop JTRAC, then from the previous release folder run `jtrac restore " + backup + "`.");
        return code;
    }

    /** Removes the service registration and the link to the data; keeps all data unless told otherwise. */
    int uninstall(boolean deleteData) throws IOException {
        Out.title("Uninstall JTRAC");
        if (installation.store().exists()) {
            new Lifecycle(installation).stop();
        }
        Services services = new Services(installation);
        if (services.installed()) {
            services.remove();
        }
        installation.removePointer();
        if (!deleteData) {
            Out.ok("JTRAC is stopped and unregistered. Your data is kept in " + installation.data() + ".");
            Out.info("You can now delete the program folder " + installation.home() + ".");
            Out.info("To also delete all data (database, attachments, backups, settings), run `jtrac uninstall --delete-data`.");
            return 0;
        }
        Path data = installation.data();
        Out.warn("This permanently deletes ALL JTRAC data in " + data + ":");
        Out.warn("the database, every attachment, all backups in that folder, settings and secrets.");
        String confirm = prompter.ask("confirm-delete", "Type the full path of the data directory to confirm", "", null);
        if (!confirm.strip().equals(data.toString())) {
            Out.info("Not confirmed; nothing was deleted.");
            return 1;
        }
        try (var walk = Files.walk(data)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
        Out.ok("Deleted " + data + ".");
        return 0;
    }

    static Optional<Path> path(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
    }
}
