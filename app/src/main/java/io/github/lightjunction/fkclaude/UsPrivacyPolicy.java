package io.github.lightjunction.fkclaude;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeMap;

/** Canonical restrictions for the explicit US-only profile, independent of Android. */
final class UsPrivacyPolicy {
    static final String TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace";
    static final String LOCALE = "en-US";
    static final String TIMEZONE = "America/New_York";
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
        for (String key : new String[]{"carrier_name", "sim_operator", "network_operator", "local_ip", "serial"})
            values.put(key, "");
        values.putIfAbsent("claude_device_id", "");
        values.putIfAbsent("android_id", "");
        values.put("wifi_ssid", "<unknown ssid>");
        for (String key : new String[]{"wifi_bssid", "wifi_mac", "network_mac"})
            values.put(key, "02:00:00:00:00:00");
        for (String key : new String[]{"latitude", "longitude", "location_accuracy"}) values.remove(key);
        values.put("web_origins", "*");
        // Never discover and trust the current address automatically.
        values.putIfAbsent("ip_guard_cidrs", "");
        values.put("ip_guard_probe_url", TRACE_URL);
        long interval = Long.parseLong(values.getOrDefault("ip_guard_interval", "3"));
        values.put("ip_guard_interval", Long.toString(Math.min(3, interval)));

        Map<String, String> properties = new TreeMap<>(PropertyView.parse(values.getOrDefault("system_properties", "")));
        properties.put("persist.sys.locale", LOCALE);
        properties.put("ro.product.locale", LOCALE);
        for (String key : new String[]{"persist.sys.language", "ro.product.locale.language"}) properties.put(key, "en");
        for (String key : new String[]{"persist.sys.country", "ro.product.locale.region"}) properties.put(key, "US");
        properties.put("persist.sys.timezone", TIMEZONE);
        for (String key : new String[]{"gsm.operator.iso-country", "gsm.sim.operator.iso-country"}) properties.put(key, "us");
        for (String key : new String[]{"gsm.operator.numeric", "gsm.sim.operator.numeric", "gsm.operator.alpha", "gsm.sim.operator.alpha"})
            properties.put(key, "");
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
        StringBuilder out = new StringBuilder();
        values.forEach((key, value) -> out.append(key).append('=').append(value).append('\n'));
        return out.toString();
    }
}
