package io.github.lightjunction.fkclaude;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/** Immutable, process-wide snapshot. Missing keys mean passthrough, never random per call. */
public final class Profile {
    public static final String TARGET = "com.anthropic.claude";
    public static final long TARGET_VERSION = 26111218L;
    private final Map<String, String> values;

    public Profile(Map<String, String> source) {
        if (source.size() > 256) throw new IllegalArgumentException("Too many profile fields");
        for (var entry : source.entrySet()) {
            if (!Schema.isKnown(entry.getKey())) throw new IllegalArgumentException("Unknown field: " + entry.getKey());
            if (entry.getValue() == null || entry.getValue().length() > 4096)
                throw new IllegalArgumentException("Invalid value: " + entry.getKey());
            validate(entry.getKey(), entry.getValue());
        }
        values = Collections.unmodifiableMap(UsPrivacyPolicy.enforce(source));
        for (var entry : values.entrySet()) {
            if (entry.getValue().length() > 4096) throw new IllegalArgumentException("Expanded profile field too large");
            validate(entry.getKey(), entry.getValue());
        }
        Map<String, String> properties = PropertyView.parse(get("system_properties", ""));
        for (String[] binding : PropertyView.BINDINGS)
            if (!has(binding[1]) && properties.containsKey(binding[0])) validate(binding[1], properties.get(binding[0]));
        if (has("memory_total") && has("memory_available") && number("memory_available") > number("memory_total"))
            throw new IllegalArgumentException("Available memory exceeds total");
        if (has("storage_total") && has("storage_available") && number("storage_available") > number("storage_total"))
            throw new IllegalArgumentException("Available storage exceeds total");
    }

    public boolean has(String key) { return values.containsKey(key); }
    public String get(String key) { return values.get(key); }
    public String get(String key, String fallback) { return values.getOrDefault(key, fallback); }
    public boolean flag(String key) { return Boolean.parseBoolean(get(key, "false")); }
    public long number(String key) { return Long.parseLong(get(key)); }
    public Map<String, String> copy() { return new HashMap<>(values); }
    public boolean enabled() { return flag("enabled"); }

    public static Profile parse(String text) {
        if (text == null || text.length() > 65536) throw new IllegalArgumentException("Invalid profile size");
        Properties props = new Properties();
        try { props.load(new StringReader(text)); }
        catch (IOException e) { throw new IllegalArgumentException(e); }
        Map<String, String> result = new HashMap<>();
        for (String key : props.stringPropertyNames()) result.put(key, props.getProperty(key));
        return new Profile(result);
    }

    public String encode() {
        Properties props = new Properties();
        props.putAll(values);
        StringWriter writer = new StringWriter();
        try { props.store(writer, null); }
        catch (IOException e) { throw new IllegalStateException(e); }
        // Properties supplies the escaping. Remove its wall-clock comment and sort complete
        // physical records so snapshots never carry the module process's real time zone.
        java.util.List<String> records = new java.util.ArrayList<>();
        for (String line : writer.toString().split("\\r?\\n"))
            if (!line.startsWith("#") && !line.isEmpty()) records.add(line);
        java.util.Collections.sort(records);
        return records.isEmpty() ? "" : String.join("\n", records) + "\n";
    }

    public static Profile defaults() {
        return defaults(null);
    }

    /** Applying the preset never changes an existing identity or weakens an enabled IP guard. */
    public static Profile defaults(Profile existing) {
        Map<String, String> map = new HashMap<>();
        map.put("enabled", "true");
        randomize(map);
        map.put("native_enabled", "true"); map.put("command_isolation", "true"); map.put("web_enabled", "true");
        map.put("block_web_fingerprinting", "false");
        map.put("ip_guard_enabled", "false");
        map.put("ip_guard_probe_url", IpGuardDefaults.PROBE);
        map.put("ip_guard_interval", "3");
        for (String key : new String[]{"block_sensors", "block_location", "block_personal_data", "block_package_list", "block_sift"})
            map.put(key, "true");
        for (String key : new String[]{"carrier_name", "sim_phone_number", "sim_operator", "network_operator", "sim_country", "network_country"})
            map.put(key, "");
        map.put("wifi_ssid", "<unknown ssid>");
        map.put("wifi_bssid", "02:00:00:00:00:00");
        map.put("wifi_mac", "02:00:00:00:00:00");
        map.put("local_ip", "");
        map.put("network_mac", "02:00:00:00:00:00");
        // Browser intake hosts are hyphenated, not subdomains of datadoghq.com (q8g in the sample).
        map.put("blocked_hosts", UsPrivacyPolicy.TELEMETRY_HOSTS);
        if (existing != null) {
            for (String key : new String[]{"claude_device_id", "android_id"}) if (existing.has(key)) map.put(key, existing.get(key));
            existing.copy().forEach((key, value) -> { if (key.startsWith("ip_guard_") || key.equals("privacy_us_only")) map.put(key, value); });
            if (existing.flag("privacy_us_only")) map.putAll(existing.copy());
        }
        return new Profile(map);
    }

    /** Explicit opt-in policy; an empty exit allowlist stays blocked. */
    public static Profile usPrivacy(Profile existing) {
        Map<String, String> map = defaults(existing).copy();
        if (existing != null) map.putAll(existing.copy());
        map.put("privacy_us_only", "true");
        return new Profile(map);
    }

    public static void randomize(Map<String, String> map) {
        map.put("claude_device_id", UUID.randomUUID().toString());
        map.put("android_id", String.format(Locale.ROOT, "%016x", new SecureRandom().nextLong()));
    }

