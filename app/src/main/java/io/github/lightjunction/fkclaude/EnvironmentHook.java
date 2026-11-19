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
    private volatile String projectedUserAgent;
    private PrivacyStartupGate startup;
    private Method defaultUserAgentMethod;
    // Read the platform level before any configured Build projection changes it.
    private final int platformSdk = Build.VERSION.SDK_INT;
    private final Set<String> reported = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, String> androidProperties = new HashMap<>();
    private final Map<String, String> javaProperties = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> REQUIRED_US_HOOKS = Set.of(
            "android.location.LocationManager#getLastKnownLocation",
            "android.location.LocationManager#getCurrentLocation",
            "android.location.LocationManager#requestLocationUpdates",
            "android.location.Location#getLatitude", "android.location.Location#getLongitude",
            "android.location.Location#writeToParcel",
            "java.util.Locale#getDefault", "java.util.TimeZone#getDefault",
            "android.icu.util.ULocale#getDefault", "android.icu.util.TimeZone#getDefault",
            "android.os.LocaleList#getDefault", "android.os.LocaleList#getAdjustedDefault",
            "android.content.res.Resources#getConfiguration",
            "android.telephony.TelephonyManager#getNetworkCountryIso",
            "android.telephony.TelephonyManager#getSimCountryIso",
            "android.telephony.TelephonyManager#getAllCellInfo",
            "android.telephony.TelephonyManager#getCellLocation",
            "android.telephony.TelephonyManager#getServiceState",
            "android.telephony.TelephonyManager#requestCellInfoUpdate",
            "android.telephony.TelephonyManager#listen",
            "android.telephony.TelephonyManager#registerTelephonyCallback",
            "android.telephony.SubscriptionManager#getActiveSubscriptionInfoList",
            "android.os.SystemProperties#get", "android.os.SystemProperties#getInt",
            "android.os.SystemProperties#getLong", "android.os.SystemProperties#getBoolean",
            "android.os.Build#getSerial", "java.lang.System#getProperty",
            "android.webkit.WebSettings#getDefaultUserAgent",
            "android.provider.Settings$Secure#getString",
            "android.net.wifi.WifiInfo#getSSID", "android.net.wifi.WifiInfo#getBSSID",
            "android.net.wifi.WifiInfo#getMacAddress");
    private final Set<String> requiredPolicyHooks = requiredPolicyHooks();
    private final Set<String> installedPolicyHooks = new HashSet<>();

    private Set<String> requiredPolicyHooks() {
        Set<String> required = new HashSet<>(REQUIRED_US_HOOKS);
        required.addAll(SimIdentity.requiredHooks(platformSdk));
        return Set.copyOf(required);
    }
    @FunctionalInterface private interface Action { void run(XC_MethodHook.MethodHookParam param) throws Throwable; }
    @Override public void initZygote(StartupParam param) { modulePath = param.modulePath; }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) throws Throwable {
        if (!Profile.TARGET.equals(load.packageName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                initialize((Context) param.args[0], load.classLoader);
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                initialize((Context) param.args[0], load.classLoader);
            }
        });
        XposedHelpers.findAndHookMethod(android.app.Instrumentation.class, "callApplicationOnCreate", Application.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Application application = (Application) param.args[0];
                        if (Profile.TARGET.equals(application.getPackageName())) {
                            initialize(application, load.classLoader);
                            if (!finishStrictPrivacy(application, load.classLoader))
                                param.setThrowable(new SecurityException("Privacy startup unavailable"));
                        }
                    }
                });
    }

    private void initialize(Context context, ClassLoader loader) {
        if (initialized) return;
        try {
            long version = context.getPackageManager().getPackageInfo(Profile.TARGET, 0).getLongVersionCode();
            AuditHooks.install(context, loader, version, modulePath);
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
            if (encoded == null) throw new IllegalStateException("Profile unavailable");
            profile = PropertyView.normalize(Profile.parse(encoded));
            startup = new PrivacyStartupGate(profile.flag("privacy_us_only"));
            initialized = true; // Freeze only after configuration was actually obtained; never install twice.
            IpGuard.install(context, profile, startup);
            AuditNative.install(profile.flag("ip_guard_enabled"));
            AuditLog.record("system", "environment_policy", "EnvironmentHook", "observed",
                    Map.of("phase", profile.enabled() ? "modified_environment" : "environment_disabled",
                            "reason", "sift_block=" + profile.flag("block_sift") + ";ip_guard=" + profile.flag("ip_guard_enabled")
                                     + ";us_only=" + profile.flag("privacy_us_only")));
            // Standard privacy controls remain active even when targeted app hooks are unavailable.
            if (profile.flag("privacy_us_only")) {
                installPlatformPrivacy(context, loader);
            }
            if (version != Profile.TARGET_VERSION) {
                if (profile.flag("privacy_us_only")) {
                    AuditLog.record("system", "privacy_policy", "EnvironmentHook", "blocked",
                            Map.of("reason", "unsupported_target_version"));
                    log("strict privacy blocked: unsupported_target_version");
                    throw new IllegalStateException("Unsupported target privacy version");
                }
                log("unsupported environment version; IP guard remains independent"); return;
            }
            if (!profile.enabled()) { log("environment disabled; IP guard remains independent"); return; }
            install(context, loader);
            if (profile.flag("privacy_us_only")) log("early privacy installed; Web and egress waiting for application readiness");
            else {
                log("installed " + hookCount + " hooks for target version " + version);
                selfCheck(context);
            }
        } catch (Throwable e) {
            try { if (startup != null) IpGuard.privacyFailed(startup); }
            finally {
                try {
                    log("initialization failed: " + e.getClass().getSimpleName());
                    StackTraceElement[] frames = e.getStackTrace();
                    for (int i = 0; i < Math.min(frames.length, 4); i++) log("initialization frame: " + frames[i]);
                } finally {
                    // Neither a failed revocation nor a caller swallowing an exception may continue the target.
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            }
        }
    }

    private boolean finishStrictPrivacy(Application application, ClassLoader loader) {
        if (profile == null) return false;
        if (!profile.flag("privacy_us_only")) return true;
        try {
            if (!startup.beginFinalization()) return startup.isReady();
            Object current = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", loader), "currentApplication");
            if (current != application || android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                throw new IllegalStateException("Application is not ready for Web privacy");
            // Bypass only this one internal getter invocation. Public early calls remain gated.
            Object original = XposedBridge.invokeOriginalMethod(defaultUserAgentMethod, null, new Object[]{application});
            if (!(original instanceof String raw)) throw new IllegalStateException("WebView user agent unavailable");
            projectedUserAgent = PixelUserAgent.project(raw);
            javaProperties.put("http.agent", projectedUserAgent);
            installUserAgentHooks(loader);
            WebEnvironment.install(profile, modulePath, projectedUserAgent, loader);
            selfCheck(application);

            // Initialize the framework class before opening the single-object bootstrap claim.
            Class.forName("android.webkit.WebView", true, android.webkit.WebView.class.getClassLoader());
            android.webkit.WebView probe = null;
            startup.openBootstrap();
            try {
                probe = new android.webkit.WebView(application);
                WebEnvironment.applyMetadata(probe.getSettings());
                startup.confirmBootstrap(probe);
            } finally {
                try { if (probe != null) probe.destroy(); }
                finally { startup.closeBootstrap(); }
            }
            startup.complete();
            IpGuard.privacyStateChanged();
            log("privacy startup ready; offline Web metadata and policy readbacks verified");
            log("installed " + hookCount + " hooks for target version " + Profile.TARGET_VERSION);
            return true;
        } catch (Throwable failure) {
            try { IpGuard.privacyFailed(startup); }
            finally {
                try {
                    log("privacy startup failed: " + failure.getClass().getSimpleName());
                    StackTraceElement[] frames = failure.getStackTrace();
                    for (int i = 0; i < Math.min(frames.length, 4); i++) log("privacy startup frame: " + frames[i]);
                } finally { android.os.Process.killProcess(android.os.Process.myPid()); }
            }
            return false;
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
        if (profile.flag("privacy_us_only")) {
            Locale configured = Locale.forLanguageTag(profile.get("locale"));
            checks++;
            if (android.icu.util.ULocale.forLocale(configured).equals(android.icu.util.ULocale.getDefault())) passed++;
            checks++;
            if (profile.get("timezone").equals(android.icu.util.TimeZone.getDefault().getID())) passed++;
            Configuration config = context.getResources().getConfiguration();
            checks++;
            if (config.mcc == 0 && config.mnc == 0 && !config.getLocales().isEmpty()
                    && configured.equals(config.getLocales().get(0))) passed++;
            android.telephony.TelephonyManager phone = context.getSystemService(android.telephony.TelephonyManager.class);
            checks++;
            if (phone != null && profile.get("sim_country").equals(phone.getSimCountryIso())) passed++;
            checks++;
            if (phone != null && profile.get("network_country").equals(phone.getNetworkCountryIso())) passed++;
            // These getters are checked only after all required hooks have been installed.
            checks++;
            if (syntheticLineNumberMatches(phone)) passed++;
            checks++;
            if (phone != null && profile.get("sim_operator").equals(phone.getSimOperator())) passed++;
            checks++;
            if (phone != null && profile.get("network_operator").equals(phone.getNetworkOperator())) passed++;
            checks++;
            if (phone != null && profile.get("carrier_name").equals(phone.getSimOperatorName())) passed++;
            if (platformSdk >= 33) {
                android.telephony.SubscriptionManager subscriptions = context.getSystemService(android.telephony.SubscriptionManager.class);
                checks++;
                if (syntheticSubscriptionNumberMatches(subscriptions)) passed++;
            }
            android.telecom.PhoneAccount syntheticAccount = new android.telecom.PhoneAccount.Builder(
                    new android.telecom.PhoneAccountHandle(
                            new android.content.ComponentName("io.github.lightjunction.fkclaude", "PolicyCheck"),
                            "fkclaude-policy-check"), "Policy check")
                    .setAddress(Uri.fromParts("tel", profile.get("sim_phone_number"), null))
                    .setSubscriptionAddress(Uri.fromParts("tel", profile.get("sim_phone_number"), null)).build();
            checks++;
            if (syntheticAccount.getAddress() == null) passed++;
            checks++;
            if (syntheticAccount.getSubscriptionAddress() == null) passed++;
            checks++;
            if ("PhoneAccount[private]".equals(syntheticAccount.toString())) passed++;
            for (Schema.Field field : Schema.FIELDS) if (field.key().startsWith("build.") && profile.has(field.key())) {
                checks++;
                if (profile.get(field.key()).equals(XposedHelpers.getStaticObjectField(Build.class, field.key().substring(6)))) passed++;
            }
            for (String[] binding : PropertyView.REDACTED_BUILD_PROPERTIES) {
                checks++;
                if ("".equals(XposedHelpers.getStaticObjectField(Build.class, binding[0]))) passed++;
            }
            Class<?> systemProperties = XposedHelpers.findClass("android.os.SystemProperties", context.getClassLoader());
            for (String[] binding : new String[][]{{"ro.product.model", "build.MODEL"},
                    {"ro.product.manufacturer", "build.MANUFACTURER"}, {"ro.product.device", "build.DEVICE"}}) {
                checks++;
                if (profile.get(binding[1]).equals(XposedHelpers.callStaticMethod(systemProperties, "get", binding[0]))) passed++;
            }
            checks++;
            if (projectedUserAgent.equals(android.webkit.WebSettings.getDefaultUserAgent(context))) passed++;
            checks++;
            if (projectedUserAgent.equals(System.getProperty("http.agent"))) passed++;
            // Synthetic values only: verify blocked accessors without requesting a real device fix.
            android.location.Location synthetic = new android.location.Location("fkclaude-policy-check");
            android.location.Location other = new android.location.Location("fkclaude-policy-check");
            checks++;
            if (locationReadDenied(() -> synthetic.getLatitude())) passed++;
            checks++;
            if (locationReadDenied(() -> synthetic.getLongitude())) passed++;
            checks++;
            if (locationReadDenied(() -> synthetic.distanceTo(other))) passed++;
            checks++;
            if (locationReadDenied(() -> synthetic.bearingTo(other))) passed++;
        }
        log("readback self-check: passed=" + passed + " checked=" + checks + "; no values logged");
        if (profile.flag("privacy_us_only") && passed != checks)
            throw new IllegalStateException("Privacy readback verification failed");
    }

    // Diagnostics call only our installed BEFORE hooks. Never request a real telephony permission
    // or fall back to the device service if interception is missing or access is denied.
    @android.annotation.SuppressLint("MissingPermission")
    private boolean syntheticLineNumberMatches(android.telephony.TelephonyManager phone) {
        if (!profile.flag("privacy_us_only")
                || !installedPolicyHooks.contains("android.telephony.TelephonyManager#getLine1Number"))
            throw new IllegalStateException("Synthetic line-number hook unavailable");
        if (phone == null) return false;
        try { return profile.get("sim_phone_number").equals(phone.getLine1Number()); }
        catch (SecurityException denied) { return false; }
    }

    @android.annotation.TargetApi(33)
    @android.annotation.SuppressLint("MissingPermission")
    private boolean syntheticSubscriptionNumberMatches(android.telephony.SubscriptionManager subscriptions) {
        if (platformSdk < 33 || !profile.flag("privacy_us_only")
                || !installedPolicyHooks.contains("android.telephony.SubscriptionManager#getPhoneNumber"))
            throw new IllegalStateException("Synthetic subscription-number hook unavailable");
        if (subscriptions == null) return false;
        try { return profile.get("sim_phone_number").equals(
                subscriptions.getPhoneNumber(android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID)); }
        catch (SecurityException denied) { return false; }
    }

    private static boolean locationReadDenied(Runnable query) {
        try { query.run(); return false; }
        catch (SecurityException expected) { return true; }
    }

    private void installPlatformPrivacy(Context context, ClassLoader loader) throws Throwable {
        buildProperties(loader);
        NativeBridge.install(profile, androidProperties);
        projectedUserAgent = profile.has("user_agent") ? profile.get("user_agent") : null;
        if (profile.flag("privacy_us_only")) installEarlyWebGates(loader);
        environmentAndCommands(loader);
        androidIdentity(loader);
        locale(loader);
        network(loader);
        privateData(loader);
        if (!profile.flag("privacy_us_only")) {
            installUserAgentHooks(loader);
            WebEnvironment.install(profile, modulePath, projectedUserAgent, loader);
        }
        if (profile.flag("privacy_us_only") && !installedPolicyHooks.containsAll(requiredPolicyHooks))
            throw new IllegalStateException("Required privacy hooks missing");
    }

    private void install(Context context, ClassLoader loader) throws Throwable {
        if (!profile.flag("privacy_us_only")) installPlatformPrivacy(context, loader);
        identity(loader);
        screen(loader);
        battery(loader);
        resources(loader);
        diagnostics(loader);
        sift(loader);
        outbound(loader);
    }

    private int hook(String cls, ClassLoader loader, String name, boolean before, Action action) {
        String signature = cls + "#" + name;
        int installed = 0;
        try {
            Class<?> type = XposedHelpers.findClass(cls, loader);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(type, name, new XC_MethodHook() {
                private void invoke(MethodHookParam param) {
                    if (!before && param.hasThrowable()) return;
                    try { action.run(param); }
                    catch (Throwable e) {
                        if (reported.add(signature + ":runtime")) log("hook failed: " + signature + " (" + e.getClass().getSimpleName() + ")");
                        if (profile.flag("privacy_us_only"))
                            param.setThrowable(new SecurityException("Privacy interception unavailable"));
                    }
                }
                @Override protected void beforeHookedMethod(MethodHookParam param) { if (before) invoke(param); }
                @Override protected void afterHookedMethod(MethodHookParam param) { if (!before) invoke(param); }
            });
            installed = hooks.size();
            hookCount += installed;
            if (installed > 0 && requiredPolicyHooks.contains(signature)) installedPolicyHooks.add(signature);
            if (hooks.isEmpty() && reported.add(signature)) log("unavailable: " + signature);
        } catch (Throwable e) {
            if (reported.add(signature)) log("unavailable: " + signature);
        }
        if (installed == 0 && profile.flag("privacy_us_only") && requiredPolicyHooks.contains(signature))
            throw new IllegalStateException("Required privacy hook unavailable: " + signature);
        return installed;
    }
    private void value(String cls, ClassLoader loader, String name, String key) {
        if (!profile.has(key)) return;
        hook(cls, loader, name, true, param -> param.setResult(convert(profile.get(key), ((Method) param.method).getReturnType())));
    }
    private static Object convert(String value, Class<?> type) {
        if (type == String.class || type == CharSequence.class) return value;
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(value);
        if (type == int.class || type == Integer.class) return Integer.parseInt(value);
        if (type == long.class || type == Long.class) return Long.parseLong(value);
        if (type == float.class || type == Float.class) return Float.parseFloat(value);
        if (type == double.class || type == Double.class) return Double.parseDouble(value);
        throw new IllegalArgumentException("Unsupported return type");
    }
    private void setBuildField(String name, String value) {
        try {
            XposedHelpers.setStaticObjectField(Build.class, name, value);
            if (profile.flag("privacy_us_only")
                    && !value.equals(XposedHelpers.getStaticObjectField(Build.class, name)))
                throw new IllegalStateException("Build identity readback mismatch");
        } catch (Throwable e) {
            log("Build field unavailable: " + name);
            if (profile.flag("privacy_us_only"))
                throw new IllegalStateException("Required Build identity unavailable", e);
        }
    }

    private void buildProperties(ClassLoader loader) {
        for (Schema.Field item : Schema.FIELDS) if (item.key().startsWith("build.") && profile.has(item.key())) {
            String name = item.key().substring(6);
            setBuildField(name, profile.get(item.key()));
        }
        if (profile.flag("privacy_us_only"))
            for (String[] binding : PropertyView.REDACTED_BUILD_PROPERTIES) setBuildField(binding[0], "");
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
    }
    private void androidIdentity(ClassLoader loader) {
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
            android.icu.util.ULocale icu = android.icu.util.ULocale.forLocale(configured);
            hook("android.icu.util.ULocale", loader, "getDefault", true, p -> p.setResult(icu));
        }
        if (profile.has("locale") || profile.flag("block_location")) {
            hook("android.content.res.Resources", loader, "getConfiguration", false, p -> {
                Configuration copy = new Configuration((Configuration) p.getResult());
                if (profile.has("locale")) {
                    Locale configured = Locale.forLanguageTag(profile.get("locale"));
                    copy.setLocales(new LocaleList(configured)); copy.setLayoutDirection(configured);
                }
                if (profile.flag("block_location")) { copy.mcc = 0; copy.mnc = 0; }
                p.setResult(copy);
            });
        }
        if (profile.has("timezone")) {
            TimeZone configured = TimeZone.getTimeZone(profile.get("timezone"));
            for (String method : new String[]{"getDefault", "getDefaultRef"})
                hook("java.util.TimeZone", loader, method, true, p -> p.setResult(configured.clone()));
            android.icu.util.TimeZone icu = android.icu.util.TimeZone.getTimeZone(profile.get("timezone"));
            hook("android.icu.util.TimeZone", loader, "getDefault", true, p -> p.setResult(icu.clone()));
        }
    }
    private void network(ClassLoader loader) {
        String[][] phone = {{"getNetworkOperatorName", "carrier_name"}, {"getSimOperatorName", "carrier_name"},
                {"getNetworkOperator", "network_operator"}, {"getSimOperator", "sim_operator"},
                {"getSimCountryIso", "sim_country"}, {"getNetworkCountryIso", "network_country"}};
        for (String[] item : phone) value("android.telephony.TelephonyManager", loader, item[0], item[1]);
        simIdentity(loader);
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
    }

    private void installEarlyWebGates(ClassLoader loader) throws Throwable {
        defaultUserAgentMethod = android.webkit.WebSettings.class.getDeclaredMethod("getDefaultUserAgent", Context.class);
        hook("android.webkit.WebSettings", loader, "getDefaultUserAgent", true, call -> {
            String projected = projectedUserAgent;
            if (projected == null) call.setThrowable(new SecurityException("Web identity is not ready"));
            else call.setResult(projected);
        });
        Set<XC_MethodHook.Unhook> constructors = XposedBridge.hookAllConstructors(
                android.webkit.WebView.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam call) {
                        if (startup == null || !startup.allowConstructor(call.thisObject))
                            call.setThrowable(new SecurityException("Web privacy is not ready"));
                    }
                });
        hookCount += constructors.size();
        if (constructors.isEmpty()) throw new IllegalStateException("Early Web constructor gate unavailable");
        int settings = hook("android.webkit.WebView", loader, "getSettings", true, call -> {
            if (startup == null || !startup.allowAccess(call.thisObject))
                call.setThrowable(new SecurityException("Web privacy is not ready"));
        });
        if (settings == 0) throw new IllegalStateException("Early Web settings gate unavailable");
    }

    private void installUserAgentHooks(ClassLoader loader) {
        if (projectedUserAgent != null) {
            final String userAgent = projectedUserAgent;
            final boolean strict = profile.flag("privacy_us_only");
            if (!strict)
                hook("android.webkit.WebSettings", loader, "getDefaultUserAgent", true, p -> p.setResult(userAgent));
            // WebSettings getters/setters are abstract. Require both concrete provider hooks in strict mode.
            try {
                Set<XC_MethodHook.Unhook> constructors = XposedBridge.hookAllConstructors(android.webkit.WebView.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (p.hasThrowable()) return;
                        try {
                            android.webkit.WebSettings settings = ((android.webkit.WebView) p.thisObject).getSettings();
                            String concrete = settings.getClass().getName(), key = "web-settings:" + concrete;
                            synchronized (reported) {
                                if (!reported.contains(key)) {
                                    int getters = hook(concrete, settings.getClass().getClassLoader(), "getUserAgentString", true,
                                            call -> call.setResult(userAgent));
                                    int setters = hook(concrete, settings.getClass().getClassLoader(), "setUserAgentString", true,
                                            call -> call.args[0] = userAgent);
                                    int metadata = strict ? hook(concrete, settings.getClass().getClassLoader(),
                                            "setUserAgentString", false, call -> {
                                                try { WebEnvironment.applyMetadata((android.webkit.WebSettings) call.thisObject); }
                                                catch (Throwable failure) {
                                                    // The original setter already ran. A host may catch an exception,
                                                    // so stop this process before an existing WebView can keep loading.
                                                    try { IpGuard.privacyFailed(startup); }
                                                    finally {
                                                        try { log("strict privacy blocked: web_metadata_unverified"); }
                                                        finally { android.os.Process.killProcess(android.os.Process.myPid()); }
                                                    }
                                                    throw failure;
                                                }
                                            }) : 1;
                                    if (strict && (getters == 0 || setters == 0 || metadata == 0))
                                        throw new IllegalStateException("Required WebView user agent hooks unavailable");
                                    reported.add(key);
                                }
                            }
                            settings.setUserAgentString(userAgent);
                        } catch (Throwable e) {
                            if (reported.add("web-settings:error")) log("WebView settings override unavailable");
                            if (strict) p.setThrowable(new SecurityException("Web identity policy unavailable"));
                        }
                    }
                });
                hookCount += constructors.size();
                if (strict && constructors.isEmpty()) throw new IllegalStateException("WebView constructors unavailable");
            } catch (Throwable e) {
                log("WebView constructors unavailable");
                if (strict) throw new IllegalStateException("Web identity policy unavailable", e);
            }
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
            for (String name : new String[]{"requestLocationUpdates", "requestSingleUpdate", "addProximityAlert"})
                hook("android.location.LocationManager", loader, name, true, p -> p.setResult(null));
            for (String name : new String[]{"getAllProviders", "getProviders"})
                hook("android.location.LocationManager", loader, name, true, p -> p.setResult(Collections.emptyList()));
            hook("android.location.LocationManager", loader, "getBestProvider", true, p -> p.setResult(null));
            for (String name : new String[]{"isLocationEnabled", "isProviderEnabled", "addNmeaListener",
                    "addGpsStatusListener", "registerGnssStatusCallback", "registerGnssMeasurementsCallback",
                    "registerGnssNavigationMessageCallback", "registerGnssBatchedLocationCallback"})
                hook("android.location.LocationManager", loader, name, true, p -> p.setResult(false));
            hook("android.location.LocationManager", loader, "getCurrentLocation", true, p -> {
                // Suppress the provider BEFORE inspecting args or invoking any user-supplied executor.
                p.setResult(null);
                java.util.concurrent.Executor executor = null;
                java.util.function.Consumer<android.location.Location> consumer = null;
                android.os.CancellationSignal cancellation = null;
                for (Object arg : p.args) {
                    if (arg instanceof java.util.concurrent.Executor e) executor = e;
                    if (arg instanceof android.os.CancellationSignal signal) cancellation = signal;
                    if (arg instanceof java.util.function.Consumer<?> c) {
                        @SuppressWarnings("unchecked") var typed = (java.util.function.Consumer<android.location.Location>) c;
                        consumer = typed;
                    }
                }
                var signal = cancellation;
                LocationPrivacy.unavailable(executor, () -> signal != null && signal.isCanceled(), consumer);
            });
            // A bogus (0,0) fix is still a location. Deny access instead of inventing coordinates.
            for (String method : new String[]{"getLatitude", "getLongitude", "getAltitude",
                    "getMslAltitudeMeters", "writeToParcel", "distanceTo", "bearingTo", "dump"})
                hook("android.location.Location", loader, method, true,
                        p -> p.setThrowable(new SecurityException("Location unavailable by privacy policy")));
            hook("android.location.Location", loader, "getExtras", true, p -> p.setResult(null));
            hook("android.location.Location", loader, "toString", true, p -> p.setResult("Location[unavailable]"));
            locationSideChannels(loader);
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

    private void simIdentity(ClassLoader loader) {
        String number = SimIdentity.phone(profile);
        if (number != null) {
            for (String method : new String[]{"getLine1Number", "getMsisdn"})
                hook("android.telephony.TelephonyManager", loader, method, true, p -> p.setResult(number));
            hook("android.telecom.TelecomManager", loader, "getLine1Number", true, p -> p.setResult(number));
            hook("android.telephony.SubscriptionInfo", loader, "getNumber", true, p -> p.setResult(number));
            if (platformSdk >= 33)
                hook("android.telephony.SubscriptionManager", loader, "getPhoneNumber", true, p -> p.setResult(number));
        }
        if (profile.flag("privacy_us_only")) {
            hook("android.telecom.TelecomManager", loader, "getPhoneAccount", true, p -> p.setResult(null));
            for (String method : new String[]{"getAddress", "getSubscriptionAddress"})
                hook("android.telecom.PhoneAccount", loader, method, true, p -> p.setResult(null));
            hook("android.telecom.PhoneAccount", loader, "writeToParcel", true,
                    p -> p.setThrowable(new SecurityException("Phone account unavailable by privacy policy")));
            hook("android.telecom.PhoneAccount", loader, "toString", true, p -> p.setResult("PhoneAccount[private]"));
        }
        boolean locationBlocked = profile.flag("block_location");
        String info = "android.telephony.SubscriptionInfo";
        if (locationBlocked || profile.has("sim_country"))
            hook(info, loader, "getCountryIso", true, p -> p.setResult(profile.get("sim_country", "")));
        if (locationBlocked || profile.has("sim_operator")) {
            String mcc = SimIdentity.operatorPart(profile, true), mnc = SimIdentity.operatorPart(profile, false);
            hook(info, loader, "getMccString", true, p -> p.setResult(mcc));
            hook(info, loader, "getMncString", true, p -> p.setResult(mnc));
            hook(info, loader, "getMcc", true, p -> p.setResult(mcc.isEmpty() ? 0 : Integer.parseInt(mcc)));
            hook(info, loader, "getMnc", true, p -> p.setResult(mnc.isEmpty() ? 0 : Integer.parseInt(mnc)));
        }
        if (locationBlocked || profile.has("carrier_name")) {
            String carrier = profile.get("carrier_name", "");
            for (String method : new String[]{"getCarrierName", "getDisplayName"})
                hook(info, loader, method, true, p -> p.setResult(carrier));
            for (String method : new String[]{"getSimCarrierIdName", "getSimSpecificCarrierIdName"})
                hook("android.telephony.TelephonyManager", loader, method, true, p -> p.setResult(carrier));
        }
        if (profile.flag("block_personal_data")) {
            for (String method : new String[]{"getSubscriberId", "getSimSerialNumber", "getDeviceId", "getImei", "getMeid",
                    "getVoiceMailNumber", "getVoiceMailAlphaTag", "getGroupIdLevel1"})
                hook("android.telephony.TelephonyManager", loader, method, true, p -> p.setResult(""));
            for (String method : new String[]{"getIccId", "getCardString", "getGroupOwner"})
                hook(info, loader, method, true, p -> p.setResult(""));
            hook(info, loader, "getGroupUuid", true, p -> p.setResult(null));
            hook(info, loader, "getCardId", true, p -> p.setResult(-1));
            // Parcel and diagnostic formatting use fields directly, bypassing the accessors above.
            hook(info, loader, "writeToParcel", true,
                    p -> p.setThrowable(new SecurityException("SIM details unavailable by privacy policy")));
            hook(info, loader, "toString", true, p -> p.setResult("SubscriptionInfo[private]"));
        }
        if (locationBlocked) {
            hook(info, loader, "getCarrierId", true, p -> p.setResult(-1));
            for (String method : new String[]{"getSimCarrierId", "getSimSpecificCarrierId", "getCarrierIdFromSimMccMnc"})
                hook("android.telephony.TelephonyManager", loader, method, true, p -> p.setResult(-1));
            for (String method : new String[]{"getEhplmns", "getHplmns"})
                hook(info, loader, method, true, p -> p.setResult(new String[0]));
        }
    }

    private void locationSideChannels(ClassLoader loader) {
        for (String method : new String[]{"getAllCellInfo", "getNeighboringCellInfo"})
            hook("android.telephony.TelephonyManager", loader, method, true, p -> p.setResult(Collections.emptyList()));
        for (String method : new String[]{"getCellLocation", "getServiceState"})
            hook("android.telephony.TelephonyManager", loader, method, true, p -> p.setResult(null));
        for (String method : new String[]{"listen", "registerTelephonyCallback"})
            hook("android.telephony.TelephonyManager", loader, method, true, p -> p.setResult(null));
        hook("android.telephony.TelephonyManager", loader, "requestCellInfoUpdate", true, p -> {
            p.setResult(null);
            java.util.concurrent.Executor executor = null;
            android.telephony.TelephonyManager.CellInfoCallback callback = null;
            for (Object arg : p.args) {
                if (arg instanceof java.util.concurrent.Executor e) executor = e;
                if (arg instanceof android.telephony.TelephonyManager.CellInfoCallback c) callback = c;
            }
            if (executor != null && callback != null) {
                var target = callback; executor.execute(() -> target.onCellInfo(Collections.emptyList()));
            }
        });
        for (String method : new String[]{"getActiveSubscriptionInfoList", "getAvailableSubscriptionInfoList",
                "getAccessibleSubscriptionInfoList", "getAllSubscriptionInfoList", "getCompleteActiveSubscriptionInfoList"})
            hook("android.telephony.SubscriptionManager", loader, method, true, p -> p.setResult(Collections.emptyList()));
        for (String method : new String[]{"getActiveSubscriptionInfo", "getActiveSubscriptionInfoForSimSlotIndex",
                "getActiveSubscriptionInfoForIccIndex"})
            hook("android.telephony.SubscriptionManager", loader, method, true, p -> p.setResult(null));
        for (String method : new String[]{"getScanResults", "getConfiguredNetworks", "getPrivilegedConfiguredNetworks"})
            hook("android.net.wifi.WifiManager", loader, method, true, p -> p.setResult(Collections.emptyList()));
        hook("android.net.wifi.WifiManager", loader, "startScan", true, p -> p.setResult(false));
        for (String method : new String[]{"getWifiSsid", "getPasspointFqdn", "getPasspointProviderFriendlyName",
                "getApMldMacAddress"})
            hook("android.net.wifi.WifiInfo", loader, method, true, p -> p.setResult(null));
        for (String method : new String[]{"getInformationElements", "getAffiliatedMloLinks", "getAssociatedMloLinks"})
            hook("android.net.wifi.WifiInfo", loader, method, true, p -> p.setResult(Collections.emptyList()));
        hook("android.net.wifi.WifiInfo", loader, "writeToParcel", true,
                p -> p.setThrowable(new SecurityException("Wi-Fi details unavailable by privacy policy")));
        hook("android.net.wifi.WifiInfo", loader, "toString", true, p -> p.setResult("WifiInfo[unavailable]"));
        for (String method : new String[]{"getSSID", "getBSSID", "getMacAddress"})
            hook("android.net.wifi.WifiInfo", loader, method, true, p -> p.setResult(
                    method.equals("getSSID") ? "<unknown ssid>" : "02:00:00:00:00:00"));
        hook("android.net.wifi.rtt.WifiRttManager", loader, "startRanging", true,
                p -> p.setThrowable(new SecurityException("Nearby ranging unavailable by privacy policy")));
        hook("android.net.wifi.aware.WifiAwareManager", loader, "attach", true,
                p -> p.setThrowable(new SecurityException("Nearby discovery unavailable by privacy policy")));
        for (String method : new String[]{"startDiscovery", "startLeScan"})
            hook("android.bluetooth.BluetoothAdapter", loader, method, true, p -> p.setResult(false));
        hook("android.bluetooth.BluetoothAdapter", loader, "getBondedDevices", true,
                p -> p.setResult(Collections.emptySet()));
        hook("android.bluetooth.le.BluetoothLeScanner", loader, "startScan", true,
                p -> p.setThrowable(new SecurityException("Nearby scan unavailable by privacy policy")));
        hook("android.bluetooth.BluetoothManager", loader, "getConnectedDevices", true,
                p -> p.setResult(Collections.emptyList()));
        for (String cls : new String[]{"android.bluetooth.BluetoothAdapter", "android.bluetooth.BluetoothDevice"}) {
            hook(cls, loader, "getName", true, p -> p.setResult(""));
            hook(cls, loader, "getAddress", true, p -> p.setResult("02:00:00:00:00:00"));
        }
        for (String method : new String[]{"getFromLocation", "getFromLocationName"})
            hook("android.location.Geocoder", loader, method, true, p -> {
                if (((Method) p.method).getReturnType() != void.class) {
                    p.setResult(Collections.emptyList()); return;
                }
                p.setResult(null);
                if (Build.VERSION.SDK_INT >= 33)
                    for (Object arg : p.args) if (arg instanceof android.location.Geocoder.GeocodeListener listener)
                        listener.onGeocode(Collections.emptyList());
            });
        // A network-country broadcast is another entry point independent of TelephonyManager getters.
        hook("android.content.Intent", loader, "getStringExtra", true, p -> {
            if (isNetworkCountry((Intent) p.thisObject) && p.args.length > 0
                    && "android.telephony.extra.NETWORK_COUNTRY".equals(p.args[0]))
                p.setResult(profile.get("network_country", ""));
        });
        hook("android.content.Intent", loader, "getExtras", false, p -> {
            if (isNetworkCountry((Intent) p.thisObject) && p.getResult() instanceof Bundle extras) {
                Bundle copy = new Bundle(extras);
                copy.putString("android.telephony.extra.NETWORK_COUNTRY", profile.get("network_country", ""));
                p.setResult(copy);
            }
        });
        optionalFusedLocation(loader);
    }

    private void optionalFusedLocation(ClassLoader loader) {
        // Current target APK has no unobfuscated FLP entry point. Future compatible clients are
        // intercepted through the concrete object returned by the factory, never an interface.
        String factory = "com.google.android.gms.location.LocationServices";
        if (XposedHelpers.findClassIfExists(factory, loader) == null) return;
        hook(factory, loader, "getFusedLocationProviderClient", false, p -> {
            Object client = p.getResult();
            if (client == null) return;
            p.setThrowable(new SecurityException("Location client unavailable by privacy policy"));
            int protectedMethods = 0;
            for (Class<?> type = client.getClass(); type != null && type != Object.class;
                    type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (!Set.of("getLastLocation", "getCurrentLocation", "getLocationAvailability",
                            "requestLocationUpdates", "flushLocations").contains(method.getName())
                            || java.lang.reflect.Modifier.isAbstract(method.getModifiers())) continue;
                    String key = "fused:" + type.getName() + "#" + method.getName();
                    synchronized (reported) {
                        if (reported.contains(key)) { protectedMethods++; continue; }
                        Set<XC_MethodHook.Unhook> installed = XposedBridge.hookAllMethods(type, method.getName(), new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam call) {
                                SecurityException denied = new SecurityException("Location unavailable by privacy policy");
                                call.setThrowable(denied);
                                try {
                                    Class<?> tasks = XposedHelpers.findClass("com.google.android.gms.tasks.Tasks", loader);
                                    Object result = XposedHelpers.callStaticMethod(tasks, "forException", denied);
                                    if (((Method) call.method).getReturnType().isInstance(result)) call.setResult(result);
                                } catch (Throwable ignored) {
                                    // Keep the installed denial; never invoke the real provider.
                                }
                            }
                        });
                        if (installed.isEmpty()) continue;
                        hookCount += installed.size();
                        reported.add(key); protectedMethods++;
                    }
                }
            }
            if (protectedMethods > 0) p.setResult(client);
        });
        String result = "com.google.android.gms.location.LocationResult";
        if (XposedHelpers.findClassIfExists(result, loader) != null) {
            hook(result, loader, "getLastLocation", true, p -> p.setResult(null));
            hook(result, loader, "getLocations", true, p -> p.setResult(Collections.emptyList()));
        }
    }

    private static boolean isNetworkCountry(Intent intent) {
        return "android.telephony.action.NETWORK_COUNTRY_CHANGED".equals(intent.getAction());
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
                if (receiver != null && receiver.getClass().getName().equals("h2a0")) {
                    AuditHooks.blocked("telemetry", "sift_collect", "v6g#invokeSuspend", "profile.block_sift");
                    p.setResult(XposedHelpers.getStaticObjectField(XposedHelpers.findClass("dpf0", loader), "a"));
                }
            });
            hook("h2a0", loader, "b", true, p -> {
                AuditHooks.blocked("telemetry", "sift_queue", "h2a0#b", "profile.block_sift");
                p.setResult(null);
            }); // verified void event queue entry
            hook("g2a0", loader, "invokeSuspend", true, p -> {
                AuditHooks.blocked("telemetry", "sift_dispatch", "g2a0#invokeSuspend", "profile.block_sift");
                p.setResult(XposedHelpers.getStaticObjectField(XposedHelpers.findClass("dpf0", loader), "a"));
            });
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
                if (p.args.length > 0 && p.args[0] instanceof String host && profile.blocksHost(host)) {
                    AuditLog.record("network", "dns_lookup", "EnvironmentHook", "blocked", Map.of("host", host, "reason", "profile.blocked_hosts"));
                    p.setThrowable(new UnknownHostException("Blocked by fkclaude profile"));
                }
            });
        // Both synchronous and async calls reach ko10.f() in the analyzed build.
        hook("ko10", loader, "f", true, p -> {
            Object request = XposedHelpers.getObjectField(p.thisObject, "b");
            Object url = XposedHelpers.getObjectField(request, "a");
            String host = (String) XposedHelpers.getObjectField(url, "d");
            String path = (String) XposedHelpers.callMethod(url, "b");
            if (profile.blocksHost(host) || (profile.flag("block_sift") && path.matches("/v3/accounts/[^/]+/mobile_events"))) {
                AuditLog.record(AuditClassifier.category("UNKNOWN", host, path), "http_request", "EnvironmentHook", "blocked",
                        Map.of("host", host, "path", path, "reason", "profile.outbound_policy"));
                p.setThrowable(new IOException("Blocked by fkclaude profile"));
            }
        });
        hook("java.net.URL", loader, "openConnection", true, p -> {
            java.net.URL url = (java.net.URL) p.thisObject;
            if (profile.blocksHost(url.getHost()) || (profile.flag("block_sift") && url.getPath().matches("/v3/accounts/[^/]+/mobile_events"))) {
                AuditLog.record(AuditClassifier.category("UNKNOWN", url.getHost(), url.getPath()), "connection_created", "EnvironmentHook", "blocked",
                        Map.of("url", url.toString(), "reason", "profile.outbound_policy"));
                p.setThrowable(new IOException("Blocked by fkclaude profile"));
            }
        });
    }
    private static void log(String message) { XposedBridge.log("fkclaude: " + message); }
}
