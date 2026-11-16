package io.github.lightjunction.fkclaude;

import android.webkit.WebView;
import android.net.Uri;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.zip.ZipFile;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** One-way metadata reporting. Page messages cannot read logs, control settings or execute native code. */
final class AuditWeb {
    private static final Set<String> ACTIONS = Set.of("installed", "request", "response", "failure", "beacon_queued",
            "device_read", "sensitive_api", "dropped");
    private static final Set<String> FIELDS = Set.of("userAgent","platform","vendor","language","languages",
            "hardwareConcurrency","deviceMemory","maxTouchPoints","canvas.toDataURL","canvas.toBlob","canvas.getImageData",
            "geolocation.getCurrentPosition","geolocation.watchPosition","clipboard.read","clipboard.readText",
            "mediaDevices.enumerateDevices","mediaDevices.getUserMedia");
    private static final Set<String> TRANSPORTS = Set.of("fetch","xhr","beacon","websocket");
    private static final Set<WebView> CONFIGURED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final AtomicLong VIEW_IDS = new AtomicLong();
    private static long windowStart;
    private static int received, dropped;

    static void install(String modulePath) {
        try {
            final String source;
            try (ZipFile apk = new ZipFile(modulePath)) {
                java.util.zip.ZipEntry entry = apk.getEntry("assets/audit.js");
                if (entry == null) throw new java.io.FileNotFoundException("Audit script missing");
                try (java.io.InputStream in = apk.getInputStream(entry);
                     java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (bytes.size() > 32768 - count)
                            throw new java.io.IOException("Audit script exceeds size limit");
                        bytes.write(buffer, 0, count);
                    }
                    source = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            XposedBridge.hookAllConstructors(WebView.class, new XC_MethodHook(10000) {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    WebView view = (WebView) p.thisObject;
                    synchronized (CONFIGURED) { if (CONFIGURED.contains(view)) return; }
                    try {
                        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                                || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                            AuditLog.record("system","webview_coverage","AuditWeb","unavailable",
                                    Map.of("coverage","document_start_or_message_listener_unsupported"));
                            return;
                        }
                        long viewId = VIEW_IDS.incrementAndGet();
                        WebViewCompat.addWebMessageListener(view, "fkclaudeAuditBridge", Set.of("*"),
                                (webView,message,origin,mainFrame,reply) -> {
                                    try { accept(message.getData(), origin, mainFrame, viewId); }
                                    catch (RuntimeException ignored) { /* Non-text or invalid page message. */ }
                                });
                        WebViewCompat.addDocumentStartJavaScript(view, source, Set.of("*"));
                        synchronized (CONFIGURED) { CONFIGURED.add(view); }
                    } catch (Throwable error) {
                        AuditLog.record("system","webview_coverage","AuditWeb","failed",
                                Map.of("error_type",error.getClass().getSimpleName()));
                    }
                }
            });
        } catch (Throwable error) {
            AuditLog.record("system","webview_coverage","AuditWeb","failed",
                    Map.of("error_type",error.getClass().getSimpleName()));
        }
    }

    private static void accept(String text, Uri sourceOrigin, boolean mainFrame, long viewId) {
        if (!AuditLog.isEnabled() || text == null || text.length() > 8192) return;
        synchronized (AuditWeb.class) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - windowStart > 1000) {
                if (dropped > 0) AuditLog.record("system","webview_rate_limit","AuditWeb","dropped",
                        Map.of("count",Integer.toString(dropped)));
                windowStart=now; received=0; dropped=0;
            }
            if (++received > 240) { dropped++; return; }
        }
        try {
            JSONObject event = new JSONObject(text);
            String action=event.optString("action");
            if (!ACTIONS.contains(action)) return;
            String documentId = event.optString("document_id");
            if (!documentId.matches("d[0-9a-f]{32}")) return;
            Map<String,String> details = new LinkedHashMap<>();
            details.put("document_id", documentId);
            details.put("view_id", Long.toString(viewId));
            details.put("main_frame", Boolean.toString(mainFrame));
            if (sourceOrigin != null) details.put("origin", sourceOrigin.toString());
            // The bridge is writable by page JS. This provenance is kept on every record.
            details.put("coverage","page_reported_metadata;workers_native_and_server_processing_not_observed");
            details.put("layer","webview");
            String category="webview", outcome="observed";
            if (action.equals("request") || action.equals("response") || action.equals("failure") || action.equals("beacon_queued")) {
                String raw=event.optString("url"), method=event.optString("method","UNKNOWN");
                java.net.URI uri;
                try { uri=java.net.URI.create(raw); } catch (IllegalArgumentException invalid) { return; }
                if (!Set.of("http","https","ws","wss").contains(uri.getScheme() == null ? "" : uri.getScheme())) return;
                details.put("url",raw);
                if (!method.matches("[A-Za-z]{1,16}")) method="UNKNOWN";
                details.put("method",method);
                String phase=event.optString("phase");
                if (Set.of("attempt","accepted","rejected").contains(phase)) details.put("phase",phase);
                String transport=event.optString("transport");
                details.put("transport",TRANSPORTS.contains(transport)?transport:"unknown");
                String id=event.optString("id");
                if (id.matches("w[0-9]{1,12}")) details.put("request_id",id);
                int status=event.optInt("status",-1);
                if (status>=0 && status<=599) details.put("status_code",Integer.toString(status));
                long bytes=event.optLong("bytes",-1);
                if (bytes>=0 && bytes<=1_000_000_000L) details.put("bytes",Long.toString(bytes));
                category=AuditClassifier.category(method,uri.getHost(),uri.getPath());
                outcome=action.equals("request")?"attempted":action.equals("response")?"response_received"
                        :action.equals("failure")?"failed":"queue_result";
            } else if (action.equals("device_read") || action.equals("sensitive_api")) {
                String field=event.optString("field");
                if (!FIELDS.contains(field)) return;
                details.put("field",field);
                category=field.startsWith("geolocation")?"location":field.startsWith("clipboard")?"clipboard"
                        :field.startsWith("mediaDevices")?"microphone":"device";
            } else if (action.equals("dropped")) {
                details.put("count",Integer.toString(Math.max(0,Math.min(1_000_000,event.optInt("count")))));
                category="system"; outcome="dropped";
            }
            AuditLog.record(category,"web_"+action,"WebView.document_start",outcome,details);
        } catch (Exception ignored) { /* Invalid or hostile page messages never affect the host. */ }
    }
}
