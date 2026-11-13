package io.github.lightjunction.fkclaude;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

public class IsolationTest {
    @Test public void canonicalFieldsOverrideConflictingLowLevelProperty() {
        Profile p = PropertyView.normalize(new Profile(Map.of("build.MODEL", "chosen", "system_properties", "ro.product.model=other")));
        assertEquals("chosen", PropertyView.properties(p).get("ro.product.model"));
        Profile inherited = PropertyView.normalize(new Profile(Map.of("system_properties", "ro.product.model=inherited")));
        assertEquals("inherited", inherited.get("build.MODEL"));
    }
    @Test public void localeAndTimeZoneAreSharedWithNativeEnvironment() {
        Profile p = new Profile(Map.of("locale", "zh-CN", "timezone", "Asia/Taipei", "native_env", "SECRET=\nCUSTOM=value\nTZ=wrong"));
        assertEquals("Asia/Taipei", PropertyView.environment(p).get("TZ"));
        assertEquals("zh_CN.UTF-8", PropertyView.environment(p).get("LANG"));
        assertEquals("", PropertyView.environment(p).get("SECRET"));
        assertEquals("Asia/Taipei", PropertyView.properties(p).get("persist.sys.timezone"));
    }
    @Test public void nativeWireMatchesTheCppGoldenFixture() throws Exception {
        Profile p = new Profile(Map.of("cpu_count", "4", "timezone", "UTC", "native_hostname", "virtual-host"));
        byte[] actual = NativePayload.encode(p, Map.of("ro.product.model", "隔离"));
        try (var fixture = getClass().getResourceAsStream("/native-golden.hex")) {
            assertNotNull(fixture);
            assertEquals(new String(fixture.readAllBytes(), StandardCharsets.US_ASCII).trim(), HexFormat.of().formatHex(actual));
        }
    }
    @Test public void getpropUsesOnlyTheVirtualPropertyMap() throws Exception {
        Profile p = new Profile(Map.of("command_isolation", "true"));
        Process command = VirtualCommand.match(List.of("/system/bin/getprop"), p, Map.of("ro.product.model", "private"));
        assertEquals("[ro.product.model]: [private]\n", new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(0, command.waitFor()); assertTrue(command.waitFor(1, TimeUnit.MILLISECONDS)); assertFalse(command.isAlive());
        Process unknown = VirtualCommand.match(List.of("getprop", "unknown"), p, Map.of());
        assertEquals("\n", new String(unknown.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
    @Test public void mountQueryCannotTurnIntoAFakeWriteCommand() throws Exception {
        Profile p = new Profile(Map.of("native_mount", "virtual-mount\n"));
        Process query = VirtualCommand.match(List.of("mount"), p, Map.of());
        assertEquals("virtual-mount\n", new String(query.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertNull(VirtualCommand.match(List.of("mount", "-o", "rw", "/system"), p, Map.of()));
        assertNull(VirtualCommand.match(List.of("sh", "-c", "getprop"), p, Map.of()));
        assertNull(VirtualCommand.match(List.of("getprop", "-T"), p, Map.of()));
        assertNull(VirtualCommand.match(List.of("getprop"), new Profile(Map.of("command_isolation", "false")), Map.of()));
    }
    @Test public void invalidNativeValuesAreRejectedAtSaveTime() {
        assertThrows(IllegalArgumentException.class, () -> new Profile(Map.of("native_hostname", "x".repeat(65))));
        assertThrows(IllegalArgumentException.class, () -> new Profile(Map.of("native_env", "BAD-NAME=value")));
        assertThrows(IllegalArgumentException.class, () -> new Profile(Map.of("native_env", "SECRET=\\u0000")));
        assertThrows(IllegalArgumentException.class, () -> new Profile(Map.of("web_device_memory", "1025")));
        assertThrows(IllegalArgumentException.class, () -> new Profile(Map.of("system_properties", "ro.build.version.sdk=bogus")));
    }
}
