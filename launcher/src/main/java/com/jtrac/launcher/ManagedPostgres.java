package com.jtrac.launcher;

import com.jtrac.serverconfig.FilePermissions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The bundled PostgreSQL 18 cluster in managed mode: initialized in the data directory,
 * listening on 127.0.0.1 only, password authentication (SCRAM-SHA-256) for every role, and
 * started and stopped by the jtrac command.
 */
final class ManagedPostgres {

    static final String SUPERUSER = "postgres";

    private final Installation installation;
    private final int port;

    ManagedPostgres(Installation installation, int port) {
        this.installation = installation;
        this.port = port;
    }

    boolean initialized() {
        return Files.isRegularFile(installation.pgData().resolve("PG_VERSION"));
    }

    /** Creates the cluster. The superuser password is passed through an owner-only temporary file. */
    void initialize(String superuserPassword) throws IOException {
        if (initialized()) {
            return;
        }
        Path data = installation.pgData();
        Files.createDirectories(data.getParent());
        Path pwFile = Files.createTempFile(installation.data(), "pg-init", ".tmp");
        try {
            FilePermissions.restrictToOwner(pwFile);
            Files.writeString(pwFile, superuserPassword, StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>(List.of(installation.pgTool("initdb").toString(),
                    "--pgdata=" + data, "--username=" + SUPERUSER, "--pwfile=" + pwFile,
                    "--auth=scram-sha-256", "--encoding=UTF8",
                    "--locale-provider=builtin", "--builtin-locale=C.UTF-8", "--data-checksums"));
            Processes.Result result = Processes.run(command, Map.of(), null, Duration.ofMinutes(5));
            if (!result.ok()) {
                throw new SetupException("initdb failed:\n" + result.output());
            }
        } finally {
            Files.deleteIfExists(pwFile);
        }
        // Loopback only, never on the network; no Unix socket in /tmp.
        Path conf = data.resolve("postgresql.conf");
        Files.writeString(conf, Files.readString(conf, StandardCharsets.UTF_8) + """

                # --- JTRAC managed settings -------------------------------------------------
                listen_addresses = '127.0.0.1'
                port = %d
                unix_socket_directories = ''
                password_encryption = scram-sha-256
                max_connections = 50
                logging_collector = off
                """.formatted(port), StandardCharsets.UTF_8);
        // Only TCP from loopback, always with a password.
        Files.writeString(data.resolve("pg_hba.conf"), """
                # JTRAC managed cluster: loopback TCP only, SCRAM passwords for every role.
                # TYPE  DATABASE  USER  ADDRESS        METHOD
                host    all       all   127.0.0.1/32   scram-sha-256
                host    all       all   ::1/128        scram-sha-256
                """, StandardCharsets.UTF_8);
        FilePermissions.restrictToOwner(data);
    }

    boolean running() {
        if (!initialized()) {
            return false;
        }
        Processes.Result result = Processes.run(List.of(installation.pgTool("pg_ctl").toString(), "status",
                "--pgdata=" + installation.pgData()), Map.of(), null, Duration.ofSeconds(30));
        return result.exitCode() == 0;
    }

    void start() throws IOException {
        if (running()) {
            return;
        }
        Path logDir = installation.defaultLogDir();
        Files.createDirectories(logDir);
        Processes.Result result = Processes.run(List.of(installation.pgTool("pg_ctl").toString(), "start",
                "--pgdata=" + installation.pgData(), "--log=" + logDir.resolve("postgresql.log"), "--wait", "--timeout=90",
                "--silent"), Map.of(), installation.data(), Duration.ofSeconds(120));
        if (!result.ok() || !running()) {
            throw new SetupException("The managed PostgreSQL server did not start (see " + logDir.resolve("postgresql.log")
                    + "). Is port " + port + " already in use?\n" + result.output());
        }
    }

    void stop() {
        if (!running()) {
            return;
        }
        Processes.Result result = Processes.run(List.of(installation.pgTool("pg_ctl").toString(), "stop",
                "--pgdata=" + installation.pgData(), "--mode=fast", "--wait", "--timeout=90", "--silent"),
                Map.of(), null, Duration.ofSeconds(120));
        if (!result.ok()) {
            throw new SetupException("The managed PostgreSQL server did not stop cleanly:\n" + result.output());
        }
    }

    String version() {
        Processes.Result result = Processes.run(List.of(installation.pgTool("postgres").toString(), "--version"),
                Map.of(), null, Duration.ofSeconds(30));
        return result.ok() ? result.output().strip() : "unavailable (" + result.output().strip() + ")";
    }
}
