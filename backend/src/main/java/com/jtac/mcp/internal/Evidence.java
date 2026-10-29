package com.jtac.mcp.internal;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic extraction from untrusted text (issue descriptions, comments, text and log
 * attachments). Nothing is executed, fetched or interpreted: only bounded pattern matching.
 * Lines are capped before matching so every pattern runs in linear time.
 */
final class Evidence {

    static final int MAX_LINE = 2000;
    private static final int MAX_LINES = 200_000;

    private static final Pattern HEADING = Pattern.compile(
            "^\\s{0,3}(?:#{1,6}\\s*|\\*\\*|__)?([A-Za-z][A-Za-z /&()-]{1,60}?)(?:\\*\\*|__)?\\s*:?\\s*(?:\\*\\*|__)?\\s*$");
    private static final Map<String, List<String>> SECTIONS = new LinkedHashMap<>();

    static {
        SECTIONS.put("reproductionSteps", List.of("steps to reproduce", "reproduction steps", "how to reproduce", "repro steps",
                "reproduction", "repro", "steps"));
        SECTIONS.put("expectedBehavior", List.of("expected behavior", "expected behaviour", "expected result", "expected results",
                "expected"));
        SECTIONS.put("actualBehavior", List.of("actual behavior", "actual behaviour", "actual result", "actual results", "actual",
                "observed behavior", "observed behaviour", "observed", "current behavior", "current behaviour"));
        SECTIONS.put("environment", List.of("environment", "environment details", "system information", "platform",
                "versions", "version", "setup"));
        SECTIONS.put("acceptanceCriteria", List.of("acceptance criteria", "definition of done", "done when", "ac"));
    }

