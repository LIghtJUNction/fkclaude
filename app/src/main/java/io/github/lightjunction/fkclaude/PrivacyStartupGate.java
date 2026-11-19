package io.github.lightjunction.fkclaude;

/** Strict startup stays closed until one owned, offline WebView and all policy readbacks pass. */
final class PrivacyStartupGate {
    private enum State { PENDING, INITIALIZING, READY, FAILED }
    private volatile State state;
    private Thread owner;
    private Object bootstrapView;
    private boolean bootstrapOpen, bootstrapUsed, bootstrapVerified;

    PrivacyStartupGate(boolean strict) { state = strict ? State.PENDING : State.READY; }
    boolean isReady() { return state == State.READY; }

    synchronized boolean beginFinalization() {
        if (state == State.READY) return false;
        if (state != State.PENDING) throw new IllegalStateException("Privacy startup cannot restart");
        owner = Thread.currentThread();
        state = State.INITIALIZING;
        return true;
    }

    synchronized void openBootstrap() {
        requireOwner();
        if (bootstrapUsed) throw new IllegalStateException("Web bootstrap already consumed");
        bootstrapUsed = bootstrapOpen = true;
    }

    synchronized boolean allowConstructor(Object candidate) {
        if (state == State.READY) return true;
        if (!bootstrapOwner() || candidate == null) return false;
        if (bootstrapView == null) bootstrapView = candidate;
        return bootstrapView == candidate;
    }

    synchronized boolean allowAccess(Object candidate) {
        return state == State.READY || (bootstrapOwner() && candidate != null && bootstrapView == candidate);
    }

    synchronized void confirmBootstrap(Object candidate) {
        requireOwner();
        if (!bootstrapOpen || candidate == null || bootstrapView != candidate)
            throw new IllegalStateException("Unverified Web bootstrap");
        bootstrapVerified = true;
    }

    synchronized void closeBootstrap() {
        if (state != State.FAILED) requireOwner();
        bootstrapOpen = false;
        bootstrapView = null;
    }

    synchronized void complete() {
        requireOwner();
        if (!bootstrapUsed || bootstrapOpen || !bootstrapVerified || bootstrapView != null)
            throw new IllegalStateException("Privacy bootstrap incomplete");
        owner = null;
        state = State.READY; // Publish all completed hooks and readbacks only after the owned view is destroyed.
    }

    synchronized void fail() {
        state = State.FAILED;
        bootstrapOpen = false;
        bootstrapView = null;
        bootstrapVerified = false;
        owner = null;
    }

    private boolean bootstrapOwner() {
        return state == State.INITIALIZING && bootstrapOpen && owner == Thread.currentThread();
    }

    private void requireOwner() {
        if (state != State.INITIALIZING || owner != Thread.currentThread())
            throw new IllegalStateException("Privacy initializer unavailable");
    }
}
