package com.jtac.mcp.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tool arguments. Values come from an agent and are untrusted: everything is validated here
 * or by the domain services, never used to choose a tenant, user or permission. Invalid
 * arguments raise {@link Invalid}, reported to the agent as a tool error.
 */
final class Args {

    static final Pattern ISSUE_KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,19}-[1-9][0-9]{0,9}");
    static final Pattern PROJECT_KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,19}");

    private final Map<String, Object> values;

    Args(Map<String, Object> values) {
        this.values = values == null ? Map.of() : values;
    }

    static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message);
        }
    }

    boolean has(String name) {
        return values.containsKey(name);
    }

    boolean isNull(String name) {
        return values.containsKey(name) && values.get(name) == null;
    }

    String str(String name) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new Invalid(name + " must be a string.");
        }
        return s;
    }

    String required(String name) {
        String value = str(name);
        if (value == null || value.isBlank()) {
            throw new Invalid(name + " is required.");
        }
        return value.strip();
    }

    String issueKey(String name) {
        String key = required(name).toUpperCase(Locale.ROOT);
        if (!ISSUE_KEY.matcher(key).matches()) {
            throw new Invalid(name + " must be an issue key such as WEB-12.");
        }
        return key;
    }

    String projectKey(String name, boolean required) {
        String key = required ? required(name) : str(name);
        if (key == null || key.isBlank()) {
            return null;
        }
        key = key.strip().toUpperCase(Locale.ROOT);
        if (!PROJECT_KEY.matcher(key).matches()) {
            throw new Invalid(name + " must be a project key such as WEB.");
        }
        return key;
    }

    int integer(String name, int defaultValue, int min, int max) {
        Object value = values.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
            throw new Invalid(name + " must be an integer.");
        }
        long v = n.longValue();
        if (v < min || v > max) {
            throw new Invalid(name + " must be between " + min + " and " + max + ".");
        }
        return (int) v;
    }

    Long longValue(String name) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
            throw new Invalid(name + " must be an integer.");
        }
        return n.longValue();
    }

    Boolean bool(String name) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Boolean b)) {
            throw new Invalid(name + " must be true or false.");
        }
        return b;
    }

    boolean bool(String name, boolean defaultValue) {
        Boolean value = bool(name);
        return value == null ? defaultValue : value;
    }

    List<String> strings(String name) {
        Object value = values.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new Invalid(name + " must be a list of strings.");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String s)) {
                throw new Invalid(name + " must be a list of strings.");
            }
            result.add(s.strip());
        }
        return result;
    }

    UUID uuid(String name) {
        String value = required(name);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new Invalid(name + " must be an id as returned by another tool.");
        }
    }

    LocalDate date(String name) {
        String value = str(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new Invalid(name + " must be a date (YYYY-MM-DD).");
        }
    }

    /** An instant (ISO-8601) or a date (start of that day, UTC). */
    Instant since(String name) {
        String value = str(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(value.strip()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException e2) {
                throw new Invalid(name + " must be a date (YYYY-MM-DD) or timestamp (ISO-8601).");
            }
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> map(String name) {
        Object value = values.get(name);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> m)) {
            throw new Invalid(name + " must be an object.");
        }
        return (Map<String, Object>) m;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> objects(String name) {
        Object value = values.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || list.stream().anyMatch(i -> !(i instanceof Map))) {
            throw new Invalid(name + " must be a list of objects.");
        }
        return (List<Map<String, Object>>) value;
    }

    /** The target (issue or project key) recorded in the activity log, if the call names one. */
    String target() {
        for (String name : List.of("issueKey", "projectKey")) {
            if (values.get(name) instanceof String s && s.length() <= 40) {
                return s.strip().toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }
}
