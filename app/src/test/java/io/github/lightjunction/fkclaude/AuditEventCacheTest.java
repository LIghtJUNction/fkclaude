package io.github.lightjunction.fkclaude;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AuditEventCacheTest {
    @Test public void aSlidingWindowParsesOnlyNewEvents() {
        AuditEventCache<Object> cache = new AuditEventCache<>(300);
        AtomicInteger parses = new AtomicInteger();
        Function<String, Object> parse = line -> { parses.incrementAndGet(); return new Object(); };
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 300; i++) lines.add("event-" + i);
        List<Object> first = cache.parse(lines, parse);
        assertEquals(300, parses.get());
        lines.add(0, "event-new");
        lines.remove(lines.size() - 1);
        List<Object> second = cache.parse(lines, parse);
        assertEquals(301, parses.get());
        assertSame(first.get(0), second.get(1));
        assertEquals(300, cache.size());
    }

    @Test public void cacheDropsEventsOutsideTheCurrentWindowAndIsBounded() {
        AuditEventCache<Object> cache = new AuditEventCache<>(2);
        AtomicInteger parses = new AtomicInteger();
        Function<String, Object> parse = line -> { parses.incrementAndGet(); return new Object(); };
        assertEquals(2, cache.parse(List.of("a", "b", "c"), parse).size());
        assertEquals(2, parses.get());
        assertEquals(2, cache.size());
        cache.parse(List.of("c"), parse);
        assertEquals(1, cache.size());
        cache.parse(List.of("a"), parse);
        assertEquals(4, parses.get()); // "a" was dropped, not kept in an unbounded history cache.
        cache.parse(List.of(), parse);
        assertEquals(0, cache.size());
    }

    @Test public void exactContentIsRequiredForReuseAcrossSessionsOrMetadataChanges() {
        AuditEventCache<Object> cache = new AuditEventCache<>(3);
        AtomicInteger parses = new AtomicInteger();
        Function<String, Object> parse = line -> { parses.incrementAndGet(); return new Object(); };
        String first = "{session:a,seq:1,outcome:attempted}";
        String changed = "{session:a,seq:1,outcome:response_received}";
        String otherSession = "{session:b,seq:1,outcome:attempted}";
        List<Object> previous = cache.parse(List.of(first), parse);
        List<Object> current = cache.parse(List.of(first, changed, otherSession), parse);
        assertEquals(3, parses.get());
        assertSame(previous.get(0), current.get(0));
        assertNotSame(current.get(0), current.get(1));
        assertNotSame(current.get(0), current.get(2));
    }
}
