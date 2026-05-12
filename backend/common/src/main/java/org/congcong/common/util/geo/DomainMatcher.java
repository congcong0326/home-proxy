package org.congcong.common.util.geo;

import java.net.IDN;
import java.util.Locale;

/**
 * Lightweight local domain matcher used by user-defined route rules.
 */
public final class DomainMatcher {

    private DomainMatcher() {
    }

    public static boolean matches(String host, String pattern) {
        if (host == null || pattern == null) {
            return false;
        }
        String normalizedHost = normalize(host);
        String normalizedPattern = normalize(pattern);

        if ("*".equals(normalizedPattern)) {
            return !normalizedHost.isEmpty();
        }
        if (normalizedHost.isEmpty() || normalizedPattern.isEmpty()) {
            return false;
        }
        if (normalizedPattern.startsWith("*.")) {
            String suffix = normalizedPattern.substring(1);
            return normalizedHost.endsWith(suffix) && normalizedHost.length() > suffix.length();
        }
        if (normalizedPattern.startsWith(".")) {
            String base = normalizedPattern.substring(1);
            return normalizedHost.equals(base)
                    || (normalizedHost.endsWith(normalizedPattern) && normalizedHost.length() > normalizedPattern.length());
        }
        return normalizedHost.equals(normalizedPattern);
    }

    private static String normalize(String value) {
        String normalized = value.trim();
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            return IDN.toASCII(normalized.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return normalized.toLowerCase(Locale.ROOT);
        }
    }
}
