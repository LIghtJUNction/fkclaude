package io.github.lightjunction.fkclaude;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local, bounded, metadata-only audit trail. Hooks enqueue without disk I/O.
 * Files are isolated by Android process name. This is an observation log, not a
 * trusted recorder against code that can modify the same application process.
 */
public final class AuditLog {
    private static final int MAX_EVENTS = 1000;
    private static final int MAX_LINE_BYTES = 4096;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int FILE_COUNT = 3;
    private static final AuditRecordingState LOCK = new AuditRecordingState();
    private static final ArrayDeque<Entry> HISTORY = new ArrayDeque<>();
    private static final ArrayBlockingQueue<Pending> QUEUE = new ArrayBlockingQueue<>(512);
    private static final ThreadLocal<Boolean> INTERNAL = ThreadLocal.withInitial(() -> false);
    private static final CountDownLatch HISTORY_READY = new CountDownLatch(1);
    private static final AtomicLong REVISION = new AtomicLong();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong DISK_ERRORS = new AtomicLong();
    private static final AtomicLong INVALID_HISTORY = new AtomicLong();
    private static final AtomicLong EVICTED = new AtomicLong();
    private static volatile boolean initialized, historyLoaded;
    private static volatile String lastError = "", session = "";
    private static volatile int pid;
    private static File directory;
    private static String filePrefix;
    private static SharedPreferences preferences;

    private record Entry(String category, String json) {}
    private record Pending(long generation, Entry entry) {}
    private AuditLog() {}

    public static void init(Context context) {
        if (context == null || initialized) return;
        boolean previous = isInternal();
        INTERNAL.set(true);
        try {
            synchronized (LOCK) {
                if (initialized) return;
                Context app = context.getApplicationContext();
                if (app == null) app = context;
                preferences = app.getSharedPreferences("fkclaude_audit", Context.MODE_PRIVATE);
                boolean initialEnabled = preferences.getBoolean("enabled", true);
                LOCK.setEnabled(initialEnabled, AuditNative::setEnabled);
                directory = new File(app.getNoBackupFilesDir(), "fkclaude-audit");
                String process = Application.getProcessName();
                if (process == null) process = app.getPackageName();
                filePrefix = "audit-" + Integer.toHexString(process.hashCode());
                pid = Process.myPid();
                session = UUID.randomUUID().toString();
                Thread worker = new Thread(() -> writeLoop(initialEnabled), "fkclaude-audit-writer");
                worker.setDaemon(true);
                initialized = true;
                worker.start();
            }
        } catch (Throwable failure) {
            initialized = false;
            LOCK.setEnabled(false, AuditNative::setEnabled);
            failed(failure);
        } finally { INTERNAL.set(previous); }
        record("system", "audit_started", "fkclaude", "observed", Map.of(
                "max_events", String.valueOf(MAX_EVENTS), "max_file_bytes", String.valueOf(MAX_FILE_BYTES),
                "file_count", String.valueOf(FILE_COUNT), "value_policy", "metadata_only",
                "coverage", "current_process"));
    }

    /** Hooks should skip callbacks originating in the logger itself. */
    public static boolean isInternal() { return Boolean.TRUE.equals(INTERNAL.get()); }
    public static boolean isEnabled() { return initialized && LOCK.isEnabled(); }
    public static long revision() { return REVISION.get(); }

    public static void setEnabled(boolean value) {
        if (!initialized) return;
        synchronized (LOCK) {
            if (LOCK.isEnabled() == value) return;
            boolean nativeApplied = LOCK.setEnabled(value, AuditNative::setEnabled);
            REVISION.incrementAndGet();
            // Control events describe the recorder, not monitored application activity.
            add("system", "recording_changed", "fkclaude", value ? "enabled" : "disabled",
                    Map.of("enabled", String.valueOf(value),
                            "state", nativeApplied ? "applied" : "native_control_failed"), true);
        }
    }

    static long captureEpoch() { return initialized ? LOCK.capture() : AuditRecordingState.PAUSED; }

