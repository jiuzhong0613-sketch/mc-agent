package dev.mcagent.llm;

import java.net.URI;
import java.util.Locale;

/**
 * Hosted APIs only. Local and self-hosted endpoints are rejected on purpose.
 */
public final class ApiGate {
    private ApiGate() {
    }

    public static void check(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid llm.baseUrl");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("llm.baseUrl must be https");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("llm.baseUrl has no host");
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.equals("localhost") || h.endsWith(".local") || h.equals("::1") || h.equals("0.0.0.0")
                || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")
                || h.startsWith("169.254.") || isPrivate172(h)) {
            throw new IllegalArgumentException(
                    "local endpoints are not supported; configure a hosted OpenAI-compatible API");
        }
    }

    private static boolean isPrivate172(String host) {
        if (!host.startsWith("172.")) {
            return false;
        }
        String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
