package com.jtac;

import com.jtac.shared.database.MigrationSettings;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateResult;

/**
 * Command-line entry point for schema migrations, run by the Gradle tasks
 * {@code migrateDevDb}, {@code migrateTestDb}, {@code validateDevDb} and {@code infoDevDb}.
 *
 * <p>Migrations run as the schema owner ({@code jtac_owner}) in a separate JVM, so the
 * owner's credentials are never part of the running application. No Spring context is
 * started. The password is never accepted as an argument: the PostgreSQL JDBC driver
 * reads the user's pgpass file, or {@code JTAC_MIGRATION_PASSWORD} may be set in the
 * environment.
 */
public final class DatabaseMigrations {

    private DatabaseMigrations() {
    }

    public static void main(String[] args) {
        String command = args.length > 0 ? args[0] : "migrate";
        String url = requiredProperty("jtac.migration.url");
        String user = requiredProperty("jtac.migration.user");
        String password = System.getenv("JTRAC_MIGRATION_PASSWORD") != null ? System.getenv("JTRAC_MIGRATION_PASSWORD") : System.getenv("JTAC_MIGRATION_PASSWORD");

        String appRole = System.getProperty("jtac.migration.app-role", MigrationSettings.DEFAULT_APP_ROLE);
        Flyway flyway = MigrationSettings.apply(Flyway.configure(), appRole)
                .dataSource(url, user, password)
                .load();

        // Never echo URL parameters: they could carry credentials (e.g. ?password=...).
        String displayUrl = url.contains("?") ? url.substring(0, url.indexOf('?')) + "?…" : url;
        System.out.printf("Flyway %s on %s as %s%n", command, displayUrl, user);
        switch (command) {
            case "migrate" -> {
                MigrateResult result = flyway.migrate();
                MigrationInfo current = flyway.info().current();
                System.out.printf("Applied %d migration(s); schema version is now %s%n",
                        result.migrationsExecuted, current == null ? "empty" : current.getVersion());
            }
            case "validate" -> {
                ValidateResult result = flyway.validateWithResult();
                if (!result.validationSuccessful) {
                    result.invalidMigrations.forEach(m -> System.err.println("INVALID " + m.version + ": " + m.errorDetails.errorMessage));
                    System.exit(1);
                }
                System.out.println("Schema matches the migrations in this build.");
            }
            case "info" -> {
                for (MigrationInfo info : flyway.info().all()) {
                    System.out.printf("%-8s %-40s %-10s %s%n", info.getVersion(), info.getDescription(),
                            info.getState().getDisplayName(), info.getInstalledOn());
                }
            }
            default -> {
                System.err.println("Unknown command '" + command + "'. Use migrate, validate or info.");
                System.exit(2);
            }
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing system property " + name);
        }
        return value;
    }
}
