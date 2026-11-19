package io.github.lightjunction.fkclaude;

import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Immutable export contents and metadata from one completed-history snapshot. */
public final class AuditExportSnapshot {
    public static final class HistoryNotReadyException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private HistoryNotReadyException() { super("Audit history is still loading"); }
    }

    record Metadata(int pid, int maxEvents, long queueDropped, long diskErrors,
                    long memoryEvicted, long invalidHistory, boolean enabled) {}

    private final Instant capturedAt;
    private final List<String> events;
    private final Metadata metadata;

    private AuditExportSnapshot(Instant capturedAt, List<String> events, Metadata metadata) {
        this.capturedAt = capturedAt;
        this.events = List.copyOf(events);
        this.metadata = metadata;
    }

    static AuditExportSnapshot capture(Object lock, BooleanSupplier historyLoaded,
                                       Supplier<List<String>> events, Supplier<Metadata> metadata) {
        synchronized (lock) {
            if (!historyLoaded.getAsBoolean()) throw new HistoryNotReadyException();
            return new AuditExportSnapshot(Instant.now(), events.get(), metadata.get());
        }
    }

    /** Serialization reads only this snapshot, never the live recorder. */
    public String toJsonl() {
        StringBuilder out = new StringBuilder(512 + events.size() * 512);
        out.append("{\"schema\":\"fkclaude.audit.export.v1\",\"time\":\"").append(capturedAt)
                .append("\",\"scope\":\"current_process_retained_window\",\"pid\":").append(metadata.pid())
                .append(",\"retained\":").append(events.size())
                .append(",\"max_events\":").append(metadata.maxEvents())
                .append(",\"queue_dropped\":").append(metadata.queueDropped())
                .append(",\"disk_errors\":").append(metadata.diskErrors())
                .append(",\"memory_evicted\":").append(metadata.memoryEvicted())
                .append(",\"invalid_history\":").append(metadata.invalidHistory())
                .append(",\"history_loaded\":true")
                .append(",\"enabled\":").append(metadata.enabled())
                .append(",\"value_policy\":\"metadata_only\",\"server_receipt\":\"not_asserted\"}\n");
        for (String event : events) out.append(event).append('\n');
        return out.toString();
    }
}
