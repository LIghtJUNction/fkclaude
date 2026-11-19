package io.github.lightjunction.fkclaude;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class AuditRecordingStateTest {
    private static final class NativeRecorder implements AuditRecordingState.NativeControl {
        boolean enabled;
        final List<String> queued = new ArrayList<>();
        final List<Boolean> changes = new ArrayList<>();
        @Override public boolean setEnabled(boolean value) {
            changes.add(value);
            enabled = value;
            if (!value) queued.clear();
            return true;
        }
        List<String> drain() {
            List<String> result = new ArrayList<>(queued);
            queued.clear();
            return result;
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue("worker timed out", latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { throw new AssertionError(error); }
    }

    private static void join(Thread worker, AtomicReference<Throwable> error) throws Exception {
        worker.join(5000);
        assertFalse("worker did not complete", worker.isAlive());
        if (error.get() != null) throw new AssertionError(error.get());
    }

    @Test public void clearDiscardsQueuedAndAlreadyDrainedEvents() {
        AuditRecordingState state = new AuditRecordingState();
        NativeRecorder nativeRecorder = new NativeRecorder();
        List<String> history = new ArrayList<>();
        assertTrue(state.setEnabled(true, nativeRecorder));
        long oldEpoch = state.capture();
        nativeRecorder.queued.add("drained-before-clear");
        List<String> drained = nativeRecorder.drain();
        nativeRecorder.queued.add("queued-before-clear");
        history.add("published-before-clear");

        assertTrue(state.clear(nativeRecorder, history::clear));
        assertTrue(nativeRecorder.enabled);
        assertTrue(nativeRecorder.queued.isEmpty());
        assertFalse(state.publish(oldEpoch, false, () -> history.addAll(drained)));
        assertTrue(history.isEmpty());
        assertTrue(state.publish(state.capture(), false, () -> history.add("after-clear")));
        assertEquals(List.of("after-clear"), history);
    }

    @Test public void clearFencesConcurrentPublicationAfterDrain() throws Exception {
        AuditRecordingState state = new AuditRecordingState();
        NativeRecorder nativeRecorder = new NativeRecorder();
        List<String> history = new ArrayList<>();
        state.setEnabled(true, nativeRecorder);
        nativeRecorder.queued.add("old-native-event");
        CountDownLatch drained = new CountDownLatch(1), publish = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(true);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                long epoch = state.capture();
                List<String> batch = nativeRecorder.drain();
                drained.countDown();
                await(publish);
                accepted.set(state.publish(epoch, false, () -> history.addAll(batch)));
            } catch (Throwable failure) { error.set(failure); }
        });
        worker.start();
        try {
            await(drained);
            assertTrue(state.clear(nativeRecorder, history::clear));
        } finally { publish.countDown(); }
        join(worker, error);
        assertFalse(accepted.get());
        assertTrue(history.isEmpty());
        assertTrue(state.isEnabled());
        assertTrue(nativeRecorder.enabled);
    }

    @Test public void delayedPausedPollCannotDisableAResumedRecorder() throws Exception {
        AuditRecordingState state = new AuditRecordingState();
        NativeRecorder nativeRecorder = new NativeRecorder();
        CountDownLatch paused = new CountDownLatch(1), resumed = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(true);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread poll = new Thread(() -> {
            try {
                long epoch = state.capture();
                paused.countDown();
                await(resumed);
                accepted.set(state.publish(epoch, false, () -> fail("paused batch published")));
            } catch (Throwable failure) { error.set(failure); }
        });
        poll.start();
        try {
            await(paused);
            assertTrue(state.setEnabled(true, nativeRecorder));
        } finally { resumed.countDown(); }
        join(poll, error);
        assertFalse(accepted.get());
        assertTrue(state.isEnabled());
        assertTrue(nativeRecorder.enabled);
        assertEquals(List.of(true), nativeRecorder.changes);
    }

    @Test public void pauseResumeInvalidatesAnOlderBatch() {
        AuditRecordingState state = new AuditRecordingState();
        NativeRecorder nativeRecorder = new NativeRecorder();
        state.setEnabled(true, nativeRecorder);
        long beforePause = state.capture();
        state.setEnabled(false, nativeRecorder);
        assertEquals(AuditRecordingState.PAUSED, state.capture());
        state.setEnabled(true, nativeRecorder);
        assertFalse(state.publish(beforePause, false, () -> fail("old batch published")));
        assertTrue(state.publish(state.capture(), false, () -> {}));
    }

    @Test public void clearWhilePausedDoesNotEnableRecording() {
        AuditRecordingState state = new AuditRecordingState();
        NativeRecorder nativeRecorder = new NativeRecorder();
        state.setEnabled(true, nativeRecorder);
        state.setEnabled(false, nativeRecorder);
        assertTrue(state.clear(nativeRecorder, () -> {}));
        assertFalse(state.isEnabled());
        assertFalse(nativeRecorder.enabled);
        assertEquals(List.of(true, false, false), nativeRecorder.changes);
        assertFalse(state.publish(state.capture(), false, () -> fail("paused event published")));
        assertTrue(state.publish(AuditRecordingState.UNFENCED, true, () -> {}));
    }

    @Test public void failedNativeResetIsReportedAndNeverReenabled() {
        AuditRecordingState state = new AuditRecordingState();
        NativeRecorder nativeRecorder = new NativeRecorder();
        state.setEnabled(true, nativeRecorder);
        long oldEpoch = state.capture();
        AtomicBoolean clearedMemory = new AtomicBoolean();
        List<Boolean> attempts = new ArrayList<>();
        assertFalse(state.clear(value -> { attempts.add(value); return false; },
                () -> clearedMemory.set(true)));
        assertEquals(List.of(false), attempts);
        assertTrue(clearedMemory.get());
        assertFalse(state.publish(oldEpoch, false, () -> fail("old batch published")));
    }

    @Test public void installationFinishingAfterPauseAppliesTheLatestState() throws Exception {
        AuditRecordingState state = new AuditRecordingState();
        NativeRecorder nativeRecorder = new NativeRecorder();
        state.setEnabled(true, nativeRecorder);
        CountDownLatch installing = new CountDownLatch(1), finishInstall = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread installer = new Thread(() -> {
            try {
                installing.countDown();
                await(finishInstall);
                assertTrue(state.synchronizeNative(nativeRecorder));
            } catch (Throwable failure) { error.set(failure); }
        });
        installer.start();
        try {
            await(installing);
            assertTrue(state.setEnabled(false, nativeRecorder));
        } finally { finishInstall.countDown(); }
        join(installer, error);
        assertFalse(state.isEnabled());
        assertFalse(nativeRecorder.enabled);
        assertEquals(List.of(true, false, false), nativeRecorder.changes);
    }

    @Test public void lateInstallationSynchronizesTheCurrentControlState() {
        AuditRecordingState state = new AuditRecordingState();
        NativeRecorder nativeRecorder = new NativeRecorder();
        state.setEnabled(true, nativeRecorder);
        state.setEnabled(false, nativeRecorder);
        assertTrue(state.synchronizeNative(nativeRecorder));
        assertFalse(nativeRecorder.enabled);
        state.setEnabled(true, nativeRecorder);
        assertTrue(state.synchronizeNative(nativeRecorder));
        assertTrue(nativeRecorder.enabled);
    }
}
