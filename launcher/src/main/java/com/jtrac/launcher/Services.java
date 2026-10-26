package com.jtrac.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Automatic start with the operating system, without administrator rights by default:
 * a systemd user unit on Linux, a launchd agent on macOS, a script in the user's
 * Startup folder on Windows. Linux and macOS run {@code jtrac run} in the foreground under
 * their service manager; Windows runs {@code jtrac start} at sign-in.
 */
final class Services {

    private static final String NAME = "jtrac";
    private static final String MAC_LABEL = "com.jtrac.server";

    private final Installation installation;

    Services(Installation installation) {
        this.installation = installation;
    }

    private Path jtracCommand() {
        return installation.home().resolve("bin").resolve(Platform.current().os() == Platform.Os.WINDOWS ? "jtrac.cmd" : "jtrac");
    }

    int install() throws IOException {
        if (!installation.store().exists()) {
            throw new SetupException("Run `jtrac setup` first.");
        }
        return switch (Platform.current().os()) {
            case LINUX -> installSystemd();
            case MACOS -> installLaunchd();
            case WINDOWS -> installWindowsTask();
        };
    }

    int remove() throws IOException {
        return switch (Platform.current().os()) {
            case LINUX -> {
                Path unit = systemdUnit();
                systemctl("disable", "--now", NAME + ".service");
                Files.deleteIfExists(unit);
                systemctl("daemon-reload");
                Out.ok("Removed the systemd user service.");
                yield 0;
            }
            case MACOS -> {
                Path plist = launchAgent();
                Processes.run(List.of("launchctl", "bootout", "gui/" + uid(), plist.toString()), Map.of(), null, Duration.ofSeconds(30));
                Files.deleteIfExists(plist);
                Out.ok("Removed the launchd agent.");
                yield 0;
            }
            case WINDOWS -> {
                boolean existed = Files.deleteIfExists(windowsStartupScript());
                Out.ok(existed ? "Removed the automatic start at sign-in." : "Automatic start was not registered.");
                yield 0;
            }
        };
    }

    boolean installed() {
        return switch (Platform.current().os()) {
            case LINUX -> Files.exists(systemdUnit());
            case MACOS -> Files.exists(launchAgent());
            case WINDOWS -> Files.exists(windowsStartupScript());
        };
    }

    private int installSystemd() throws IOException {
        Path unit = systemdUnit();
        Files.createDirectories(unit.getParent());
        Files.writeString(unit, """
                [Unit]
                Description=JTRAC issue tracker
                After=network-online.target

                [Service]
                Type=simple
                Environment=JTRAC_DATA_DIR=%s
                ExecStart=%s run
                # A JVM stopped by SIGTERM exits with 143 after its clean shutdown: that's success.
                SuccessExitStatus=143
                Restart=on-failure
                RestartSec=10
                TimeoutStopSec=120

                [Install]
                WantedBy=default.target
                """.formatted(installation.data(), jtracCommand()), StandardCharsets.UTF_8);
        Processes.Result r = systemctl("daemon-reload");
        if (!r.ok()) {
            Out.warn("systemctl --user is not available here (" + r.output().strip() + ").");
            Out.info("The unit was written to " + unit + "; use `jtrac start` instead, or run it under a system service manager.");
            return 1;
        }
        Out.ok("Installed systemd user service " + unit);
        Out.info("Start now:          systemctl --user start jtrac   (stop JTRAC first if it runs via `jtrac start`)");
        Out.info("Start at login:     systemctl --user enable jtrac");
        Out.info("Start at boot without logging in needs: sudo loginctl enable-linger " + System.getProperty("user.name"));
        return 0;
    }

    private int installLaunchd() throws IOException {
        Path plist = launchAgent();
        Files.createDirectories(plist.getParent());
        Path logs = installation.defaultLogDir();
        Files.createDirectories(logs);
        Files.writeString(plist, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                  <key>Label</key><string>%s</string>
                  <key>ProgramArguments</key><array><string>%s</string><string>run</string></array>
                  <key>EnvironmentVariables</key><dict><key>JTRAC_DATA_DIR</key><string>%s</string></dict>
                  <key>RunAtLoad</key><true/>
                  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
                  <key>StandardOutPath</key><string>%s</string>
                  <key>StandardErrorPath</key><string>%s</string>
                </dict>
                </plist>
                """.formatted(MAC_LABEL, xml(jtracCommand().toString()), xml(installation.data().toString()),
                xml(logs.resolve("launchd.log").toString()), xml(logs.resolve("launchd.log").toString())), StandardCharsets.UTF_8);
        Processes.Result r = Processes.run(List.of("launchctl", "bootstrap", "gui/" + uid(), plist.toString()), Map.of(), null,
                Duration.ofSeconds(30));
        if (!r.ok()) {
            Out.warn("launchctl bootstrap failed: " + r.output().strip());
            return 1;
        }
        Out.ok("Installed launchd agent " + plist + " (starts at login).");
        return 0;
    }

    /**
     * Per-user autostart without administrator rights: a script in the Startup folder runs
     * {@code jtrac start} minimized when the user signs in. (Creating a logon-triggered scheduled
     * task needs administrator rights on most Windows installations; see docs/installation.md for
     * running JTRAC as a service under a dedicated account.)
     */
    private int installWindowsTask() throws IOException {
        Path script = windowsStartupScript();
        Files.createDirectories(script.getParent());
        Path log = installation.defaultLogDir().resolve("autostart.log");
        Files.writeString(script, "@echo off\r\n"
                + "rem Starts JTRAC when you sign in to Windows. Remove with: jtrac service remove\r\n"
                + "start \"JTRAC\" /min cmd /c \"\"" + jtracCommand() + "\" start --data-dir \"" + installation.data()
                + "\" >> \"" + log + "\" 2>&1\"\r\n", StandardCharsets.UTF_8);
        Out.ok("JTRAC will start when you sign in to Windows (" + script + ").");
        Out.info("Start it now with `jtrac start`.");
        return 0;
    }

    private static Path windowsStartupScript() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null ? Path.of(appData) : Path.of(System.getProperty("user.home"), "AppData", "Roaming");
        return base.resolve("Microsoft").resolve("Windows").resolve("Start Menu").resolve("Programs").resolve("Startup")
                .resolve("JTRAC Server.cmd");
    }

    private static Processes.Result systemctl(String... args) {
        List<String> command = new java.util.ArrayList<>(List.of("systemctl", "--user"));
        command.addAll(List.of(args));
        return Processes.run(command, Map.of(), null, Duration.ofSeconds(60));
    }

    private static Path systemdUnit() {
        String config = System.getenv("XDG_CONFIG_HOME");
        Path base = config != null && !config.isBlank() ? Path.of(config) : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("systemd").resolve("user").resolve(NAME + ".service");
    }

    private static Path launchAgent() {
        return Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", MAC_LABEL + ".plist");
    }

    private static String uid() {
        Processes.Result r = Processes.run(List.of("id", "-u"), Map.of(), null, Duration.ofSeconds(10));
        return r.output().strip();
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
