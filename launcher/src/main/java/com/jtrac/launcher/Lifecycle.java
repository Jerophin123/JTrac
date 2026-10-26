package com.jtrac.launcher;

import com.jtrac.serverconfig.DatabaseMode;
import com.jtrac.serverconfig.PortCheck;
import com.jtrac.serverconfig.ServerConfig;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/** start, stop, restart, status, run (foreground) and logs. */
final class Lifecycle {

    private final Installation installation;
    private final ServerProcess server;

    Lifecycle(Installation installation) {
        this.installation = installation;
        this.server = new ServerProcess(installation);
    }

    ServerConfig config() throws IOException {
        if (!installation.store().exists()) {
            throw new SetupException("JTRAC is not set up in " + installation.data() + ". Run `jtrac setup` first.");
        }
        return installation.store().loadConfig();
    }

    private Optional<ManagedPostgres> managed(ServerConfig config) {
        return config.database().mode() == DatabaseMode.MANAGED
                ? Optional.of(new ManagedPostgres(installation, config.database().port())) : Optional.empty();
    }

    int start(Duration timeout) throws IOException {
        ServerConfig config = config();
        Optional<ProcessHandle> already = server.running();
        if (already.isPresent()) {
            Out.ok("JTRAC is already running (process " + already.get().pid() + ") at " + config.http().publicUrl());
            return 0;
        }
        Optional<String> portProblem = PortCheck.problem(config.http().bindAddress(), config.http().port());
        if (portProblem.isPresent()) {
            Out.fail(portProblem.get());
            Out.info("Another program is using the port. Stop it, or choose another port with `jtrac configure http`.");
            Out.info("(JTRAC never stops other programs.)");
            return 2;
        }
        Optional<ManagedPostgres> pg = managed(config);
        if (pg.isPresent()) {
            pg.get().start();
            Out.ok("Database running on 127.0.0.1:" + config.database().port());
        }
        long pid = server.startDetached(config);
        Out.info("Starting JTRAC (process " + pid + ")...");
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (server.waitUntilHealthy(config, timeout, handle)) {
            Out.ok("JTRAC is running and healthy at " + config.http().publicUrl());
            Out.info("Local check: " + ServerProcess.localUrl(config) + "/actuator/health/readiness");
            return 0;
        }
        Out.fail("JTRAC did not become healthy within " + timeout.toSeconds() + " s.");
        Out.info("Last lines of " + server.consoleLog(config) + ":");
        tail(server.consoleLog(config), 25).forEach(l -> Out.info("  " + l));
        if (handle != null && handle.isAlive()) {
            Out.info("It is still starting; check again with `jtrac status`.");
        } else {
            Files.deleteIfExists(server.pidFile());
        }
        return 1;
    }

    int stop() throws IOException {
        ServerConfig config = config();
        boolean wasRunning = server.stop(Duration.ofSeconds(45));
        Out.ok(wasRunning ? "JTRAC stopped." : "JTRAC was not running.");
        Optional<ManagedPostgres> pg = managed(config);
        if (pg.isPresent() && pg.get().running()) {
            pg.get().stop();
            Out.ok("Database stopped.");
        }
        return 0;
    }

    int restart() throws IOException {
        stop();
        return start(Duration.ofMinutes(3));
    }

    int status() throws IOException {
        ServerConfig config = config();
        Out.title("JTRAC status");
        Out.info("Version:   " + installation.version() + " (" + Platform.current().id() + ")");
        Out.info("Home:      " + installation.home());
        Out.info("Data:      " + installation.data());
        Out.info("Address:   " + config.http().publicUrl() + "  (listening on " + config.http().bindAddress() + ":" + config.http().port() + ")");
        Optional<ManagedPostgres> pg = managed(config);
        boolean dbOk = true;
        if (pg.isPresent()) {
            dbOk = pg.get().running();
            Out.info("Database:  managed, " + (dbOk ? "running" : "stopped") + " on 127.0.0.1:" + config.database().port());
        } else {
            Out.info("Database:  external " + config.database().host() + ":" + config.database().port() + "/" + config.database().name());
        }
        Optional<ProcessHandle> running = server.running();
        boolean healthy = running.isPresent() && ServerProcess.healthy(config);
        Out.info("Server:    " + (running.map(h -> "running (process " + h.pid() + ")").orElse("stopped"))
                + (running.isPresent() ? (healthy ? ", healthy" : ", NOT healthy") : ""));
        Out.info("Email:     " + (config.smtp().enabled() ? config.smtp().host() + ":" + config.smtp().port() : "pending (not configured)"));
        Out.info("Logs:      " + server.logDir(config));
        return running.isPresent() && healthy && dbOk ? 0 : 3;
    }

    /** Foreground mode for systemd, launchd, the Windows task scheduler and containers. */
    int run() throws IOException, InterruptedException {
        ServerConfig config = config();
        if (server.running().isPresent()) {
            Out.fail("JTRAC is already running in the background; stop it first.");
            return 2;
        }
        Optional<ManagedPostgres> pg = managed(config);
        if (pg.isPresent()) {
            pg.get().start();
        }
        Files.createDirectories(installation.runDir().resolve("tmp"));
        Files.deleteIfExists(server.stopFile());
        Process process = new ProcessBuilder(server.command(config, false)).directory(installation.data().toFile()).inheritIO().start();
        Files.writeString(server.pidFile(), Long.toString(process.pid()), StandardCharsets.UTF_8);
        Thread hook = new Thread(() -> {
            try {
                Files.writeString(server.stopFile(), "stop", StandardCharsets.UTF_8);
                if (!process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroy();
                }
                pg.ifPresent(ManagedPostgres::stop);
            } catch (IOException | InterruptedException e) {
                process.destroy();
            }
        });
        Runtime.getRuntime().addShutdownHook(hook);
        int code = process.waitFor();
        Files.deleteIfExists(server.pidFile());
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
            pg.ifPresent(ManagedPostgres::stop);
        } catch (IllegalStateException e) {
            // JVM is already shutting down: the hook stops the database
        }
        return code;
    }

    int logs(int lines, boolean follow) throws IOException, InterruptedException {
        ServerConfig config = config();
        Path log = server.logDir(config).resolve("jtrac.log");
        if (!Files.exists(log)) {
            log = server.consoleLog(config);
        }
        if (!Files.exists(log)) {
            Out.info("No log file yet in " + server.logDir(config));
            return 0;
        }
        Out.info("== " + log);
        tail(log, lines).forEach(System.out::println);
        if (follow) {
            long position = Files.size(log);
            while (true) {
                Thread.sleep(1000);
                long size = Files.size(log);
                if (size < position) {
                    position = 0;
                }
                if (size > position) {
                    try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
                        file.seek(position);
                        byte[] bytes = new byte[(int) (size - position)];
                        file.readFully(bytes);
                        System.out.print(new String(bytes, StandardCharsets.UTF_8));
                    }
                    position = size;
                }
            }
        }
        return 0;
    }

    static List<String> tail(Path file, int lines) {
        if (!Files.exists(file)) {
            return List.of();
        }
        Deque<String> last = new ArrayDeque<>();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                last.addLast(line);
                if (last.size() > lines) {
                    last.removeFirst();
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            return List.of("(log unreadable: " + e.getMessage() + ")");
        }
        return List.copyOf(last);
    }
}
