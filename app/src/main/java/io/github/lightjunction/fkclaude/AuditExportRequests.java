package io.github.lightjunction.fkclaude;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Main-thread ownership of document-picker requests. Only small tokens are saved. */
final class AuditExportRequests<T> {
    record Request(String token, int requestCode) {}
    private static final int RECENT_COMPLETIONS = 64;
    private final Map<T, Request> pending = new WeakHashMap<>();
    private final LinkedHashSet<String> completed = new LinkedHashSet<>();

    Request begin(T owner, int requestCode) {
        if (pending.containsKey(owner)) return null;
        Request request = new Request(UUID.randomUUID().toString(), requestCode);
        pending.put(owner, request);
        return request;
    }

    Request pending(T owner) { return pending.get(owner); }

    boolean restore(T owner, String token, int requestCode) {
        if (token == null || requestCode < 0 || requestCode > 0xffff || completed.contains(token)) return false;
        try {
            if (!UUID.fromString(token).toString().equals(token)) return false;
        } catch (IllegalArgumentException invalid) { return false; }
        Request current = pending.get(owner);
        if (current != null) return current.token().equals(token) && current.requestCode() == requestCode;
        // A configuration replacement owns its restored request; the old instance no longer does.
        pending.entrySet().removeIf(entry -> entry.getValue().token().equals(token));
        pending.put(owner, new Request(token, requestCode));
        return true;
    }

    boolean consume(T owner, int requestCode) {
        Request request = pending.get(owner);
        if (request == null || request.requestCode() != requestCode) return false;
        pending.remove(owner);
        completed.add(request.token());
        if (completed.size() > RECENT_COMPLETIONS) {
            var oldest = completed.iterator();
            oldest.next();
            oldest.remove();
        }
        return true;
    }

    void release(T owner) { pending.remove(owner); }
}
