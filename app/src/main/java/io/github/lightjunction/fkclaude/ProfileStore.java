package io.github.lightjunction.fkclaude;

import android.content.Context;

final class ProfileStore {
    static final String BRIDGE = "environment_bridge";
    private ProfileStore() {}
    static Profile load(Context context) {
        String saved = context.getSharedPreferences("environment", Context.MODE_PRIVATE).getString("profile", null);
        // API 93+ redirects preferences. Migrate the existing private profile before creating any IDs.
        if (saved == null) {
            saved = legacy(context);
            if (saved != null) {
                Profile.parse(saved);
                if (!context.getSharedPreferences("environment", Context.MODE_PRIVATE).edit().putString("profile", saved).commit())
                    throw new IllegalStateException("Profile migration failed");
            }
        }
        // Only the module UI creates initial random values, never the target processes.
        Profile profile = saved == null ? new Profile(java.util.Map.of("enabled", "false")) : Profile.parse(saved);
        publish(context, profile);
        return profile;
    }
    static boolean exists(Context context) {
        return context.getSharedPreferences("environment", Context.MODE_PRIVATE).contains("profile") || legacyFile(context).isFile();
    }
    static void save(Context context, Profile profile) {
        if (!context.getSharedPreferences("environment", Context.MODE_PRIVATE).edit()
                .putString("profile", profile.encode()).commit()) throw new IllegalStateException("Profile commit failed");
        publish(context, profile);
    }

    @android.annotation.SuppressLint("WorldReadableFiles")
    @SuppressWarnings("deprecation")
    static void publish(Context context, Profile profile) {
        try {
            // LSPosed API 93's randomized preference directory, not a public external-storage file.
            // Contains only the virtual environment; never store account tokens in this profile.
            if (!context.getSharedPreferences(BRIDGE, Context.MODE_WORLD_READABLE).edit()
                    .putString("profile", profile.encode()).commit()) throw new IllegalStateException("Snapshot commit failed");
        } catch (SecurityException e) {
            android.util.Log.w("fkclaude", "Framework preferences unavailable; private provider retained");
        }
    }

    private static java.io.File legacyFile(Context context) {
        return new java.io.File(context.getApplicationInfo().dataDir, "shared_prefs/environment.xml");
    }
    private static String legacy(Context context) {
        java.io.File file = legacyFile(context);
        if (!file.isFile()) return null;
        if (file.length() > 1024 * 1024) throw new IllegalArgumentException("Legacy profile too large");
        try (java.io.InputStream input = new java.io.FileInputStream(file)) {
            org.xmlpull.v1.XmlPullParser parser = android.util.Xml.newPullParser();
            parser.setInput(input, "UTF-8");
            int event;
            while ((event = parser.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT)
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && "string".equals(parser.getName())
                        && "profile".equals(parser.getAttributeValue(null, "name"))) return parser.nextText();
            return null;
        } catch (java.io.IOException | org.xmlpull.v1.XmlPullParserException e) { throw new IllegalArgumentException("Legacy profile unreadable", e); }
    }
}
