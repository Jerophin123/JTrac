plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.cyclonedx)
}

group = "com.jtac"
version = rootProject.version

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))

    implementation(libs.spring.modulith.api)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.spring.boot.starter.session.jdbc)
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.spring.modulith.starter.jdbc)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.commons.csv)
    implementation(libs.mcp.core)
    implementation(libs.mcp.json.jackson3)
    implementation(project(":serverconfig"))
    runtimeOnly(libs.micrometer.registry.prometheus)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.greenmail.junit5)
    testImplementation(testFixtures(project(":serverconfig")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing",
        // Third-party jars (e.g. commons-csv) reference annotation classes that are not on
        // the classpath; those classfile warnings concern library bytecode, not our code.
        "-Xlint:-classfile", "-Werror"))
}

// ---------------------------------------------------------------------------
// Tests: `test` runs fast tests with no database; `integrationTest` runs the
// tests tagged "db" against the local jtac_test database (after migrating it
// as the schema owner). `check` (and therefore `build`) runs both.
// ---------------------------------------------------------------------------
tasks.test {
    useJUnitPlatform { excludeTags("db") }
}

// ---------------------------------------------------------------------------
// Database migrations run in a separate JVM as the schema owner (jtac_owner).
// The application itself connects as jtac_app and only *validates* the schema.
// Passwords are never passed as arguments: the PostgreSQL JDBC driver reads
// %APPDATA%\postgresql\pgpass.conf (or set JTAC_MIGRATION_PASSWORD in the env).
// ---------------------------------------------------------------------------
fun registerMigrationTask(name: String, database: String, command: String) =
    tasks.register<JavaExec>(name) {
        group = "database"
        description = "Flyway $command on $database as the schema owner"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = "com.jtac.DatabaseMigrations"
        args(command)
        val url = providers.environmentVariable("JTRAC_MIGRATION_URL")
            .orElse(providers.environmentVariable("JTAC_MIGRATION_URL").orElse("jdbc:postgresql://localhost:5432/$database"))
        val user = providers.environmentVariable("JTRAC_MIGRATION_USER").orElse(providers.environmentVariable("JTAC_MIGRATION_USER").orElse("jtac_owner"))
        systemProperty("jtac.migration.url", url.get())
        systemProperty("jtac.migration.user", user.get())
    }

registerMigrationTask("migrateDevDb", "jtac_dev", "migrate")
registerMigrationTask("migrateTestDb", "jtac_test", "migrate")
registerMigrationTask("validateDevDb", "jtac_dev", "validate")
registerMigrationTask("infoDevDb", "jtac_dev", "info")

val integrationTest = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs tests tagged 'db' against the jtac_test database as jtac_app."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("db") }
    systemProperty("spring.profiles.active", "test")
    dependsOn("migrateTestDb")
    shouldRunAfter(tasks.test)
    // Results depend on external database state: never reuse cached or up-to-date results.
    outputs.cacheIf("depends on the live jtac_test database") { false }
    outputs.upToDateWhen { false }
}

tasks.check { dependsOn(integrationTest) }

// ---------------------------------------------------------------------------
// User administration (no self-registration in M1). Values are read from the
// environment so that passwords never appear on a command line:
//   JTAC_USER_EMAIL, JTAC_USER_DISPLAY_NAME, JTAC_USER_PASSWORD
// Target another database with JTAC_DB_URL (e.g. the e2e run uses jtac_test).
// ---------------------------------------------------------------------------
fun registerUserTask(name: String, action: String) =
    tasks.register<JavaExec>(name) {
        group = "administration"
        description = "User administration: $action (reads JTAC_USER_* environment variables)"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = "com.jtac.JtacApplication"
        args(
            "--spring.main.web-application-type=none",
            "--jtac.admin.user-action=$action",
        )
    }

registerUserTask("createUser", "create")
registerUserTask("deleteUser", "delete")
// Server administrators are the only accounts that may create organizations (JTAC_USER_EMAIL only).
registerUserTask("grantServerAdmin", "grant-admin")
registerUserTask("revokeServerAdmin", "revoke-admin")

// ---------------------------------------------------------------------------
// Optionally package the built SPA (../frontend/dist) into the jar so the
// application serves UI and API from one origin: ./gradlew bootJar -PwithFrontend
// ---------------------------------------------------------------------------
if (providers.gradleProperty("withFrontend").isPresent) {
    tasks.processResources {
        from(rootProject.layout.projectDirectory.dir("frontend/dist")) {
            into("static")
        }
    }
}

tasks.bootJar {
    archiveFileName = "jtac.jar"
}

// Version, name and build time for /api/v1/system/info and the launcher's `jtrac version`.
springBoot {
    buildInfo {
        properties {
            name = "JTRAC"
            // Keep the jar reproducible: the build time would change it on every build.
            time = null
        }
    }
}

// SBOM of what ships (runtime classpath only), copied into release packages.
tasks.cyclonedxDirectBom {
    includeConfigs = listOf("runtimeClasspath")
}
