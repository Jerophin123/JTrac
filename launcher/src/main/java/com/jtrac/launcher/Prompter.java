package com.jtrac.launcher;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Asks questions interactively, or takes the answers from an answers file for unattended
 * installs ({@code jtrac setup --answers file}). Secrets can come from environment variables
 * so they never sit in the answers file.
 */
class Prompter {

    /** Validates an answer: empty when fine, otherwise the message to show. */
    interface Check extends Function<String, Optional<String>> {
    }

    private final Map<String, String> answers;
    private final Set<String> used = new HashSet<>();
    private final BufferedReader stdin;
    private final Console console;

    private Prompter(Map<String, String> answers) {
        this.answers = answers;
        this.console = System.console();
        this.stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    static Prompter interactive() {
        return new Prompter(null);
    }

    /**
     * Reads {@code key=value} lines literally (no escape processing), so Windows paths such as
     * {@code C:\JTRAC\data} work as written. Lines starting with # or ! are comments.
     */
    static Prompter fromFile(Path file) throws IOException {
        Map<String, String> map = new TreeMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            // Notepad and Windows PowerShell 5.1 write a UTF-8 byte-order mark.
            String l = (line.startsWith("\uFEFF") ? line.substring(1) : line).strip();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("!")) {
                continue;
            }
            int eq = l.indexOf('=');
            if (eq <= 0) {
                throw new SetupException("Answers file line is not key=value: " + l);
            }
            map.put(l.substring(0, eq).strip(), l.substring(eq + 1).strip());
        }
        return new Prompter(map);
    }

    boolean unattended() {
        return answers != null;
    }

    /** Keys in the answers file that no question used (probably typos). */
    List<String> unusedAnswers() {
        return answers == null ? List.of() : answers.keySet().stream().filter(k -> !used.contains(k)).sorted().toList();
    }

    String ask(String key, String question, String defaultValue, Check check) {
        while (true) {
            String answer;
            if (answers != null) {
                used.add(key);
                answer = answers.getOrDefault(key, defaultValue == null ? "" : defaultValue);
                Out.info(question + ": " + answer);
            } else {
                String prompt = "   " + question + (defaultValue == null || defaultValue.isEmpty() ? "" : " [" + defaultValue + "]") + ": ";
                System.out.print(prompt);
                System.out.flush();
                String line = readLine();
                answer = line == null || line.isBlank() ? (defaultValue == null ? "" : defaultValue) : line.strip();
            }
            Optional<String> problem = check == null ? Optional.empty() : check.apply(answer);
            if (problem.isEmpty()) {
                return answer;
            }
            if (answers != null) {
                throw new SetupException("Answer '" + key + "' is not valid: " + problem.get());
            }
            Out.warn(problem.get());
        }
    }

    int askInt(String key, String question, int defaultValue, int min, int max) {
        String value = ask(key, question, Integer.toString(defaultValue), v -> {
            try {
                int n = Integer.parseInt(v);
                return n < min || n > max ? Optional.of("Enter a number from " + min + " to " + max + ".") : Optional.empty();
            } catch (NumberFormatException e) {
                return Optional.of("Enter a number.");
            }
        });
        return Integer.parseInt(value);
    }

    boolean askYesNo(String key, String question, boolean defaultValue) {
        String value = ask(key, question + " (y/n)", defaultValue ? "y" : "n", v -> {
            String lower = v.toLowerCase(Locale.ROOT);
            return List.of("y", "yes", "n", "no", "true", "false").contains(lower) ? Optional.empty() : Optional.of("Answer y or n.");
        });
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("y") || lower.equals("true");
    }

    /** Asks to pick one of the options (1-based numbers or the option key). */
    String askChoice(String key, String question, List<String[]> options, String defaultKey) {
        if (answers == null) {
            for (int i = 0; i < options.size(); i++) {
                Out.info("  " + (i + 1) + ") " + options.get(i)[1]);
            }
        }
        int defaultIndex = 1;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i)[0].equals(defaultKey)) {
                defaultIndex = i + 1;
            }
        }
        String value = ask(key, question, answers == null ? Integer.toString(defaultIndex) : defaultKey, v -> {
            for (int i = 0; i < options.size(); i++) {
                if (v.equals(Integer.toString(i + 1)) || v.equalsIgnoreCase(options.get(i)[0])) {
                    return Optional.empty();
                }
            }
            return Optional.of("Choose one of the listed options.");
        });
        for (int i = 0; i < options.size(); i++) {
            if (value.equals(Integer.toString(i + 1)) || value.equalsIgnoreCase(options.get(i)[0])) {
                return options.get(i)[0];
            }
        }
        throw new IllegalStateException();
    }

    /**
     * Reads a secret without echo. Unattended: from environment variable {@code envName}, or the
     * answers file (discouraged). Blank is allowed only when {@code allowBlank}.
     */
    String askSecret(String key, String envName, String question, boolean confirm, boolean allowBlank, Check check) {
        if (answers != null) {
            used.add(key);
            String value = envName != null && System.getenv(envName) != null ? System.getenv(envName) : answers.get(key);
            if (value == null || (!allowBlank && value.isEmpty())) {
                throw new SetupException("Set " + (envName != null ? "environment variable " + envName + " or " : "")
                        + "answer '" + key + "' for: " + question);
            }
            Optional<String> problem = check == null ? Optional.empty() : check.apply(value);
            if (problem.isPresent()) {
                throw new SetupException(question + ": " + problem.get());
            }
            Out.info(question + ": ****");
            return value;
        }
        while (true) {
            String first = readSecret("   " + question + ": ");
            if (first.isEmpty() && allowBlank) {
                return "";
            }
            Optional<String> problem = check == null ? Optional.empty() : check.apply(first);
            if (problem.isPresent()) {
                Out.warn(problem.get());
                continue;
            }
            if (confirm && !first.equals(readSecret("   Repeat to confirm: "))) {
                Out.warn("The two entries don't match. Try again.");
                continue;
            }
            return first;
        }
    }

    private String readSecret(String prompt) {
        if (console != null) {
            char[] chars = console.readPassword("%s", prompt);
            return chars == null ? "" : new String(chars);
        }
        // No console (e.g. piped input): the value will be visible while typing.
        System.out.print(prompt + "(input visible) ");
        System.out.flush();
        String line = readLine();
        return line == null ? "" : line;
    }

    private String readLine() {
        try {
            if (console != null) {
                return console.readLine();
            }
            return stdin.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
