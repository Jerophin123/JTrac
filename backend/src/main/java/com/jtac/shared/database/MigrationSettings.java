package com.jtac.shared.database;

import java.util.Map;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * The single source of Flyway settings, shared by the migration command (run as the
 * schema owner) and the application's startup validation (run as the application role),
 * so that both always agree on locations, schema, history table and placeholders.
 */
public final class MigrationSettings {

    public static final String SCHEMA = "jtac";
    public static final String HISTORY_TABLE = "flyway_schema_history";
    public static final String DEFAULT_APP_ROLE = "jtac_app";

    private MigrationSettings() {
    }

    public static FluentConfiguration apply(FluentConfiguration configuration, String appRole) {
        return configuration
                .locations("classpath:db/migration")
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(false)           // the schema is provisioned by the DBA, owned by jtac_owner
                .table(HISTORY_TABLE)
                .cleanDisabled(true)            // never allow `clean` against any JTAC database
                .baselineOnMigrate(false)
                .validateMigrationNaming(true)
                .failOnMissingLocations(true)
                .placeholders(Map.of("appRole", appRole));
    }
}
