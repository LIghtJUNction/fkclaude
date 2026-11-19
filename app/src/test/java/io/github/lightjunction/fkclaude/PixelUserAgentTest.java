package io.github.lightjunction.fkclaude;

import org.junit.Test;
import static org.junit.Assert.*;

public class PixelUserAgentTest {
    private static final String ENGINE = "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36";
    @Test public void engineAndAndroidVersionsStayUnchangedWhileOemAndBuildAreRemoved() {
        String result = PixelUserAgent.project("Mozilla/5.0 (Linux; Android 16; OEM-private Build/private-build; wv) " + ENGINE);
        assertEquals("Mozilla/5.0 (Linux; Android 16; Pixel 8; wv) " + ENGINE, result);
        assertFalse(result.contains("private"));
        assertFalse(result.contains("Build/"));
    }
    @Test public void localeAndAdditionalDeviceTokensAreDiscarded() {
        assertEquals("Mozilla/5.0 (Linux; Android 12.1; Pixel 8; wv) " + ENGINE,
                PixelUserAgent.project("Mozilla/5.0 (Linux; U; Android 12.1; xx-YY; OEM; wv) " + ENGINE));
    }
    @Test public void metadataPartsComeFromTheSameVerifiedDefault() {
        PixelUserAgent.Parts parts = PixelUserAgent.parse("Mozilla/5.0 (Linux; Android 16; OEM; wv) " + ENGINE);
        assertEquals("16", parts.platformVersion());
        assertEquals("141.0.7390.122", parts.fullVersion());
        assertEquals("141", parts.majorVersion());
        assertEquals(parts.userAgent(), PixelUserAgent.project(parts.userAgent()));
    }
    @Test public void alreadyProjectedDefaultIsIdempotent() {
        String ua = "Mozilla/5.0 (Linux; Android 10; Pixel 8; wv) " + ENGINE;
        assertEquals(ua, PixelUserAgent.project(PixelUserAgent.project(ua)));
    }
    @Test public void optionalWebviewAndMobileTokensArePreservedFromDefault() {
        String engine = "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.1.2.3 Safari/537.36";
        assertEquals("Mozilla/5.0 (Linux; Android 15; Pixel 8) " + engine,
                PixelUserAgent.project("Mozilla/5.0 (Linux; Android 15; OEM) " + engine));
    }
    @Test public void malformedUnknownAndAmbiguousDefaultsAreRejected() {
        for (String ua : new String[]{null, "", "private-placeholder", "Mozilla/5.0 (Linux; private-placeholder) " + ENGINE,
                "Mozilla/5.0 (Linux; Android 16; Android 15; private-placeholder) " + ENGINE,
                "Mozilla/5.0 (Linux; Android 16; private-placeholder) " + ENGINE + " OEM/private",
                "Mozilla/5.0 (Linux; Android 16; private-placeholder) " + ENGINE.replace("141.0.7390.122", "141"),
                "Mozilla/5.0 (Linux; Android 16; private-placeholder) " + ENGINE + "\r\n",
                "x".repeat(1025)}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> PixelUserAgent.project(ua));
            assertFalse(error.getMessage().contains("private-placeholder"));
        }
    }
}