    public boolean blocksHost(String host) {
        if (host == null) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".")) lower = lower.substring(0, lower.length() - 1);
        for (String domain : get("blocked_hosts", "").split(",")) {
            String d = domain.trim().toLowerCase(Locale.ROOT);
            if (!d.isEmpty() && (lower.equals(d) || lower.endsWith("." + d))) return true;
        }
        return false;
    }

    public boolean allowsPackage(String name) {
        if (name == null) return false;
        if (Set.of(TARGET, "android", "com.google.android.gms", "com.android.vending",
                "io.github.lightjunction.fkclaude").contains(name)) return true;
        for (String item : get("visible_packages", "").split(","))
            if (!item.isBlank() && name.equals(item.trim())) return true;
        return false;
    }

    private static void validate(String key, String value) {
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("Embedded NUL");
        String type = Schema.type(key);
        if ("bool".equals(type) && !value.equals("true") && !value.equals("false"))
            throw new IllegalArgumentException(key + ": true / false");
        if ("long".equals(type) && Long.parseLong(value) < 0) throw new IllegalArgumentException(key + ": >= 0");
        if ("float".equals(type)) {
            double d = Double.parseDouble(value);
            if (!Double.isFinite(d) || !Float.isFinite(Float.parseFloat(value))) throw new IllegalArgumentException(key + ": finite number required");
        }
        if (key.equals("claude_device_id") && !value.isEmpty() && !UUID.fromString(value).toString().equals(value))
            throw new IllegalArgumentException("claude_device_id: canonical UUID required");
        if (key.equals("android_id") && !value.isEmpty() && !value.matches("[0-9a-f]{16}"))
            throw new IllegalArgumentException("android_id: 16 lowercase hex digits");
        if (key.equals("sim_phone_number") && !value.isEmpty() && !value.matches("\\+[1-9][0-9]{1,14}"))
            throw new IllegalArgumentException("sim_phone_number: E.164 number or empty required");
        if (key.equals("timezone") && !Set.of(TimeZone.getAvailableIDs()).contains(value))
            throw new IllegalArgumentException("Unknown timezone");
        if (key.equals("locale") && (value.isEmpty() || Locale.forLanguageTag(value).getLanguage().isEmpty()
                || !Locale.forLanguageTag(value).toLanguageTag().equalsIgnoreCase(value)))
            throw new IllegalArgumentException("Invalid locale");
        if (key.equals("latitude") && Math.abs(Double.parseDouble(value)) > 90) throw new IllegalArgumentException("latitude");
        if (key.equals("longitude") && Math.abs(Double.parseDouble(value)) > 180) throw new IllegalArgumentException("longitude");
        if ((key.equals("network_mac") || key.equals("wifi_mac") || key.equals("wifi_bssid"))
                && !value.isEmpty() && !value.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))
            throw new IllegalArgumentException("Invalid MAC address");
        if (key.equals("local_ip") && !value.isEmpty()) {
            // Numeric IPv4 only: validating a profile must never perform DNS queries.
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) throw new IllegalArgumentException("IPv4 required");
            for (String part : parts) if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255)
                throw new IllegalArgumentException("Invalid IPv4");
        }
        if (key.equals("battery_level") && Long.parseLong(value) > 100) throw new IllegalArgumentException("battery_level: 0..100");
        if (key.equals("battery_health") && (Long.parseLong(value) < 1 || Long.parseLong(value) > 7))
            throw new IllegalArgumentException("battery_health: 1..7");
        if (key.equals("sdk_level") && (Long.parseLong(value) < 24 || Long.parseLong(value) > 99))
            throw new IllegalArgumentException("sdk_level: 24..99");
        if (key.equals("cpu_count") && (Long.parseLong(value) < 1 || Long.parseLong(value) > 256))
            throw new IllegalArgumentException("cpu_count: 1..256");
        if (Set.of("screen_width", "screen_height", "screen_dpi", "network_type", "network_subtype", "network_transport").contains(key)
                && Long.parseLong(value) > Integer.MAX_VALUE) throw new IllegalArgumentException("Integer overflow");
        if (key.equals("network_transport") && Long.parseLong(value) > 10) throw new IllegalArgumentException("network_transport");
        if (key.equals("location_accuracy") && Float.parseFloat(value) < 0) throw new IllegalArgumentException("location_accuracy");
        if (key.equals("ip_guard_cidrs")) new IpAllowlist(value);
        if (key.equals("ip_guard_probe_url")) ProbeResponse.endpoint(value);
        if (key.equals("ip_guard_interval") && (Long.parseLong(value) < 1 || Long.parseLong(value) > 5))
            throw new IllegalArgumentException("ip_guard_interval: 1..5 seconds");
        if (key.equals("web_device_memory") && (Float.parseFloat(value) < 0 || Float.parseFloat(value) > 1024))
            throw new IllegalArgumentException("web_device_memory");
        if (Set.of("native_sysname", "native_hostname", "native_kernel_release", "native_kernel_version", "native_machine").contains(key)
                && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64)
            throw new IllegalArgumentException("uname field too long");
        if (key.equals("native_env")) for (var entry : PropertyView.parse(value).entrySet()) {
            if (!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*") || entry.getValue().indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid environment entry");
        }
        if (key.equals("system_properties")) for (var entry : PropertyView.parse(value).entrySet()) {
            if (!entry.getKey().matches("[A-Za-z0-9_.:-]{1,127}") || entry.getValue().indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid property entry");
        }
        if ((key.equals("screen_width") || key.equals("screen_height") || key.equals("screen_dpi")
                || key.equals("screen_density")) && Double.parseDouble(value) <= 0)
            throw new IllegalArgumentException(key + ": > 0");
    }
}
