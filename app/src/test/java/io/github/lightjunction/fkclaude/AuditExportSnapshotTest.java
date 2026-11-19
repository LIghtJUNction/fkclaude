package io.github.lightjunction.fkclaude;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class AuditExportSnapshotTest {
    private static AuditExportSnapshot.Metadata metadata(long dropped, boolean enabled) {
        return new AuditExportSnapshot.Metadata(42, 1000, dropped, 2, 3, 4, enabled);
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue("worker timed out", latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
    }

    private static void join(Thread worker, AtomicReference<Throwable> failure) throws Exception {
        worker.join(5000);
        assertFalse("worker did not finish", worker.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @Test public void capturedEventsCountersAndStateRemainImmutableUntilSerialization() {
        Object lock = new Object();
        List<String> live = new ArrayList<>(List.of("{\"seq\":1}", "{\"seq\":2}"));
        AtomicReference<AuditExportSnapshot.Metadata> state = new AtomicReference<>(metadata(7, true));
        AuditExportSnapshot captured = AuditExportSnapshot.capture(lock, () -> true, () -> live, state::get);
        synchronized (lock) {
            live.clear();
            live.add("{\"seq\":3}");
            state.set(metadata(99, false));
        }

        String exported = captured.toJsonl();
        String header = exported.lines().findFirst().orElseThrow();
        assertTrue(header.contains("\"retained\":2"));
        assertTrue(header.contains("\"queue_dropped\":7"));
        assertTrue(header.contains("\"disk_errors\":2"));
        assertTrue(header.contains("\"memory_evicted\":3"));
        assertTrue(header.contains("\"invalid_history\":4"));
        assertTrue(header.contains("\"enabled\":true"));
        assertTrue(header.contains("\"history_loaded\":true"));
        assertEquals(List.of("{\"seq\":1}", "{\"seq\":2}"), exported.lines().skip(1).toList());
        assertEquals("A snapshot must keep its capture timestamp", exported, captured.toJsonl());
    }

    @Test public void loadingHistoryFailsBeforeReadingOrSerializingPartialContents() {
        Object lock = new Object();
        AtomicInteger reads = new AtomicInteger();
        assertThrows(AuditExportSnapshot.HistoryNotReadyException.class,
                () -> AuditExportSnapshot.capture(lock, () -> false,
                        () -> { reads.incrementAndGet(); return List.of("{\"seq\":1}"); },
                        () -> { reads.incrementAndGet(); return metadata(0, true); }));
        assertEquals(0, reads.get());
    }

    @Test public void completedHistoryCanBeExportedAfterAnEarlierNotReadyAttempt() {
        Object lock = new Object();
        boolean[] ready = {false};
        List<String> live = new ArrayList<>(List.of("{\"seq\":3}"));
        assertThrows(AuditExportSnapshot.HistoryNotReadyException.class,
                () -> AuditExportSnapshot.capture(lock, () -> ready[0], () -> live, () -> metadata(0, false)));
        synchronized (lock) {
            live.add(0, "{\"seq\":2}");
            live.add(0, "{\"seq\":1}");
            ready[0] = true;
        }
        String exported = AuditExportSnapshot.capture(lock, () -> ready[0], () -> live,
                () -> metadata(0, false)).toJsonl();
        assertTrue(exported.lines().findFirst().orElseThrow().contains("\"retained\":3"));
        assertEquals(List.of("{\"seq\":1}", "{\"seq\":2}", "{\"seq\":3}"),
                exported.lines().skip(1).toList());
    }

    @Test public void concurrentClearCannotMixOldEventsWithNewCountersAndState() throws Exception {
        Object lock = new Object();
        List<String> live = new ArrayList<>(List.of("{\"seq\":1}"));
        AtomicReference<AuditExportSnapshot.Metadata> state = new AtomicReference<>(metadata(7, true));
        AtomicReference<AuditExportSnapshot> captured = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch eventsCopied = new CountDownLatch(1), continueCapture = new CountDownLatch(1);
        CountDownLatch clearAttempted = new CountDownLatch(1), clearCompleted = new CountDownLatch(1);
        Thread exporter = new Thread(() -> {
            try {
                captured.set(AuditExportSnapshot.capture(lock, () -> true, () -> {
                    List<String> events = List.copyOf(live);
                    eventsCopied.countDown();
                    await(continueCapture);
                    return events;
                }, state::get));
            } catch (Throwable error) { failure.set(error); }
        });
        Thread clearer = new Thread(() -> {
            try {
                clearAttempted.countDown();
                synchronized (lock) {
                    live.clear();
                    state.set(metadata(0, false));
                }
                clearCompleted.countDown();
            } catch (Throwable error) { failure.set(error); }
        });
        exporter.setDaemon(true);
        clearer.setDaemon(true);
        exporter.start();
        boolean clearBypassedSnapshotLock;
        try {
            await(eventsCopied);
            clearer.start();
            await(clearAttempted);
            clearBypassedSnapshotLock = clearCompleted.await(150, TimeUnit.MILLISECONDS);
        } finally {
            continueCapture.countDown();
        }
        join(exporter, failure);
        join(clearer, failure);
        assertFalse("Clear crossed an active export snapshot", clearBypassedSnapshotLock);
        assertTrue(live.isEmpty());
        assertFalse(state.get().enabled());
        String exported = captured.get().toJsonl();
        String header = exported.lines().findFirst().orElseThrow();
        assertTrue(header.contains("\"retained\":1"));
        assertTrue(header.contains("\"queue_dropped\":7"));
        assertTrue(header.contains("\"enabled\":true"));
        assertEquals(List.of("{\"seq\":1}"), exported.lines().skip(1).toList());
    }
}
