package io.github.lightjunction.fkclaude;

import android.content.Context;

final class ProfileStore {
    private ProfileStore() {}
    static Profile load(Context context) {
        String saved = context.getSharedPreferences("environment", Context.MODE_PRIVATE).getString("profile", null);
        // Only the module UI creates initial random values, never the target processes.
        return saved == null ? new Profile(java.util.Map.of("enabled", "false")) : Profile.parse(saved);
    }
    static boolean exists(Context context) {
        return context.getSharedPreferences("environment", Context.MODE_PRIVATE).contains("profile");
    }
    static void save(Context context, Profile profile) {
        if (!context.getSharedPreferences("environment", Context.MODE_PRIVATE).edit()
                .putString("profile", profile.encode()).commit()) throw new IllegalStateException("Profile commit failed");
    }
}
