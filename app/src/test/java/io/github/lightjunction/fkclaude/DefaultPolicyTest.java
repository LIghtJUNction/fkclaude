package io.github.lightjunction.fkclaude;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Map;

public class DefaultPolicyTest {
    @Test public void defaultDoesNotInventARegionOrDisableWebDrawing() {
        Profile profile = Profile.defaults();
        assertFalse(profile.has("locale")); assertFalse(profile.has("timezone"));
        assertFalse(profile.flag("block_web_fingerprinting"));
        assertEquals("", profile.get("local_ip"));
        assertFalse(profile.flag("ip_guard_enabled"));
    }
    @Test public void applyingPresetKeepsIdentityAndGuardRestrictions() {
        Profile current = Profile.defaults();
        Map<String,String> values = current.copy();
        values.put("ip_guard_enabled", "true"); values.put("ip_guard_cidrs", "192.0.2.0/24\n2001:db8::/32");
        values.put("ip_guard_interval", "5");
        Profile preset = Profile.defaults(new Profile(values));
        assertEquals(current.get("claude_device_id"), preset.get("claude_device_id"));
        assertEquals(current.get("android_id"), preset.get("android_id"));
        assertTrue(preset.flag("ip_guard_enabled"));
        assertEquals(values.get("ip_guard_cidrs"), preset.get("ip_guard_cidrs"));
        assertEquals("5", preset.get("ip_guard_interval"));
        assertEquals(preset.copy(), Profile.defaults(preset).copy());
    }
    @Test public void existingSavedPreferencesAreNotMigratedIntoNewDefaults() {
        Profile existing = new Profile(Map.of("locale", "en-US", "timezone", "UTC", "block_web_fingerprinting", "true"));
        assertEquals(existing.copy(), Profile.parse(existing.encode()).copy());
    }
    @Test public void guardValidationFailsClosedWithoutBroadeningRules() {
        new Profile(Map.of("enabled", "false", "ip_guard_enabled", "true", "ip_guard_cidrs", ""));
        assertThrows(IllegalArgumentException.class, () -> new Profile(Map.of("ip_guard_cidrs", "allow.example")));
        assertThrows(IllegalArgumentException.class, () -> new Profile(Map.of("ip_guard_probe_url", "http://claude.ai/cdn-cgi/trace")));
        assertThrows(IllegalArgumentException.class, () -> new Profile(Map.of("ip_guard_interval", "0")));
        assertThrows(IllegalArgumentException.class, () -> new Profile(Map.of("ip_guard_interval", "6")));
    }
}
