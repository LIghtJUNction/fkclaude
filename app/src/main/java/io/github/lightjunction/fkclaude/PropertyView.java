package io.github.lightjunction.fkclaude;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** One canonical mapping for Build, Java getters, native getters and getprop output. */
final class PropertyView {
    static final String[][] BINDINGS = {
            {"ro.product.manufacturer", "build.MANUFACTURER"}, {"ro.product.brand", "build.BRAND"},
            {"ro.product.model", "build.MODEL"}, {"ro.product.device", "build.DEVICE"},
            {"ro.product.name", "build.PRODUCT"}, {"ro.product.board", "build.BOARD"},
            {"ro.hardware", "build.HARDWARE"}, {"ro.bootloader", "build.BOOTLOADER"},
            {"ro.build.fingerprint", "build.FINGERPRINT"}, {"ro.build.display.id", "build.DISPLAY"},
            {"ro.build.id", "build.ID"}, {"ro.build.tags", "build.TAGS"}, {"ro.build.type", "build.TYPE"},
            {"ro.build.user", "build.USER"}, {"ro.build.host", "build.HOST"},
            {"ro.build.version.release", "os_release"}, {"ro.build.version.security_patch", "security_patch"},
            {"ro.build.version.sdk", "sdk_level"}, {"ro.serialno", "serial"}, {"ro.boot.serialno", "serial"},
            {"persist.sys.locale", "locale"}, {"persist.sys.timezone", "timezone"}
    };
    static Map<String, String> parse(String text) {
        Properties props = new Properties();
        try { props.load(new StringReader(text)); }
        catch (IOException e) { throw new IllegalArgumentException(e); }
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) values.put(key, props.getProperty(key));
        return values;
    }
    static Profile normalize(Profile profile) {
        Map<String, String> values = profile.copy();
        Map<String, String> properties = parse(profile.get("system_properties", ""));
        for (String[] binding : BINDINGS) if (!values.containsKey(binding[1]) && properties.containsKey(binding[0]))
            values.put(binding[1], properties.get(binding[0]));
        return new Profile(values);
    }
    static Map<String, String> properties(Profile profile) {
        Map<String, String> properties = parse(profile.get("system_properties", ""));
        for (String[] binding : BINDINGS) if (profile.has(binding[1])) properties.put(binding[0], profile.get(binding[1]));
        return properties;
    }
    static Map<String, String> environment(Profile profile) {
        Map<String, String> values = parse(profile.get("native_env", ""));
        if (profile.has("timezone")) values.put("TZ", profile.get("timezone"));
        if (profile.has("locale")) values.put("LANG", profile.get("locale").replace('-', '_') + ".UTF-8");
        return values;
    }
}
