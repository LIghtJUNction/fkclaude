package io.github.lightjunction.fkclaude;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Member;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Observers never replace arguments, results, exceptions, requests or response bodies. */
final class AuditHooks {
    private static boolean installed;
    private static final ThreadLocal<Boolean> INTERNAL = ThreadLocal.withInitial(() -> false);
    private static final AtomicLong IDS = new AtomicLong();
    private static final Set<String> REPORTED = Collections.synchronizedSet(new HashSet<>());
    private static final Set<Class<?>> ACTIVITY_TYPES = Collections.synchronizedSet(new HashSet<>());
    private static int available, missing;
    @FunctionalInterface private interface Inspector { Observation inspect(XC_MethodHook.MethodHookParam p) throws Throwable; }
    private record Observation(String category, String action, String source, Map<String,String> details,
                               long started, boolean http) {}

    static synchronized void install(Context context, ClassLoader loader, long version, String modulePath) {
        if (installed) return;
        try {
            AuditLog.init(context);
            installed = true;
            lifecycle(loader);
            platform(loader);
            network(loader);
            if (version == Profile.TARGET_VERSION) {
                target(loader);
            } else {
                AuditLog.record("system", "target_adapter", "AuditHooks", "unsupported",
                        Map.of("version", Long.toString(version), "coverage", "platform_only"));
            }
            AuditWeb.install(modulePath);
            AuditLog.record("system", "coverage", "AuditHooks", "installed",
                    Map.of("hook_count", Integer.toString(available), "count", Integer.toString(missing),
                            "version", Long.toString(version),
                            "coverage", "java_api,okhttp,urlconnection,dns,socket,webview;native_status_separate"));
            XposedBridge.log("fkclaude: audit installed hooks=" + available + " unavailable=" + missing);
        } catch (Throwable error) {
            // Audit failures must not terminate or weaken the existing environment / IP policy.
            XposedBridge.log("fkclaude: audit unavailable (" + error.getClass().getSimpleName() + ")");
        }
    }

