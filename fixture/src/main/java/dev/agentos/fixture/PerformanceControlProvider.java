package dev.agentos.fixture;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.UUID;

/** Fixed, UID-checked measurement protocol in the synthetic target APK only. */
public final class PerformanceControlProvider extends ContentProvider {
    private static final String AUTHORITY = "dev.agentos.fixture.performance";
    @Override public boolean onCreate() { return true; }

    private void requireCaller() {
        try {
            int expected = getContext().getPackageManager().getPackageUid("dev.agentos.app", 0);
            if (Binder.getCallingUid() != expected || expected == Process.myUid())
                throw new SecurityException("Instrumented Agent UID required");
        } catch (PackageManager.NameNotFoundException missing) {
            throw new SecurityException("Instrumented Agent package is not installed", missing);
        }
    }
    @Override public Bundle call(String authority, String method, String arg, Bundle extras) {
        requireCaller();
        if (!AUTHORITY.equals(authority)) throw new SecurityException("Unexpected measurement authority");
        return call(method, arg, extras);
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        requireCaller();
        if ("status".equals(method)) {
            if (arg != null || extras != null) throw new IllegalArgumentException("Status takes no arguments");
            return PerformanceProbe.status();
        }
        String id = identifier(arg);
        switch (method) {
            case "begin":
                if (extras != null) throw new IllegalArgumentException("Begin takes only a session UUID");
                return PerformanceProbe.begin(id);
            case "end":
                if (extras != null) throw new IllegalArgumentException("End takes only a session UUID");
                return PerformanceProbe.end(id);
            case "page":
                if (extras == null || extras.size() != 1 || !(extras.get("offset") instanceof Integer))
                    throw new IllegalArgumentException("Page takes one integer offset");
                return PerformanceProbe.page(id, extras.getInt("offset"));
            default: throw new UnsupportedOperationException("Unknown measurement operation");
        }
    }
    private static String identifier(String id) {
        if (id == null || id.length() != 36 || !UUID.fromString(id).toString().equals(id))
            throw new IllegalArgumentException("Canonical session UUID required");
        return id;
    }
    private UnsupportedOperationException denied() {
        requireCaller();
        return new UnsupportedOperationException("Only the fixed measurement call protocol is supported");
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw denied(); }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o, CancellationSignal c) { throw denied(); }
    @Override public Cursor query(Uri u, String[] p, Bundle a, CancellationSignal c) { throw denied(); }
    @Override public String getType(Uri u) { throw denied(); }
    @Override public String getTypeAnonymous(Uri u) { throw denied(); }
    @Override public Uri canonicalize(Uri u) { throw denied(); }
    @Override public Uri uncanonicalize(Uri u) { throw denied(); }
    @Override public boolean refresh(Uri u, Bundle b, CancellationSignal c) { throw denied(); }
    @Override public Uri insert(Uri u, ContentValues v) { throw denied(); }
    @Override public Uri insert(Uri u, ContentValues v, Bundle b) { throw denied(); }
    @Override public int bulkInsert(Uri u, ContentValues[] v) { throw denied(); }
    @Override public int delete(Uri u, String s, String[] a) { throw denied(); }
    @Override public int delete(Uri u, Bundle b) { throw denied(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw denied(); }
    @Override public int update(Uri u, ContentValues v, Bundle b) { throw denied(); }
    @Override public ParcelFileDescriptor openFile(Uri u, String m) { throw denied(); }
    @Override public ParcelFileDescriptor openFile(Uri u, String m, CancellationSignal c) { throw denied(); }
    @Override public AssetFileDescriptor openAssetFile(Uri u, String m) { throw denied(); }
    @Override public AssetFileDescriptor openAssetFile(Uri u, String m, CancellationSignal c) { throw denied(); }
    @Override public String[] getStreamTypes(Uri u, String f) { throw denied(); }
    @Override public AssetFileDescriptor openTypedAssetFile(Uri u, String m, Bundle b) { throw denied(); }
    @Override public AssetFileDescriptor openTypedAssetFile(Uri u, String m, Bundle b, CancellationSignal c) { throw denied(); }
    @Override public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> o) { throw denied(); }
    @Override public ContentProviderResult[] applyBatch(String a, ArrayList<ContentProviderOperation> o) { throw denied(); }
    @Override public void dump(FileDescriptor f, PrintWriter w, String[] a) { throw denied(); }
}
