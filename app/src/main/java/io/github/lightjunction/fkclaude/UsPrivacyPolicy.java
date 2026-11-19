package io.github.lightjunction.fkclaude;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Canonical restrictions for the explicit US-only profile, independent of Android. */
final class UsPrivacyPolicy {
    static final String TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace";
    static final String LOCALE = "en-US";
    static final String TIMEZONE = "America/New_York";
    // Public test metadata, never a subscriber identity or proof of a network exit.
    // https://www.nanpa.com/numbering/555-line-numbers
    static final String SIM_PHONE_NUMBER = "+12025550142";
    // AOSP's emulator operator table maps 310260 to T-Mobile.
    static final String US_OPERATOR = "310260";
    static final String US_CARRIER = "T-Mobile";
    // https://developers.google.com/android/drivers#shiba
    static final String PIXEL_MODEL = "Pixel 8";
    static final String PIXEL_DEVICE = "shiba";
    static final String TELEMETRY_HOSTS =
            "sentry.io,datadoghq.com,datadoghq.eu,"
            + "browser-intake-datadoghq.com,browser-intake-datadoghq.eu,"
            + "browser-intake-us3-datadoghq.com,browser-intake-us5-datadoghq.com,"
            + "browser-intake-ap1-datadoghq.com,browser-intake-ap2-datadoghq.com,"
            + "browser-intake-uk1-datadoghq.com,browser-intake-ddog-gov.com,"
            + "browser-intake-us2-ddog-gov.com,browser-intake-datad0g.com,"
            + "firebaselogging.googleapis.com,firebaselogging-pa.googleapis.com,app-measurement.com";

    private UsPrivacyPolicy() {}

    static Map<String, String> enforce(Map<String, String> source) {
        Map<String, String> values = new HashMap<>(source);
        if (!Boolean.parseBoolean(source.get("privacy_us_only"))) return values;
        for (String key : new String[]{"enabled", "native_enabled", "command_isolation", "web_enabled",
                "ip_guard_enabled", "block_location", "block_personal_data", "block_package_list",
                "block_clipboard", "block_sensors", "block_sift", "block_web_fingerprinting"})
            values.put(key, "true");
        values.put("locale", LOCALE);
        values.put("timezone", TIMEZONE);
        values.put("sim_country", "us");
        values.put("network_country", "us");
        values.put("sim_phone_number", SIM_PHONE_NUMBER);
        values.put("carrier_name", US_CARRIER);
        values.put("sim_operator", US_OPERATOR);
        values.put("network_operator", US_OPERATOR);
        values.put("build.MANUFACTURER", "Google");
        values.put("build.BRAND", "google");
        values.put("build.MODEL", PIXEL_MODEL);
        values.put("build.DEVICE", PIXEL_DEVICE);
        values.put("build.PRODUCT", PIXEL_DEVICE);
        // WebView/provider selection must observe the real runtime API level.
        values.remove("sdk_level");
        // Unverified firmware identity is unavailable, never a made-up signed Pixel build.
        // Do not change the runtime API level, OS release or screen compatibility settings.
        for (String key : new String[]{"BOARD", "HARDWARE", "BOOTLOADER", "FINGERPRINT", "DISPLAY",
                "ID", "TAGS", "TYPE", "USER", "HOST"})
            values.put("build." + key, "");
        for (String key : new String[]{"local_ip", "serial"}) values.put(key, "");
        values.putIfAbsent("claude_device_id", "");
        values.putIfAbsent("android_id", "");
        values.put("wifi_ssid", "<unknown ssid>");
        for (String key : new String[]{"wifi_bssid", "wifi_mac", "network_mac"})
            values.put(key, "02:00:00:00:00:00");
        for (String key : new String[]{"latitude", "longitude", "location_accuracy"}) values.remove(key);
        values.put("web_origins", "*");
        // Strict WebView derives a safe UA from the installed engine; never persist a
        // guessed browser version or retain an old custom OEM user-agent string.
        values.remove("user_agent");
        values.put("web_platform", "Android");
        values.put("web_vendor", "Google Inc.");
        Map<String, String> javaProperties = new TreeMap<>(
                PropertyView.parse(values.getOrDefault("java_properties", "")));
        javaProperties.put("http.agent", "");
        values.put("java_properties", encode(javaProperties));
        // Never discover and trust the current address automatically.
        values.putIfAbsent("ip_guard_cidrs", "");
        values.put("ip_guard_probe_url", TRACE_URL);
        long interval = Long.parseLong(values.getOrDefault("ip_guard_interval", "3"));
        values.put("ip_guard_interval", Long.toString(Math.min(3, interval)));

        Map<String, String> properties = new TreeMap<>(PropertyView.parse(values.getOrDefault("system_properties", "")));
        // normalize() imports this alias, so dropping only sdk_level is insufficient.
        properties.remove("ro.build.version.sdk");
        properties.put("persist.sys.locale", LOCALE);
        properties.put("ro.product.locale", LOCALE);
        for (String key : new String[]{"persist.sys.language", "ro.product.locale.language"}) properties.put(key, "en");
        for (String key : new String[]{"persist.sys.country", "ro.product.locale.region"}) properties.put(key, "US");
        properties.put("persist.sys.timezone", TIMEZONE);
        for (String key : new String[]{"gsm.operator.iso-country", "gsm.sim.operator.iso-country"}) properties.put(key, "us");
        for (String key : new String[]{"gsm.operator.numeric", "gsm.sim.operator.numeric"})
            properties.put(key, US_OPERATOR);
        for (String key : new String[]{"gsm.operator.alpha", "gsm.sim.operator.alpha"})
            properties.put(key, US_CARRIER);
        values.put("system_properties", encode(properties));
        Map<String, String> environment = new TreeMap<>(PropertyView.parse(values.getOrDefault("native_env", "")));
        environment.put("TZ", TIMEZONE);
        environment.put("LANG", "en_US.UTF-8");
        environment.put("LC_ALL", "en_US.UTF-8");
        values.put("native_env", encode(environment));

        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        for (String list : new String[]{values.getOrDefault("blocked_hosts", ""), TELEMETRY_HOSTS})
            for (String host : list.split(",")) if (!host.isBlank()) hosts.add(host.trim());
        values.put("blocked_hosts", String.join(",", hosts));
        return values;
    }

    private static String encode(Map<String, String> values) {
        // Preserve Properties escaping so a custom value cannot become a new property line.
        Properties properties = new Properties();
        properties.putAll(values);
        StringWriter writer = new StringWriter();
        try { properties.store(writer, null); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
        java.util.List<String> records = new java.util.ArrayList<>();
        for (String line : writer.toString().split("\\r?\\n"))
            if (!line.startsWith("#") && !line.isEmpty()) records.add(line);
        java.util.Collections.sort(records);
        return records.isEmpty() ? "" : String.join("\n", records) + "\n";
    }
}
