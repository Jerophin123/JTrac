package com.jtrac.launcher;

import com.jtrac.serverconfig.AdminSecrets;
import com.jtrac.serverconfig.DatabaseMode;
import com.jtrac.serverconfig.FilePermissions;
import com.jtrac.serverconfig.Secrets;
import com.jtrac.serverconfig.ServerConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Backups of the database, attachments and configuration as one zip with a manifest of
 * SHA-256 checksums. A backup is verified (every file re-read and checked) before success is
 * reported. Backups contain all data and the server's secrets, so the archive is owner-only.
 *
 * <ul>
 *   <li>Managed database: a consistent offline copy of the PostgreSQL cluster (the server and
 *       database are stopped briefly and restarted). Restored onto the same PostgreSQL major
 *       version.</li>
 *   <li>External database: a logical dump made with {@code pg_dump} (must be installed and
 *       match the server's major version) plus attachments and configuration.</li>
 * </ul>
 */
final class Backups {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    static final String MANIFEST = "jtrac-backup-manifest.properties";

    private final Installation installation;
    private final Lifecycle lifecycle;
    private final ServerProcess server;

    Backups(Installation installation) {
        this.installation = installation;
        this.lifecycle = new Lifecycle(installation);
        this.server = new ServerProcess(installation);
    }

    Path backup(Path outputDir, boolean quiet) throws IOException {
        ServerConfig config = lifecycle.config();
        Path dir = outputDir != null ? outputDir : Path.of(config.storage().backupDir());
        Files.createDirectories(dir);
        Instant now = Instant.now();
        Path archive = dir.resolve("jtrac-backup-" + STAMP.format(now) + ".zip");
        if (Files.exists(archive)) {
            throw new SetupException("A backup named " + archive.getFileName() + " already exists.");
        }
        Secrets secrets = installation.store().loadSecrets();
        AdminSecrets admin = installation.store().loadAdminSecrets();
        String schema = DatabaseSetup.schemaVersion(config.database(), secrets.databasePassword(), config.database().appUser());
        Map<String, String> sums = new LinkedHashMap<>();
        List<String> dirs = new ArrayList<>();
        Path temp = dir.resolve(archive.getFileName() + ".partial");
        boolean managed = config.database().mode() == DatabaseMode.MANAGED;
        boolean serverWasRunning = server.running().isPresent();
        ManagedPostgres pg = new ManagedPostgres(installation, config.database().port());
        boolean dbWasRunning = managed && pg.running();
        try (OutputStream file = Files.newOutputStream(temp); ZipOutputStream zip = new ZipOutputStream(file)) {
            FilePermissions.restrictToOwner(temp);
            if (managed) {
                if (serverWasRunning) {
                    Out.info("Stopping JTRAC for a consistent copy...");
                    server.stop(Duration.ofSeconds(45));
                }
                pg.stop();
                try {
                    addTree(zip, installation.pgData(), "database/pgdata/", sums, dirs);
                } finally {
                    if (dbWasRunning || serverWasRunning) {
                        pg.start();
                    }
                    if (serverWasRunning) {
                        long pid = server.startDetached(config);
                        Out.info("Starting JTRAC again...");
                        if (!server.waitUntilHealthy(config, Duration.ofMinutes(3), ProcessHandle.of(pid).orElse(null))) {
                            Out.warn("JTRAC did not report healthy after the backup; check `jtrac status`.");
                        }
                    }
                }
            } else {
                Path dump = Files.createTempFile(installation.runDir(), "dump", ".pgdump");
                try {
                    pgDump(config.database(), admin.ownerPassword(), dump);
                    addFile(zip, dump, "database/jtrac.pgdump", sums);
                } finally {
                    Files.deleteIfExists(dump);
                }
            }
            Path attachments = Path.of(config.storage().attachmentsDir());
            if (Files.isDirectory(attachments)) {
                addTree(zip, attachments, "attachments/", sums, dirs);
            }
            for (Path f : List.of(installation.store().settingsFile(), installation.store().secretsFile(),
                    installation.store().adminSecretsFile())) {
                if (Files.isRegularFile(f)) {
                    addFile(zip, f, "config/" + f.getFileName(), sums);
                }
            }
            Properties manifest = new Properties();
            manifest.setProperty("format", "2");
            manifest.setProperty("created", now.toString());
            manifest.setProperty("jtrac.version", installation.version());
            manifest.setProperty("platform", Platform.current().id());
            manifest.setProperty("database.mode", config.database().mode().name());
            manifest.setProperty("database.schema-version", schema);
            manifest.setProperty("database.postgres-major", "18");
            manifest.setProperty("files", Integer.toString(sums.size()));
            manifest.setProperty("directories", Integer.toString(dirs.size()));
            sums.forEach((name, sum) -> manifest.setProperty("sha256." + name, sum));
            zip.putNextEntry(new ZipEntry(MANIFEST));
            manifest.store(zip, "JTRAC backup manifest");
            zip.closeEntry();
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, archive, StandardCopyOption.ATOMIC_MOVE);
        FilePermissions.restrictToOwner(archive);
        Map<String, String> verified = verify(archive);
        if (!quiet) {
            Out.ok("Backup verified: " + verified.get("files") + " files, schema version " + schema + ", "
                    + Files.size(archive) / 1024 + " KiB");
            Out.ok(archive.toString());
            Out.warn("The backup contains all JTRAC data and its secrets: store it as securely as the server itself.");
        }
        return archive;
    }

    /** Re-reads every file in the archive and compares it with the manifest. */
    static Map<String, String> verify(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry manifestEntry = zip.getEntry(MANIFEST);
            if (manifestEntry == null) {
                throw new SetupException(archive + " is not a JTRAC backup (no manifest).");
            }
            Properties manifest = new Properties();
            try (InputStream in = zip.getInputStream(manifestEntry)) {
                manifest.load(in);
            }
            int checked = 0;
            for (String key : manifest.stringPropertyNames()) {
                if (!key.startsWith("sha256.")) {
                    continue;
                }
                String name = key.substring("sha256.".length());
                ZipEntry entry = zip.getEntry(name);
                if (entry == null) {
                    throw new SetupException("Backup is damaged: " + name + " is missing.");
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    if (!sha256(in).equals(manifest.getProperty(key))) {
                        throw new SetupException("Backup is damaged: checksum mismatch for " + name + ".");
                    }
                }
                checked++;
            }
            if (!"2".equals(manifest.getProperty("format"))) {
                throw new SetupException("Unsupported backup format " + manifest.getProperty("format")
                        + " (format 1 backups lack PostgreSQL's empty directories and can't be restored).");
            }
            long directories = zip.stream().filter(ZipEntry::isDirectory).count();
            if (directories != Long.parseLong(manifest.getProperty("directories", "-1"))) {
                throw new SetupException("Backup is damaged: expected " + manifest.getProperty("directories") + " directories, found "
                        + directories + ".");
            }
            if (checked != Integer.parseInt(manifest.getProperty("files", "-1"))) {
                throw new SetupException("Backup is damaged: expected " + manifest.getProperty("files") + " files, found " + checked + ".");
            }
            Map<String, String> info = new LinkedHashMap<>();
            manifest.stringPropertyNames().stream().filter(k -> !k.startsWith("sha256.")).sorted()
                    .forEach(k -> info.put(k, manifest.getProperty(k)));
            return info;
        }
    }

    /**
     * Restores a verified backup. The current database and attachments are moved aside (kept,
     * never deleted) so the restore can be undone by hand.
     */
    void restore(Path archive, boolean restoreConfig) throws IOException {
        Map<String, String> info = verify(archive);
        Out.ok("Backup verified (" + info.get("files") + " files, created " + info.get("created") + ", JTRAC "
                + info.get("jtrac.version") + ", schema " + info.get("database.schema-version") + ").");
        ServerConfig config = lifecycle.config();
        if (!info.get("database.mode").equals(config.database().mode().name())) {
            throw new SetupException("This backup is from a " + info.get("database.mode") + " database; this installation uses "
                    + config.database().mode() + ".");
        }
        String stamp = STAMP.format(Instant.now());
        server.stop(Duration.ofSeconds(45));
        ManagedPostgres pg = new ManagedPostgres(installation, config.database().port());
        if (config.database().mode() == DatabaseMode.MANAGED) {
            pg.stop();
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (restoreConfig) {
                Path aside = installation.configDir().resolveSibling("config.before-restore-" + stamp);
                copyTree(installation.configDir(), aside);
                extract(zip, "config/", installation.configDir());
                config = installation.store().loadConfig();
                Out.ok("Configuration restored (previous copy in " + aside + ").");
            }
            if (config.database().mode() == DatabaseMode.MANAGED) {
                Path data = installation.pgData();
                Path aside = data.resolveSibling("data.before-restore-" + stamp);
                boolean hadData = Files.exists(data);
                if (hadData) {
                    Files.move(data, aside);
                    Out.ok("Current database moved to " + aside + " (delete it once you're satisfied).");
                }
                extract(zip, "database/pgdata/", data);
                FilePermissions.restrictToOwner(data);
                try {
                    pg.start();
                } catch (SetupException e) {
                    Path failed = data.resolveSibling("data.failed-restore-" + stamp);
                    Files.move(data, failed);
                    if (hadData) {
                        Files.move(aside, data);
                        pg.start();
                    }
                    throw new SetupException("The restored database did not start, so the previous database was put back "
                            + "(the failed restore is kept in " + failed + "). " + e.getMessage(), e);
                }
            } else {
                Path dump = Files.createTempFile(installation.runDir(), "restore", ".pgdump");
                try (InputStream in = zip.getInputStream(zip.getEntry("database/jtrac.pgdump"))) {
                    Files.copy(in, dump, StandardCopyOption.REPLACE_EXISTING);
                    pgRestore(config.database(), installation.store().loadAdminSecrets().ownerPassword(), dump);
                } finally {
                    Files.deleteIfExists(dump);
                }
            }
            Path attachments = Path.of(config.storage().attachmentsDir());
            if (Files.exists(attachments)) {
                Path aside = attachments.resolveSibling(attachments.getFileName() + ".before-restore-" + stamp);
                Files.move(attachments, aside);
                Out.ok("Current attachments moved to " + aside + ".");
            }
            Files.createDirectories(attachments);
            extract(zip, "attachments/", attachments);
        }
        // A backup from an older version is brought up to this version's schema.
        DatabaseSetup.migrate(installation, config.database(), installation.store().loadAdminSecrets().ownerPassword());
        Out.ok("Restore complete. Start JTRAC with `jtrac start`.");
    }

    private void pgDump(ServerConfig.Database db, String ownerPassword, Path target) {
        Path tool = findTool("pg_dump");
        Processes.Result r = Processes.run(List.of(tool.toString(), "--format=custom", "--no-owner", "--schema=jtac",
                "--host=" + db.host(), "--port=" + db.port(), "--username=" + db.ownerUser(), "--dbname=" + db.name(),
                "--file=" + target), env(db, ownerPassword), null, Duration.ofHours(2));
        if (!r.ok()) {
            throw new SetupException("pg_dump failed:\n" + r.output());
        }
    }

    private void pgRestore(ServerConfig.Database db, String ownerPassword, Path dump) {
        Path tool = findTool("pg_restore");
        Processes.Result r = Processes.run(List.of(tool.toString(), "--clean", "--if-exists", "--no-owner", "--single-transaction",
                "--host=" + db.host(), "--port=" + db.port(), "--username=" + db.ownerUser(), "--dbname=" + db.name(),
                dump.toString()), env(db, ownerPassword), null, Duration.ofHours(2));
        if (!r.ok()) {
            throw new SetupException("pg_restore failed:\n" + r.output());
        }
    }

    private static Map<String, String> env(ServerConfig.Database db, String password) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PGPASSWORD", password);
        if (db.sslMode() != null && !db.sslMode().isBlank()) {
            env.put("PGSSLMODE", db.sslMode());
        }
        return env;
    }

    private Path findTool(String name) {
        Path bundled = installation.pgTool(name);
        if (Files.isRegularFile(bundled)) {
            return bundled;
        }
        String exe = Platform.current().executable(name);
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir, exe);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new SetupException(name + " was not found. For an external database, install the PostgreSQL 18 client tools "
                + "and make sure " + name + " is on the PATH.");
    }

    private static void addTree(ZipOutputStream zip, Path root, String prefix, Map<String, String> sums, List<String> dirs)
            throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        List<Path> directories = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root)) {
                    directories.add(dir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        directories.sort(null);
        for (Path dir : directories) {
            String name = prefix + root.relativize(dir).toString().replace('\\', '/') + "/";
            zip.putNextEntry(new ZipEntry(name));
            zip.closeEntry();
            dirs.add(name);
        }
        files.sort(null);
        for (Path file : files) {
            addFile(zip, file, prefix + root.relativize(file).toString().replace('\\', '/'), sums);
        }
    }

    private static void addFile(ZipOutputStream zip, Path file, String name, Map<String, String> sums) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        MessageDigest digest = digest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(zip);
        }
        zip.closeEntry();
        sums.put(name, HexFormat.of().formatHex(digest.digest()));
    }

    private static void extract(ZipFile zip, String prefix, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.getName().startsWith(prefix)) {
                continue;
            }
            Path out = root.resolve(entry.getName().substring(prefix.length())).normalize();
            if (!out.startsWith(root)) {
                throw new SetupException("Backup entry escapes the target directory: " + entry.getName());
            }
            if (entry.isDirectory()) {
                Files.createDirectories(out);
                continue;
            }
            Files.createDirectories(out.getParent());
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        try (var stream = Files.walk(source)) {
            for (Path p : stream.toList()) {
                Path out = target.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(out);
                } else {
                    Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    static String sha256(InputStream in) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
