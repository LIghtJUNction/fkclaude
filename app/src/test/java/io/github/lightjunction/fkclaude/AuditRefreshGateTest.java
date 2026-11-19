package io.github.lightjunction.fkclaude;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AuditRefreshGateTest {
    @Test public void pauseRejectsAnAlreadyRunningAutomaticRefresh() {
        AuditRefreshGate gate = new AuditRefreshGate();
        AuditRefreshGate.Ticket old = gate.begin(true, true);
        assertNotNull(old);
        gate.invalidate(false);
        assertFalse(gate.complete(old));
        assertFalse(gate.hasRequest());
        assertNull(gate.begin(false, true));
    }

    @Test public void manualFilterRunsAfterOldWorkEvenWhilePaused() {
        AuditRefreshGate gate = new AuditRefreshGate();
        AuditRefreshGate.Ticket old = gate.begin(true, true);
        gate.invalidate(false);
        gate.request();
        assertNull(gate.begin(false, true));
        assertFalse(gate.complete(old));
        AuditRefreshGate.Ticket filter = gate.begin(false, false);
        assertNotNull(filter);
        assertTrue(gate.complete(filter));
        assertNull(gate.begin(false, true));
    }

    @Test public void suspensionRetriesExplicitSearchButNeverAppliesItsOldResult() {
        AuditRefreshGate gate = new AuditRefreshGate();
        gate.request();
        AuditRefreshGate.Ticket old = gate.begin(false, false);
        gate.invalidate(true);
        assertFalse(gate.complete(old));
        AuditRefreshGate.Ticket retry = gate.begin(false, false);
        assertNotNull(retry);
        assertTrue(gate.complete(retry));
    }

    @Test public void suspensionPreservesAnExplicitRequestWaitingToStart() {
        AuditRefreshGate gate = new AuditRefreshGate();
        gate.request();
        gate.invalidate(true);
        AuditRefreshGate.Ticket resumed = gate.begin(false, false);
        assertNotNull(resumed);
        assertTrue(gate.complete(resumed));
    }

    @Test public void lifecycleChangeCannotResurrectACancelledExplicitRequest() {
        AuditRefreshGate gate = new AuditRefreshGate();
        gate.request();
        AuditRefreshGate.Ticket old = gate.begin(false, false);
        gate.invalidate(false);
        gate.invalidate(true);
        assertFalse(gate.complete(old));
        assertNull(gate.begin(false, true));
    }

    @Test public void closeAndReopenRejectsOldResultWithoutLosingNewRequest() {
        AuditRefreshGate gate = new AuditRefreshGate();
        AuditRefreshGate.Ticket old = gate.begin(true, true);
        gate.invalidate(false);
        gate.request();
        assertFalse(gate.complete(old));
        AuditRefreshGate.Ticket fresh = gate.begin(true, true);
        assertNotNull(fresh);
        assertFalse(gate.complete(old)); // A repeated old callback cannot release fresh work.
        assertNull(gate.begin(true, true));
        assertTrue(gate.complete(fresh));
    }

    @Test public void queueRejectionRetainsExplicitWorkForTheNextTick() {
        AuditRefreshGate gate = new AuditRefreshGate();
        gate.request();
        AuditRefreshGate.Ticket rejected = gate.begin(false, false);
        gate.rejected(rejected);
        assertTrue(gate.hasRequest());
        assertNotNull(gate.begin(false, false));
    }

    @Test public void unchangedDataAndPausedAutomaticWorkNeverStarts() {
        AuditRefreshGate gate = new AuditRefreshGate();
        assertNull(gate.begin(true, false));
        assertNull(gate.begin(false, true));
        AuditRefreshGate.Ticket current = gate.begin(true, true);
        assertTrue(gate.complete(current));
    }
}
