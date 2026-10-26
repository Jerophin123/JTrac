package com.jtac.shared.database;

import org.flywaydb.core.api.output.ValidateResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * At startup the application <em>validates</em> the schema instead of migrating it.
 * It connects as {@code jtac_app}, which has no DDL privileges; schema changes are
 * applied separately as {@code jtac_owner} (see {@code DatabaseMigrations}).
 * Startup fails fast if the database is behind or diverges from this build.
 */
@Configuration(proxyBeanMethods = false)
class FlywayValidationConfiguration {

    @Bean
    FlywayConfigurationCustomizer jtacMigrationSettings(@Value("${jtac.database.app-role:jtac_app}") String appRole) {
        return configuration -> MigrationSettings.apply(configuration, appRole);
    }

    @Bean
    FlywayMigrationStrategy validateOnlyMigrationStrategy() {
        return flyway -> {
            ValidateResult result = flyway.validateWithResult();
            if (!result.validationSuccessful) {
                throw new IllegalStateException("Database schema does not match this build. "
                        + "Run the migration task as the schema owner (e.g. ./gradlew migrateDevDb). "
                        + result.getAllErrorMessages());
            }
        };
    }
}
