package io.github.lightjunction.fkclaude;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfileEncodingTest {
    @Test public void encodingHasNoWallClockMetadataAndIgnoresDefaultRegion() {
        Profile profile = new Profile(Map.of("enabled", "true", "carrier_name", "test carrier"));
        Locale originalLocale = Locale.getDefault();
        TimeZone originalZone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.JAPAN); TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            String first = profile.encode();
            Locale.setDefault(Locale.GERMANY); TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            String second = profile.encode();
            assertEquals(first, second);
            for (String line : first.split("\n")) assertFalse(line.startsWith("#"));
            assertEquals("carrier_name=test carrier\nenabled=true\n", first);
        } finally {
            Locale.setDefault(originalLocale); TimeZone.setDefault(originalZone);
        }
    }
    @Test public void standardPropertiesEscapingAndUnicodeRoundTripRemainIntact() {
        Profile profile = new Profile(Map.of("enabled", "true", "carrier_name", "  空格:=#!\\\t\n引号\"é😀",
                "wifi_ssid", "测试网络", "java_properties", "属性键=属性值\nkey.with\\=escape=value"));
        String encoded = profile.encode();
        assertEquals(profile.copy(), Profile.parse(encoded).copy());
        ArrayList<String> lines = new ArrayList<>(Arrays.asList(encoded.split("\n")));
        ArrayList<String> sorted = new ArrayList<>(lines); Collections.sort(sorted);
        assertEquals(sorted, lines);
        assertEquals(encoded, Profile.parse(encoded).encode());
    }
}