    static boolean synchronizeNative() {
        return LOCK.synchronizeNative(AuditNative::setEnabled);
    }

    static void recordIfCurrent(long epoch, String category, String action, String source,
                                String outcome, Map<String, String> details) {
        add(category, action, source, outcome, details, false, epoch);
    }

    public static void record(String category, String action, String source, String outcome,
                              Map<String, String> details) {
        add(category, action, source, outcome, details, false);
    }

    private static void add(String category, String action, String source, String outcome,
                            Map<String, String> details, boolean control) {
        add(category, action, source, outcome, details, control, AuditRecordingState.UNFENCED);
    }

    private static void add(String category, String action, String source, String outcome,
                            Map<String, String> details, boolean control, long expectedEpoch) {
        if (!initialized || isInternal() || (!LOCK.isEnabled() && !control)) return;
        INTERNAL.set(true);
        try {
            String safeCategory = category != null && category.matches("[a-z_]{1,32}") ? category : "unknown";
            Map<String, String> safeDetails = AuditSanitizer.details(details);
            LOCK.publish(expectedEpoch, control, () -> {
                Entry entry = new Entry(safeCategory, eventJson(safeCategory, action, source, outcome, safeDetails));
                if (entry.json().getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
                    entry = new Entry(safeCategory, eventJson(safeCategory, action, source, outcome,
                            Map.of("reason", "metadata_limit", "value_policy", "metadata_only")));
                }
                appendMemory(entry);
                if (!QUEUE.offer(new Pending(GENERATION.get(), entry))) DROPPED.incrementAndGet();
                REVISION.incrementAndGet();
            });
        } catch (Throwable failure) { failed(failure); }
        finally { INTERNAL.set(false); }
    }

    private static String eventJson(String category, String action, String source, String outcome,
                                    Map<String, String> details) {
        long sequence = SEQUENCE.incrementAndGet();
        StringBuilder out = new StringBuilder(512);
        out.append("{\"schema\":\"fkclaude.audit.v1\",\"session_id\":").append(quote(session))
                .append(",\"seq\":").append(sequence)
                .append(",\"time\":").append(quote(Instant.now().toString()))
                .append(",\"elapsed_ms\":").append(SystemClock.elapsedRealtime())
                .append(",\"pid\":").append(pid)
                .append(",\"thread_id\":").append(Thread.currentThread().getId())
                .append(",\"category\":").append(quote(category))
                .append(",\"action\":").append(quote(AuditSanitizer.identifier(action == null ? "" : action)))
                .append(",\"source\":").append(quote(AuditSanitizer.identifier(source == null ? "" : source)))
                .append(",\"outcome\":").append(quote(AuditSanitizer.identifier(outcome == null ? "" : outcome)))
                .append(",\"evidence\":\"observed\",\"details\":{");
        boolean first = true;
        for (Map.Entry<String, String> detail : details.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append(quote(detail.getKey())).append(':').append(quote(detail.getValue()));
        }
        return out.append("}}").toString();
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32 || Character.isSurrogate(c))
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    public static List<String> snapshot(String query, String category, boolean nonConversationOnly, int limit) {
        String needle = query == null ? "" : query.substring(0, Math.min(query.length(), 256)).toLowerCase(Locale.ROOT);
        int maximum = Math.min(MAX_EVENTS, Math.max(0, limit));
        ArrayList<String> result = new ArrayList<>();
        if (maximum == 0) return result;
        ArrayList<Entry> copy;
        synchronized (LOCK) { copy = new ArrayList<>(HISTORY); }
        for (int index = copy.size() - 1; index >= 0 && result.size() < maximum; index--) {
            Entry entry = copy.get(index);
            if (matches(entry.json(), entry.category(), needle, category, nonConversationOnly))
                result.add(entry.json());
        }
        return result;
    }

