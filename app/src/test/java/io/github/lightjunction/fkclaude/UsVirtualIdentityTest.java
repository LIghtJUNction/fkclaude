package io.github.lightjunction.fkclaude;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.Test;
import static org.junit.Assert.*;

public class UsVirtualIdentityTest {
    @Test public void strictProfileUsesOnlyTheReservedFictionalNumberAndUsCarrierMetadata() {
        Profile profile = new Profile(Map.of("privacy_us_only", "true",
                "sim_phone_number", "+12025550199", "sim_country", "jp",
                "network_country", "jp", "carrier_name", "Other carrier",
                "sim_operator", "44010", "network_operator", "44010"));
        assertEquals("+12025550142", profile.get("sim_phone_number"));
        assertTrue(profile.get("sim_phone_number").matches("\\+120255501[0-9]{2}"));
        assertEquals("us", profile.get("sim_country"));
        assertEquals("us", profile.get("network_country"));
        assertEquals("T-Mobile", profile.get("carrier_name"));
        assertEquals("310260", profile.get("sim_operator"));
        assertEquals("310260", profile.get("network_operator"));
        assertTrue(profile.flag("block_location"));
        assertFalse(profile.has("latitude"));
        assertFalse(profile.has("longitude"));
    }

    @Test public void strictPixelIdentityCannotRetainOriginalModelOrFirmwareIdentity() {
        Map<String, String> values = new HashMap<>();
        values.put("privacy_us_only", "true");
        for (String key : new String[]{"MANUFACTURER", "BRAND", "MODEL", "DEVICE", "PRODUCT",
                "BOARD", "HARDWARE", "BOOTLOADER", "FINGERPRINT", "DISPLAY", "ID",
                "TAGS", "TYPE", "USER", "HOST"}) values.put("build." + key, "original-placeholder");
        Profile profile = new Profile(values);
        assertEquals("Google", profile.get("build.MANUFACTURER"));
        assertEquals("google", profile.get("build.BRAND"));
        assertEquals("Pixel 8", profile.get("build.MODEL"));
        assertEquals("shiba", profile.get("build.DEVICE"));
        assertEquals("shiba", profile.get("build.PRODUCT"));
        for (String key : new String[]{"BOARD", "HARDWARE", "BOOTLOADER", "FINGERPRINT",
                "DISPLAY", "ID", "TAGS", "TYPE", "USER", "HOST"})
            assertEquals(key, "", profile.get("build." + key));
    }

    @Test public void primaryPropertiesMatchJavaIdentityAndUsMetadata() {
        Profile profile = new Profile(Map.of("privacy_us_only", "true", "system_properties",
                "ro.product.model=Other model\nro.build.fingerprint=original-placeholder\n"
                + "gsm.sim.operator.numeric=44010\ngsm.operator.alpha=Other carrier"));
        Map<String, String> properties = PropertyView.properties(profile);
        assertEquals("Pixel 8", properties.get("ro.product.model"));
        assertEquals("Google", properties.get("ro.product.manufacturer"));
        assertEquals("google", properties.get("ro.product.brand"));
        assertEquals("shiba", properties.get("ro.product.device"));
        assertEquals("shiba", properties.get("ro.product.name"));
        assertEquals("", properties.get("ro.build.fingerprint"));
        assertEquals("us", properties.get("gsm.operator.iso-country"));
        assertEquals("us", properties.get("gsm.sim.operator.iso-country"));
        assertEquals("310260", properties.get("gsm.operator.numeric"));
        assertEquals("310260", properties.get("gsm.sim.operator.numeric"));
        assertEquals("T-Mobile", properties.get("gsm.operator.alpha"));
        assertEquals("T-Mobile", properties.get("gsm.sim.operator.alpha"));
    }

    @Test public void ordinaryProfilesDoNotGainAPixelOrFictionalSimIdentity() {
        Map<String, String> values = Map.of("build.MODEL", "Custom device",
                "sim_phone_number", "+12025550199", "carrier_name", "Custom carrier");
        Profile ordinary = new Profile(values);
        assertEquals(values, ordinary.copy());
        assertFalse(new Profile(Map.of()).has("sim_phone_number"));
        Profile defaults = Profile.defaults();
        assertEquals("", defaults.get("sim_phone_number"));
        assertFalse(defaults.has("build.MODEL"));
        assertFalse(defaults.has("sdk_level"));
    }

    @Test public void presetPreservesVirtualIdsAllowlistAndRuntimeCompatibilitySettings() {
        Profile previous = new Profile(Map.of(
                "claude_device_id", "00000000-0000-4000-8000-000000000042",
                "android_id", "0011223344556677",
                "ip_guard_cidrs", "192.0.2.7/32;2001:db8::7/128",
                "sdk_level", "36", "os_release", "16", "security_patch", "2026-01-01",
                "screen_width", "1080", "system_properties", "custom.keep=yes"));
        Profile next = Profile.usPrivacy(previous);
        for (String key : new String[]{"claude_device_id", "android_id", "ip_guard_cidrs",
                "os_release", "security_patch", "screen_width"})
            assertEquals(key, previous.get(key), next.get(key));
        assertFalse(next.has("sdk_level"));
        assertEquals("yes", PropertyView.properties(next).get("custom.keep"));
        assertEquals(next.copy(), Profile.parse(next.encode()).copy());
        assertEquals(next.copy(), Profile.usPrivacy(next).copy());
        Profile empty = Profile.usPrivacy(new Profile(Map.of()));
        assertTrue(new IpAllowlist(empty.get("ip_guard_cidrs")).isEmpty());
    }

