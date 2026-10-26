package com.jtrac.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs helper programs (pg_ctl, initdb, migrations) and captures their output. */
final class Processes {

    record Result(int exitCode, String output) {
        boolean ok() {
            return exitCode == 0;
        }
    }

    private Processes() {
    }

    /**
     * Runs a command to completion. Secrets go in {@code environment}, never in arguments, so
     * they don't show up in process listings. Output goes through a temporary file rather than a
     * pipe: programs such as {@code pg_ctl start} leave a background server holding the output
     * handle, which would block a pipe reader forever.
     */
    static Result run(List<String> command, Map<String, String> environment, Path workingDir, Duration timeout) {
        Path output = null;
        try {
            output = Files.createTempFile("jtrac-cmd", ".out");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(ServerProcess.nullDevice().toFile()));
            if (workingDir != null) {
                builder.directory(workingDir.toFile());
            }
            builder.environment().putAll(environment);
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(124, "Timed out after " + timeout.toSeconds() + " s: " + command.getFirst());
            }
            // Lenient decoding: helper programs may print in the console's code page.
            return new Result(process.exitValue(), new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Result(127, "Could not run " + command.getFirst() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(130, "Interrupted");
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // On Windows a background child may still hold the file; it's in the temp folder.
                }
            }
        }
    }
}
