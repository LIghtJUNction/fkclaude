package io.github.lightjunction.fkclaude;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Delivers an unavailable result without ever consulting a real location provider. */
final class LocationPrivacy {
    private LocationPrivacy() {}

    static <T> void unavailable(Executor executor, BooleanSupplier cancelled, Consumer<T> callback) {
        if (executor == null || callback == null || cancelled.getAsBoolean()) return;
        try {
            executor.execute(() -> {
                if (!cancelled.getAsBoolean()) callback.accept(null);
            });
        } catch (RejectedExecutionException ignored) {
            // The caller has already suppressed the original request. Never retry the real provider.
        }
    }
}
