package io.github.lightjunction.fkclaude;

/**
 * Serializes recorder controls with event publication. Native callbacks must not
 * retain their queue lock after returning; publication never holds that lock.
 */
final class AuditRecordingState {
    static final long UNFENCED = Long.MIN_VALUE;
    static final long PAUSED = -1;
    @FunctionalInterface interface NativeControl { boolean setEnabled(boolean value); }
    private volatile boolean enabled;
    private long epoch;

    boolean isEnabled() { return enabled; }

    synchronized long capture() { return enabled ? epoch : PAUSED; }

    synchronized boolean setEnabled(boolean value, NativeControl nativeControl) {
        if (enabled == value) return true;
        enabled = value;
        epoch++;
        return nativeControl.setEnabled(value);
    }

    /** Installation observes the current state while holding the same control lock. */
    synchronized boolean synchronizeNative(NativeControl nativeControl) {
        return nativeControl.setEnabled(enabled);
    }

    /** Fences batches already drained from native code but not yet published. */
    synchronized boolean clear(NativeControl nativeControl, Runnable clearMemory) {
        epoch++;
        boolean cleared = nativeControl.setEnabled(false);
        clearMemory.run();
        boolean restored = !enabled || (cleared && nativeControl.setEnabled(true));
        return cleared && restored;
    }

    synchronized boolean publish(long expectedEpoch, boolean control, Runnable commit) {
        if ((!enabled && !control) || (expectedEpoch != UNFENCED && expectedEpoch != epoch)) return false;
        commit.run();
        return true;
    }
}
