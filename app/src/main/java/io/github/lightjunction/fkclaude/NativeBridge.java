package io.github.lightjunction.fkclaude;

import de.robv.android.xposed.XposedBridge;
import java.util.Map;

final class NativeBridge {
    static void install(Profile profile, Map<String, String> properties) {
        if (!Boolean.parseBoolean(profile.get("native_enabled", "true"))) return;
        try {
            byte[] payload = NativePayload.encode(profile, properties);
            System.loadLibrary("fkclaude");
            long[] report = configure(payload);
            if (report == null || report.length != 5) { XposedBridge.log("fkclaude: native configuration rejected"); return; }
            XposedBridge.log("fkclaude: native framework=" + report[0] + " requested=" + report[1]
                    + " installed=" + report[2] + " failed=" + report[3] + " accepted=" + report[4]);
        } catch (Throwable e) { XposedBridge.log("fkclaude: native unavailable (" + e.getClass().getSimpleName() + ")"); }
    }
    private static native long[] configure(byte[] payload);
}
