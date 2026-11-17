package io.github.lightjunction.fkclaude;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Metadata-only boundary. Request/response bodies and header values are never accepted. */
public final class AuditSanitizer {
    private static final Pattern UUID = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern HEX_ID = Pattern.compile("(?i)\\b[0-9a-f]{16,}\\b");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_{|}~-]+@[A-Za-z0-9.-]+");
    private static final Pattern TOKEN = Pattern.compile("(?i)(?:bearer\\s+\\S+|(?:sk-ant-|sk-|eyJ)[A-Za-z0-9_.-]+)");
    private static final Pattern LONG_TOKEN = Pattern.compile("[A-Za-z0-9_+/=-]{32,}");
    private static final Set<String> PATH_WORDS = Set.of(
            "api", "v1", "v2", "v3", "v4", "accounts", "account", "organizations", "organization",
            "users", "user", "me", "mobile_events", "events", "event", "event_logging", "batch",
            "analytics", "telemetry", "track", "track_open", "experiences", "notification", "notifications",
            "push", "register", "unregister", "installations", "firebaseinstallations", "projects",
            "auth", "session", "sessions", "session_reattest", "device_key", "attestation", "attest",
            "login", "logout", "signin", "sign_in", "sign_up", "signup", "trusted_devices", "devices",
            "chat_conversations", "conversations", "conversation", "completion", "completions", "messages",
            "message", "append_message", "retry", "retry_completion", "stop", "title", "sync", "settings",
            "profile", "preferences", "bootstrap", "config", "configuration", "feature_flags",
            "attachments", "files", "file", "upload", "uploads", "download", "search", "list",
            "envelope", "store", "sentry", "rum", "logs", "log", "log_event", "metrics", "traces",
            "spans", "intake", "collect", "g", "mp", "batch_log", "crash", "reports", "report",
            "code", "status", "health", "ping", "token", "tokens", "refresh", "credits", "billing",
            "subscriptions", "models", "model", "voice", "realtime", "feedback", "flags",
            "initialize", "check", "identify", "device", "key", "keys", "challenge", "verify");
    private static final Set<String> IDENTIFIER_PARENTS = Set.of(
            "accounts", "organizations", "users", "projects", "chat_conversations", "conversations",
            "sessions", "attachments", "files", "devices", "trusted_devices");
    private static final Set<String> QUERY_KEYS = Set.of(
            "locale", "lang", "language", "format", "version", "platform", "device_id", "account_id",
            "organization_id", "cursor", "limit", "offset", "page", "token", "access_token", "api_key",
            "key", "code", "state", "redirect_uri", "sentry_key", "sentry_version", "sentry_client",
            "ddsource", "ddtags", "dd-api-key", "batch_time", "client", "sdk_version", "session_id",
            "q", "query", "search", "include", "exclude", "type", "timestamp", "t");
    private static final Set<String> NUMERIC = Set.of(
            "status_code", "port", "bytes", "count", "hook_count", "duration_ms", "content_length",
            "queue_dropped", "disk_errors", "retained", "history_count", "max_events", "max_file_bytes",
            "file_count", "pid", "thread_id", "sequence", "installed", "failed", "requested",
            "fd", "return_value", "errno", "native_elapsed_ms", "native_thread_id", "batch_count", "view_id");
    private static final Set<String> IDENTIFIERS = Set.of(
            "transport", "api", "error_type", "coverage", "reason", "operation", "body_type",
            "caller", "phase", "version", "process", "layer", "field", "mime_type", "event_type",
            "result_type", "permission", "sdk", "stack", "direction", "upload", "classification",
            "observation", "value_policy", "category", "enabled", "state", "address_family");
    // These are exact, source-defined diagnostics, never arbitrary caller-supplied tokens.
    // Keep the exception scoped to its detail key; the generic redactor remains unchanged.
    private static final Map<String, Set<String>> FIXED_METADATA = Map.of(
            "observation", Set.of("opaque_bytes_no_payload_or_source_address",
                    "per_message_bytes_accepted_by_socket"),
            "coverage", Set.of("request_construction_not_delivery",
                    "document_start_or_message_listener_unsupported",
                    "page_reported_metadata;workers_native_and_server_processing_not_observed"),
            "reason", Set.of("native_queue_contention_capacity_or_batch_limit"));
    private AuditSanitizer() {}

    public static String text(String value) {
        if (value == null) return "";
        String safe = value.length() > 2048 ? value.substring(0, 2048) : value;
        safe = safe.replaceAll("[\\p{Cntrl}]", " ");
        safe = TOKEN.matcher(safe).replaceAll("[redacted]");
        safe = EMAIL.matcher(safe).replaceAll("[email]");
        safe = UUID.matcher(safe).replaceAll("[id]");
        safe = HEX_ID.matcher(safe).replaceAll("[id]");
        safe = LONG_TOKEN.matcher(safe).replaceAll("[token]");
        safe = safe.replaceAll("(?<![A-Za-z0-9])[0-9]{7,}(?![A-Za-z0-9])", "[number]");
        return safe.length() > 240 ? safe.substring(0, 240) + "\u2026" : safe;
    }

    /** Keeps the destination and a route template; discards userinfo, values and fragments. */
    public static String url(String raw) {
        if (raw == null || raw.length() > 8192) return "[invalid-url]";
        try {
            URI uri = new URI(raw);
            String scheme = uri.getScheme();
            if (scheme == null || !Set.of("http", "https", "ws", "wss").contains(scheme.toLowerCase(Locale.ROOT)))
                return "[non-http-url]";
            String host = host(uri.getHost());
            if (host.isEmpty() || host.equals("[invalid-host]")) return "[invalid-url]";
            StringBuilder out = new StringBuilder(scheme.toLowerCase(Locale.ROOT)).append("://").append(host);
            if (uri.getPort() >= 0) out.append(':').append(uri.getPort());
            out.append(path(uri.getRawPath()));
            String query = uri.getRawQuery();
            if (query != null && !query.isEmpty()) {
                out.append('?');
                int count = 0;
                for (String part : query.split("&", 33)) {
                    if (count == 16) { out.append("&:more"); break; }
                    if (count++ > 0) out.append('&');
                    String key = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
                    out.append(QUERY_KEYS.contains(key) ? key : ":key").append("=[redacted]");
                }
            }
            return out.toString();
        } catch (RuntimeException | java.net.URISyntaxException ignored) { return "[invalid-url]"; }
    }

    public static String host(String raw) {
        if (raw == null || raw.length() > 253) return "[invalid-host]";
        String h = raw.toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.contains(":") || h.matches("[0-9.]+")) return "[ip]";
        if (!h.matches("[a-z0-9.-]+") || h.isEmpty()) return "[invalid-host]";
        StringBuilder out = new StringBuilder();
        for (String label : h.split("\\.", -1)) {
            if (out.length() > 0) out.append('.');
            if (label.isEmpty()) return "[invalid-host]";
            out.append(label.length() > 31 || UUID.matcher(label).find() || HEX_ID.matcher(label).find()
                    || label.matches("[0-9]+") ? ":id" : label);
        }
        return out.toString();
    }

    public static String path(String raw) {
        if (raw == null || raw.isEmpty()) return "/";
        if (raw.length() > 4096) return "/:oversize";
        int query = raw.indexOf('?'), fragment = raw.indexOf('#');
        int end = query < 0 ? raw.length() : query;
        if (fragment >= 0) end = Math.min(end, fragment);
        StringBuilder out = new StringBuilder();
        String previous = "";
        int count = 0;
        for (String part : raw.substring(0, end).split("/", -1)) {
            if (part.isEmpty()) continue;
            if (count++ == 18) { out.append("/:more"); break; }
            String lower = part.toLowerCase(Locale.ROOT);
            boolean dynamic = IDENTIFIER_PARENTS.contains(previous);
            out.append('/').append(dynamic ? ":id" : PATH_WORDS.contains(lower) ? lower : ":segment");
            previous = dynamic ? "" : lower;
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    /** Only API/schema metadata is accepted. Unrecognised keys are omitted, not stringified. */
    public static Map<String, String> details(Map<String, String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        int budget = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (out.size() >= 24 || budget >= 2300) break;
            String key = entry.getKey(), value = entry.getValue();
            if (key == null || value == null) continue;
            String safe;
            if (key.equals("url")) safe = url(value);
            else if (key.equals("path")) safe = path(value);
            else if (key.equals("host")) safe = host(value);
            else if (key.equals("origin")) safe = url(value);
            else if (key.equals("peer_address")) safe = value.length() <= 45 && value.matches("[0-9a-fA-F:.]+") ? value.toLowerCase(Locale.ROOT) : "[ip]";
            else if (key.equals("main_frame")) safe = value.equals("true") || value.equals("false") ? value : "unknown";
            else if (key.equals("document_id")) safe = value.matches("d[0-9a-f]{32}") ? value : "[id]";
            else if (key.equals("version")) safe = value.matches("[0-9.]{1,32}") ? value : identifier(value);
            else if (key.equals("method")) safe = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD",
                    "OPTIONS", "CONNECT", "TRACE", "WS", "FRAME").contains(value) ? value : "UNKNOWN";
            else if (key.equals("fields")) safe = fields(value);
            else if (key.equals("request_id")) safe = value.matches("(?:r|w|local-)?[0-9]{1,18}") ? value : "[id]";
            else if (key.equals("content_type")) {
                String media = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                safe = media.matches("[a-z0-9.+-]{1,40}/[a-z0-9.+-]{1,60}") ? media : "[type]";
            } else if (NUMERIC.contains(key)) safe = value.matches("-?[0-9]{1,18}") ? value : "[number]";
            else if (IDENTIFIERS.contains(key)) {
                Set<String> fixed = FIXED_METADATA.get(key);
                safe = fixed != null && fixed.contains(value) ? value : identifier(value);
            }
            else continue;
            if (safe.length() > 640) safe = safe.substring(0, 640) + "\u2026";
            budget += key.length() + safe.length();
            out.put(key, safe);
        }
        return Collections.unmodifiableMap(out);
    }

    static String identifier(String value) {
        String safe = text(value);
        return safe.matches("[A-Za-z0-9_$#.:/<>\\[\\](), =;-]{0,240}") ? safe : "[metadata]";
    }

    static String fields(String value) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String part : value.split("[,\\s]+", 65)) {
            if (part.isEmpty()) continue;
            if (count++ == 48) { out.append(",\u2026"); break; }
            if (out.length() > 0) out.append(',');
            out.append(part.matches("[A-Za-z_][A-Za-z0-9_.$]{0,80}") ? text(part) : "[field]");
        }
        return out.toString();
    }
}
