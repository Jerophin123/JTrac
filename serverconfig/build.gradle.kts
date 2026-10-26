plugins {
    `java-library`
    `java-test-fixtures`
}

group = "com.jtrac"
version = rootProject.version

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
}

// Shared by the launcher (setup wizard, configure, test-email) and the server (admin
// settings): one configuration model, one set of validation rules, one SMTP client setup.
dependencies {
    api(platform(libs.spring.boot.bom))
    api(libs.angus.mail)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Test fixtures: a controlled SMTP server (plain, STARTTLS, implicit TLS, AUTH) used by the
// tests of this module, the server, and the installer verification. Never sends real email.
tasks.register<JavaExec>("runTestSmtpServer") {
    group = "verification"
    description = "Runs the controlled test SMTP server (see TestSmtpServer) until stopped."
    classpath = sourceSets["testFixtures"].runtimeClasspath
    mainClass = "com.jtrac.serverconfig.testing.TestSmtpServer"
    args(providers.gradleProperty("smtpArgs").orElse("").get().split(" ").filter { it.isNotBlank() })
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing", "-Xlint:-classfile", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
}
