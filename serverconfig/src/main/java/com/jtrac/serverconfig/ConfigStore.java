package com.jtrac.serverconfig;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Reads and writes the configuration directory of an installation:
 *
 * <ul>
 *   <li>{@code jtrac.properties}: ordinary settings, imported by the server;</li>
 *   <li>{@code secrets.properties}: application secrets, imported by the server, owner-only;</li>
 *   <li>{@code admin-secrets.properties}: database owner and superuser passwords, read only by
 *       the launcher, owner-only.</li>
 * </ul>
 *
 * Writes are atomic (temporary file, then rename), so a crash never leaves a half-written file.
 */
public final class ConfigStore {

    public static final String SETTINGS_FILE = "jtrac.properties";
    public static final String SECRETS_FILE = "secrets.properties";
    public static final String ADMIN_SECRETS_FILE = "admin-secrets.properties";

    private static final String SETTINGS_HEADER = """
            JTRAC server settings. Written by the setup wizard, `jtrac configure` and the
            administration pages; edit with care and restart JTRAC afterwards.
            Secrets are NOT stored here (see secrets.properties). Environment variables
            override these values (docs/configuration-reference.md).""";
    private static final String SECRETS_HEADER = """
            JTRAC application secrets. Readable only by the account that runs JTRAC.
            Never copy this file into tickets, chats, backups you share, or version control.""";

    private final Path directory;

    public ConfigStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Path directory() {
        return directory;
    }

    public Path settingsFile() {
        return directory.resolve(SETTINGS_FILE);
    }

    public Path secretsFile() {
        return directory.resolve(SECRETS_FILE);
    }

    public Path adminSecretsFile() {
        return directory.resolve(ADMIN_SECRETS_FILE);
    }

    public boolean exists() {
        return Files.isRegularFile(settingsFile());
    }

    public ServerConfig loadConfig() throws IOException {
        return ConfigMapper.fromProperties(read(settingsFile()));
    }

    public Secrets loadSecrets() throws IOException {
        return ConfigMapper.secretsFromProperties(read(secretsFile()));
    }

    public AdminSecrets loadAdminSecrets() throws IOException {
        return ConfigMapper.adminSecretsFromProperties(read(adminSecretsFile()));
    }

    /** Raw settings, including keys this model doesn't know (they are preserved on save). */
    public Map<String, String> loadRawSettings() throws IOException {
        return read(settingsFile());
    }

    public void saveConfig(ServerConfig config) throws IOException {
        Map<String, String> merged = new LinkedHashMap<>(read(settingsFile()));
        merged.putAll(ConfigMapper.toProperties(config));
        write(settingsFile(), merged, SETTINGS_HEADER, false);
    }

    /** Merges these keys into the settings file, keeping every other key as it is. */
    public void mergeSettings(Map<String, String> values) throws IOException {
        Map<String, String> merged = new LinkedHashMap<>(read(settingsFile()));
        merged.putAll(values);
        write(settingsFile(), merged, SETTINGS_HEADER, false);
    }

    /** Merges these keys into the secrets file, keeping every other key as it is. */
    public void mergeSecrets(Map<String, String> values) throws IOException {
        Map<String, String> merged = new LinkedHashMap<>(read(secretsFile()));
        merged.putAll(values);
        write(secretsFile(), merged, SECRETS_HEADER, true);
    }

    public void saveSecrets(Secrets secrets) throws IOException {
        Map<String, String> merged = new LinkedHashMap<>(read(secretsFile()));
        merged.putAll(ConfigMapper.toProperties(secrets));
        write(secretsFile(), merged, SECRETS_HEADER, true);
    }

    public void saveAdminSecrets(AdminSecrets secrets) throws IOException {
        write(adminSecretsFile(), ConfigMapper.toProperties(secrets), """
                JTRAC database administration secrets, used only by the jtrac command (migrations,
                provisioning, backups). The web application never reads this file.""", true);
    }

    private void write(Path file, Map<String, String> values, String header, boolean secret) throws IOException {
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
        try {
            if (secret) {
                FilePermissions.restrictToOwner(temp);
            }
            StringBuilder out = new StringBuilder();
            for (String line : header.split("\n")) {
                out.append("# ").append(line).append('\n');
            }
            out.append('\n');
            for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
                out.append(escape(entry.getKey(), true)).append('=').append(escape(entry.getValue(), false)).append('\n');
            }
            Files.writeString(temp, out, StandardCharsets.ISO_8859_1);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            if (secret) {
                FilePermissions.restrictToOwner(file);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Map<String, String> read(Path file) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            properties.load(reader);
        }
        properties.stringPropertyNames().stream().sorted().forEach(k -> out.put(k, properties.getProperty(k)));
        return out;
    }

    /** java.util.Properties escaping, with non-ASCII as backslash-u escapes (files are ISO 8859-1). */
    static String escape(String value, boolean key) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\f' -> out.append("\\f");
                case '=', ':', '#', '!' -> out.append('\\').append(c);
                case ' ' -> out.append(key || i == 0 ? "\\ " : " ");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
