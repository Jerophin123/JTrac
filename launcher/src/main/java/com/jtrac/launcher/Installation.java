package com.jtrac.launcher;

import com.jtrac.serverconfig.ConfigStore;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * Where things are. The <em>home</em> is the unpacked release (replaced on every update);
 * the <em>data directory</em> holds configuration, the managed database, attachments,
 * backups and logs, and survives updates and uninstalls.
 *
 * <pre>
 * home/                         data/
 *   bin/       jtrac, jtrac.cmd   config/    jtrac.properties, secrets.properties, ...
 *   lib/       jtrac-server.jar   pgsql/     data/ (managed PostgreSQL cluster)
 *   runtime/   Java runtime       attachments/  backups/  logs/  run/
 *   pgsql/     PostgreSQL binaries (managed mode)
 * </pre>
 */
public final class Installation {

    /** Records which data directory this home uses (written by setup, read by every command). */
    static final String POINTER_FILE = "jtrac-home.properties";

    private final Path home;
    private final Path data;

    Installation(Path home, Path data) {
        this.home = home.toAbsolutePath().normalize();
        this.data = data.toAbsolutePath().normalize();
    }

    /** Locates home from the launcher jar and data from --data-dir, JTRAC_DATA_DIR, the pointer file or the OS default. */
    static Installation locate(String dataDirOption) {
        Path home = detectHome();
        Path data;
        if (dataDirOption != null && !dataDirOption.isBlank()) {
            data = Path.of(dataDirOption);
        } else if (System.getenv("JTRAC_DATA_DIR") != null && !System.getenv("JTRAC_DATA_DIR").isBlank()) {
            data = Path.of(System.getenv("JTRAC_DATA_DIR"));
        } else {
            data = readPointer(home).orElse(defaultDataDir());
        }
        return new Installation(home, data);
    }

    static Path detectHome() {
        String override = System.getProperty("jtrac.home");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        try {
            Path jar = Path.of(Installation.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // home/lib/launcher/jtrac-launcher.jar
            if (Files.isRegularFile(jar) && jar.getParent() != null && jar.getParent().getParent() != null) {
                return jar.getParent().getParent().getParent();
            }
        } catch (URISyntaxException | SecurityException e) {
            // fall through to the working directory
        }
        return Path.of("").toAbsolutePath();
    }

    static Path defaultDataDir() {
        String userHome = System.getProperty("user.home");
        return switch (Platform.current().os()) {
            case WINDOWS -> {
                String local = System.getenv("LOCALAPPDATA");
                yield Path.of(local != null ? local : userHome, "JTRAC");
            }
            case MACOS -> Path.of(userHome, "Library", "Application Support", "JTRAC");
            case LINUX -> {
                String xdg = System.getenv("XDG_DATA_HOME");
                yield xdg != null && !xdg.isBlank() ? Path.of(xdg, "jtrac") : Path.of(userHome, ".local", "share", "jtrac");
            }
        };
    }

    static Optional<Path> readPointer(Path home) {
        Path pointer = home.resolve(POINTER_FILE);
        if (!Files.isRegularFile(pointer)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(pointer, StandardCharsets.UTF_8)) {
            p.load(in);
            String dir = p.getProperty("data.dir");
            return dir == null || dir.isBlank() ? Optional.empty() : Optional.of(Path.of(dir));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    void writePointer() throws IOException {
        Properties p = new Properties();
        p.setProperty("data.dir", data.toString());
        try (Writer out = Files.newBufferedWriter(home.resolve(POINTER_FILE), StandardCharsets.UTF_8)) {
            p.store(out, "JTRAC data directory used by this installation");
        }
    }

    void removePointer() throws IOException {
        Files.deleteIfExists(home.resolve(POINTER_FILE));
    }

    Installation withData(Path newData) {
        return new Installation(home, newData);
    }

    Path home() {
        return home;
    }

    Path data() {
        return data;
    }

    Path configDir() {
        return data.resolve("config");
    }

    ConfigStore store() {
        return new ConfigStore(configDir());
    }

    Path runDir() {
        return data.resolve("run");
    }

    Path defaultLogDir() {
        return data.resolve("logs");
    }

    Path pgData() {
        return data.resolve("pgsql").resolve("data");
    }

    Path serverJar() {
        return home.resolve("lib").resolve("jtrac-server.jar");
    }

    Path pgBin() {
        return home.resolve("pgsql").resolve("bin");
    }

    boolean hasBundledPostgres() {
        return Files.isRegularFile(pgBin().resolve(Platform.current().executable("pg_ctl")));
    }

    Path pgTool(String name) {
        return pgBin().resolve(Platform.current().executable(name));
    }

    /** The bundled runtime's java; falls back to the running JVM (development). */
    Path java(boolean windowless) {
        String name = windowless && Platform.current().os() == Platform.Os.WINDOWS ? "javaw" : "java";
        Path bundled = home.resolve("runtime").resolve("bin").resolve(Platform.current().executable(name));
        if (Files.isRegularFile(bundled)) {
            return bundled;
        }
        return Path.of(System.getProperty("java.home"), "bin", Platform.current().executable(name));
    }

    String version() {
        Package p = Installation.class.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        return v == null ? "development" : v;
    }

    /** Settings completed by setup (also marks a finished installation). */
    Path setupStateFile() {
        return configDir().resolve("setup-state.properties");
    }

    boolean setupComplete() {
        Properties p = new Properties();
        if (!Files.isRegularFile(setupStateFile())) {
            return false;
        }
        try (Reader in = Files.newBufferedReader(setupStateFile(), StandardCharsets.UTF_8)) {
            p.load(in);
            return "true".equals(p.getProperty("complete"));
        } catch (IOException e) {
            return false;
        }
    }

    void markStep(String step) throws IOException {
        Properties p = new Properties();
        if (Files.isRegularFile(setupStateFile())) {
            try (Reader in = Files.newBufferedReader(setupStateFile(), StandardCharsets.UTF_8)) {
                p.load(in);
            }
        }
        p.setProperty(step, "true");
        Files.createDirectories(configDir());
        try (Writer out = Files.newBufferedWriter(setupStateFile(), StandardCharsets.UTF_8)) {
            p.store(out, "JTRAC setup progress (steps already completed are skipped when setup runs again)");
        }
    }

    boolean stepDone(String step) {
        Properties p = new Properties();
        if (!Files.isRegularFile(setupStateFile())) {
            return false;
        }
        try (Reader in = Files.newBufferedReader(setupStateFile(), StandardCharsets.UTF_8)) {
            p.load(in);
            return "true".equals(p.getProperty(step));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "home=" + home + ", data=" + data + ", platform=" + Platform.current().id().toLowerCase(Locale.ROOT);
    }
}
