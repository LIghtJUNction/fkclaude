package io.github.lightjunction.fkclaude;

/** One authoritative profile; framework and private preferences are derived mirrors. */
final class ProfilePersistence {
    interface Storage {
        String readSnapshot();
        String readActive();
        String readBridge();
        String readLegacy();
        void writeSnapshot(String encoded);
        boolean writeActive(String encoded);
        boolean writeBridge(String encoded);
    }
    private ProfilePersistence() {}

    static Profile load(Storage storage) {
        try {
            String authority = storage.readSnapshot();
            Profile profile;
            if (authority != null) {
                profile = checked(authority);
            } else {
                // Read the bridge first. Failure to access the framework must not make an
                // unredirected, stale private XML look like a fresh authoritative profile.
                Profile bridge = optional(storage.readBridge());
                Profile active = optional(storage.readActive());
                if (bridge != null && bridge.flag("privacy_us_only")
                        && (active == null || !active.flag("privacy_us_only"))) profile = bridge;
                else profile = active != null ? active : bridge;
                if (profile == null) profile = optional(storage.readLegacy());
                if (profile == null) throw new IllegalStateException("Profile has not been configured");
            }
            String encoded = profile.encode();
            if (!encoded.equals(authority)) storage.writeSnapshot(encoded);
            synchronize(storage, encoded);
            return profile;
        } catch (RuntimeException failure) {
            throw state(failure);
        }
    }

    static final class Conflict extends IllegalStateException {
        Conflict() { super("Saved profile changed; reload before editing"); }
    }

    static void save(Storage storage, Profile profile) {
        save(storage, profile, null);
    }

    static void save(Storage storage, Profile profile, Profile expectedBaseline) {
        try {
            String encoded = profile.encode();
            checked(encoded);
            String authority = storage.readSnapshot();
            if (authority == null) {
                if (expectedBaseline != null) throw new Conflict();
            } else {
                Profile current = checked(authority);
                boolean sameContent = current.copy().equals(profile.copy());
                boolean sameBaseline = expectedBaseline != null && current.copy().equals(expectedBaseline.copy());
                // Same-content retry repairs mirrors after a partial publication. An absent
                // baseline otherwise means bootstrap, never permission to overwrite policy.
                if (!sameContent && !sameBaseline) throw new Conflict();
            }
            if (!encoded.equals(authority)) storage.writeSnapshot(encoded);
            // A failed mirror write may already have changed SharedPreferences memory.
            // Do not claim rollback. The verified snapshot remains the source of truth.
            synchronize(storage, encoded);
        } catch (RuntimeException failure) {
            throw state(failure);
        }
    }

    static Profile restoreDraft(Profile saved, String baseline, String draft) {
        if (baseline == null || draft == null) return saved;
        try {
            return saved.copy().equals(checked(baseline).copy()) ? checked(draft) : saved;
        } catch (RuntimeException invalidDraft) {
            return saved;
        }
    }

    private static void synchronize(Storage storage, String encoded) {
        if (!storage.writeActive(encoded)) throw new IllegalStateException("Active profile commit failed");
        if (!storage.writeBridge(encoded)) throw new IllegalStateException("Profile publication failed");
    }

    private static Profile optional(String encoded) {
        return encoded == null ? null : checked(encoded);
    }
    private static Profile checked(String encoded) {
        Profile profile = Profile.parse(encoded);
        if (!profile.has("enabled")) throw new IllegalStateException("Profile state is incomplete");
        return profile;
    }
    private static IllegalStateException state(RuntimeException failure) {
        // In the target, IllegalArgumentException/SecurityException mean an unavailable
        // provider and permit fallback. Publication/data failures must instead fail closed.
        return failure instanceof IllegalStateException state ? state
                : new IllegalStateException("Profile storage unavailable");
    }
}
