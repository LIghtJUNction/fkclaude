package io.github.lightjunction.fkclaude;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfileSnapshotTest {
    @Test public void unicodeAndEscapedProfilePayloadRoundTrips() {
        String text = "enabled=true\ncarrier_name=中文\\nvalue\\twith=signs\n";
        assertEquals(text, ProfileSnapshot.decode(ProfileSnapshot.encode(text)));
    }
    @Test public void completeLineTruncationCannotTurnUsPolicyIntoOrdinaryPolicy() {
        byte[] complete = ProfileSnapshot.encode("enabled=true\nprivacy_us_only=true\n");
        byte[] truncated = Arrays.copyOf(complete, complete.length - "privacy_us_only=true\n".length());
        assertThrows(IllegalStateException.class, () -> ProfileSnapshot.decode(truncated));
    }
    @Test public void sameLengthMutationAndTrailingDataAreRejected() {
        byte[] changed = ProfileSnapshot.encode("enabled=true\n");
        changed[changed.length - 2] ^= 1;
        assertThrows(IllegalStateException.class, () -> ProfileSnapshot.decode(changed));
        byte[] extra = Arrays.copyOf(ProfileSnapshot.encode("enabled=true\n"), changed.length + 1);
        assertThrows(IllegalStateException.class, () -> ProfileSnapshot.decode(extra));
    }
    @Test public void rawPropertiesAndEmptyFilesAreNeverCanonicalSnapshots() {
        assertThrows(IllegalStateException.class, () -> ProfileSnapshot.decode(new byte[0]));
        assertThrows(IllegalStateException.class, () -> ProfileSnapshot.decode("enabled=false\n".getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void oversizedPayloadsAreRejectedBeforeStorage() {
        assertThrows(IllegalStateException.class, () -> ProfileSnapshot.encode("x".repeat(ProfileSnapshot.MAX_BYTES)));
    }
}
