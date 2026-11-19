package io.github.lightjunction.fkclaude;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AuditExportRequestsTest {
    private static final int CODE = 0x5f4c;

    @Test public void resultBelongsToTheInitiatingActivityAndIsConsumedOnce() {
        AuditExportRequests<Object> requests = new AuditExportRequests<>();
        Object owner = new Object(), other = new Object();
        assertNotNull(requests.begin(owner, CODE));
        assertFalse(requests.consume(other, CODE));
        assertFalse(requests.consume(owner, CODE + 1));
        assertTrue(requests.consume(owner, CODE));
        assertFalse(requests.consume(owner, CODE));
    }

    @Test public void oneOwnersPendingPickerDoesNotBlockAnotherActivity() {
        AuditExportRequests<Object> requests = new AuditExportRequests<>();
        Object first = new Object(), second = new Object();
        assertNotNull(requests.begin(first, CODE));
        assertNull(requests.begin(first, CODE));
        assertNotNull(requests.begin(second, CODE));
        requests.release(first);
        assertNotNull(requests.begin(first, CODE));
        assertTrue(requests.consume(second, CODE));
    }

    @Test public void recreationTransfersOwnershipEvenIfTheOldObjectStillExists() {
        AuditExportRequests<Object> requests = new AuditExportRequests<>();
        Object old = new Object(), replacement = new Object();
        AuditExportRequests.Request saved = requests.begin(old, CODE);
        assertTrue(requests.restore(replacement, saved.token(), saved.requestCode()));
        assertNull(requests.pending(old));
        requests.release(old);
        assertTrue(requests.consume(replacement, CODE));
        assertFalse(requests.consume(old, CODE));
    }

    @Test public void savedRequestSurvivesACompletelyNewProcessTracker() {
        AuditExportRequests<Object> oldProcess = new AuditExportRequests<>();
        AuditExportRequests.Request saved = oldProcess.begin(new Object(), CODE);
        AuditExportRequests<Object> newProcess = new AuditExportRequests<>();
        Object restoredActivity = new Object();
        assertTrue(newProcess.restore(restoredActivity, saved.token(), saved.requestCode()));
        assertTrue(newProcess.consume(restoredActivity, CODE));
        assertFalse(newProcess.consume(restoredActivity, CODE));
    }

    @Test public void cancellationOrLaunchFailureReleasesOnlyItsOwner() {
        AuditExportRequests<Object> requests = new AuditExportRequests<>();
        Object first = new Object(), second = new Object();
        requests.begin(first, CODE);
        requests.begin(second, CODE);
        requests.release(first);
        assertFalse(requests.consume(first, CODE));
        assertTrue(requests.consume(second, CODE));
        assertNotNull(requests.begin(first, CODE));
    }

    @Test public void completedRequestsCannotBeRestoredOrWrittenTwice() {
        AuditExportRequests<Object> requests = new AuditExportRequests<>();
        Object owner = new Object();
        AuditExportRequests.Request saved = requests.begin(owner, CODE);
        assertTrue(requests.consume(owner, CODE));
        assertFalse(requests.restore(new Object(), saved.token(), saved.requestCode()));
        assertNotNull(requests.begin(owner, CODE));
    }

    @Test public void invalidOrConflictingSavedStateCannotReplaceAnActiveRequest() {
        AuditExportRequests<Object> requests = new AuditExportRequests<>();
        Object owner = new Object();
        assertFalse(requests.restore(owner, null, CODE));
        assertFalse(requests.restore(owner, "private-text", CODE));
        assertFalse(requests.restore(owner, "00000000-0000-0000-0000-000000000000", -1));
        assertFalse(requests.restore(owner, "00000000-0000-0000-0000-000000000000", 0x10000));
        AuditExportRequests.Request active = requests.begin(owner, CODE);
        assertFalse(requests.restore(owner, "00000000-0000-0000-0000-000000000000", CODE));
        assertEquals(active, requests.pending(owner));
        assertTrue(requests.restore(owner, active.token(), active.requestCode()));
    }
}
