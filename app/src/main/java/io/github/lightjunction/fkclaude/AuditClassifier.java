package io.github.lightjunction.fkclaude;

import java.util.Locale;
import java.util.Set;

/** Endpoint labels are routing hints. They do not claim payload contents or server retention. */
public final class AuditClassifier {
    private AuditClassifier() {}
    public static boolean isConversation(String host, String path) {
        String h = normalizeHost(host), p = normalizePath(path);
        if (!domain(h, "claude.ai") && !domain(h, "anthropic.com")) return false;
        return segment(p, "chat_conversations") || segment(p, "conversations")
                || segment(p, "completion") || segment(p, "completions") || segment(p, "messages")
                || segment(p, "append_message") || segment(p, "retry_completion")
                || p.matches(".*/code/sessions/[^/]+/events(?:/.*)?");
    }
    public static String category(String method, String host, String path) {
        String h = normalizeHost(host), p = normalizePath(path);
        if (isConversation(h, p)) return "conversation";
        if (domain(h, "siftscience.com") || domain(h, "sentry.io") || domain(h, "datadoghq.com") || domain(h, "datadoghq.eu")
                || domain(h, "app-measurement.com") || h.equals("firebaselogging.googleapis.com")
                || h.equals("firebaselogging-pa.googleapis.com")
                || h.matches("(?:[a-z0-9-]+\\.)*browser-intake-(?:[a-z0-9-]+-)?datadoghq\\.(?:com|eu)")
                || h.equals("browser-intake-ddog-gov.com") || h.equals("browser-intake-us2-ddog-gov.com"))
            return "telemetry";
        if (domain(h, "claude.ai") || domain(h, "anthropic.com")) {
            if (segment(p, "mobile_events") || p.contains("/event_logging/v2/batch")
                    || p.endsWith("/notification/push/track_open") || p.endsWith("/experiences/track"))
                return "telemetry";
            if (segment(p, "session_reattest") || segment(p, "attestation") || segment(p, "attest")
                    || segment(p, "trusted_devices") || segment(p, "device_key"))
                return "integrity";
        }
        return "network";
    }
    public static boolean isNonConversationNetwork(String category) {
        return Set.of("network", "telemetry", "integrity", "webview").contains(category);
    }
    private static String normalizeHost(String value) {
        String h = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }
    private static String normalizePath(String value) {
        if (value == null) return "";
        String p = value.toLowerCase(Locale.ROOT);
        int query = p.indexOf('?');
        return query < 0 ? p : p.substring(0, query);
    }
    private static boolean domain(String host, String suffix) {
        return host.equals(suffix) || host.endsWith("." + suffix);
    }
    private static boolean segment(String path, String value) { return (path + "/").contains("/" + value + "/"); }
}
