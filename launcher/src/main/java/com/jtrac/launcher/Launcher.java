package com.jtrac.launcher;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code jtrac} command. Exit codes: 0 success, 1 failure, 2 usage or conflict,
 * 3 not running / unhealthy (status).
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (SetupException e) {
            Out.fail(e.getMessage());
            code = 1;
        } catch (IOException e) {
            Out.fail("File system error: " + e.getMessage());
            code = 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = 130;
        }
        pauseIfDoubleClicked();
        System.exit(code);
    }

    /** A .bat started from Explorer closes its window at once; keep it open so the result can be read. */
    private static void pauseIfDoubleClicked() {
        if (Platform.current().os() != Platform.Os.WINDOWS || System.console() == null) {
            return;
        }
        boolean fromExplorer = ProcessHandle.current().parent().flatMap(ProcessHandle::parent)
                .flatMap(p -> p.info().command())
                .map(c -> c.toLowerCase(java.util.Locale.ROOT).endsWith("explorer.exe"))
                .orElse(false);
        if (fromExplorer) {
            System.out.print("Press Enter to close this window.");
            System.console().readLine();
        }
    }

    static int run(String[] rawArgs) throws IOException, InterruptedException {
        Args args = Args.parse(rawArgs);
        if (args.command.isEmpty() || args.command.equals("help") || args.flags.containsKey("help")) {
            help();
            return args.command.isEmpty() ? 2 : 0;
        }
        Installation installation = Installation.locate(args.flags.get("data-dir"));
        Prompter prompter = args.flags.containsKey("answers") ? Prompter.fromFile(Path.of(args.flags.get("answers"))) : Prompter.interactive();
        Lifecycle lifecycle = new Lifecycle(installation);
        Maintenance maintenance = new Maintenance(installation, prompter);
        return switch (args.command) {
            case "version" -> {
                Out.line("JTRAC " + installation.version() + " (" + Platform.current().id() + ", Java " + Runtime.version() + ")");
                yield 0;
            }
            case "setup" -> new SetupWizard(prompter, installation).run(!args.flags.containsKey("no-start"));
            case "start" -> lifecycle.start(Duration.ofSeconds(Long.parseLong(args.flags.getOrDefault("timeout", "180"))));
            case "stop" -> lifecycle.stop();
            case "restart" -> lifecycle.restart();
            case "status" -> lifecycle.status();
            case "run" -> lifecycle.run();
            case "logs" -> lifecycle.logs(Integer.parseInt(args.flags.getOrDefault("lines", "100")), args.flags.containsKey("follow"));
            case "configure" -> maintenance.configure(args.positional.isEmpty() ? null : args.positional.getFirst());
            case "test-email" -> maintenance.testEmail(args.flags.get("to"));
            case "backup" -> {
                new Backups(installation).backup(Maintenance.path(args.flags.get("output")).orElse(null), false);
                yield 0;
            }
            case "verify-backup" -> {
                Map<String, String> info = Backups.verify(Path.of(requirePositional(args, "backup file")));
                Out.ok("Backup is intact: " + info);
                yield 0;
            }
            case "restore" -> {
                Path archive = Path.of(requirePositional(args, "backup file"));
                Out.warn("Restoring replaces the current database and attachments with the backup's contents.");
                Out.info("The current data is moved aside, not deleted.");
                if (!args.flags.containsKey("yes") && !prompter.askYesNo("confirm", "Restore " + archive.getFileName(), false)) {
                    Out.info("Nothing was changed.");
                    yield 1;
                }
                new Backups(installation).restore(archive, args.flags.containsKey("with-config"));
                yield 0;
            }
            case "migrate" -> maintenance.migrate();
            case "update" -> {
                Path from = Maintenance.path(args.flags.get("from")).orElse(null);
                Installation target = installation;
                if (from != null && args.flags.get("data-dir") == null) {
                    target = Installation.readPointer(from).map(installation::withData).orElse(installation);
                }
                yield new Maintenance(target, prompter).update(from);
            }
            case "service" -> {
                Services services = new Services(installation);
                String action = args.positional.isEmpty() ? "status" : args.positional.getFirst();
                yield switch (action) {
                    case "install" -> services.install();
                    case "remove" -> services.remove();
                    case "status" -> {
                        Out.info(services.installed() ? "Automatic start is registered." : "Automatic start is not registered.");
                        yield 0;
                    }
                    default -> throw new SetupException("Use: jtrac service install | remove | status");
                };
            }
            case "uninstall" -> maintenance.uninstall(args.flags.containsKey("delete-data"));
            default -> {
                Out.fail("Unknown command '" + args.command + "'.");
                help();
                yield 2;
            }
        };
    }

    private static String requirePositional(Args args, String what) {
        if (args.positional.isEmpty()) {
            throw new SetupException("Give the " + what + ".");
        }
        return args.positional.getFirst();
    }

    private static void help() {
        Out.line("""
                JTRAC server command

                Usage: jtrac <command> [options]

                  setup                 First-run setup wizard (database, administrator, port, email); safe to re-run
                      --answers FILE    Unattended setup from an answers file (see docs/setup-wizard.md)
                      --no-start        Don't start JTRAC at the end
                  start | stop | restart | status
                  run                   Run in the foreground (used by services; Ctrl+C stops)
                  logs [--lines N] [--follow]
                  configure [http|smtp|security|storage|legal]   Change settings without reinstalling
                  test-email [--to ADDRESS]                      Send a test email with the saved settings
                  backup [--output DIR]                          Verified backup of database, attachments, settings
                  verify-backup FILE
                  restore FILE [--with-config] [--yes]           Current data is moved aside, never deleted
                  migrate                                        Apply database migrations of this release
                  update [--from OLD_FOLDER]                     Move an installation to this release (backup first)
                  service install|remove|status                  Start automatically with the OS (no admin rights)
                  uninstall [--delete-data]                      Keeps your data unless --delete-data is confirmed
                  version

                Common option: --data-dir DIR (or JTRAC_DATA_DIR) selects the data directory.
                """);
    }

    /** Minimal option parsing: --flag, --flag value, --flag=value and positionals. */
    record Args(String command, Map<String, String> flags, List<String> positional) {

        private static final List<String> VALUE_FLAGS = List.of("data-dir", "answers", "timeout", "lines", "to", "output", "from");

        static Args parse(String[] raw) {
            String command = "";
            Map<String, String> flags = new java.util.HashMap<>();
            List<String> positional = new ArrayList<>();
            for (int i = 0; i < raw.length; i++) {
                String a = raw[i];
                if (a.startsWith("--")) {
                    String name = a.substring(2);
                    String value = "";
                    int eq = name.indexOf('=');
                    if (eq >= 0) {
                        value = name.substring(eq + 1);
                        name = name.substring(0, eq);
                    } else if (VALUE_FLAGS.contains(name) && i + 1 < raw.length) {
                        value = raw[++i];
                    }
                    flags.put(name, value);
                } else if (command.isEmpty()) {
                    command = a;
                } else {
                    positional.add(a);
                }
            }
            return new Args(command, flags, positional);
        }
    }
}
