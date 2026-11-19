package io.github.lightjunction.fkclaude;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class UsPrivacyProfileTest {
    @Test public void ordinaryProfilesRemainExplicitOptIn() {
        Profile regular = Profile.defaults();
        assertFalse(regular.flag("privacy_us_only"));
        assertFalse(regular.has("locale"));
        assertFalse(regular.has("timezone"));
        assertFalse(regular.flag("ip_guard_enabled"));
    }

    @Test public void strictProfileRejectsConflictingProtectionValues() {
        Map<String,String> values = new HashMap<>();
        values.put("privacy_us_only", "true");
        for (String key : new String[]{"enabled","ip_guard_enabled","block_location","web_enabled","block_sift"})
            values.put(key, "false");
        values.put("locale","ja-JP");
        values.put("timezone","Asia/Tokyo");
        values.put("sim_country","jp");
        values.put("network_country","jp");
        values.put("latitude","35");
        values.put("longitude","139");
        Profile p = new Profile(values);
        for (String key : new String[]{"enabled","ip_guard_enabled","block_location","web_enabled","block_sift",
                "block_personal_data","block_clipboard","block_web_fingerprinting"})
            assertTrue(key, p.flag(key));
        assertEquals("en-US",p.get("locale"));
        assertEquals("America/New_York",p.get("timezone"));
        assertEquals("us",p.get("sim_country"));
        assertEquals("us",p.get("network_country"));
        assertFalse(p.has("latitude"));
        assertFalse(p.has("longitude"));
        assertEquals(p.copy(),Profile.parse(p.encode()).copy());
    }

    @Test public void countryPolicyNeverCreatesOrBroadensAnAllowlist() {
        Profile empty = Profile.usPrivacy(new Profile(Map.of("enabled","false")));
        assertEquals("",empty.get("ip_guard_cidrs"));
        assertTrue(new IpAllowlist(empty.get("ip_guard_cidrs")).isEmpty());
        Profile restricted = Profile.usPrivacy(new Profile(Map.of("ip_guard_cidrs","192.0.2.5/32;2001:db8::5/128",
                "ip_guard_probe_url","https://example.com/ip","ip_guard_interval","1")));
        assertEquals("192.0.2.5/32;2001:db8::5/128",restricted.get("ip_guard_cidrs"));
        assertEquals(UsPrivacyPolicy.TRACE_URL,restricted.get("ip_guard_probe_url"));
        assertEquals("1",restricted.get("ip_guard_interval"));
        assertFalse(new IpAllowlist(restricted.get("ip_guard_cidrs")).matches("192.0.2.6"));
    }

    @Test public void applyingEitherPresetPreservesExistingStrictPolicyAndIdentity() {
        Profile first = Profile.usPrivacy(Profile.defaults());
        Profile second = Profile.usPrivacy(first);
        Profile regularButton = Profile.defaults(first);
        assertEquals(first.copy(),second.copy());
        assertEquals(first.copy(),regularButton.copy());
        assertEquals(first.get("claude_device_id"),second.get("claude_device_id"));
        assertEquals(first.get("android_id"),second.get("android_id"));
        Map<String,String> disabled=first.copy();disabled.put("privacy_us_only","false");
        assertFalse(new Profile(disabled).flag("privacy_us_only"));
    }

    @Test public void systemPropertiesAndNativeEnvironmentCannotRestoreRealRegion() {
        Profile p = new Profile(Map.of("privacy_us_only","true",
                "system_properties","persist.sys.locale=ja-JP\npersist.sys.timezone=Asia/Tokyo\ngsm.operator.iso-country=jp\ncustom.property=keep",
                "native_env","TZ=Asia/Tokyo\nLANG=ja_JP.UTF-8\nLC_ALL=ja_JP.UTF-8\nCUSTOM=keep"));
        Map<String,String> properties=PropertyView.parse(p.get("system_properties"));
        Map<String,String> environment=PropertyView.parse(p.get("native_env"));
        assertEquals("en-US",properties.get("persist.sys.locale"));
        assertEquals("US",properties.get("ro.product.locale.region"));
        assertEquals("America/New_York",properties.get("persist.sys.timezone"));
        assertEquals("us",properties.get("gsm.operator.iso-country"));
        assertEquals("keep",properties.get("custom.property"));
        assertEquals("America/New_York",environment.get("TZ"));
        assertEquals("en_US.UTF-8",environment.get("LC_ALL"));
        assertEquals("keep",environment.get("CUSTOM"));
    }

    @Test public void nearbyIdentifiersAndRequiredTelemetryRemainSuppressed() {
        Profile p = new Profile(Map.of("privacy_us_only","true","blocked_hosts","private.example",
                "wifi_bssid","aa:bb:cc:dd:ee:ff","local_ip","192.0.2.9","serial","placeholder"));
        assertEquals("02:00:00:00:00:00",p.get("wifi_bssid"));
        assertEquals("",p.get("local_ip"));
        assertEquals("",p.get("serial"));
        assertTrue(p.blocksHost("private.example"));
        assertTrue(p.blocksHost("browser-intake-us5-datadoghq.com"));
        assertTrue(p.blocksHost("firebaselogging.googleapis.com"));
        assertFalse(p.blocksHost("api.anthropic.com"));
    }

    @Test public void invalidSavedRulesStillFailInsteadOfWeakeningPolicy() {
        assertThrows(IllegalArgumentException.class,()->new Profile(Map.of("privacy_us_only","true",
                "ip_guard_cidrs","untrusted.example")));
        assertThrows(IllegalArgumentException.class,()->new Profile(Map.of("privacy_us_only","true",
                "ip_guard_interval","0")));
        assertThrows(IllegalArgumentException.class,()->new Profile(Map.of("privacy_us_only","true",
                "native_env","CUSTOM="+"a".repeat(4089))));
    }
}
