package io.github.lightjunction.fkclaude;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PropertyIdentityTest {
    @Test public void strictPixelOverridesPersistedPartitionAliasesAndHardware() {
        Profile profile = new Profile(Map.of("privacy_us_only", "true", "system_properties",
                "ro.product.vendor.model=private-oem\nro.vendor.build.fingerprint=private-firmware\nro.soc.model=private-chip\n"));
        Map<String, String> properties = PropertyView.properties(profile);
        for (String partition : new String[]{"system", "system_ext", "vendor", "odm", "product"}) {
            assertEquals("Pixel 8", properties.get("ro.product." + partition + ".model"));
            assertEquals("google", properties.get("ro.product." + partition + ".brand"));
            assertEquals("Google", properties.get("ro.product." + partition + ".manufacturer"));
            assertEquals("shiba", properties.get("ro.product." + partition + ".device"));
        }
        for (String partition : new String[]{"system", "system_ext", "vendor", "odm", "product", "bootimage", "vendor_dlkm", "odm_dlkm"})
            assertEquals("", properties.get("ro." + partition + ".build.fingerprint"));
        for (String[] binding : PropertyView.REDACTED_BUILD_PROPERTIES)
            assertEquals("", properties.get(binding[1]));
    }
    @Test public void ordinaryCanonicalIdentityAlsoOverridesItsAliases() {
        Profile profile = new Profile(Map.of("build.MODEL", "selected-model",
                "system_properties", "ro.product.vendor.model=conflicting\ncustom.property=retained\n"));
        Map<String, String> properties = PropertyView.properties(profile);
        assertEquals("selected-model", properties.get("ro.product.model"));
        assertEquals("selected-model", properties.get("ro.product.vendor.model"));
        assertEquals("retained", properties.get("custom.property"));
        assertFalse(properties.containsKey("ro.soc.model"));
    }
    @Test public void absentOrdinaryIdentityDoesNotForceNewValues() {
        Map<String, String> properties = PropertyView.properties(new Profile(
                Map.of("system_properties", "ro.product.vendor.model=selected\n")));
        assertEquals("selected", properties.get("ro.product.vendor.model"));
        assertFalse(properties.containsKey("ro.product.model"));
        assertFalse(properties.containsKey("ro.soc.model"));
    }
    @Test public void nativePayloadAndGetpropUseTheSamePixelAliasesAndEmptyHardware() throws Exception {
        Profile profile = new Profile(Map.of("privacy_us_only", "true"));
        Map<String, String> properties = PropertyView.properties(profile);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(NativePayload.encode(profile, properties)));
        assertEquals(0x464b4331, in.readInt());
        int count = in.readInt();
        Map<String, String> decoded = new HashMap<>();
        for (int i = 0; i < count; i++) decoded.put(read(in), read(in));
        assertEquals(-1, in.read());
        assertEquals("Pixel 8", decoded.get("prop.ro.product.vendor.model"));
        assertEquals("", decoded.get("prop.ro.soc.model"));
        assertFalse(decoded.containsKey("sim_phone_number"));
        Process command = VirtualCommand.match(List.of("getprop", "ro.product.vendor.model"), profile, properties);
        assertNotNull(command);
        assertEquals("Pixel 8\n", new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
    private static String read(DataInputStream in) throws Exception {
        return new String(in.readNBytes(in.readInt()), StandardCharsets.UTF_8);
    }
}
