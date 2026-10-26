package com.jtrac.launcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherTests {

    @TempDir
    Path dir;

    @Test
    void parsesCommandsFlagsAndPositionals() {
        Launcher.Args args = Launcher.Args.parse(new String[] {"restore", "backup.zip", "--yes", "--data-dir", "D:\\data", "--lines=5"});
        assertThat(args.command()).isEqualTo("restore");
        assertThat(args.positional()).containsExactly("backup.zip");
        assertThat(args.flags()).containsEntry("yes", "").containsEntry("data-dir", "D:\\data").containsEntry("lines", "5");
    }

    @Test
    void readsAnswersLiterallySoWindowsPathsSurvive() throws IOException {
        Path answers = dir.resolve("answers.properties");
        Files.writeString(answers, """
                # comment
                data.dir=C:\\JTRAC\\data
                http.port = 9090
                """, StandardCharsets.UTF_8);
        Prompter p = Prompter.fromFile(answers);

        assertThat(p.ask("data.dir", "Data", "x", null)).isEqualTo("C:\\JTRAC\\data");
        assertThat(p.askInt("http.port", "Port", 8080, 1, 65535)).isEqualTo(9090);
        assertThat(p.ask("missing", "Missing", "fallback", null)).isEqualTo("fallback");
        assertThat(p.unusedAnswers()).isEmpty();
    }

    @Test
    void ignoresAUtf8ByteOrderMark() throws IOException {
        Path answers = dir.resolve("bom.properties");
        Files.write(answers, ("\uFEFFconfirm-delete=C:\\data\n").getBytes(StandardCharsets.UTF_8));
        Prompter p = Prompter.fromFile(answers);

        assertThat(p.ask("confirm-delete", "Confirm", "", null)).isEqualTo("C:\\data");
    }

    @Test
    void rejectsInvalidUnattendedAnswersInsteadOfGuessing() throws IOException {
        Path answers = dir.resolve("answers.properties");
        Files.writeString(answers, "http.port=99999\n", StandardCharsets.UTF_8);
        Prompter p = Prompter.fromFile(answers);

        assertThatThrownBy(() -> p.askInt("http.port", "Port", 8080, 1, 65535)).isInstanceOf(SetupException.class)
                .hasMessageContaining("http.port");
    }

    @Test
    void unattendedSecretsComeFromTheEnvironmentOrFailClearly() throws IOException {
        Path answers = dir.resolve("answers.properties");
        Files.writeString(answers, "admin.name=Ada\n", StandardCharsets.UTF_8);
        Prompter p = Prompter.fromFile(answers);

        assertThatThrownBy(() -> p.askSecret("admin.password", "JTRAC_TEST_UNSET_VARIABLE", "Password", true, false, null))
                .isInstanceOf(SetupException.class).hasMessageContaining("JTRAC_TEST_UNSET_VARIABLE");
    }

    @Test
    void refusesBackupsWithoutAManifestOrWithTamperedFiles() throws IOException {
        Path noManifest = dir.resolve("plain.zip");
        try (OutputStream out = Files.newOutputStream(noManifest); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("database/pgdata/PG_VERSION"));
            zip.write("18".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThatThrownBy(() -> Backups.verify(noManifest)).isInstanceOf(SetupException.class).hasMessageContaining("manifest");

        Path tampered = dir.resolve("tampered.zip");
        try (OutputStream out = Files.newOutputStream(tampered); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("attachments/a.txt"));
            zip.write("changed".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(Backups.MANIFEST));
            zip.write(("format=2\nfiles=1\ndirectories=0\nsha256.attachments/a.txt="
                    + "0000000000000000000000000000000000000000000000000000000000000000\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThatThrownBy(() -> Backups.verify(tampered)).isInstanceOf(SetupException.class).hasMessageContaining("checksum");
    }

    @Test
    void platformIdsMatchReleaseTargets() {
        assertThat(List.of("windows-x64", "windows-arm64", "linux-x64", "linux-arm64", "macos-x64", "macos-arm64"))
                .contains(Platform.current().id());
        assertThat(new Platform(Platform.Os.WINDOWS, Platform.Arch.X64).executable("pg_ctl")).isEqualTo("pg_ctl.exe");
        assertThat(new Platform(Platform.Os.LINUX, Platform.Arch.ARM64).executable("pg_ctl")).isEqualTo("pg_ctl");
    }

    @Test
    void defaultsKeepTheManagedDatabaseOnLoopback() {
        var config = SetupWizard.defaults(dir);
        assertThat(config.database().host()).isEqualTo("127.0.0.1");
        assertThat(config.http().bindAddress()).isEqualTo("127.0.0.1");
        assertThat(config.smtp().enabled()).isFalse();
        assertThat(config.storage().attachmentsDir()).startsWith(dir.toString());
    }

    @Test
    void provisioningScriptGrantsTheAppRoleDataAccessOnly() {
        String sql = DatabaseSetup.provisioningScript(SetupWizard.defaults(dir).database());
        assertThat(sql).contains("GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO \"jtrac_app\"")
                .contains("CREATE SCHEMA jtac AUTHORIZATION \"jtrac_owner\"")
                .doesNotContain("GRANT CREATE")
                .doesNotContain("SUPERUSER");
    }
}
