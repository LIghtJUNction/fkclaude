package io.github.lightjunction.fkclaude;

import de.robv.android.xposed.XposedBridge;
import java.util.Map;

final class NativeBridge {
    static void install(Profile profile, Map<String, String> properties) {
        boolean strict = profile.flag("privacy_us_only");
        if (!Boolean.parseBoolean(profile.get("native_enabled", "true"))) {
            if (strict) throw new IllegalStateException("Native privacy projection disabled");
            return;
        }
        try {
            byte[] payload = NativePayload.encode(profile, properties);
            System.loadLibrary("fkclaude");
            long[] report = configure(payload);
            if (report == null || report.length != 5)
                throw new IllegalStateException("Native configuration rejected");
            XposedBridge.log("fkclaude: native framework=" + report[0] + " requested=" + report[1]
                    + " installed=" + report[2] + " failed=" + report[3] + " accepted=" + report[4]);
            if (strict && !NativeHookReport.completeForUsPolicy(report))
                throw new IllegalStateException("Required native privacy projections incomplete");
        } catch (Throwable e) {
            XposedBridge.log("fkclaude: native unavailable (" + e.getClass().getSimpleName() + ")");
            if (strict) throw new IllegalStateException("Native privacy initialization failed", e);
        }
    }
    private static native long[] configure(byte[] payload);
}