    private static void lifecycle(ClassLoader loader) {
        hook("android.app.Instrumentation", loader, "callActivityOnResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable() && p.args.length > 0 && p.args[0] instanceof Activity activity) {
                    try {
                        activityResults(activity);
                        AuditPanel.attach(activity);
                        AuditLog.record("system", "activity_resumed", "Instrumentation", "observed",
                                Map.of("field", activity.getClass().getName()));
                    } catch (Throwable error) { failed("activity_panel", error); }
                }
            }
        });
        hook("android.app.Instrumentation", loader, "callActivityOnPause", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && p.args[0] instanceof Activity activity) {
                    try { AuditPanel.pause(activity); } catch (Throwable error) { failed("activity_pause", error); }
                }
            }
        });
        hook("android.app.Instrumentation", loader, "callActivityOnDestroy", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && p.args[0] instanceof Activity activity) {
                    try { AuditPanel.detach(activity); } catch (Throwable error) { failed("activity_detach", error); }
                }
            }
        });
        hook("android.app.Instrumentation", loader, "callActivityOnNewIntent", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length > 1 && p.args[0] instanceof Activity activity && p.args[1] instanceof Intent intent
                        && intent.getBooleanExtra("fkclaude.openAudit", false)) {
                    try { activity.setIntent(intent); AuditPanel.attach(activity); }
                    catch (Throwable error) { failed("activity_intent", error); }
                }
            }
        });
    }

    private static void activityResults(Activity activity) {
        for (Class<?> type = activity.getClass(); type != null && Activity.class.isAssignableFrom(type); type = type.getSuperclass()) {
            if (!ACTIVITY_TYPES.add(type)) continue;
            XposedBridge.hookAllMethods(type, "onActivityResult", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length == 3 && p.args[0] instanceof Integer request && p.args[1] instanceof Integer result) {
                        try { AuditPanel.onActivityResult((Activity) p.thisObject, request, result, (Intent) p.args[2]); }
                        catch (Throwable error) { failed("activity_result", error); }
                    }
                }
            });
        }
    }

    private static boolean active() {
        return !INTERNAL.get() && !AuditLog.isInternal() && AuditLog.isEnabled()
                && !Thread.currentThread().getName().startsWith("fkclaude-");
    }

    private static Observation observation(String category, String action, XC_MethodHook.MethodHookParam p,
                                            Map<String,String> details, boolean http) {
        Map<String,String> fields = new LinkedHashMap<>(details);
        fields.put("request_id", "r" + IDS.incrementAndGet());
        String caller = caller();
        if (caller.startsWith("io.github.lightjunction.fkclaude.EnvironmentHook")
                || caller.startsWith("io.github.lightjunction.fkclaude.Audit")) return null;
        fields.put("caller", caller);
        return new Observation(category, action, signature(p.method), fields, SystemClock.elapsedRealtime(), http);
    }

    private static String caller() {
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : frames) {
            String name = frame.getClassName();
            if (name.startsWith("io.github.lightjunction.fkclaude.") && !name.startsWith("io.github.lightjunction.fkclaude.AuditHooks"))
                return name + "#" + frame.getMethodName();
        }
        for (StackTraceElement frame : frames) {
            String cls = frame.getClassName();
            if (cls.startsWith("java.") || cls.startsWith("javax.") || cls.startsWith("android.")
                    || cls.startsWith("dalvik.") || cls.startsWith("sun.") || cls.startsWith("de.robv.")
                    || cls.startsWith("org.lsposed.") || cls.startsWith("LSPHooker_")
                    || cls.startsWith("io.github.lightjunction.fkclaude.AuditHooks")) continue;
            return cls + "#" + frame.getMethodName();
        }
        return "unknown";
    }

    private static void observe(String cls, ClassLoader loader, String method, Inspector inspector) {
        String key = "fkclaude.audit." + cls + "#" + method;
        hook(cls, loader, method, new XC_MethodHook(10000) {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!active()) return;
                INTERNAL.set(true);
                try {
                    Observation event = inspector.inspect(p);
                    if (event == null) return;
                    p.setObjectExtra(key, event);
                    AuditLog.record(event.category, event.action, event.source,
                            event.http ? "attempted" : "called", event.details);
                } catch (Throwable error) { failed(key, error); }
                finally { INTERNAL.remove(); }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object stored = p.getObjectExtra(key);
                if (!(stored instanceof Observation event)) return;
                INTERNAL.set(true);
                try {
                    Map<String,String> fields = new LinkedHashMap<>(event.details);
                    fields.put("duration_ms", Long.toString(SystemClock.elapsedRealtime() - event.started));
                    String outcome = p.hasThrowable() ? "failed" : "returned";
                    if (p.hasThrowable()) fields.put("error_type", p.getThrowable().getClass().getSimpleName());
                    else if (event.http && p.getResult() != null) {
                        if ("ko10".equals(p.thisObject.getClass().getName())) {
                            fields.put("status_code", Integer.toString(XposedHelpers.getIntField(p.getResult(), "d")));
                            outcome = "response_received";
                        } else if (p.method.getName().equals("getResponseCode") && p.getResult() instanceof Integer code) {
                            fields.put("status_code", code.toString()); outcome = "response_received";
                        }
                    }
                    AuditLog.record(event.category, event.action, event.source, outcome, fields);
                } catch (Throwable error) { failed(key, error); }
                finally { INTERNAL.remove(); }
            }
        });
    }

    private static void simple(String cls, ClassLoader loader, String category, String... methods) {
        for (String method : methods) observe(cls, loader, method,
                p -> observation(category, "api_access", p, Map.of("api", cls + "#" + method), false));
    }

    private static void platform(ClassLoader loader) {
        for (String method : new String[]{"getString", "getStringForUser"}) {
            observe("android.provider.Settings$Secure", loader, method, p -> p.args.length > 1 && "android_id".equals(p.args[1])
                    ? observation("identifier", "identifier_read", p, Map.of("field", "android_id"), false) : null);
        }
        simple("android.os.Build", loader, "identifier", "getSerial");
        simple("android.telephony.TelephonyManager", loader, "identifier", "getDeviceId", "getImei", "getMeid",
                "getSubscriberId", "getSimSerialNumber", "getLine1Number");
        simple("android.telephony.TelephonyManager", loader, "device", "getNetworkOperatorName", "getSimOperatorName",
                "getNetworkOperator", "getSimOperator", "getSimCountryIso", "getNetworkCountryIso");
        simple("android.net.wifi.WifiInfo", loader, "device", "getSSID", "getBSSID", "getMacAddress", "getIpAddress");
        simple("android.net.wifi.WifiManager", loader, "device", "getScanResults");
        simple("java.net.NetworkInterface", loader, "device", "getHardwareAddress", "getInetAddresses", "getInterfaceAddresses");
        simple("android.app.ApplicationPackageManager", loader, "packages", "getInstalledPackages", "getInstalledApplications",
                "getPackageInfo", "getApplicationInfo", "queryIntentActivities");
        simple("android.content.ClipboardManager", loader, "clipboard", "getPrimaryClip", "getPrimaryClipDescription", "hasPrimaryClip");
        simple("android.location.LocationManager", loader, "location", "getLastKnownLocation", "getCurrentLocation",
                "requestLocationUpdates", "requestSingleUpdate");
        simple("android.location.Location", loader, "location", "getLatitude", "getLongitude", "getAltitude");
        simple("android.hardware.SensorManager", loader, "sensor", "getSensorList", "getDynamicSensorList", "getDefaultSensor", "registerListener");
        simple("android.media.AudioRecord", loader, "microphone", "startRecording");
        simple("android.media.MediaRecorder", loader, "microphone", "start");
        simple("android.hardware.camera2.CameraManager", loader, "camera", "openCamera");
        simple("android.hardware.Camera", loader, "camera", "open");
        simple("android.os.BatteryManager", loader, "device", "getIntProperty", "isCharging");
        simple("android.app.ActivityManager", loader, "device", "getMemoryInfo");
        simple("android.os.StatFs", loader, "device", "getTotalBytes", "getAvailableBytes", "getFreeBytes");
        for (String method : new String[]{"get", "getInt", "getLong", "getBoolean"}) {
            observe("android.os.SystemProperties", loader, method, p -> {
                if (p.args.length == 0 || !(p.args[0] instanceof String name)
                        || !(name.startsWith("ro.") || name.startsWith("gsm.") || name.startsWith("persist.sys."))) return null;
                return observation("device", "system_property_read", p, Map.of("field", name), false);
            });
        }
        observe("android.content.ContentResolver", loader, "query", p -> {
            if (p.args.length == 0 || !(p.args[0] instanceof Uri uri)) return null;
            String authority = uri.getAuthority();
            String category = Set.of("com.android.contacts", "contacts").contains(authority == null ? "" : authority) ? "contacts"
                    : Set.of("com.android.calendar", "calendar").contains(authority == null ? "" : authority) ? "calendar" : null;
            return category == null ? null : observation(category, "provider_query", p, Map.of("field", category), false);
        });
        observe("java.lang.Runtime", loader, "exec", p -> {
            if (p.args.length == 0) return null;
            String first = p.args[0] instanceof String[] array && array.length > 0 ? array[0]
                    : p.args[0] instanceof String text ? text.split("\\s+", 2)[0] : "";
            return commandObservation(first, p);
        });
        observe("java.lang.ProcessBuilder", loader, "start",
                p -> commandObservation(((ProcessBuilder) p.thisObject).command().isEmpty() ? ""
                        : ((ProcessBuilder) p.thisObject).command().get(0), p));
        for (String method : new String[]{"exists", "canExecute", "canWrite"}) observe("java.io.File", loader, method, p -> {
            String path = ((java.io.File) p.thisObject).getPath();
            if (!(path.startsWith("/system/") || path.startsWith("/sbin/") || path.startsWith("/vendor/bin/")
                    || path.equals("/proc/mounts") || path.equals("/proc/self/maps"))) return null;
            return observation("integrity", "environment_file_probe", p, Map.of("field", "system_path_metadata"), false);
        });
    }

    private static Observation commandObservation(String first, XC_MethodHook.MethodHookParam p) {
        String name = first.substring(first.lastIndexOf('/') + 1);
        return Set.of("getprop", "mount", "which", "su").contains(name)
                ? observation("integrity", "environment_command", p, Map.of("operation", name), false) : null;
    }

    private static void network(ClassLoader loader) {
        for (String method : new String[]{"getByName", "getAllByName"}) observe("java.net.InetAddress", loader, method, p -> {
            if (p.args.length == 0 || !(p.args[0] instanceof String host)) return null;
            return observation("network", "dns_lookup", p,
                    Map.of("host", host, "transport", "dns", "coverage", "resolution_only"), false);
        });
        observe("java.net.Socket", loader, "connect", p -> {
            if (p.args.length == 0 || !(p.args[0] instanceof InetSocketAddress address)) return null;
            return observation("network", "socket_connect", p,
                    Map.of("host", address.getHostString(), "port", Integer.toString(address.getPort()),
                            "transport", "tcp", "coverage", "payload_opaque"), false);
        });
        observe("java.net.DatagramSocket", loader, "send", p -> {
            if (p.args.length == 0 || !(p.args[0] instanceof java.net.DatagramPacket packet) || packet.getAddress() == null) return null;
            return observation("network", "datagram_send", p,
                    Map.of("host", packet.getAddress().getHostAddress(), "port", Integer.toString(packet.getPort()),
                            "bytes", Integer.toString(packet.getLength()), "transport", "udp", "coverage", "payload_opaque"), false);
        });
        observe("java.net.URL", loader, "openConnection", p -> {
            URL url = (URL) p.thisObject;
            if (!url.getProtocol().startsWith("http")) return null;
            return observation(AuditClassifier.category("UNKNOWN", url.getHost(), url.getPath()), "connection_created", p,
                    Map.of("url", url.toString(), "transport", "urlconnection", "coverage", "connection_object_only"), false);
        });
        for (String cls : new String[]{"com.android.okhttp.internal.huc.HttpURLConnectionImpl",
                "com.android.okhttp.internal.huc.HttpsURLConnectionImpl", "sun.net.www.protocol.http.HttpURLConnection"}) {
            for (String method : new String[]{"connect", "getInputStream", "getOutputStream", "getResponseCode"}) observe(cls, loader, method, p -> {
                if (!(p.thisObject instanceof URLConnection connection)) return null;
                URL url = connection.getURL();
                String verb = connection instanceof java.net.HttpURLConnection http ? http.getRequestMethod() : "UNKNOWN";
                return observation(AuditClassifier.category(verb, url.getHost(), url.getPath()), "http_" + method, p,
                        Map.of("url", url.toString(), "method", verb, "transport", "urlconnection",
                                "coverage", "metadata_only_body_not_read"), true);
            });
        }
        for (String method : new String[]{"loadUrl", "postUrl"}) observe("android.webkit.WebView", loader, method, p -> {
            if (p.args.length == 0 || !(p.args[0] instanceof String url) || !url.startsWith("http")) return null;
            Map<String,String> data = new LinkedHashMap<>();
            data.put("url", url); data.put("method", method.equals("postUrl") ? "POST" : "GET");
            data.put("transport", "webview"); data.put("coverage", "navigation_requested");
            if (p.args.length > 1 && p.args[1] instanceof byte[] body) data.put("bytes", Integer.toString(body.length));
            return observation("webview", "navigation", p, data, false);
        });
    }

    private static void target(ClassLoader loader) {
        observe("ko10", loader, "f", p -> {
            Object request = XposedHelpers.getObjectField(p.thisObject, "b");
            Object url = XposedHelpers.getObjectField(request, "a");
            String host = (String) XposedHelpers.getObjectField(url, "d");
            String path = (String) XposedHelpers.callMethod(url, "b");
            String method = (String) XposedHelpers.getObjectField(request, "b");
            Object body = XposedHelpers.getObjectField(request, "d");
            Map<String,String> fields = new LinkedHashMap<>();
            fields.put("host", host); fields.put("path", path); fields.put("method", method);
            fields.put("body_type", body == null ? "none" : body.getClass().getName());
            fields.put("transport", "okhttp"); fields.put("coverage", "metadata_only_body_not_read");
            return observation(AuditClassifier.category(method, host, path), "http_request", p, fields, true);
        });
        observe("v6g", loader, "invokeSuspend", p -> {
            Object receiver = XposedHelpers.getObjectField(p.thisObject, "r");
            return receiver != null && receiver.getClass().getName().equals("h2a0")
                    ? observation("telemetry", "sift_collect", p, Map.of("phase", "collection"), false) : null;
        });
        observe("h2a0", loader, "b", p -> observation("telemetry", "sift_queue", p, Map.of("phase", "queue_attempt"), false));
        observe("g2a0", loader, "invokeSuspend", p -> observation("telemetry", "sift_dispatch", p,
                Map.of("phase", "coroutine_entry", "path", "/v3/accounts/:id/mobile_events",
                        "coverage", "dispatch_entry_not_delivery"), false));
        schema(loader, "et0", "sift_device_properties",
                "app_name,app_version,sdk_version,mobile_carrier_name,mobile_iso_country_code,device_manufacturer,device_model,device_system_version,android_id,build_tags,evidence_files_present,evidence_packages_present,evidence_properties,evidence_directories_writable,installed_apps");
        schema(loader, "jo0", "sift_app_state",
                "activity_class_name,sdk_version,battery_level,battery_state,battery_health,plug_state,network_addresses");
        schema(loader, "tdz", "analytics_context", "ant_device_id,primary_language,locale");
        schema(loader, "vdz", "product_analytics_event", "event_name,event_id,event_timestamp,account_uuid,organization_uuid,anonymous_id,client_platform,client_app,properties,context");
        observe("raj", loader, "a", p -> observation("telemetry", "analytics_batch", p,
                Map.of("path", "/event_logging/v2/batch", "phase", "request_factory",
                        "coverage", "request_construction_not_delivery"), false));
        for (String cls : new String[]{"io.sentry.Sentry", "io.sentry.Hub"}) {
            simple(cls, loader, "telemetry", "captureEvent", "captureException", "captureMessage", "captureEnvelope", "captureTransaction");
        }
    }

    private static void schema(ClassLoader loader, String cls, String action, String fields) {
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllConstructors(XposedHelpers.findClass(cls, loader), new XC_MethodHook(10000) {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!active() || p.hasThrowable()) return;
                    INTERNAL.set(true);
                    try {
                        AuditLog.record("telemetry", action, cls + "#<init>", "constructed",
                                Map.of("fields", fields, "coverage", "schema_only_values_not_read"));
                    } finally { INTERNAL.remove(); }
                }
            });
            available += hooks.size();
            if (hooks.isEmpty()) unavailable(cls + "#<init>");
        } catch (Throwable error) { unavailable(cls + "#<init>"); }
    }

    static void blocked(String category, String action, String source, String reason) {
        AuditLog.record(category, action, source, "blocked", Map.of("reason", reason));
    }

    private static void hook(String cls, ClassLoader loader, String method, XC_MethodHook callback) {
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(XposedHelpers.findClass(cls, loader), method, callback);
            available += hooks.size();
            if (hooks.isEmpty()) unavailable(cls + "#" + method);
        } catch (Throwable error) { unavailable(cls + "#" + method); }
    }
    private static void unavailable(String name) {
        missing++;
        AuditLog.record("system", "hook_coverage", name, "unavailable", Map.of("coverage", "not_observed"));
    }
    private static String signature(Member member) { return member.getDeclaringClass().getName() + "#" + member.getName(); }
    private static void failed(String source, Throwable error) {
        if (REPORTED.add(source)) AuditLog.record("system", "observer_error", source, "failed",
                Map.of("error_type", error.getClass().getSimpleName()));
    }
}
