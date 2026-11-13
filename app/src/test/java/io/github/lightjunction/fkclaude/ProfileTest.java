package io.github.lightjunction.fkclaude;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ProfileTest {
    @Test public void idsStayStableAcrossIndependentReaders() {
        Profile initial = Profile.defaults();
        Profile processA = Profile.parse(initial.encode());
        Profile processB = Profile.parse(initial.encode());
        assertEquals(initial.copy(), processA.copy());
        assertEquals(processA.copy(), processB.copy());
        assertEquals(4, UUID.fromString(processA.get("claude_device_id")).version());
        assertEquals(2, UUID.fromString(processA.get("claude_device_id")).variant());
        assertTrue(processA.get("android_id").matches("[0-9a-f]{16}"));
        assertTrue(processA.flag("block_sift"));
    }
    @Test public void manualRandomizationChangesIdsOnly() {
        Profile initial = Profile.defaults();
        Map<String, String> changed = initial.copy();
        Profile.randomize(changed);
        assertNotEquals(initial.get("claude_device_id"), changed.get("claude_device_id"));
        assertNotEquals(initial.get("android_id"), changed.get("android_id"));
        changed.put("claude_device_id", initial.get("claude_device_id"));
        changed.put("android_id", initial.get("android_id"));
        assertEquals(initial.copy(), changed);
    }
    @Test public void serializedValuesCannotInjectAnotherField() {
        Profile p = new Profile(Map.of("carrier_name", "line1\nandroid_id=deadbeefdeadbeef\n中文=\\test"));
        Profile copy = Profile.parse(p.encode());
        assertEquals(p.copy(), copy.copy());
        assertFalse(copy.has("android_id"));
    }
    @Test public void inputMapAndSnapshotsAreIsolated() {
        HashMap<String, String> map = new HashMap<>(Map.of("carrier_name", "one"));
        Profile p = new Profile(map);
        map.put("carrier_name", "two"); p.copy().put("carrier_name", "three");
        assertEquals("one", p.get("carrier_name"));
    }
    @Test public void missingMeansPassthroughButEmptyIsAnExplicitOverride() {
        Profile p = new Profile(Map.of("android_id", ""));
        assertTrue(p.has("android_id")); assertEquals("", p.get("android_id"));
        assertFalse(p.has("claude_device_id"));
        assertFalse(p.enabled());
    }
    @Test public void blockedDomainsRespectBoundariesAndCase() {
        Profile p = new Profile(Map.of("blocked_hosts", "sentry.io, telemetry.example"));
        assertTrue(p.blocksHost("SENTRY.IO."));
        assertTrue(p.blocksHost("o123.ingest.sentry.io"));
        assertTrue(p.blocksHost("telemetry.example"));
        assertFalse(p.blocksHost("notsentry.io"));
        assertFalse(p.blocksHost("sentry.io.attacker.example"));
        assertFalse(p.blocksHost("claude.ai"));
        assertFalse(p.blocksHost(null));
    }
    @Test public void packageFilterKeepsRequiredServicesAndExplicitAllowlist() {
        Profile p = new Profile(Map.of("visible_packages", "com.example.one, com.example.two"));
        assertTrue(p.allowsPackage(Profile.TARGET)); assertTrue(p.allowsPackage("com.google.android.gms"));
        assertTrue(p.allowsPackage("com.example.two")); assertFalse(p.allowsPackage("com.example"));
        assertFalse(p.allowsPackage(null));
    }
    @Test public void presetBlocksActualHyphenatedDatadogHosts() {
        Profile p = Profile.defaults();
        assertTrue(p.blocksHost("browser-intake-datadoghq.com"));
        assertTrue(p.blocksHost("sdk-configuration.browser-intake-ap1-datadoghq.com"));
        assertTrue(p.blocksHost("o123.ingest.us.sentry.io"));
        assertFalse(p.blocksHost("browser-intake-datadoghq.com.example.org"));
    }
    @Test public void allSchemaBlockValuesAreValid() {
        for (Schema.Field f : Schema.FIELDS) new Profile(Map.of(f.key(), f.blocked()));
    }
    @Test public void numericAddressesDoNotAcceptHostnamesOrInvalidOctets() {
        rejects("local_ip", "example.com"); rejects("local_ip", "256.1.2.3");
        rejects("local_ip", "1.2.3"); rejects("local_ip", "1.2.3.4.");
        new Profile(Map.of("local_ip", "192.0.2.10"));
    }
    @Test public void validationRejectsAmbiguousAndNonFiniteValues() {
        rejects("latitude", "91"); rejects("longitude", "-181");
        rejects("latitude", "NaN"); rejects("screen_density", "Infinity");
        rejects("screen_density", "1e100"); rejects("screen_width", "0");
        rejects("screen_width", "2147483648"); rejects("battery_level", "101");
        rejects("sdk_level", "0"); rejects("cpu_count", "0"); rejects("memory_total", "-1");
        rejects("network_transport", "99"); rejects("location_accuracy", "-1");
    }
    @Test public void validatesIdentifiersLanguageAndTimeZone() {
        rejects("android_id", "xyz"); rejects("android_id", "ABCDEFABCDEFABCD");
        rejects("claude_device_id", "1-1-1-1-1"); rejects("timezone", "Not/AZone");
        rejects("locale", "en_US"); rejects("locale", "");
        rejects("wifi_mac", "01:02:03:04:05"); rejects("enabled", "yes");
        rejects("unknown", "value");
        new Profile(Map.of("locale", "zh-CN", "timezone", "Asia/Taipei"));
    }
    @Test public void rejectsInconsistentMemoryAndStorage() {
        assertThrows(IllegalArgumentException.class, () -> new Profile(Map.of("memory_total", "10", "memory_available", "11")));
        assertThrows(IllegalArgumentException.class, () -> new Profile(Map.of("storage_total", "10", "storage_available", "11")));
    }
    @Test public void disablingDoesNotDiscardConfiguration() {
        Map<String, String> values = Profile.defaults().copy(); values.put("enabled", "false");
        Profile p = Profile.parse(new Profile(values).encode());
        assertFalse(p.enabled()); assertNotNull(p.get("claude_device_id"));
    }
    @Test public void rejectsOversizedConfiguration() {
        rejects("carrier_name", "x".repeat(4097));
        assertThrows(IllegalArgumentException.class, () -> Profile.parse("x".repeat(65537)));
    }
    private static void rejects(String key, String value) {
        assertThrows(key + "=" + value, IllegalArgumentException.class, () -> new Profile(Map.of(key, value)));
    }
}
