package com.jtrac.launcher;

import com.jtrac.serverconfig.ServerConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Starts, stops and checks the JTRAC web server process. The process is identified by a PID
 * file plus its command line (it must be a java process running jtrac-server.jar from this
 * data directory), so a reused PID or another program on the same port is never touched.
 * Stopping is graceful: the server watches a stop-request file and shuts down cleanly.
 */
final class ServerProcess {

    private static final String JAR_NAME = "jtrac-server.jar";

    private final Installation installation;

    ServerProcess(Installation installation) {
        this.installation = installation;
    }

    Path pidFile() {
        return installation.runDir().resolve("jtrac-server.pid");
    }

    Path stopFile() {
        return installation.runDir().resolve("stop.request");
    }

    Path consoleLog(ServerConfig config) {
        return logDir(config).resolve("jtrac-console.log");
    }

    Path logDir(ServerConfig config) {
        String dir = config.storage().logDir();
        return dir == null || dir.isBlank() ? installation.defaultLogDir() : Path.of(dir);
    }

    /** The running server, if this installation's PID file points at it. */
    Optional<ProcessHandle> running() {
        if (!Files.isRegularFile(pidFile())) {
            return Optional.empty();
        }
        try {
            long pid = Long.parseLong(Files.readString(pidFile(), StandardCharsets.UTF_8).strip());
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).filter(this::isOurs);
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    private boolean isOurs(ProcessHandle handle) {
        ProcessHandle.Info info = handle.info();
        String command = info.command().orElse("").toLowerCase(java.util.Locale.ROOT);
        if (!command.isEmpty() && !command.contains("java")) {
            return false;
        }
        Optional<String> line = info.commandLine();
        // Some platforms hide other processes' arguments; then the PID file plus "java" must do.
        return line.map(l -> l.contains(JAR_NAME) && l.contains(installation.configDir().toString())).orElse(true);
    }

    List<String> command(ServerConfig config, boolean windowless) {
        List<String> command = new ArrayList<>();
        command.add(installation.java(windowless).toString());
        command.add("-Xms128m");
        command.add("-Xmx" + System.getProperty("jtrac.server.max-heap", "1g"));
        command.add("-XX:+ExitOnOutOfMemoryError");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Djava.io.tmpdir=" + installation.runDir().resolve("tmp"));
        command.add("-Djtrac.config-dir=" + installation.configDir());
        command.add("-Djtrac.stop-file=" + stopFile());
        command.add("-Djtac.setup.code-file=" + installation.runDir().resolve("setup-code.txt"));
        command.add("-jar");
        command.add(installation.serverJar().toString());
        return command;
    }

    /** Starts the server detached from this console and records its PID. */
    long startDetached(ServerConfig config) throws IOException {
        Files.createDirectories(installation.runDir().resolve("tmp"));
        Files.createDirectories(logDir(config));
        Files.deleteIfExists(stopFile());
        List<String> command = command(config, true);
        if (Platform.current().os() != Platform.Os.WINDOWS && Files.isExecutable(Path.of("/usr/bin/nohup"))) {
            // Keep running when the terminal that started it closes.
            command.addFirst("/usr/bin/nohup");
        }
        Path log = consoleLog(config);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(installation.data().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .redirectInput(ProcessBuilder.Redirect.from(nullDevice().toFile()));
        Process process = builder.start();
        Files.writeString(pidFile(), Long.toString(process.pid()), StandardCharsets.UTF_8);
        return process.pid();
    }

    /** Asks the server to stop and waits; forces termination only after the grace period. */
    boolean stop(Duration grace) throws IOException {
        Optional<ProcessHandle> handle = running();
        if (handle.isEmpty()) {
            Files.deleteIfExists(pidFile());
            return false;
        }
        Files.createDirectories(installation.runDir());
        Files.writeString(stopFile(), Instant.now().toString(), StandardCharsets.UTF_8);
        Instant deadline = Instant.now().plus(grace);
        while (handle.get().isAlive() && Instant.now().isBefore(deadline)) {
            sleep(500);
        }
        if (handle.get().isAlive()) {
            Out.warn("JTRAC did not stop within " + grace.toSeconds() + " s; terminating it.");
            handle.get().destroy();
            sleep(5000);
            if (handle.get().isAlive()) {
                handle.get().destroyForcibly();
            }
        }
        Files.deleteIfExists(pidFile());
        Files.deleteIfExists(stopFile());
        return true;
    }

    /** Polls the readiness endpoint until it reports UP. */
    boolean waitUntilHealthy(ServerConfig config, Duration timeout, ProcessHandle process) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (process != null && !process.isAlive()) {
                return false;
            }
            if (healthy(config)) {
                return true;
            }
            sleep(1000);
        }
        return false;
    }

    static boolean healthy(ServerConfig config) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(localUrl(config) + "/actuator/health/readiness"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("UP");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The address the launcher itself uses to reach the server (loopback when bound to all interfaces). */
    static String localUrl(ServerConfig config) {
        String host = config.http().bindAddress();
        if (host.equals("0.0.0.0") || host.equals("::") || host.equals("[::]")) {
            host = "127.0.0.1";
        }
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return "http://" + host + ":" + config.http().port();
    }

    static Path nullDevice() {
        return Path.of(Platform.current().os() == Platform.Os.WINDOWS ? "NUL" : "/dev/null");
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
