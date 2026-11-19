package io.github.lightjunction.fkclaude;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Worker-confined cache of the current window only, keyed by exact event bytes. */
final class AuditEventCache<T> {
    private final int maximum;
    private Map<String, T> window = Map.of();

    AuditEventCache(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("Empty cache");
        this.maximum = maximum;
    }

    List<T> parse(List<String> lines, Function<String, T> parser) {
        int count = Math.min(maximum, lines.size());
        List<T> result = new ArrayList<>(count);
        Map<String, T> next = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            String line = lines.get(i);
            T value = next.get(line);
            if (value == null) value = window.get(line);
            if (value == null) value = Objects.requireNonNull(parser.apply(line));
            next.put(line, value);
            result.add(value);
        }
        window = next;
        return result;
    }

    int size() { return window.size(); }
}
