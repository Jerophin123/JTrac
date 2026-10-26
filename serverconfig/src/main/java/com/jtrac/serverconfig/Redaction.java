package com.jtrac.serverconfig;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Masks secret values before settings are logged, printed or included in diagnostics. */
public final class Redaction {

    private static final String[] SECRET_MARKERS = {"password", "secret", "token", "credential", "key"};

    private Redaction() {
    }

    public static boolean isSecretKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String marker : SECRET_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** A copy of the settings with every secret value replaced by {@code ****}. */
    public static Map<String, String> redact(Map<String, String> settings) {
        Map<String, String> out = new TreeMap<>();
        settings.forEach((key, value) -> out.put(key, isSecretKey(key) && value != null && !value.isEmpty() ? "****" : value));
        return out;
    }

    /** Removes a known secret from free text (for example a server response that echoes it). */
    public static String scrub(String text, String... secrets) {
        if (text == null) {
            return "";
        }
        String out = text;
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 4) {
                out = out.replace(secret, "****");
            }
        }
        return out;
    }
}
