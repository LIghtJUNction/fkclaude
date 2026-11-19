package io.github.lightjunction.fkclaude;

/**
 * Main-thread state for one asynchronous list refresh. Explicit user requests
 * can run while automatic refresh is paused; stale results can never do so.
 */
final class AuditRefreshGate {
    record Ticket(long generation, boolean explicit) {}
    private long generation;
    private boolean requested;
    private Ticket active;

    void request() {
        generation++;
        requested = true;
    }

    /**
     * Pause/close discards all pending work. Activity suspension retains an
     * explicit search/filter request so it can be retried on the next resume.
     */
    void invalidate(boolean preserveExplicit) {
        boolean activeExplicit = active != null && active.generation() == generation && active.explicit();
        generation++;
        requested = preserveExplicit && (requested || activeExplicit);
    }

    Ticket begin(boolean automaticAllowed, boolean dataChanged) {
        if (active != null || !requested && (!automaticAllowed || !dataChanged)) return null;
        active = new Ticket(generation, requested);
        requested = false;
        return active;
    }

    boolean complete(Ticket ticket) {
        if (active != ticket) return false;
        active = null;
        return ticket.generation() == generation;
    }

    void rejected(Ticket ticket) {
        if (active != ticket) return;
        active = null;
        requested |= ticket.explicit();
    }

    boolean hasRequest() { return requested; }
}
