package io.github.lightjunction.fkclaude;

import android.content.Context;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

final class ProfileStore {
    static final String BRIDGE = "environment_bridge";
    static final String SNAPSHOT = "environment-profile.snapshot";
    private ProfileStore() {}

    // Provider Binder calls and UI saves share the module process and this transaction lock.
    static synchronized Profile load(Context context) {
        return ProfilePersistence.load(new Storage(context));
    }

    static synchronized boolean exists(Context context) {
        Storage storage = new Storage(context);
        if (storage.snapshotExists()) return true;
        // Check the framework before interpreting a possibly unredirected private XML.
        return storage.readBridge() != null || storage.readActive() != null || legacyFile(context).isFile();
    }

    static synchronized void save(Context context, Profile profile) {
        ProfilePersistence.save(new Storage(context), profile);
    }

    static synchronized void save(Context context, Profile profile, Profile expectedBaseline) {
        ProfilePersistence.save(new Storage(context), profile, expectedBaseline);
    }

    @android.annotation.SuppressLint("WorldReadableFiles")
    @SuppressWarnings("deprecation")
    private static synchronized boolean publish(Context context, String encoded) {
        try {
            return context.getSharedPreferences(BRIDGE, Context.MODE_WORLD_READABLE).edit()
                    .putString("profile", encoded).commit();
        } catch (RuntimeException failure) {
            // SecurityException must not reach the provider caller as "try the old bridge".
            throw new IllegalStateException("Framework profile publication unavailable");
        }
    }

    private static final class Storage implements ProfilePersistence.Storage {
        private final Context context;
        private final AtomicFile snapshot;

        Storage(Context context) {
            this.context = context;
            snapshot = new AtomicFile(new File(context.getNoBackupFilesDir(), SNAPSHOT));
        }
        boolean snapshotExists() {
            return snapshot.getBaseFile().exists()
                    || new File(snapshot.getBaseFile().getPath() + ".bak").exists();
        }
        @Override public String readSnapshot() {
            if (!snapshotExists()) return null;
            return ProfileSnapshot.decode(readSnapshotBytes());
        }
        private byte[] readSnapshotBytes() {
            try (FileInputStream input = snapshot.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > ProfileSnapshot.MAX_BYTES)
                        throw new IllegalStateException("Profile snapshot too large");
                    bytes.write(buffer, 0, count);
                }
                return bytes.toByteArray();
            } catch (IOException failure) {
                throw new IllegalStateException("Profile snapshot unreadable");
            }
        }
        @Override public void writeSnapshot(String encoded) {
            byte[] wanted = ProfileSnapshot.encode(encoded);
            FileOutputStream output = null;
            try {
                output = snapshot.startWrite();
                output.write(wanted);
                snapshot.finishWrite(output);
                output = null;
                // finishWrite is void and may log an I/O error rather than throw.
                if (!Arrays.equals(wanted, readSnapshotBytes()))
                    throw new IllegalStateException("Profile snapshot verification failed");
            } catch (IOException | RuntimeException failure) {
                if (output != null) snapshot.failWrite(output);
                throw new IllegalStateException("Profile snapshot commit failed");
            }
        }
        @Override public String readActive() {
            try { return context.getSharedPreferences("environment", Context.MODE_PRIVATE).getString("profile", null); }
            catch (RuntimeException failure) { throw new IllegalStateException("Active profile unavailable"); }
        }
        @android.annotation.SuppressLint("WorldReadableFiles")
        @SuppressWarnings("deprecation")
        @Override public String readBridge() {
            try { return context.getSharedPreferences(BRIDGE, Context.MODE_WORLD_READABLE).getString("profile", null); }
            catch (RuntimeException failure) { throw new IllegalStateException("Framework profile unavailable"); }
        }
        @Override public String readLegacy() { return legacy(context); }
        @Override public boolean writeActive(String encoded) {
            try {
                return context.getSharedPreferences("environment", Context.MODE_PRIVATE).edit()
                        .putString("profile", encoded).commit();
            } catch (RuntimeException failure) { throw new IllegalStateException("Active profile commit unavailable"); }
        }
        @Override public boolean writeBridge(String encoded) { return publish(context, encoded); }
    }

    private static File legacyFile(Context context) {
        return new File(context.getApplicationInfo().dataDir, "shared_prefs/environment.xml");
    }
    private static String legacy(Context context) {
        File file = legacyFile(context);
        if (!file.isFile()) return null;
        if (file.length() > 1024 * 1024) throw new IllegalStateException("Legacy profile too large");
        try (java.io.InputStream input = new FileInputStream(file)) {
            org.xmlpull.v1.XmlPullParser parser = android.util.Xml.newPullParser();
            parser.setInput(input, "UTF-8");
            int event;
            while ((event = parser.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT)
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && "string".equals(parser.getName())
                        && "profile".equals(parser.getAttributeValue(null, "name"))) return parser.nextText();
            return null;
        } catch (IOException | org.xmlpull.v1.XmlPullParserException failure) {
            throw new IllegalStateException("Legacy profile unreadable");
        }
    }
}
