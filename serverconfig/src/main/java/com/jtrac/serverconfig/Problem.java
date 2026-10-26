package com.jtrac.serverconfig;

/**
 * One validation finding. Errors block saving; warnings are shown and need confirmation.
 *
 * @param field   dotted setting name, e.g. {@code smtp.port}
 */
public record Problem(Severity severity, String field, String message) {

    public enum Severity { ERROR, WARNING }

    public static Problem error(String field, String message) {
        return new Problem(Severity.ERROR, field, message);
    }

    public static Problem warning(String field, String message) {
        return new Problem(Severity.WARNING, field, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }
}
