package io.github.lightjunction.fkclaude;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import java.util.Arrays;

/** Read-only cross-UID channel; write access never leaves the module process. */
public final class ProfileProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.lightjunction.fkclaude.profile";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);
    @Override public boolean onCreate() {
        try { ProfileStore.load(requireContext()); }
        catch (RuntimeException e) { android.util.Log.w("fkclaude", "Snapshot unavailable: " + e.getClass().getSimpleName()); }
        return true;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        String[] packages = requireContext().getPackageManager().getPackagesForUid(uid);
        if (uid != Process.myUid() && (packages == null || !Arrays.asList(packages).contains(Profile.TARGET)))
            throw new SecurityException("Caller not allowed");
        if (!"read".equals(method)) throw new UnsupportedOperationException("Read only");
        Profile profile;
        try { profile = ProfileStore.load(requireContext()); }
        catch (IllegalArgumentException e) {
            Bundle invalid = new Bundle(); invalid.putBoolean("profile_invalid", true); return invalid;
        }
        Bundle result = new Bundle();
        result.putString("profile", profile.encode());
        return result;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
