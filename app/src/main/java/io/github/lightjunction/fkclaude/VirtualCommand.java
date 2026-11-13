package io.github.lightjunction.fkclaude;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Only emulates the two observed read-only environment queries, never arbitrary shell commands. */
final class VirtualCommand extends Process {
    private final InputStream output;
    private VirtualCommand(String text) { output = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    static Process match(List<String> args, Profile profile, Map<String, String> properties) {
        if (!Boolean.parseBoolean(profile.get("command_isolation", "true")) || args.isEmpty()) return null;
        String executable = args.get(0);
        String name = executable.substring(executable.lastIndexOf('/') + 1);
        if (name.equals("getprop") && args.size() <= 2) {
            if (args.size() == 2) {
                if (args.get(1).startsWith("-")) return null;
                return new VirtualCommand(properties.getOrDefault(args.get(1), "") + "\n");
            }
            StringBuilder result = new StringBuilder();
            new TreeMap<>(properties).forEach((key, value) -> result.append('[').append(key).append("]: [").append(value).append("]\n"));
            return new VirtualCommand(result.toString());
        }
        if (name.equals("mount") && args.size() == 1) return new VirtualCommand(profile.get("native_mount", ""));
        return null;
    }
    @Override public OutputStream getOutputStream() { return new OutputStream() { @Override public void write(int value) {} }; }
    @Override public InputStream getInputStream() { return output; }
    @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
    @Override public int waitFor() { return 0; }
    @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
    @Override public int exitValue() { return 0; }
    @Override public boolean isAlive() { return false; }
    @Override public void destroy() {}
    @Override public Process destroyForcibly() { return this; }
}
