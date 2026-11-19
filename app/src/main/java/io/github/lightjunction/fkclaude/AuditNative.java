package io.github.lightjunction.fkclaude;

import android.system.OsConstants;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Process-local socket metadata. Native code never receives or copies body buffers. */
final class AuditNative {
    private static volatile boolean ready;
    private static volatile boolean started;
    private static volatile boolean errorReported;

    private AuditNative() {}

    /** Must run after IpGuard.install when that independent guard is enabled. */
    static synchronized void install(boolean ipGuardEnabled) {
        if (started) return;
        try {
            System.loadLibrary("fkclaude");
            int mask = nativeInstall(ipGuardEnabled);
            if (mask < 0) {
                AuditLog.record("system", "native_observer_unavailable", "fkclaude", "unsupported",
                        Map.of("coverage", "native_inline_hooks_unavailable"));
                return;
            }
            started = true;
            ready = true;
            if (!AuditLog.synchronizeNative()) throw new IllegalStateException("Native control unavailable");
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
                Thread worker = new Thread(task, "fkclaude-audit-native");
                worker.setDaemon(true);
                return worker;
            });
            executor.setRemoveOnCancelPolicy(true);
            executor.scheduleWithFixedDelay(AuditNative::poll, 0, 500, TimeUnit.MILLISECONDS);
            AuditLog.record("system", "native_observer_installed", "fkclaude", mask == 127 ? "installed" : "partial",
                    Map.of("installed", Integer.toString(Integer.bitCount(mask)),
                            "requested", "7", "failed", Integer.toString(7 - Integer.bitCount(mask)),
                            "coverage", ipGuardEnabled ? "libc_with_shared_ip_guard" : "libc_internet_sockets",
                            "value_policy", "destination_metadata_only",
                            "observation", "opaque_bytes_no_payload_or_source_address"));
        } catch (Throwable failure) {
            if (ready) {
                try { nativeSetEnabled(false); } catch (Throwable ignored) { }
            }
            ready = false;
            AuditLog.record("system", "native_observer_unavailable", "fkclaude", "failed",
                    Map.of("error_type", failure.getClass().getSimpleName(), "coverage", "native_unavailable"));
        }
    }

    /** Synchronous pause; clears pending native records before returning. */
    static boolean setEnabled(boolean enabled) {
        if (!ready) return !started;
        try {
            nativeSetEnabled(enabled);
            return true;
        }
        catch (Throwable failure) {
            ready = false;
            AuditLog.record("system", "native_observer_control_failed", "fkclaude", "failed",
                    Map.of("error_type", failure.getClass().getSimpleName()));
            return false;
        }
    }

    private static void poll() {
        if (!ready) return;
        try {
            // Only the serialized UI/control path changes native recording state.
            // A delayed poll cannot overwrite a newer pause/resume decision.
            long epoch = AuditLog.captureEpoch();
            if (epoch == AuditRecordingState.PAUSED) return;
            String[] lines = nativeDrain();
            if (lines == null) throw new IllegalStateException("Native drain unavailable");
            for (String line : lines) {
                if (line == null) continue;
                String[] fields = line.split("\\|", -1);
                if (fields.length == 2 && fields[0].equals("dropped")) {
                    long count = Long.parseLong(fields[1]);
                    if (count > 0) AuditLog.recordIfCurrent(epoch, "system", "native_queue_dropped", "fkclaude", "dropped",
                            Map.of("queue_dropped", Long.toString(count),
                                    "reason", "native_queue_contention_capacity_or_batch_limit", "layer", "native"));
                    continue;
                }
                if (fields.length != 13) throw new IllegalArgumentException("Invalid native metadata");
                String operation = fields[3];
                if (!java.util.Set.of("connect", "send", "sendto", "sendmsg", "sendmmsg", "write", "writev").contains(operation))
                    throw new IllegalArgumentException("Unknown native operation");
                long result = Long.parseLong(fields[8]);
                int error = Integer.parseInt(fields[10]);
                int flags = Integer.parseInt(fields[11]);
                String outcome = (flags & 1) != 0 ? "blocked"
                        : operation.equals("connect") && error == OsConstants.EINPROGRESS ? "attempted"
                        : result < 0 ? "failed" : "returned";
                Map<String, String> details = new LinkedHashMap<>();
                details.put("layer", "native");
                details.put("coverage", "encrypted_or_opaque");
                details.put("native_elapsed_ms", fields[0]);
                details.put("native_thread_id", fields[1]);
                details.put("fd", fields[2]);
                details.put("address_family", fields[4].equals("4") ? "ipv4" : "ipv6");
                if (!fields[5].isEmpty()) details.put("peer_address", fields[5]);
                details.put("port", fields[6]);
                int socketType = Integer.parseInt(fields[7]);
                details.put("transport", socketType == OsConstants.SOCK_STREAM ? "tcp"
                        : socketType == OsConstants.SOCK_DGRAM ? "udp" : "socket");
                details.put("return_value", fields[8]);
                details.put("bytes", fields[9]);
                details.put("errno", fields[10]);
                details.put("batch_count", fields[12]);
                details.put("observation", operation.equals("connect") ? "connection_status"
                        : (flags & 2) != 0 ? "per_message_bytes_accepted_by_socket" : "bytes_accepted_by_socket");
                details.put("caller", (flags & 4) != 0 ? "fkclaude_ip_guard_probe" : "application");
                details.put("classification", (flags & 4) != 0 ? "instrumentation" : "unknown");
                if (error != 0) details.put("error_type", "errno_" + error);
                AuditLog.recordIfCurrent(epoch, "network", "native_" + operation, "libc." + operation, outcome, details);
            }
        } catch (Throwable failure) {
            if (!errorReported) {
                errorReported = true;
                AuditLog.record("system", "native_observer_error", "fkclaude", "failed",
                        Map.of("error_type", failure.getClass().getSimpleName(), "layer", "native"));
            }
        }
    }

    private static native int nativeInstall(boolean ipGuardEnabled);
    private static native void nativeSetEnabled(boolean enabled);
    private static native String[] nativeDrain();
}