    static boolean matches(String json, String eventCategory, String query, String category, boolean nonConversationOnly) {
        return (category == null || category.isEmpty() || category.equals("all") || category.equals(eventCategory))
                && (!nonConversationOnly || AuditClassifier.isNonConversationNetwork(eventCategory))
                && (query == null || query.isEmpty() || json.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)));
    }

    public static String summary() {
        synchronized (LOCK) {
            return (isEnabled() ? "Recording" : "Paused") + " | PID " + pid
                    + " | " + HISTORY.size() + "/" + MAX_EVENTS + " retained"
                    + " | evicted " + EVICTED.get() + " | queue dropped " + DROPPED.get() + " | disk errors " + DISK_ERRORS.get()
                    + (INVALID_HISTORY.get() > 0 ? " | invalid history " + INVALID_HISTORY.get() : "")
                    + (!historyLoaded ? " | loading history" : "")
                    + (lastError.isEmpty() ? "" : " | " + lastError);
        }
    }

    public static boolean isHistoryLoaded() { return historyLoaded; }

    /** For background export work only; a timeout leaves the target document untouched. */
    public static boolean awaitHistoryLoaded(long timeoutMs) throws InterruptedException {
        if (timeoutMs < 0) throw new IllegalArgumentException("Negative history wait");
        return HISTORY_READY.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Capture one retained window, its control state and counters under the recorder lock. */
    public static AuditExportSnapshot exportSnapshot() {
        return AuditExportSnapshot.capture(LOCK, () -> historyLoaded, () -> {
            ArrayList<String> lines = new ArrayList<>(HISTORY.size());
            for (Entry entry : HISTORY) lines.add(entry.json());
            return lines;
        }, () -> new AuditExportSnapshot.Metadata(pid, MAX_EVENTS, DROPPED.get(), DISK_ERRORS.get(),
                EVICTED.get(), INVALID_HISTORY.get(), LOCK.isEnabled()));
    }

    /** Exports the retained view, not an unbounded archive. Throws until history is ready. */
    public static String exportJsonl() { return exportSnapshot().toJsonl(); }

    /** Clear is asynchronous on disk; generation fencing prevents queued events returning. */
    public static void clear() {
        if (!initialized) return;
        synchronized (LOCK) {
            boolean nativeCleared = LOCK.clear(AuditNative::setEnabled, () -> {
                GENERATION.incrementAndGet();
                QUEUE.clear();
                HISTORY.clear();
                EVICTED.set(0);
                REVISION.incrementAndGet();
            });
            add("system", "audit_cleared", "fkclaude", nativeCleared ? "observed" : "partial",
                    Map.of("coverage", "current_process",
                            "state", nativeCleared ? "native_reset_applied" : "native_reset_unconfirmed"), true);
        }
    }

    private static void appendMemory(Entry entry) {
        while (HISTORY.size() >= MAX_EVENTS) { HISTORY.removeFirst(); EVICTED.incrementAndGet(); }
        HISTORY.addLast(entry);
    }

    private static File file(int rotation) {
        return new File(directory, filePrefix + (rotation == 0 ? "" : "." + rotation) + ".jsonl");
    }

    private static void writeLoop(boolean savedEnabled) {
        INTERNAL.set(true);
        // Files on startup predate any clear(), including one before this worker starts.
        long diskGeneration = 0;
        OutputStream stream = null;
        long fileSize = 0;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Audit directory unavailable");
            restoreHistory(diskGeneration);
        } catch (Throwable failure) { failed(failure); }
        finally {
            synchronized (LOCK) {
                historyLoaded = true;
                REVISION.incrementAndGet();
            }
            HISTORY_READY.countDown();
        }
        for (;;) {
            try {
                if (savedEnabled != LOCK.isEnabled()) {
                    boolean desired = LOCK.isEnabled();
                    if (!preferences.edit().putBoolean("enabled", desired).commit())
                        throw new IOException("Audit preference commit failed");
                    savedEnabled = desired;
                }
                long currentGeneration = GENERATION.get();
                if (currentGeneration != diskGeneration) {
                    close(stream); stream = null; fileSize = 0;
                    for (int index = 0; index < FILE_COUNT; index++)
                        if (file(index).exists() && !file(index).delete()) throw new IOException("Audit clear failed");
                    diskGeneration = currentGeneration;
                }
                Pending pending = QUEUE.poll(400, TimeUnit.MILLISECONDS);
                if (pending == null) continue;
                int batch = 0;
                do {
                    if (pending.generation() == GENERATION.get()) {
                        // clear() can run while the writer is blocked in poll().
                        if (pending.generation() != diskGeneration) {
                            close(stream); stream = null; fileSize = 0;
                            for (int index = 0; index < FILE_COUNT; index++)
                                if (file(index).exists() && !file(index).delete()) throw new IOException("Audit clear failed");
                            diskGeneration = pending.generation();
                        }
                        byte[] bytes = (pending.entry().json() + "\n").getBytes(StandardCharsets.UTF_8);
                        if (stream == null) {
                            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Audit directory unavailable");
                            fileSize = AuditFileAppender.prepare(file(0));
                            stream = new BufferedOutputStream(new FileOutputStream(file(0), true), 16384);
                        }
                        if (fileSize + bytes.length > MAX_FILE_BYTES) {
                            close(stream); stream = null;
                            rotate();
                            stream = new BufferedOutputStream(new FileOutputStream(file(0), false), 16384);
                            fileSize = 0;
                        }
                        stream.write(bytes);
                        fileSize += bytes.length;
                    }
                    pending = ++batch < 64 ? QUEUE.poll() : null;
                } while (pending != null);
                if (stream != null) stream.flush();
            } catch (InterruptedException ignored) {
                // Android owns this process. A hook must not terminate host work.
            } catch (Throwable failure) {
                close(stream); stream = null;
                failed(failure);
                try { Thread.sleep(400); } catch (InterruptedException ignored) { /* Retry remains bounded. */ }
            }
        }
    }

    private static void rotate() throws IOException {
        File oldest = file(FILE_COUNT - 1);
        if (oldest.exists() && !oldest.delete()) throw new IOException("Audit retention delete failed");
        for (int index = FILE_COUNT - 2; index >= 0; index--)
            if (file(index).exists() && !file(index).renameTo(file(index + 1)))
                throw new IOException("Audit rotation failed");
    }

    private static void restoreHistory(long generation) {
        ArrayDeque<Entry> previous = new ArrayDeque<>();
        for (int index = FILE_COUNT - 1; index >= 0; index--) {
            File source = file(index);
            if (!source.isFile()) continue;
            if (source.length() > MAX_FILE_BYTES + MAX_LINE_BYTES) { INVALID_HISTORY.incrementAndGet(); continue; }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(source), StandardCharsets.UTF_8), 16384)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) { INVALID_HISTORY.incrementAndGet(); continue; }
                    try {
                        org.json.JSONObject object = new org.json.JSONObject(line);
                        String category = object.getString("category");
                        if (!"fkclaude.audit.v1".equals(object.getString("schema"))
                                || !category.matches("[a-z_]{1,32}") || !object.has("session_id") || !object.has("seq")) {
                            INVALID_HISTORY.incrementAndGet(); continue;
                        }
                        if (previous.size() == MAX_EVENTS) previous.removeFirst();
                        previous.addLast(new Entry(category, line));
                    } catch (org.json.JSONException | RuntimeException invalid) { INVALID_HISTORY.incrementAndGet(); }
                }
            } catch (IOException failure) { failed(failure); }
        }
        synchronized (LOCK) {
            if (GENERATION.get() != generation) return;
            ArrayList<Entry> current = new ArrayList<>(HISTORY);
            HISTORY.clear();
            for (Entry entry : previous) appendMemory(entry);
            for (Entry entry : current) appendMemory(entry);
        }
    }

    private static void close(OutputStream stream) {
        if (stream != null) try { stream.close(); } catch (IOException failure) { failed(failure); }
    }
    private static void failed(Throwable failure) {
        synchronized (LOCK) {
            DISK_ERRORS.incrementAndGet();
            lastError = failure.getClass().getSimpleName();
            REVISION.incrementAndGet();
        }
    }
}