    @Test public void oldSdkOverridesCannotReappearWhenStrictProfilesAreNormalized() {
        Map<String, String> oldValues = Map.of("sdk_level", "24",
                "system_properties", "ro.build.version.sdk=25\ncustom.keep=yes");
        Profile ordinary = new Profile(oldValues);
        assertEquals(oldValues, ordinary.copy());
        assertEquals("24", PropertyView.normalize(ordinary).get("sdk_level"));
        Profile strict = Profile.usPrivacy(ordinary);
        assertFalse(strict.has("sdk_level"));
        assertFalse(PropertyView.parse(strict.get("system_properties")).containsKey("ro.build.version.sdk"));
        assertFalse(PropertyView.properties(strict).containsKey("ro.build.version.sdk"));
        assertFalse(PropertyView.normalize(strict).has("sdk_level"));
        assertEquals("yes", PropertyView.properties(strict).get("custom.keep"));
        assertEquals(strict.copy(), Profile.parse(strict.encode()).copy());

        Profile aliasOnly = new Profile(Map.of("privacy_us_only", "true",
                "system_properties", "ro.build.version.sdk=24"));
        assertFalse(PropertyView.normalize(aliasOnly).has("sdk_level"));
        assertFalse(PropertyView.properties(aliasOnly).containsKey("ro.build.version.sdk"));
    }

    @Test public void phoneOverridesAcceptOnlyEmptyOrUnambiguousInternationalDigits() {
        for (String value : new String[]{"", "+12025550142", "+12025550199"})
            assertEquals(value, new Profile(Map.of("sim_phone_number", value)).get("sim_phone_number"));
        for (String value : new String[]{"2025550142", "+1 202-555-0142", "+02025550142",
                "+1", "+1234567890123456", "+１２３４５６７８９"})
            assertThrows(IllegalArgumentException.class,
                    () -> new Profile(Map.of("sim_phone_number", value)));
    }

    @Test public void strictWebIdentityDiscardsSavedOemAgentsWithoutInventingAnEngineVersion() {
        Profile profile = new Profile(Map.of("privacy_us_only", "true",
                "user_agent", "old OEM browser", "web_platform", "Other platform",
                "web_vendor", "Other vendor",
                "java_properties", "http.agent=old OEM agent\ncustom.keep=yes"));
        assertFalse(profile.has("user_agent"));
        assertEquals("Android", profile.get("web_platform"));
        assertEquals("Google Inc.", profile.get("web_vendor"));
        Map<String, String> properties = PropertyView.parse(profile.get("java_properties"));
        assertEquals("", properties.get("http.agent"));
        assertEquals("yes", properties.get("custom.keep"));
        assertEquals(profile.copy(), Profile.parse(profile.encode()).copy());
        Profile ordinary = new Profile(Map.of("user_agent", "Custom browser"));
        assertEquals("Custom browser", ordinary.get("user_agent"));
    }

    @Test public void escapedCustomValuesCannotCreateNewProtectedProperties() throws Exception {
        String propertyValue = "Unicode值\\path\npersist.sys.locale=ja-JP\ngsm.operator.iso-country=jp";
        String environmentValue = "keep\nLC_ALL=ja_JP.UTF-8\nTZ=Asia/Tokyo";
        Properties properties = new Properties();
        properties.setProperty("zz.custom:key", propertyValue);
        Properties environment = new Properties();
        environment.setProperty("ZZ_CUSTOM", environmentValue);
        StringWriter propertyText = new StringWriter();
        StringWriter environmentText = new StringWriter();
        properties.store(propertyText, null);
        environment.store(environmentText, null);
        Profile profile = new Profile(Map.of("privacy_us_only", "true",
                "system_properties", propertyText.toString(), "native_env", environmentText.toString()));
        Map<String, String> actualProperties = PropertyView.parse(profile.get("system_properties"));
        Map<String, String> actualEnvironment = PropertyView.parse(profile.get("native_env"));
        assertEquals(propertyValue, actualProperties.get("zz.custom:key"));
        assertEquals("en-US", actualProperties.get("persist.sys.locale"));
        assertEquals("us", actualProperties.get("gsm.operator.iso-country"));
        assertEquals(environmentValue, actualEnvironment.get("ZZ_CUSTOM"));
        assertEquals("en_US.UTF-8", actualEnvironment.get("LC_ALL"));
        assertEquals("America/New_York", actualEnvironment.get("TZ"));
        assertFalse(profile.get("system_properties").startsWith("#"));
        assertFalse(profile.get("native_env").startsWith("#"));
        assertEquals(profile.encode(), Profile.parse(profile.encode()).encode());
    }
}
