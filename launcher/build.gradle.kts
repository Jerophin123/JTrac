plugins {
    java
    alias(libs.plugins.cyclonedx)
}

group = "com.jtrac"
version = rootProject.version

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
}

// The `jtrac` command: setup wizard, start/stop/status, configure, test-email, backup,
// restore, update, service integration and uninstall. Plain Java, no Spring, so it starts
// fast and runs on the bundled runtime.
dependencies {
    implementation(project(":serverconfig"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.postgresql)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing", "-Xlint:-classfile", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
}

// The scripts run it as: java -cp "lib/launcher/*" com.jtrac.launcher.Launcher
tasks.jar {
    archiveFileName = "jtrac-launcher.jar"
    manifest {
        attributes(
            "Main-Class" to "com.jtrac.launcher.Launcher",
            "Implementation-Title" to "JTRAC launcher",
            "Implementation-Version" to project.version,
        )
    }
}

val launcherLibs = tasks.register<Sync>("launcherLibs") {
    description = "Collects the launcher jar and its runtime dependencies."
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("launcher-libs"))
}

// SBOM of what ships (runtime classpath only), copied into release packages.
tasks.cyclonedxDirectBom {
    includeConfigs = listOf("runtimeClasspath")
}
