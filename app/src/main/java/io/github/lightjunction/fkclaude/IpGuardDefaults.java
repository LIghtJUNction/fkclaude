package io.github.lightjunction.fkclaude;

/** Kept independent of Android/Xposed classes so profile parsing has no runtime hook dependency. */
final class IpGuardDefaults {
    static final String PROBE = "https://claude.ai/cdn-cgi/trace";
}
