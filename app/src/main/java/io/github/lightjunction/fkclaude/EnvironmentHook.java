package io.github.lightjunction.fkclaude;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.util.DisplayMetrics;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;

/** Every hook is installed only in the scoped Claude process, after loading one immutable profile. */
public final class EnvironmentHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static String modulePath;
    private Profile profile;
    private boolean initialized;
    private int hookCount;
    private final Set<String> reported = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, String> androidProperties = new HashMap<>();
    private final Map<String, String> javaProperties = new HashMap<>();
    @FunctionalInterface private interface Action { void run(XC_MethodHook.MethodHookParam param) throws Throwable; }
    @Override public void initZygote(StartupParam param) { modulePath = param.modulePath; }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) throws Throwable {
        if (!Profile.TARGET.equals(load.packageName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                initialize((Context) param.args[0], load.classLoader);
            }
        });
        XposedHelpers.findAndHookMethod(android.app.Instrumentation.class, "callApplicationOnCreate", Application.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Application application = (Application) param.args[0];
                        if (Profile.TARGET.equals(application.getPackageName())) initialize(application, load.classLoader);
                    }
                });
    }

    private void initialize(Context context, ClassLoader loader) {
        if (initialized) return;
        try {
            long version = context.getPackageManager().getPackageInfo(Profile.TARGET, 0).getLongVersionCode();
            if (version != Profile.TARGET_VERSION) { initialized = true; log("unsupported target version; no overrides installed"); return; }
            String encoded = null;
            try {
                Bundle result = context.getContentResolver().call(ProfileProvider.URI, "read", null, null);
                if (result != null) encoded = result.getString("profile");
            } catch (IllegalArgumentException | SecurityException e) {
                log("private provider unavailable; checking framework preferences");
            }
            if (encoded == null) {
                de.robv.android.xposed.XSharedPreferences shared = new de.robv.android.xposed.XSharedPreferences(
                        "io.github.lightjunction.fkclaude", ProfileStore.BRIDGE);
                shared.reload();
                encoded = shared.getString("profile", null);
                if (encoded != null) log("configuration channel=framework preferences");
            } else log("configuration channel=private provider");
            if (encoded == null) { log("profile unavailable; no overrides installed"); return; }
            profile = PropertyView.normalize(Profile.parse(encoded));
            initialized = true; // Freeze only after configuration was actually obtained; never install twice.
            if (!profile.enabled()) { log("profile disabled"); return; }
            install(context, loader);
            log("installed " + hookCount + " hooks for target version " + version);
            selfCheck(context);
        } catch (Throwable e) {
            log("initialization failed: " + e.getClass().getSimpleName());
            StackTraceElement[] frames = e.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 4); i++) log("initialization frame: " + frames[i]);
        }
    }

    private void selfCheck(Context context) {
        int checks = 0, passed = 0;
        if (profile.has("android_id")) {
            checks++;
            if (profile.get("android_id").equals(android.provider.Settings.Secure.getString(context.getContentResolver(), "android_id"))) passed++;
        }
        if (profile.has("locale")) {
            checks++;
            if (Locale.forLanguageTag(profile.get("locale")).equals(Locale.getDefault())) passed++;
        }
        if (profile.has("timezone")) {
            checks++;
            if (TimeZone.getTimeZone(profile.get("timezone")).getID().equals(TimeZone.getDefault().getID())) passed++;
        }
        for (var entry : PropertyView.environment(profile).entrySet()) {
            checks++;
            if (java.util.Objects.equals(entry.getValue().isEmpty() ? null : entry.getValue(), System.getenv(entry.getKey()))) passed++;
        }
        log("readback self-check: passed=" + passed + " checked=" + checks + "; no values logged");
    }

    private void install(Context context, ClassLoader loader) throws Throwable {
        buildProperties(loader);
        NativeBridge.install(profile, androidProperties);
        environmentAndCommands(loader);
        identity(loader);
        locale(loader);
        network(loader);
        privateData(loader);
        screen(loader);
        battery(loader);
        resources(loader);
        diagnostics(loader);
        sift(loader);
        outbound(loader);
        WebEnvironment.install(profile, modulePath);
    }

    private void hook(String cls, ClassLoader loader, String name, boolean before, Action action) {
        String signature = cls + "#" + name;
        try {
            Class<?> type = XposedHelpers.findClass(cls, loader);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(type, name, new XC_MethodHook() {
                private void invoke(MethodHookParam param) {
                    if (!before && param.hasThrowable()) return;
                    try { action.run(param); }
                    catch (Throwable e) {
                        if (reported.add(signature + ":runtime")) log("hook failed: " + signature + " (" + e.getClass().getSimpleName() + ")");
                    }
                }
                @Override protected void beforeHookedMethod(MethodHookParam param) { if (before) invoke(param); }
                @Override protected void afterHookedMethod(MethodHookParam param) { if (!before) invoke(param); }
            });
            hookCount += hooks.size();
            if (hooks.isEmpty() && reported.add(signature)) log("unavailable: " + signature);
        } catch (Throwable e) {
            if (reported.add(signature)) log("unavailable: " + signature);
        }
    }
    private void value(String cls, ClassLoader loader, String name, String key) {
        if (!profile.has(key)) return;
        hook(cls, loader, name, true, param -> param.setResult(convert(profile.get(key), ((Method) param.method).getReturnType())));
    }
    private static Object convert(String value, Class<?> type) {
        if (type == String.class) return value;
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(value);
        if (type == int.class || type == Integer.class) return Integer.parseInt(value);
        if (type == long.class || type == Long.class) return Long.parseLong(value);
        if (type == float.class || type == Float.class) return Float.parseFloat(value);
        if (type == double.class || type == Double.class) return Double.parseDouble(value);
        throw new IllegalArgumentException("Unsupported return type");
    }
    private void buildProperties(ClassLoader loader) {
        for (Schema.Field item : Schema.FIELDS) if (item.key().startsWith("build.") && profile.has(item.key())) {
            String name = item.key().substring(6);
            try { XposedHelpers.setStaticObjectField(Build.class, name, profile.get(item.key())); }
            catch (Throwable e) { log("Build field unavailable: " + name); }
        }
        if (profile.has("serial")) {
            XposedHelpers.setStaticObjectField(Build.class, "SERIAL", profile.get("serial"));
            value("android.os.Build", loader, "getSerial", "serial");
        }
        if (profile.has("os_release")) XposedHelpers.setStaticObjectField(Build.VERSION.class, "RELEASE", profile.get("os_release"));
        if (profile.has("security_patch")) XposedHelpers.setStaticObjectField(Build.VERSION.class, "SECURITY_PATCH", profile.get("security_patch"));
        if (profile.has("sdk_level")) XposedHelpers.setStaticIntField(Build.VERSION.class, "SDK_INT", (int) profile.number("sdk_level"));
        androidProperties.putAll(PropertyView.properties(profile));
        javaProperties.putAll(PropertyView.parse(profile.get("java_properties", "")));
        if (profile.has("timezone")) javaProperties.put("user.timezone", profile.get("timezone"));
        if (profile.has("locale")) {
            Locale language = Locale.forLanguageTag(profile.get("locale"));
            javaProperties.put("user.language", language.getLanguage());
            javaProperties.put("user.country", language.getCountry());
        }
        if (profile.has("user_agent")) javaProperties.put("http.agent", profile.get("user_agent"));
        for (String method : new String[]{"get", "getInt", "getLong", "getBoolean"})
            hook("android.os.SystemProperties", loader, method, true, p -> {
                if (p.args.length == 0 || !androidProperties.containsKey(p.args[0])) return;
                String v = androidProperties.get(p.args[0]);
                Class<?> type = ((Method) p.method).getReturnType();
                if (type == boolean.class) {
                    if (Set.of("1", "y", "yes", "on", "true").contains(v.toLowerCase(Locale.ROOT))) p.setResult(true);
                    else if (Set.of("0", "n", "no", "off", "false").contains(v.toLowerCase(Locale.ROOT))) p.setResult(false);
                    else p.setResult(p.args[1]);
                } else try { p.setResult(convert(v, type)); }
                catch (NumberFormatException e) { p.setResult(p.args[1]); }
            });
        if (!javaProperties.isEmpty()) {
            hook("java.lang.System", loader, "getProperty", true, p -> {
                if (javaProperties.containsKey(p.args[0])) p.setResult(javaProperties.get(p.args[0]));
            });
            hook("java.lang.System", loader, "getProperties", false, p -> {
                Properties copy = new Properties(); copy.putAll((Properties) p.getResult()); copy.putAll(javaProperties); p.setResult(copy);
            });
        }
    }
    private void environmentAndCommands(ClassLoader loader) {
        Map<String, String> environment = PropertyView.environment(profile);
        if (!environment.isEmpty()) hook("java.lang.System", loader, "getenv", true, p -> {
            if (p.args.length == 1 && environment.containsKey(p.args[0])) {
                String value = environment.get(p.args[0]); p.setResult(value.isEmpty() ? null : value);
            } else if (p.args.length == 0) {
                Map<String, String> values = new HashMap<>();
                @SuppressWarnings("unchecked") Map<String, String> original = (Map<String, String>) XposedBridge.invokeOriginalMethod(p.method, p.thisObject, p.args);
                values.putAll(original);
                environment.forEach((key, value) -> { if (value.isEmpty()) values.remove(key); else values.put(key, value); });
                p.setResult(Collections.unmodifiableMap(values));
            }
        });
        if (!Boolean.parseBoolean(profile.get("command_isolation", "true"))) return;
        hook("java.lang.ProcessBuilder", loader, "start", true, p -> {
            Process process = VirtualCommand.match(((ProcessBuilder) p.thisObject).command(), profile, androidProperties);
            if (process != null) p.setResult(process);
        });
        hook("java.lang.Runtime", loader, "exec", true, p -> {
            java.util.List<String> command;
            if (p.args[0] instanceof String[] array) command = java.util.Arrays.asList(array);
            else if (p.args[0] instanceof String text) command = java.util.Arrays.asList(text.trim().split("\\s+"));
            else return;
            Process process = VirtualCommand.match(command, profile, androidProperties);
            if (process != null) p.setResult(process);
        });
    }
    private void identity(ClassLoader loader) {
        if (profile.has("claude_device_id")) {
            try {
                Class<?> deviceId = XposedHelpers.findClass("s2h", loader);
                if (deviceId.getDeclaredField("a").getType() != String.class) throw new IllegalStateException();
                XposedHelpers.findAndHookConstructor(deviceId, String.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) { p.args[0] = profile.get("claude_device_id"); }
                });
                hookCount++;
            } catch (Throwable e) { log("device ID constructor unavailable"); }
            // Also cover the two verified outbound consumers in case ART has inlined the value constructor.
            hook("pm3", loader, "r", true, p -> { if (p.args.length == 6) p.args[5] = profile.get("claude_device_id"); });
            try {
                XposedBridge.hookAllConstructors(XposedHelpers.findClass("tdz", loader), new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.args.length == 10 && p.args[1] instanceof String) p.args[1] = profile.get("claude_device_id");
                    }
                });
                hookCount++;
            } catch (Throwable e) { log("analytics ID consumer unavailable"); }
        }
        if (profile.has("android_id")) for (String method : new String[]{"getString", "getStringForUser"})
            hook("android.provider.Settings$Secure", loader, method, true, p -> {
                if (p.args.length >= 2 && "android_id".equals(p.args[1])) p.setResult(profile.get("android_id"));
            });
    }
    private void locale(ClassLoader loader) {
        if (profile.has("locale")) {
            Locale configured = Locale.forLanguageTag(profile.get("locale"));
            hook("java.util.Locale", loader, "getDefault", true, p -> p.setResult(configured));
            for (String method : new String[]{"getDefault", "getAdjustedDefault"})
                hook("android.os.LocaleList", loader, method, true, p -> p.setResult(new LocaleList(configured)));
            hook("android.content.res.Resources", loader, "getConfiguration", false, p -> {
                Configuration copy = new Configuration((Configuration) p.getResult());
                copy.setLocales(new LocaleList(configured)); copy.setLayoutDirection(configured); p.setResult(copy);
            });
        }
        if (profile.has("timezone")) {
            TimeZone configured = TimeZone.getTimeZone(profile.get("timezone"));
            hook("java.util.TimeZone", loader, "getDefault", true, p -> p.setResult(configured.clone()));
        }
    }
    private void network(ClassLoader loader) {
        String[][] phone = {{"getNetworkOperatorName", "carrier_name"}, {"getSimOperatorName", "carrier_name"},
                {"getNetworkOperator", "network_operator"}, {"getSimOperator", "sim_operator"},
                {"getSimCountryIso", "sim_country"}, {"getNetworkCountryIso", "network_country"}};
        for (String[] item : phone) value("android.telephony.TelephonyManager", loader, item[0], item[1]);
        value("android.net.wifi.WifiInfo", loader, "getSSID", "wifi_ssid");
        value("android.net.wifi.WifiInfo", loader, "getBSSID", "wifi_bssid");
        value("android.net.wifi.WifiInfo", loader, "getMacAddress", "wifi_mac");
        if (profile.has("wifi_ssid") || profile.has("wifi_bssid"))
            hook("android.net.wifi.WifiManager", loader, "getScanResults", true, p -> p.setResult(Collections.emptyList()));
        if (profile.has("network_mac")) hook("java.net.NetworkInterface", loader, "getHardwareAddress", true, p -> {
            String configured = profile.get("network_mac");
            if (configured.isEmpty()) { p.setResult(null); return; }
            String[] octets = configured.split(":"); byte[] bytes = new byte[6];
            for (int i = 0; i < 6; i++) bytes[i] = (byte) Integer.parseInt(octets[i], 16);
            p.setResult(bytes);
        });
        if (profile.has("local_ip")) {
            hook("java.net.NetworkInterface", loader, "getInetAddresses", true, p -> {
                String ip = profile.get("local_ip");
                p.setResult(Collections.enumeration(ip.isEmpty() ? Collections.emptyList() : List.of(InetAddress.getByAddress(ipv4(ip)))));
            });
            hook("java.net.NetworkInterface", loader, "getInterfaceAddresses", true, p -> p.setResult(Collections.emptyList()));
            hook("android.net.wifi.WifiInfo", loader, "getIpAddress", true, p -> {
                String ip = profile.get("local_ip"); byte[] b = ip.isEmpty() ? new byte[4] : ipv4(ip);
                p.setResult((b[0] & 255) | (b[1] & 255) << 8 | (b[2] & 255) << 16 | (b[3] & 255) << 24);
            });
            hook("android.net.LinkProperties", loader, "getLinkAddresses", true, p -> {
                String ip = profile.get("local_ip");
                p.setResult(ip.isEmpty() ? Collections.emptyList() : List.of(XposedHelpers.newInstance(
                        android.net.LinkAddress.class, InetAddress.getByAddress(ipv4(ip)), 24)));
            });
        }
        value("android.net.NetworkInfo", loader, "getType", "network_type");
        value("android.net.NetworkInfo", loader, "getSubtype", "network_subtype");
        if (profile.has("network_transport")) {
            hook("android.net.NetworkCapabilities", loader, "hasTransport", true, p ->
                    p.setResult(((Integer) p.args[0]).longValue() == profile.number("network_transport")));
            hook("android.net.NetworkCapabilities", loader, "getTransportTypes", true, p ->
                    p.setResult(new int[]{(int) profile.number("network_transport")}));
        }
        if (profile.has("user_agent")) {
            value("android.webkit.WebSettings", loader, "getDefaultUserAgent", "user_agent");
            // WebSettings.getUserAgentString is abstract. Hook the actual provider after WebView creates it.
            try {
                hookCount += XposedBridge.hookAllConstructors(android.webkit.WebView.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (p.hasThrowable()) return;
                        try {
                            android.webkit.WebSettings settings = ((android.webkit.WebView) p.thisObject).getSettings();
                            settings.setUserAgentString(profile.get("user_agent"));
                            String concrete = settings.getClass().getName();
                            if (reported.add("web-settings:" + concrete)) {
                                hook(concrete, settings.getClass().getClassLoader(), "getUserAgentString", true,
                                        call -> call.setResult(profile.get("user_agent")));
                                hook(concrete, settings.getClass().getClassLoader(), "setUserAgentString", true,
                                        call -> call.args[0] = profile.get("user_agent"));
                            }
                        } catch (Throwable e) { if (reported.add("web-settings:error")) log("WebView settings override unavailable"); }
                    }
                }).size();
            } catch (Throwable e) { log("WebView constructors unavailable"); }
        }
    }
    private static byte[] ipv4(String ip) {
        String[] parts = ip.split("\\."); byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) bytes[i] = (byte) Integer.parseInt(parts[i]);
        return bytes;
    }

    private void privateData(ClassLoader loader) {
        if (profile.flag("block_location")) {
            hook("android.location.LocationManager", loader, "getLastKnownLocation", true, p -> p.setResult(null));
            for (String name : new String[]{"requestLocationUpdates", "requestSingleUpdate"})
                hook("android.location.LocationManager", loader, name, true, p -> p.setResult(null));
            hook("android.location.LocationManager", loader, "getCurrentLocation", true, p -> {
                java.util.concurrent.Executor executor = null;
                java.util.function.Consumer<android.location.Location> consumer = null;
                for (Object arg : p.args) {
                    if (arg instanceof java.util.concurrent.Executor e) executor = e;
                    if (arg instanceof java.util.function.Consumer<?> c) {
                        @SuppressWarnings("unchecked") var typed = (java.util.function.Consumer<android.location.Location>) c;
                        consumer = typed;
                    }
                }
                if (executor != null && consumer != null) {
                    var callback = consumer; executor.execute(() -> callback.accept(null)); p.setResult(null);
                }
            });
            // Covers Location values originating from other providers at these Java accessors.
            for (String method : new String[]{"getLatitude", "getLongitude", "getAltitude"})
                hook("android.location.Location", loader, method, true, p -> p.setResult(0d));
            hook("android.location.Location", loader, "getExtras", true, p -> p.setResult(null));
        } else {
            value("android.location.Location", loader, "getLatitude", "latitude");
            value("android.location.Location", loader, "getLongitude", "longitude");
            value("android.location.Location", loader, "getAccuracy", "location_accuracy");
        }
        if (profile.flag("block_sensors")) {
            hook("android.hardware.SensorManager", loader, "getSensorList", true, p -> p.setResult(Collections.emptyList()));
            hook("android.hardware.SensorManager", loader, "getDynamicSensorList", true, p -> p.setResult(Collections.emptyList()));
            hook("android.hardware.SensorManager", loader, "getDefaultSensor", true, p -> p.setResult(null));
            hook("android.hardware.SensorManager", loader, "registerListener", true, p -> p.setResult(false));
        }
        if (profile.flag("block_clipboard")) {
            for (String name : new String[]{"getPrimaryClip", "getPrimaryClipDescription"})
                hook("android.content.ClipboardManager", loader, name, true, p -> p.setResult(null));
            hook("android.content.ClipboardManager", loader, "hasPrimaryClip", true, p -> p.setResult(false));
        }
        if (profile.flag("block_personal_data")) {
            hook("android.content.ContentResolver", loader, "query", true, p -> {
                if (p.args.length == 0 || !(p.args[0] instanceof Uri uri)) return;
                if (Set.of("com.android.contacts", "contacts", "com.android.calendar", "calendar").contains(uri.getAuthority())) {
                    String[] columns = p.args.length > 1 && p.args[1] instanceof String[] projection ? projection : new String[0];
                    p.setResult(new android.database.MatrixCursor(columns));
                }
            });
        }
        if (profile.flag("block_package_list")) for (String name : new String[]{"getInstalledPackages", "getInstalledApplications"})
            hook("android.app.ApplicationPackageManager", loader, name, false, p -> {
                if (!(p.getResult() instanceof List<?> original)) return;
                List<Object> filtered = new ArrayList<>();
                for (Object app : original) {
                    String packageName = app instanceof PackageInfo info ? info.packageName
                            : app instanceof ApplicationInfo info ? info.packageName : null;
                    if (profile.allowsPackage(packageName)) filtered.add(app);
                }
                p.setResult(filtered);
            });
    }

    private void screen(ClassLoader loader) {
        if (!profile.has("screen_width") && !profile.has("screen_height") && !profile.has("screen_dpi") && !profile.has("screen_density")) return;
        hook("android.content.res.Resources", loader, "getDisplayMetrics", false, p -> {
            DisplayMetrics metrics = new DisplayMetrics(); metrics.setTo((DisplayMetrics) p.getResult());
            metrics(metrics); p.setResult(metrics);
        });
        for (String method : new String[]{"getMetrics", "getRealMetrics"})
            hook("android.view.Display", loader, method, false, p -> { if (p.args[0] instanceof DisplayMetrics metrics) metrics(metrics); });
        for (String method : new String[]{"getSize", "getRealSize"})
            hook("android.view.Display", loader, method, false, p -> {
                Point point = (Point) p.args[0];
                if (profile.has("screen_width")) point.x = (int) profile.number("screen_width");
                if (profile.has("screen_height")) point.y = (int) profile.number("screen_height");
            });
    }
    private void metrics(DisplayMetrics metrics) {
        if (profile.has("screen_width")) metrics.widthPixels = (int) profile.number("screen_width");
        if (profile.has("screen_height")) metrics.heightPixels = (int) profile.number("screen_height");
        if (profile.has("screen_dpi")) { metrics.densityDpi = (int) profile.number("screen_dpi"); metrics.xdpi = metrics.ydpi = metrics.densityDpi; }
        if (profile.has("screen_density")) metrics.density = metrics.scaledDensity = Float.parseFloat(profile.get("screen_density"));
    }
    private void battery(ClassLoader loader) {
        if (profile.has("battery_level")) hook("android.os.BatteryManager", loader, "getIntProperty", true, p -> {
            if (((Integer) p.args[0]) == android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) p.setResult((int) profile.number("battery_level"));
        });
        if (profile.has("battery_charging")) value("android.os.BatteryManager", loader, "isCharging", "battery_charging");
        if (profile.has("battery_level") || profile.has("battery_charging") || profile.has("battery_temperature") || profile.has("battery_health")) {
            // Limit to the battery broadcast; unrelated Intent extras are untouched.
            hook("android.content.Intent", loader, "getIntExtra", true, p -> {
                if (!Intent.ACTION_BATTERY_CHANGED.equals(((Intent) p.thisObject).getAction())) return;
                String key = (String) p.args[0];
                if (profile.has("battery_level")) {
                    if ("level".equals(key)) p.setResult((int) profile.number("battery_level"));
                    if ("scale".equals(key)) p.setResult(100);
                }
                if (profile.has("battery_charging")) {
                    if ("plugged".equals(key)) p.setResult(profile.flag("battery_charging") ? 1 : 0);
                    if ("status".equals(key)) p.setResult(profile.flag("battery_charging") ? 2 : 3);
                }
                if (profile.has("battery_temperature") && "temperature".equals(key))
                    p.setResult(Math.round(Float.parseFloat(profile.get("battery_temperature")) * 10));
                if (profile.has("battery_health") && "health".equals(key)) p.setResult((int) profile.number("battery_health"));
            });
        }
    }
    private void resources(ClassLoader loader) {
        if (profile.has("memory_total") || profile.has("memory_available"))
            hook("android.app.ActivityManager", loader, "getMemoryInfo", false, p -> {
                ActivityManager.MemoryInfo memory = (ActivityManager.MemoryInfo) p.args[0];
                if (profile.has("memory_total")) memory.totalMem = profile.number("memory_total");
                if (profile.has("memory_available")) memory.availMem = profile.number("memory_available");
                memory.threshold = 0; memory.lowMemory = false;
            });
        value("java.lang.Runtime", loader, "availableProcessors", "cpu_count");
        value("android.os.StatFs", loader, "getTotalBytes", "storage_total");
        value("android.os.StatFs", loader, "getAvailableBytes", "storage_available");
        value("android.os.StatFs", loader, "getFreeBytes", "storage_available");
        value("java.io.File", loader, "getTotalSpace", "storage_total");
        value("java.io.File", loader, "getFreeSpace", "storage_available");
        value("java.io.File", loader, "getUsableSpace", "storage_available");
        if (profile.has("storage_total") || profile.has("storage_available")) {
            for (String method : new String[]{"getBlockSize", "getBlockSizeLong"})
                hook("android.os.StatFs", loader, method, true, p -> {
                    if (((Method) p.method).getReturnType() == long.class) p.setResult(4096L); else p.setResult(4096);
                });
            for (String method : new String[]{"getBlockCount", "getBlockCountLong", "getAvailableBlocks", "getAvailableBlocksLong", "getFreeBlocks", "getFreeBlocksLong"})
                hook("android.os.StatFs", loader, method, true, p -> {
                    String key = ((Method) p.method).getName().startsWith("getBlockCount") ? "storage_total" : "storage_available";
                    if (!profile.has(key)) return;
                    long count = profile.number(key) / 4096;
                    if (((Method) p.method).getReturnType() == long.class) p.setResult(count); else p.setResult((int) Math.min(count, Integer.MAX_VALUE));
                });
        }
    }
    private void diagnostics(ClassLoader loader) {
        value("android.os.Debug", loader, "isDebuggerConnected", "debugger_connected");
        value("android.os.Debug", loader, "waitingForDebugger", "debugger_connected");
        value("io.sentry.android.core.internal.util.m", loader, "a", "sentry_rooted");
        value("io.sentry.android.core.q0", loader, "a", "sentry_emulator");
    }
    private void sift(ClassLoader loader) {
        if (profile.flag("block_sift")) {
            // v6g is R8-merged with unrelated coroutines: guard the captured receiver's exact type.
            hook("v6g", loader, "invokeSuspend", true, p -> {
                Object receiver = XposedHelpers.getObjectField(p.thisObject, "r");
                if (receiver != null && receiver.getClass().getName().equals("h2a0"))
                    p.setResult(XposedHelpers.getStaticObjectField(XposedHelpers.findClass("dpf0", loader), "a"));
            });
            hook("h2a0", loader, "b", true, p -> p.setResult(null)); // verified void event queue entry
            hook("g2a0", loader, "invokeSuspend", true, p ->
                    p.setResult(XposedHelpers.getStaticObjectField(XposedHelpers.findClass("dpf0", loader), "a")));
        }
        String[][] evidence = {{"k", "sift_file_evidence"}, {"l", "sift_package_evidence"},
                {"m", "sift_property_evidence"}, {"n", "sift_writable_evidence"}, {"o", "sift_installed_apps"}};
        String[][] scalar = {{"a", "sift_app_name"}, {"b", "sift_app_version"}, {"c", "sift_sdk_version"},
                {"d", "carrier_name"}, {"e", "sim_country"}, {"f", "build.MANUFACTURER"}, {"g", "build.MODEL"},
                {"h", "os_release"}, {"i", "android_id"}, {"j", "build.TAGS"}};
        boolean customize = false;
        for (String[] binding : evidence) customize |= profile.has(binding[1]);
        for (String[] binding : scalar) customize |= profile.has(binding[1]);
        if (customize) try {
            hookCount += XposedBridge.hookAllConstructors(XposedHelpers.findClass("et0", loader), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    try {
                        for (String[] binding : scalar) if (profile.has(binding[1]))
                            XposedHelpers.setObjectField(p.thisObject, binding[0], profile.get(binding[1]));
                        for (String[] binding : evidence) if (profile.has(binding[1])) {
                            List<String> values = new ArrayList<>();
                            for (String line : profile.get(binding[1]).split("[,\\r\\n]")) if (!line.isBlank()) values.add(line.trim());
                            XposedHelpers.setObjectField(p.thisObject, binding[0], Collections.unmodifiableList(values));
                        }
                    } catch (Throwable e) { if (reported.add("sift-payload")) log("Sift payload override failed"); }
                }
            }).size();
        } catch (Throwable e) { log("Sift device properties constructor unavailable"); }
        if (profile.has("sift_activity") || profile.has("sift_sdk_version") || profile.has("local_ip")
                || profile.has("battery_level") || profile.has("battery_charging") || profile.has("battery_health")) try {
            hookCount += XposedBridge.hookAllConstructors(XposedHelpers.findClass("jo0", loader), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    try {
                        if (profile.has("sift_activity")) XposedHelpers.setObjectField(p.thisObject, "a", profile.get("sift_activity"));
                        if (profile.has("sift_sdk_version")) XposedHelpers.setObjectField(p.thisObject, "b", profile.get("sift_sdk_version"));
                        if (profile.has("battery_level")) XposedHelpers.setDoubleField(p.thisObject, "c", profile.number("battery_level") / 100d);
                        if (profile.has("battery_health")) XposedHelpers.setLongField(p.thisObject, "e", profile.number("battery_health"));
                        if (profile.has("battery_charging")) {
                            XposedHelpers.setLongField(p.thisObject, "d", profile.flag("battery_charging") ? 2L : 3L);
                            XposedHelpers.setLongField(p.thisObject, "f", profile.flag("battery_charging") ? 1L : 0L);
                        }
                        if (profile.has("local_ip")) XposedHelpers.setObjectField(p.thisObject, "g",
                                profile.get("local_ip").isEmpty() ? Collections.emptyList() : List.of(profile.get("local_ip")));
                    } catch (Throwable e) { if (reported.add("sift-state")) log("Sift state override failed"); }
                }
            }).size();
        } catch (Throwable e) { log("Sift state constructor unavailable"); }
    }
    private void outbound(ClassLoader loader) {
        if (profile.get("blocked_hosts", "").isBlank() && !profile.flag("block_sift")) return;
        for (String method : new String[]{"getByName", "getAllByName"})
            hook("java.net.InetAddress", loader, method, true, p -> {
                if (p.args.length > 0 && p.args[0] instanceof String host && profile.blocksHost(host))
                    p.setThrowable(new UnknownHostException("Blocked by fkclaude profile"));
            });
        // Both synchronous and async calls reach ko10.f() in the analyzed build.
        hook("ko10", loader, "f", true, p -> {
            Object request = XposedHelpers.getObjectField(p.thisObject, "b");
            Object url = XposedHelpers.getObjectField(request, "a");
            String host = (String) XposedHelpers.getObjectField(url, "d");
            String path = (String) XposedHelpers.callMethod(url, "b");
            if (profile.blocksHost(host) || (profile.flag("block_sift") && path.matches("/v3/accounts/[^/]+/mobile_events")))
                p.setThrowable(new IOException("Blocked by fkclaude profile"));
        });
        hook("java.net.URL", loader, "openConnection", true, p -> {
            java.net.URL url = (java.net.URL) p.thisObject;
            if (profile.blocksHost(url.getHost()) || (profile.flag("block_sift") && url.getPath().matches("/v3/accounts/[^/]+/mobile_events")))
                p.setThrowable(new IOException("Blocked by fkclaude profile"));
        });
    }
    private static void log(String message) { XposedBridge.log("fkclaude: " + message); }
}