    private static final Pattern EXCEPTION = Pattern.compile(
            "\\b((?:[a-zA-Z_$][\\w$]{0,60}\\.){0,10}[A-Z][\\w$]{0,60}(?:Exception|Error|Panic|Fault))\\b");
    private static final Pattern STACK_FRAME = Pattern.compile(
            "^\\s+at\\s+\\S{1,300}|^\\s*File \"[^\"]{1,300}\", line \\d{1,7}|^\\s*Caused by:|^Traceback \\(most recent call last\\)"
                    + "|^goroutine \\d+|^\\s*\\d{1,3}: 0x[0-9a-f]+|^panic:");
    private static final Pattern LEVEL = Pattern.compile("\\b(FATAL|CRITICAL|ERROR|SEVERE|WARN(?:ING)?|INFO|DEBUG|TRACE)\\b");
    private static final Pattern TIMESTAMP = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?)");

    private Evidence() {
    }

    /** UTF-8 text without NUL bytes (a BOM is dropped), or empty for binary or malformed data. */
    static Optional<String> decodeText(byte[] bytes, boolean truncated) {
        int start = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        int end = bytes.length;
        if (truncated) {
            // The read may have cut a multi-byte character: drop an incomplete trailing sequence.
            int i = end - 1;
            int back = 0;
            while (i >= start && back < 3 && (bytes[i] & 0xC0) == 0x80) {
                i--;
                back++;
            }
            if (i >= start && (bytes[i] & 0x80) != 0) {
                int needed = (bytes[i] & 0xE0) == 0xC0 ? 1 : (bytes[i] & 0xF0) == 0xE0 ? 2 : (bytes[i] & 0xF8) == 0xF0 ? 3 : 0;
                if (needed > back) {
                    end = i;
                }
            }
        }
        for (int i = start; i < end; i++) {
            if (bytes[i] == 0) {
                return Optional.empty();
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return Optional.of(decoder.decode(ByteBuffer.wrap(bytes, start, end - start)).toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    /**
     * Sections of an issue description found by their headings (Markdown {@code ## Expected},
     * {@code **Steps to reproduce**}, or a line such as {@code Actual behaviour:}).
     */
    static Map<String, String> sections(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        String current = null;
        StringBuilder body = new StringBuilder();
        for (String raw : lines(text)) {
            String line = cap(raw);
            Matcher heading = HEADING.matcher(line);
            String section = heading.matches() ? sectionOf(heading.group(1)) : null;
            boolean headingLine = heading.matches() && (section != null || looksLikeHeading(line));
            if (headingLine) {
                store(result, current, body);
                current = section;
                body.setLength(0);
                continue;
            }
            if (current != null && body.length() < 20_000) {
                body.append(raw).append('\n');
            }
        }
        store(result, current, body);
        return result;
    }

    private static boolean looksLikeHeading(String line) {
        String t = line.strip();
        return t.startsWith("#") || (t.startsWith("**") && t.endsWith("**")) || (t.endsWith(":") && t.length() <= 40);
    }

    private static String sectionOf(String heading) {
        String h = heading.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (Map.Entry<String, List<String>> e : SECTIONS.entrySet()) {
            if (e.getValue().contains(h)) {
                return e.getKey();
            }
        }
        return null;
    }

    private static void store(Map<String, String> result, String section, StringBuilder body) {
        if (section != null && !body.toString().isBlank() && !result.containsKey(section)) {
            result.put(section, body.toString().strip());
        }
    }

    /** Error signals in text: exception types (with counts) and the first matching lines. */
    static Map<String, Object> errorSignals(String text, int maxLines) {
        Map<String, Integer> exceptions = new TreeMap<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        int number = 0;
        for (String raw : lines(text)) {
            number++;
            String line = cap(raw);
            Matcher m = EXCEPTION.matcher(line);
            boolean hit = false;
            while (m.find()) {
                exceptions.merge(m.group(1), 1, Integer::sum);
                hit = true;
            }
            Matcher level = LEVEL.matcher(line);
            boolean error = level.find() && isErrorLevel(level.group(1));
            if ((hit || error || STACK_FRAME.matcher(line).find()) && lines.size() < maxLines) {
                lines.add(Map.of("line", number, "text", ToolSupport.clip(line.strip(), 300)));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exceptionTypes", exceptions);
        result.put("errorLines", lines);
        return result;
    }

    /** A summary of a log: level counts, exception types, time range, first errors and stack traces. */
    static Map<String, Object> analyzeLog(String text) {
        Map<String, Integer> levels = new TreeMap<>();
        Map<String, Integer> exceptions = new TreeMap<>();
        List<Map<String, Object>> firstErrors = new ArrayList<>();
        List<Map<String, Object>> traces = new ArrayList<>();
        String firstTimestamp = null;
        String lastTimestamp = null;
        List<String> trace = null;
        int traceStart = 0;
        int number = 0;
        String previous = null;
        for (String raw : lines(text)) {
            number++;
            String line = cap(raw);
            Matcher ts = TIMESTAMP.matcher(line);
            if (ts.find()) {
                if (firstTimestamp == null) {
                    firstTimestamp = ts.group(1);
                }
                lastTimestamp = ts.group(1);
            }
            Matcher level = LEVEL.matcher(line);
            if (level.find()) {
                String l = normalizeLevel(level.group(1));
                levels.merge(l, 1, Integer::sum);
                if (isErrorLevel(l) && firstErrors.size() < 20) {
                    firstErrors.add(Map.of("line", number, "text", ToolSupport.clip(line.strip(), 400)));
                }
            }
            Matcher ex = EXCEPTION.matcher(line);
            while (ex.find()) {
                exceptions.merge(ex.group(1), 1, Integer::sum);
            }
            boolean frame = STACK_FRAME.matcher(line).find();
            if (frame) {
                if (trace == null && traces.size() < 5) {
                    trace = new ArrayList<>();
                    traceStart = number;
                    if (previous != null && !previous.isBlank()) {
                        trace.add(ToolSupport.clip(previous.strip(), 400));
                        traceStart = number - 1;
                    }
                }
                if (trace != null && trace.size() < 40) {
                    trace.add(ToolSupport.clip(line.stripTrailing(), 400));
                }
            } else if (trace != null) {
                traces.add(Map.of("startLine", traceStart, "lines", trace));
                trace = null;
            }
            previous = line;
        }
        if (trace != null) {
            traces.add(Map.of("startLine", traceStart, "lines", trace));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lines", number);
        result.put("levels", levels);
        result.put("exceptionTypes", exceptions);
        result.put("firstTimestamp", firstTimestamp);
        result.put("lastTimestamp", lastTimestamp);
        result.put("firstErrors", firstErrors);
        result.put("stackTraces", traces);
        return result;
    }

    /** Lines containing {@code query} (case-insensitive unless asked), with line numbers. */
    static List<Map<String, Object>> grep(String text, String query, boolean caseSensitive, int max) {
        List<Map<String, Object>> matches = new ArrayList<>();
        String needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        int number = 0;
        for (String raw : lines(text)) {
            number++;
            String line = cap(raw);
            if ((caseSensitive ? line : line.toLowerCase(Locale.ROOT)).contains(needle)) {
                matches.add(Map.of("line", number, "text", ToolSupport.clip(line.strip(), 400)));
                if (matches.size() >= max) {
                    break;
                }
            }
        }
        return matches;
    }

    private static List<String> lines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return text.lines().limit(MAX_LINES).toList();
    }

    private static String cap(String line) {
        return line.length() <= MAX_LINE ? line : line.substring(0, MAX_LINE);
    }

    private static String normalizeLevel(String level) {
        return switch (level) {
            case "WARNING" -> "WARN";
            case "SEVERE", "CRITICAL" -> "ERROR";
            default -> level;
        };
    }

    private static boolean isErrorLevel(String level) {
        return switch (level) {
            case "ERROR", "FATAL", "SEVERE", "CRITICAL" -> true;
            default -> false;
        };
    }
}
