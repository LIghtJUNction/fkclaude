package io.github.lightjunction.fkclaude;

import java.util.ArrayDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class LocationPrivacyTest {
    @Test public void unavailableCompletesOnceWithNullOnTheProvidedExecutor() {
        var queued = new ArrayDeque<Runnable>();
        var calls = new AtomicInteger();
        LocationPrivacy.unavailable(queued::add, () -> false, value -> {
            assertNull(value); calls.incrementAndGet();
        });
        assertEquals(0, calls.get());
        assertEquals(1, queued.size());
        queued.remove().run();
        assertEquals(1, calls.get());
    }

    @Test public void alreadyCancelledRequestDoesNotScheduleOrDeliver() {
        LocationPrivacy.unavailable(task -> fail("executor invoked"), () -> true,
                value -> fail("callback invoked"));
    }

    @Test public void cancellationBeforeQueuedDeliverySuppressesTheCallback() {
        var queued = new ArrayDeque<Runnable>();
        var cancelled = new AtomicBoolean();
        LocationPrivacy.unavailable(queued::add, cancelled::get,
                value -> fail("cancelled callback invoked"));
        cancelled.set(true);
        queued.remove().run();
    }

    @Test public void rejectedExecutorDoesNotDeliverOrRetry() {
        var attempts = new AtomicInteger();
        LocationPrivacy.unavailable(task -> {
            attempts.incrementAndGet(); throw new RejectedExecutionException();
        }, () -> false, value -> fail("callback invoked"));
        assertEquals(1, attempts.get());
    }

    @Test public void missingCallbackDoesNotStartWork() {
        LocationPrivacy.unavailable(task -> fail("executor invoked"), () -> false, null);
        LocationPrivacy.unavailable(null, () -> false, value -> fail("callback invoked"));
    }
}
