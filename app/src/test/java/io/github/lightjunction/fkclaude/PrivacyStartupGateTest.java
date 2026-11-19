package io.github.lightjunction.fkclaude;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class PrivacyStartupGateTest {
    @Test public void strictStartsClosedWhileOrdinaryModeIsReady() {
        PrivacyStartupGate strict = new PrivacyStartupGate(true);
        assertFalse(strict.isReady());
        assertFalse(strict.allowConstructor(new Object()));
        assertFalse(strict.allowAccess(new Object()));
        PrivacyStartupGate ordinary = new PrivacyStartupGate(false);
        assertTrue(ordinary.isReady());
        assertTrue(ordinary.allowConstructor(new Object()));
        assertFalse(ordinary.beginFinalization());
    }
    @Test public void initializationAndOpenBootstrapDoNotPublishNetworkReadiness() {
        PrivacyStartupGate gate = new PrivacyStartupGate(true);
        assertTrue(gate.beginFinalization());
        assertFalse(gate.isReady());
        gate.openBootstrap();
        assertFalse(gate.isReady());
        assertFalse(gate.allowConstructor(null));
    }
    @Test public void oneObjectCanReenterItsConstructorChainButNotClaimASecondView() {
        PrivacyStartupGate gate = new PrivacyStartupGate(true);
        gate.beginFinalization(); gate.openBootstrap();
        Object owned = new Object(), foreign = new Object();
        assertFalse(gate.allowAccess(owned)); // Reading settings cannot claim the bootstrap.
        assertTrue(gate.allowConstructor(owned));
        assertTrue(gate.allowConstructor(owned));
        assertTrue(gate.allowAccess(owned));
        assertFalse(gate.allowConstructor(foreign));
        assertFalse(gate.allowAccess(foreign));
    }
    @Test public void anotherThreadCannotBorrowTheOwnedViewOrBootstrapClaim() throws Exception {
        PrivacyStartupGate gate = new PrivacyStartupGate(true);
        gate.beginFinalization(); gate.openBootstrap();
        Object owned = new Object();
        AtomicBoolean admitted = new AtomicBoolean();
        Thread beforeClaim = new Thread(() -> admitted.set(gate.allowConstructor(owned)));
        beforeClaim.start(); beforeClaim.join();
        assertFalse(admitted.get());
        assertTrue(gate.allowConstructor(owned));
        Thread afterClaim = new Thread(() -> admitted.set(
                gate.allowConstructor(owned) || gate.allowAccess(owned)));
        afterClaim.start(); afterClaim.join();
        assertFalse(admitted.get());
    }
    @Test public void closedBootstrapRevokesItsObjectAndCannotBeReopened() {
        PrivacyStartupGate gate = new PrivacyStartupGate(true);
        gate.beginFinalization(); gate.openBootstrap();
        Object owned = new Object(); gate.allowConstructor(owned);
        gate.confirmBootstrap(owned); gate.closeBootstrap();
        assertFalse(gate.allowAccess(owned));
        assertFalse(gate.allowConstructor(owned));
        assertThrows(IllegalStateException.class, gate::openBootstrap);
        assertFalse(gate.isReady());
    }
    @Test public void onlyVerifiedAndClosedBootstrapCanPublishReadiness() throws Exception {
        PrivacyStartupGate gate = new PrivacyStartupGate(true);
        assertThrows(IllegalStateException.class, gate::complete);
        gate.beginFinalization(); gate.openBootstrap();
        Object owned = new Object(); gate.allowConstructor(owned);
        assertThrows(IllegalStateException.class, gate::complete);
        gate.confirmBootstrap(owned);
        assertThrows(IllegalStateException.class, gate::complete);
        gate.closeBootstrap();
        AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
        Thread foreign = new Thread(() -> { try { gate.complete(); } catch (Throwable e) { foreignFailure.set(e); } });
        foreign.start(); foreign.join();
        assertTrue(foreignFailure.get() instanceof IllegalStateException);
        assertFalse(gate.isReady());
        gate.complete();
        assertTrue(gate.isReady());
        assertTrue(gate.allowConstructor(new Object()));
        assertFalse(gate.beginFinalization());
    }
    @Test public void skippedOrWrongReadbackCannotComplete() {
        PrivacyStartupGate gate = new PrivacyStartupGate(true);
        gate.beginFinalization(); gate.openBootstrap();
        Object owned = new Object(); gate.allowConstructor(owned);
        assertThrows(IllegalStateException.class, () -> gate.confirmBootstrap(new Object()));
        gate.closeBootstrap();
        assertThrows(IllegalStateException.class, gate::complete);
        assertFalse(gate.isReady());
    }
    @Test public void failureIsTerminalBeforeDuringAndAfterInitialization() {
        for (int phase = 0; phase < 3; phase++) {
            PrivacyStartupGate gate = new PrivacyStartupGate(true);
            if (phase > 0) {
                gate.beginFinalization(); gate.openBootstrap();
                Object owned = new Object(); gate.allowConstructor(owned);
                if (phase > 1) { gate.confirmBootstrap(owned); gate.closeBootstrap(); gate.complete(); }
            }
            gate.fail();
            gate.closeBootstrap(); // Cleanup remains safe even after a constructor-side failure.
            assertFalse(gate.isReady());
            assertFalse(gate.allowConstructor(new Object()));
            assertFalse(gate.allowAccess(new Object()));
            assertThrows(IllegalStateException.class, gate::beginFinalization);
            assertThrows(IllegalStateException.class, gate::openBootstrap);
            assertThrows(IllegalStateException.class, gate::complete);
        }
    }
}
