package io.github.lightjunction.fkclaude;

/** Native configure reports framework, requested, installed, failed, accepted in this order. */
final class NativeHookReport {
    static boolean completeForUsPolicy(long[] report) {
        // US mode always requests all three property readers and getenv; explicit extra
        // projections must also succeed. These counts do not describe network hooks.
        return report != null && report.length == 5 && report[0] == 1 && report[4] == 1
                && report[1] >= 4 && report[2] == report[1] && report[3] == 0;
    }
}
