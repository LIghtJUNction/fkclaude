package io.github.lightjunction.fkclaude;

import android.webkit.WebView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.zip.ZipFile;

final class WebEnvironment {
    private static void blockNativeGeolocation() {
        Set<java.lang.reflect.Method> protectedSetters = new HashSet<>();
        // Independent of asset loading and document-start support.
        Set<XC_MethodHook.Unhook> constructors = XposedBridge.hookAllConstructors(WebView.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (call.hasThrowable()) return;
                try {
                    android.webkit.WebSettings settings = ((WebView) call.thisObject).getSettings();
                    java.lang.reflect.Method setter = settings.getClass().getMethod("setGeolocationEnabled", boolean.class);
                    synchronized (protectedSetters) {
                        if (!protectedSetters.contains(setter)) {
                            XposedBridge.hookMethod(setter, new XC_MethodHook() {
                                @Override protected void beforeHookedMethod(MethodHookParam change) {
                                    change.args[0] = false;
                                }
                            });
                            protectedSetters.add(setter);
                        }
                    }
                    settings.setGeolocationEnabled(false);
                    android.webkit.GeolocationPermissions.getInstance().clearAll();
                } catch (Throwable error) {
                    call.setThrowable(new SecurityException("Web location policy unavailable"));
                    XposedBridge.log("fkclaude: native WebView location policy unavailable");
                }
            }
        });
        if (constructors.isEmpty()) throw new IllegalStateException("Native Web location policy unavailable");
    }

    static void install(Profile profile, String modulePath) {
        if (profile.flag("block_location")) blockNativeGeolocation();
        if (!Boolean.parseBoolean(profile.get("web_enabled", "true"))) return;
        final boolean strict = profile.flag("privacy_us_only");
        try {
            String source;
            if (modulePath == null) throw new IllegalStateException("Missing module path");
            try (ZipFile apk = new ZipFile(modulePath)) {
                try (InputStream input = apk.getInputStream(apk.getEntry("assets/environment.js"));
                     ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    source = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            // Never expose device/account IDs or arbitrary environment variables to page JavaScript.
            Map<String, String> values = new HashMap<>();
            for (String key : new String[]{"locale", "timezone", "user_agent", "cpu_count", "memory_total", "screen_width", "screen_height",
                    "screen_density", "latitude", "longitude", "location_accuracy", "block_location", "block_web_fingerprinting",
                    "web_platform", "web_vendor", "web_device_memory", "build.MODEL", "os_release", "native_machine"})
                if (profile.has(key)) values.put(key, profile.get(key));
            final String script = source.replace("__FKCLAUDE_CONFIG__", new JSONObject(values).toString());
            Set<String> origins = new HashSet<>();
            for (String origin : (strict ? "*" : profile.get("web_origins", "*")).split(","))
                if (!origin.isBlank()) origins.add(origin.trim());
            if (origins.isEmpty()) { XposedBridge.log("fkclaude: no WebView origins configured"); return; }
            Set<WebView> configured = Collections.newSetFromMap(new WeakHashMap<>());
            XposedBridge.hookAllConstructors(WebView.class, new XC_MethodHook() {
                private boolean reported;
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    WebView view = (WebView) p.thisObject;
                    // WebView must be constructed on its UI thread. Do not post a late injection that could miss page scripts.
                    synchronized (configured) { if (configured.contains(view)) return; }
                    try {
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            WebViewCompat.addDocumentStartJavaScript(view, script, origins);
                            synchronized (configured) { configured.add(view); }
                        } else {
                            if (!reported) { reported = true; XposedBridge.log("fkclaude: WebView document-start unsupported; no late fallback"); }
                            if (strict) p.setThrowable(new SecurityException("Web privacy policy unavailable"));
                            return;
                        }
                    } catch (Throwable e) {
                        if (!reported) { reported = true; XposedBridge.log("fkclaude: WebView script unavailable (" + e.getClass().getSimpleName() + ")"); }
                        if (strict) p.setThrowable(new SecurityException("Web privacy policy unavailable"));
                    }
                }
            });
        } catch (Throwable e) {
            XposedBridge.log("fkclaude: WebView setup unavailable (" + e.getClass().getSimpleName() + ")");
            if (strict) throw new IllegalStateException("Web privacy policy unavailable");
        }
    }
}
