package io.github.lightjunction.fkclaude;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps the installed WebView engine versions; discards all original device/build tokens. */
final class PixelUserAgent {
    private static final Pattern DEFAULT = Pattern.compile(
            "^Mozilla/5\\.0 \\(([^\\r\\n()]*)\\) (AppleWebKit/[0-9]+(?:\\.[0-9]+)* "
            + "\\(KHTML, like Gecko\\) (?:Version/[0-9]+(?:\\.[0-9]+)* )?"
            + "Chrome/([0-9]+(?:\\.[0-9]+){3}) (?:Mobile )?Safari/[0-9]+(?:\\.[0-9]+)*)$");
    private static final Pattern ANDROID = Pattern.compile(
            "(?:^|;\\s*)Android\\s+([0-9]+(?:\\.[0-9]+){0,2})(?=;|$)");
    private static final Pattern WEBVIEW = Pattern.compile("(?:^|;\\s*)wv(?=;|$)");

    record Parts(String userAgent, String platformVersion, String fullVersion) {
        String majorVersion() { return fullVersion.substring(0, fullVersion.indexOf('.')); }
    }

    static String project(String original) { return parse(original).userAgent(); }

    static Parts parse(String original) {
        if (original == null || original.length() > 1024 || original.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("WebView user agent unavailable");
        Matcher ua = DEFAULT.matcher(original);
        if (!ua.matches() || !ua.group(1).startsWith("Linux;"))
            throw new IllegalArgumentException("Unsupported WebView user agent");
        Matcher android = ANDROID.matcher(ua.group(1));
        if (!android.find()) throw new IllegalArgumentException("Android user agent unavailable");
        String release = android.group(1);
        if (android.find()) throw new IllegalArgumentException("Ambiguous Android user agent");
        String webview = WEBVIEW.matcher(ua.group(1)).find() ? "; wv" : "";
        String projected = "Mozilla/5.0 (Linux; Android " + release + "; " + UsPrivacyPolicy.PIXEL_MODEL
                + webview + ") " + ua.group(2);
        return new Parts(projected, release, ua.group(3));
    }
}
