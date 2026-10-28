package dev.agentos.app.numericstest;

import android.content.ContentProvider;
import android.content.ContentProviderClient;
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

/** Target-UID-only control transport; no document access or URI grants are exposed here. */
public final class NumericPreferencesControlProvider extends ContentProvider {
    public static final String AUTHORITY = "dev.agentos.app.test.numericpreferences.control";
    private static final String TARGET = "dev.agentos.app";

    @Override public boolean onCreate() { return true; }

    private void requireCaller() {
        try {
            int expected = getContext().getPackageManager().getPackageUid(TARGET, 0);
            if (Binder.getCallingUid() != expected || expected == Process.myUid())
                throw new SecurityException("Only the instrumented target UID may control synthetic documents");
        } catch (PackageManager.NameNotFoundException missing) {
            throw new SecurityException("Instrumented target is not installed");
        }
    }

    @Override public Bundle call(String authority, String method, String arg, Bundle extras) {
        requireCaller();
        if (!AUTHORITY.equals(authority)) throw new SecurityException("Unexpected control authority");
        return call(method, arg, extras);
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        requireCaller();
        validateControl(method, arg, extras);
        final ContentProviderClient client;
        // AMS checks the MANAGE_DOCUMENTS provider permission even when acquiring it for call().
        // Only acquisition uses our test-APK identity; no document operation or grant runs here.
        long identity = Binder.clearCallingIdentity();
        try {
            client = getContext().getContentResolver()
                    .acquireContentProviderClient(NumericPreferencesDocumentsProvider.AUTHORITY);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        if (client == null) throw new IllegalStateException("Synthetic document provider is unavailable");
        try (ContentProviderClient owned = client) {
            ContentProvider local = owned.getLocalContentProvider();
            if (local == null || local.getClass() != NumericPreferencesDocumentsProvider.class)
                throw new SecurityException("Both synthetic providers must share the same test-APK process");
            // Do not use client.call(): this direct call retains the restored target Binder UID.
            // The local provider checks that UID again before touching its own instance state.
            return ((NumericPreferencesDocumentsProvider) local).controlCall(method, arg, extras);
        }
    }

    private static void validateControl(String method, String arg, Bundle extras) {
        if (arg != null || method == null) throw new IllegalArgumentException("A fixed control operation is required");
        if ("configure".equals(method)) {
            if (extras == null || extras.size() != 1 || !extras.containsKey("mode"))
                throw new IllegalArgumentException("Only a fixed synthetic mode may be configured");
            String mode = extras.getString("mode");
            if (!"valid".equals(mode) && !"malformed".equals(mode)
                    && !"oversize".equals(mode) && !"blocked-open".equals(mode))
                throw new IllegalArgumentException("Unknown synthetic mode");
            return;
        }
        if (!"state".equals(method) && !"reset".equals(method)
                && !"release".equals(method) && !"export".equals(method))
            throw new UnsupportedOperationException("Unknown synthetic control operation");
        if (extras != null && !extras.isEmpty())
            throw new IllegalArgumentException("This control operation has no extras");
    }

    private UnsupportedOperationException denied() {
        requireCaller();
        return new UnsupportedOperationException("Only the fixed synthetic control call protocol is supported");
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
