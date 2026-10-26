package com.jtrac.launcher;

import com.jtrac.serverconfig.ServerConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Provisions a fresh, empty JTRAC database with least privilege (ADR 0004): the schema owner
 * runs migrations; the application role may only read and write rows (no DDL, no TEMP).
 * Applies migrations in a separate JVM as the owner, and creates the first administrator.
 */
final class DatabaseSetup {

    static final String SCHEMA = "jtac";

    private DatabaseSetup() {
    }

    static Connection connect(String host, int port, String database, String user, String password, String sslMode)
            throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("connectTimeout", "10");
        props.setProperty("socketTimeout", "60");
        props.setProperty("ApplicationName", "jtrac-launcher");
        if (sslMode != null && !sslMode.isBlank()) {
            props.setProperty("sslmode", sslMode);
        }
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return DriverManager.getConnection("jdbc:postgresql://" + h + ":" + port + "/" + database, props);
    }

    /**
     * Creates the roles, the database and the schema if they don't exist yet. Runs as an
     * account allowed to create roles and databases (the managed superuser, or an external
     * administrator whose password is used once and not stored).
     */
    static void provision(ServerConfig.Database db, String adminUser, String adminPassword, String maintenanceDb,
            String ownerPassword, String appPassword) throws SQLException {
        try (Connection admin = connect(db.host(), db.port(), maintenanceDb, adminUser, adminPassword, db.sslMode())) {
            ensureRole(admin, db.ownerUser(), ownerPassword);
            ensureRole(admin, db.appUser(), appPassword);
            if (!exists(admin, "SELECT 1 FROM pg_database WHERE datname = ?", db.name())) {
                try (Statement s = admin.createStatement()) {
                    s.execute("CREATE DATABASE " + ident(db.name()) + " OWNER " + ident(db.ownerUser())
                            + " ENCODING 'UTF8' LOCALE_PROVIDER builtin BUILTIN_LOCALE 'C.UTF-8' TEMPLATE template0");
                }
            }
            try (Statement s = admin.createStatement()) {
                s.execute("REVOKE ALL ON DATABASE " + ident(db.name()) + " FROM PUBLIC");
                s.execute("GRANT CONNECT ON DATABASE " + ident(db.name()) + " TO " + ident(db.ownerUser()) + ", " + ident(db.appUser()));
                if (maintenanceDb.equals("postgres")) {
                    s.execute("REVOKE CONNECT ON DATABASE postgres FROM PUBLIC");
                }
            }
        }
        try (Connection admin = connect(db.host(), db.port(), db.name(), adminUser, adminPassword, db.sslMode());
                Statement s = admin.createStatement()) {
            s.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            s.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA + " AUTHORIZATION " + ident(db.ownerUser()));
            s.execute("GRANT USAGE ON SCHEMA " + SCHEMA + " TO " + ident(db.appUser()));
            String forOwner = "ALTER DEFAULT PRIVILEGES FOR ROLE " + ident(db.ownerUser()) + " IN SCHEMA " + SCHEMA;
            s.execute(forOwner + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + ident(db.appUser()));
            s.execute(forOwner + " GRANT USAGE, SELECT ON SEQUENCES TO " + ident(db.appUser()));
            s.execute(forOwner + " GRANT EXECUTE ON FUNCTIONS TO " + ident(db.appUser()));
            s.execute("ALTER ROLE " + ident(db.ownerUser()) + " IN DATABASE " + ident(db.name()) + " SET search_path = " + SCHEMA);
            s.execute("ALTER ROLE " + ident(db.appUser()) + " IN DATABASE " + ident(db.name()) + " SET search_path = " + SCHEMA);
        }
    }

    /** The SQL a DBA runs when JTRAC may not create roles itself (external mode). */
    static String provisioningScript(ServerConfig.Database db) {
        String o = ident(db.ownerUser());
        String a = ident(db.appUser());
        String d = ident(db.name());
        return """
                -- Run as a PostgreSQL administrator. Replace the two passwords.
                CREATE ROLE %1$s LOGIN PASSWORD '<owner password>';
                CREATE ROLE %2$s LOGIN PASSWORD '<application password>';
                CREATE DATABASE %3$s OWNER %1$s ENCODING 'UTF8' LOCALE_PROVIDER builtin BUILTIN_LOCALE 'C.UTF-8' TEMPLATE template0;
                REVOKE ALL ON DATABASE %3$s FROM PUBLIC;
                GRANT CONNECT ON DATABASE %3$s TO %1$s, %2$s;
                \\connect %3$s
                REVOKE ALL ON SCHEMA public FROM PUBLIC;
                CREATE SCHEMA jtac AUTHORIZATION %1$s;
                GRANT USAGE ON SCHEMA jtac TO %2$s;
                ALTER DEFAULT PRIVILEGES FOR ROLE %1$s IN SCHEMA jtac GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %2$s;
                ALTER DEFAULT PRIVILEGES FOR ROLE %1$s IN SCHEMA jtac GRANT USAGE, SELECT ON SEQUENCES TO %2$s;
                ALTER DEFAULT PRIVILEGES FOR ROLE %1$s IN SCHEMA jtac GRANT EXECUTE ON FUNCTIONS TO %2$s;
                ALTER ROLE %1$s IN DATABASE %3$s SET search_path = jtac;
                ALTER ROLE %2$s IN DATABASE %3$s SET search_path = jtac;
                """.formatted(o, a, d);
    }

    /** Checks that both roles can sign in and the application role can't change the schema. */
    static List<String> verifyAccess(ServerConfig.Database db, String ownerPassword, String appPassword) {
        List<String> problems = new ArrayList<>();
        try (Connection c = connect(db.host(), db.port(), db.name(), db.ownerUser(), ownerPassword, db.sslMode())) {
            if (!exists(c, "SELECT 1 FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner WHERE n.nspname = ? AND r.rolname = current_user", SCHEMA)) {
                problems.add("Schema 'jtac' must exist and be owned by " + db.ownerUser() + ".");
            }
        } catch (SQLException e) {
            problems.add("Schema owner " + db.ownerUser() + " can't connect: " + e.getMessage());
        }
        try (Connection c = connect(db.host(), db.port(), db.name(), db.appUser(), appPassword, db.sslMode())) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT rolsuper OR rolcreaterole OR rolcreatedb FROM pg_roles WHERE rolname = current_user");
                    ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getBoolean(1)) {
                    problems.add("The application role " + db.appUser() + " has administrative rights; use a role without them.");
                }
            }
            if (exists(c, "SELECT 1 WHERE has_schema_privilege(current_user, ?, 'CREATE')", SCHEMA)) {
                problems.add("The application role " + db.appUser() + " can create objects in schema jtac; it must not.");
            }
        } catch (SQLException e) {
            problems.add("Application role " + db.appUser() + " can't connect: " + e.getMessage());
        }
        return problems;
    }

    /** True when the database already contains JTRAC accounts (never import or overwrite them). */
    static long accountCount(ServerConfig.Database db, String appPassword) throws SQLException {
        try (Connection c = connect(db.host(), db.port(), db.name(), db.appUser(), appPassword, db.sslMode())) {
            if (!exists(c, "SELECT 1 FROM information_schema.tables WHERE table_schema = 'jtac' AND table_name = ?", "user_account")) {
                return 0;
            }
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) FROM jtac.user_account")) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static String schemaVersion(ServerConfig.Database db, String password, String user) {
        try (Connection c = connect(db.host(), db.port(), db.name(), user, password, db.sslMode())) {
            if (!exists(c, "SELECT 1 FROM information_schema.tables WHERE table_schema = 'jtac' AND table_name = ?", "flyway_schema_history")) {
                return "empty";
            }
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                    "SELECT version FROM jtac.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")) {
                return rs.next() ? rs.getString(1) : "empty";
            }
        } catch (SQLException e) {
            return "unknown (" + e.getMessage() + ")";
        }
    }

    /** Applies migrations as the schema owner in a separate JVM; the password travels in the environment. */
    static void migrate(Installation installation, ServerConfig.Database db, String ownerPassword) {
        List<String> command = List.of(installation.java(false).toString(), "-Dstdout.encoding=UTF-8",
                "-Djtac.migration.url=" + db.jdbcUrl(),
                "-Djtac.migration.user=" + db.ownerUser(),
                "-Djtac.migration.app-role=" + db.appUser(),
                "-Dloader.main=com.jtac.DatabaseMigrations",
                "-cp", installation.serverJar().toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher", "migrate");
        Processes.Result result = Processes.run(command, Map.of("JTRAC_MIGRATION_PASSWORD", ownerPassword), null, Duration.ofMinutes(15));
        if (!result.ok()) {
            throw new SetupException("Database migration failed:\n" + result.output().strip());
        }
        Out.ok(lastLine(result.output()));
    }

    /** Creates the first administrator through the server's own account code (Argon2id hashing). */
    static void createAdministrator(Installation installation, String name, String email, String password) {
        List<String> command = List.of(installation.java(false).toString(), "-Dstdout.encoding=UTF-8",
                "-Djtrac.config-dir=" + installation.configDir(),
                "-jar", installation.serverJar().toString(),
                "--spring.main.web-application-type=none", "--jtac.jobs.enabled=false",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN", "--logging.file.name=",
                "--jtac.admin.user-action=create");
        Processes.Result result = Processes.run(command,
                Map.of("JTRAC_USER_EMAIL", email, "JTRAC_USER_DISPLAY_NAME", name, "JTRAC_USER_PASSWORD", password),
                installation.data(), Duration.ofMinutes(5));
        if (!result.ok() || !result.output().contains("Created")) {
            throw new SetupException("Creating the administrator failed:\n" + result.output().strip());
        }
    }

    private static void ensureRole(Connection admin, String role, String password) throws SQLException {
        String sql = exists(admin, "SELECT 1 FROM pg_roles WHERE rolname = ?", role)
                ? "ALTER ROLE " + ident(role) + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD " + literal(password)
                : "CREATE ROLE " + ident(role) + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD " + literal(password);
        try (Statement s = admin.createStatement()) {
            s.execute(sql);
        }
    }

    private static boolean exists(Connection c, String sql, String param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Role and database names are validated ([a-z_][a-z0-9_]*) before they get here; quote anyway. */
    static String ident(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String lastLine(String output) {
        String[] lines = output.strip().split("\\R");
        return lines[lines.length - 1];
    }
}
