package io.github.lightjunction.fkclaude;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

final class NativePayload {
    static byte[] encode(Profile profile, Map<String, String> properties) {
        Map<String, String> values = new TreeMap<>();
        for (String key : new String[]{"cpu_count", "memory_total", "memory_available", "storage_total", "storage_available",
                "native_sysname", "native_hostname", "native_kernel_release", "native_kernel_version", "native_machine"})
            if (profile.has(key)) values.put(key, profile.get(key));
        properties.forEach((key, value) -> values.put("prop." + key, value));
        PropertyView.environment(profile).forEach((key, value) -> values.put("env." + key, value));
        if (values.size() > 512) throw new IllegalArgumentException("Native field count");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0x464b4331); out.writeInt(values.size());
            for (var entry : values.entrySet()) { write(out, entry.getKey(), 256); write(out, entry.getValue(), 16384); }
            if (bytes.size() > 1024 * 1024) throw new IllegalArgumentException("Native payload size");
            return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private static void write(DataOutputStream out, String value, int limit) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Native string size or NUL");
        out.writeInt(bytes.length); out.write(bytes);
    }
}
