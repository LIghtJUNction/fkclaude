package io.github.lightjunction.fkclaude;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfilePersistenceTest {
    private static Profile ordinary() {
        return new Profile(Map.of("enabled", "true", "claude_device_id", "11111111-2222-4333-8444-555555555555",
                "android_id", "0123456789abcdef", "carrier_name", "custom carrier", "locale", "en-GB"));
    }
    private static Profile strict() { return Profile.usPrivacy(ordinary()); }

    private static final class Memory implements ProfilePersistence.Storage {
        String snapshot, active, bridge, legacy;
        int activeReads, bridgeReads, legacyReads, snapshots, activeWrites, bridgeWrites;
        boolean activeFails, bridgeFails, snapshotFails, bridgeDenied;
        @Override public String readSnapshot() { return snapshot; }
        @Override public String readActive() { activeReads++; return active; }
        @Override public String readBridge() {
            bridgeReads++;
            if (bridgeDenied) throw new SecurityException("framework unavailable");
            return bridge;
        }
        @Override public String readLegacy() { legacyReads++; return legacy; }
        @Override public void writeSnapshot(String encoded) {
            if (snapshotFails) throw new IllegalStateException("snapshot failed");
            snapshot = encoded; snapshots++;
        }
        @Override public boolean writeActive(String encoded) {
            active = encoded; activeWrites++; // Failed disk commits can still mutate the in-memory view.
            return !activeFails;
        }
        @Override public boolean writeBridge(String encoded) {
            if (bridgeDenied) throw new SecurityException("framework unavailable");
            bridge = encoded; bridgeWrites++; return !bridgeFails;
        }
    }

    @Test public void authoritativeUsSnapshotNeverReadsOldPreferenceCandidates() {
        Memory m = new Memory(); m.snapshot = strict().encode();
        m.active = m.bridge = m.legacy = ordinary().encode();
        Profile loaded = ProfilePersistence.load(m);
        assertTrue(loaded.flag("privacy_us_only"));
        assertEquals(0, m.activeReads + m.bridgeReads + m.legacyReads);
        assertEquals(loaded.copy(), Profile.parse(m.bridge).copy());
        assertEquals(0, m.snapshots);
    }

    @Test public void firstMigrationDoesNotWeakenUsBridgeWithOldActiveOrLegacy() {
        Memory m = new Memory(); m.bridge = strict().encode(); m.active = m.legacy = ordinary().encode();
        Profile result = ProfilePersistence.load(m);
        assertEquals(strict().copy(), result.copy());
        assertEquals("0123456789abcdef", result.get("android_id"));
        assertEquals(0, m.legacyReads);
        assertEquals(1, m.snapshots);
    }

    @Test public void ordinaryMigrationPreservesAllValuesAndDoesNotOptIntoUs() {
        Memory m = new Memory(); m.active = ordinary().encode();
        Profile result = ProfilePersistence.load(m);
        assertFalse(result.flag("privacy_us_only"));
        assertEquals(ordinary().copy(), result.copy());
        assertEquals(0, m.legacyReads);
    }

    @Test public void legacyIsUsedOnlyForFirstMigrationAndThenIgnored() {
        Memory m = new Memory(); m.legacy = ordinary().encode();
        assertEquals(ordinary().copy(), ProfilePersistence.load(m).copy());
        m.legacy = strict().encode();
        assertEquals(ordinary().copy(), ProfilePersistence.load(m).copy());
        assertEquals(1, m.legacyReads);
    }

    @Test public void unavailableFrameworkCannotPromoteAnUnredirectedOldXml() {
        Memory m = new Memory(); m.bridgeDenied = true; m.active = m.legacy = ordinary().encode();
        assertThrows(IllegalStateException.class, () -> ProfilePersistence.load(m));
        assertNull(m.snapshot);
        assertEquals(0, m.activeReads + m.legacyReads + m.activeWrites);
    }

    @Test public void malformedAuthorityIsFatalWithoutFallback() {
        Memory m = new Memory(); m.snapshot = "unknown_field=bad\n"; m.bridge = strict().encode();
        assertThrows(IllegalStateException.class, () -> ProfilePersistence.load(m));
        assertEquals(0, m.bridgeReads + m.activeReads + m.legacyReads);
    }

    @Test public void emptyAuthorityCannotBecomeDisabledPolicy() {
        Memory m = new Memory(); m.snapshot = ""; m.bridge = strict().encode();
        assertThrows(IllegalStateException.class, () -> ProfilePersistence.load(m));
        assertEquals(0, m.bridgeReads + m.legacyReads);
    }

    @Test public void failedActiveCommitDoesNotPretendSuccessOrTrustItsCache() {
        Memory m = new Memory(); m.snapshot = ordinary().encode(); m.activeFails = true;
        assertThrows(IllegalStateException.class, () -> ProfilePersistence.save(m, strict(), ordinary()));
        assertTrue(Profile.parse(m.snapshot).flag("privacy_us_only"));
        assertEquals(0, m.bridgeWrites);
        m.active = ordinary().encode(); m.activeFails = false;
        assertTrue(ProfilePersistence.load(m).flag("privacy_us_only"));
        assertEquals(0, m.activeReads);
    }

    @Test public void failedPublicationIsFatalAndRetryUsesExistingAuthority() {
        Memory m = new Memory(); m.bridgeFails = true;
        assertThrows(IllegalStateException.class, () -> ProfilePersistence.save(m, strict()));
        assertTrue(Profile.parse(m.snapshot).flag("privacy_us_only"));
        m.bridgeFails = false;
        ProfilePersistence.save(m, strict());
        assertEquals(1, m.snapshots);
        assertTrue(Profile.parse(m.bridge).flag("privacy_us_only"));
    }

    @Test public void securityFailureIsWrappedToPreventTargetFallback() {
        Memory m = new Memory(); m.snapshot = strict().encode(); m.bridgeDenied = true;
        assertThrows(IllegalStateException.class, () -> ProfilePersistence.load(m));
        assertTrue(Profile.parse(m.snapshot).flag("privacy_us_only"));
    }

    @Test public void failedSnapshotWriteCannotUpdateAnyMirror() {
        Memory m = new Memory(); m.snapshot = ordinary().encode(); m.snapshotFails = true;
        assertThrows(IllegalStateException.class, () -> ProfilePersistence.save(m, strict(), ordinary()));
        assertEquals(ordinary().copy(), Profile.parse(m.snapshot).copy());
        assertEquals(0, m.activeWrites + m.bridgeWrites);
    }

    @Test public void explicitSuccessfulSaveCanLeaveStrictMode() {
        Memory m = new Memory(); m.snapshot = strict().encode();
        ProfilePersistence.save(m, ordinary(), strict());
        assertFalse(ProfilePersistence.load(m).flag("privacy_us_only"));
    }

    @Test public void staleAndUnversionedDraftsCannotReplaceNewUsBaseline() {
        Profile saved = strict();
        assertSame(saved, ProfilePersistence.restoreDraft(saved, ordinary().encode(), ordinary().encode()));
        assertSame(saved, ProfilePersistence.restoreDraft(saved, null, ordinary().encode()));
    }

    @Test public void matchingBaselineCanRestoreAnUnsavedExplicitEdit() {
        Profile saved = strict();
        assertEquals(ordinary().copy(), ProfilePersistence.restoreDraft(saved, saved.encode(), ordinary().encode()).copy());
        assertTrue(saved.flag("privacy_us_only"));
    }
    @Test public void staleLiveEditorCannotReplaceNewlyEnabledUsPolicy() {
        Memory m = new Memory(); m.snapshot = strict().encode();
        java.util.Map<String, String> draft = ordinary().copy(); draft.put("carrier_name", "edited old form");
        assertThrows(ProfilePersistence.Conflict.class,
                () -> ProfilePersistence.save(m, new Profile(draft), ordinary()));
        assertEquals(strict().copy(), Profile.parse(m.snapshot).copy());
        assertEquals(0, m.activeWrites + m.bridgeWrites);
    }

    @Test public void racingBootstrapCannotOverwriteAnExistingAuthority() {
        Memory m = new Memory(); m.snapshot = strict().encode();
        assertThrows(ProfilePersistence.Conflict.class, () -> ProfilePersistence.save(m, ordinary()));
        assertEquals(strict().copy(), Profile.parse(m.snapshot).copy());
        assertEquals(0, m.snapshots + m.activeWrites + m.bridgeWrites);
    }

    @Test public void sameContentRetryWorksAfterAuthorityChangedButPublicationFailed() {
        Memory m = new Memory(); m.snapshot = ordinary().encode(); m.bridgeFails = true;
        assertThrows(IllegalStateException.class, () -> ProfilePersistence.save(m, strict(), ordinary()));
        m.bridgeFails = false;
        ProfilePersistence.save(m, strict(), ordinary());
        assertEquals(strict().copy(), Profile.parse(m.bridge).copy());
        assertEquals(1, m.snapshots);
    }
}
