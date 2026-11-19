package io.github.lightjunction.fkclaude;

import android.webkit.WebView;
import android.webkit.WebSettings;
import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
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
    private static volatile UserAgentMetadata strictMetadata;
    private static final java.util.concurrent.atomic.AtomicBoolean metadataVerified = new java.util.concurrent.atomic.AtomicBoolean();

    private static Object metadata(ClassLoader loader, PixelUserAgent.Parts identity) throws Throwable {
        Object brand = XposedHelpers.findClass("androidx.webkit.UserAgentMetadata$BrandVersion$Builder", loader)
                .getConstructor().newInstance();
        XposedHelpers.callMethod(brand, "setBrand", "Chromium");
        XposedHelpers.callMethod(brand, "setMajorVersion", identity.majorVersion());
        XposedHelpers.callMethod(brand, "setFullVersion", identity.fullVersion());
        Object builder = XposedHelpers.findClass("androidx.webkit.UserAgentMetadata$Builder", loader)
                .getConstructor().newInstance();
        XposedHelpers.callMethod(builder, "setBrandVersionList", java.util.List.of(XposedHelpers.callMethod(brand, "build")));
        XposedHelpers.callMethod(builder, "setFullVersion", identity.fullVersion());
        XposedHelpers.callMethod(builder, "setModel", UsPrivacyPolicy.PIXEL_MODEL);
        XposedHelpers.callMethod(builder, "setPlatform", "Android");
        XposedHelpers.callMethod(builder, "setPlatformVersion", identity.platformVersion());
        // Null values fall back to the real device. Empty architecture and default bitness do not.
        XposedHelpers.callMethod(builder, "setArchitecture", "");
        XposedHelpers.callMethod(builder, "setBitness", UserAgentMetadata.BITNESS_DEFAULT);
        XposedHelpers.callMethod(builder, "setMobile", true);
        XposedHelpers.callMethod(builder, "setWow64", false);
        return XposedHelpers.callMethod(builder, "build");
    }

    private static void protectMetadataSetter(Class<?> compat, Object projected) {
        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(compat, "setUserAgentMetadata", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                if (call.args.length != 2) {
                    call.setThrowable(new SecurityException("Web identity setter unavailable"));
                    return;
                }
                call.args[1] = projected;
            }
        });
        if (hooks.isEmpty()) throw new IllegalStateException("Required Web metadata setter unavailable");
    }

    // Called after every guarded user-agent update, including the constructor's first update.
    static void applyMetadata(WebSettings settings) {
        UserAgentMetadata expected = strictMetadata;
        if (expected == null || !WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA))
            throw new IllegalStateException("Web metadata projection unavailable");
        WebSettingsCompat.setUserAgentMetadata(settings, expected);
        UserAgentMetadata actual = WebSettingsCompat.getUserAgentMetadata(settings);
        if (actual == null || !expected.getModel().equals(actual.getModel())
                || !expected.getPlatform().equals(actual.getPlatform())
                || !expected.getPlatformVersion().equals(actual.getPlatformVersion())
                || !expected.getFullVersion().equals(actual.getFullVersion())
                || !"".equals(actual.getArchitecture()) || actual.getBitness() != UserAgentMetadata.BITNESS_DEFAULT
                || !actual.isMobile() || actual.isWow64()
                || actual.getBrandVersionList().size() != 1
                || !"Chromium".equals(actual.getBrandVersionList().get(0).getBrand())
                || !expected.getBrandVersionList().get(0).getMajorVersion().equals(actual.getBrandVersionList().get(0).getMajorVersion())
                || !expected.getFullVersion().equals(actual.getBrandVersionList().get(0).getFullVersion()))
            throw new IllegalStateException("Web metadata readback failed");
        if (metadataVerified.compareAndSet(false, true))
            XposedBridge.log("fkclaude: WebView identity metadata verified: fields=9; no values logged");
    }

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

    static void install(Profile profile, String modulePath, String userAgent, ClassLoader targetLoader) {
        if (profile.flag("block_location")) blockNativeGeolocation();
        if (!Boolean.parseBoolean(profile.get("web_enabled", "true"))) return;
        final boolean strict = profile.flag("privacy_us_only");
        try {
            PixelUserAgent.Parts identity = strict ? PixelUserAgent.parse(userAgent) : null;
            if (strict) {
                Class<?> local = WebSettingsCompat.class;
                strictMetadata = (UserAgentMetadata) metadata(local.getClassLoader(), identity);
                protectMetadataSetter(local, strictMetadata);
                // The host can bundle its own AndroidX classes. Guard that setter using its own types too.
                Class<?> host = XposedHelpers.findClassIfExists("androidx.webkit.WebSettingsCompat", targetLoader);
                if (host != null && host != local && java.util.Arrays.stream(host.getDeclaredMethods())
                        .anyMatch(method -> method.getName().equals("setUserAgentMetadata")))
                    protectMetadataSetter(host, metadata(host.getClassLoader(), identity));
            }
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
            if (strict && (userAgent == null || userAgent.isEmpty()))
                throw new IllegalStateException("Required Web identity unavailable");
            if (userAgent != null) values.put("user_agent", userAgent);
            if (strict) {
                values.put("web_platform_version", identity.platformVersion());
                values.put("web_architecture", "");
                values.put("web_bitness", "");
            }
            final String script = source.replace("__FKCLAUDE_CONFIG__", new JSONObject(values).toString());
            Set<String> origins = new HashSet<>();
            for (String origin : (strict ? "*" : profile.get("web_origins", "*")).split(","))
                if (!origin.isBlank()) origins.add(origin.trim());
            if (origins.isEmpty()) { XposedBridge.log("fkclaude: no WebView origins configured"); return; }
            Set<WebView> configured = Collections.newSetFromMap(new WeakHashMap<>());
            Set<XC_MethodHook.Unhook> constructors = XposedBridge.hookAllConstructors(WebView.class, new XC_MethodHook() {
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
            if (strict && constructors.isEmpty()) throw new IllegalStateException("WebView constructors unavailable");
        } catch (Throwable e) {
            XposedBridge.log("fkclaude: WebView setup unavailable (" + e.getClass().getSimpleName() + ")");
            if (strict) throw new IllegalStateException("Web privacy policy unavailable");
        }
    }
}
