package dev.agentos.app.capturetest;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.ResultReceiver;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.UUID;

/** SDK-only test transport; it is not installed in the production APK. */
public final class CaptureBridgeProvider extends ContentProvider {
    public static final String AUTHORITY = "dev.agentos.app.test.windowcapture";
    private static final String TARGET = "dev.agentos.app";

    @Override public boolean onCreate() { return true; }

    private void requireCaller() {
        try {
            int expected = getContext().getPackageManager().getPackageUid(TARGET, 0);
            if (Binder.getCallingUid() != expected || expected == Process.myUid())
                throw new SecurityException("Only the instrumented target UID may use this test bridge");
        } catch (PackageManager.NameNotFoundException missing) {
            throw new SecurityException("Instrumented target is not installed", missing);
        }
    }

    @Override public Bundle call(String authority, String method, String arg, Bundle extras) {
        requireCaller();
        if (!AUTHORITY.equals(authority)) throw new SecurityException("Unexpected bridge authority");
        return call(method, arg, extras);
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        requireCaller();
        if (arg != null || extras == null) throw new IllegalArgumentException("Explicit test session required");
        extras.setClassLoader(ResultReceiver.class.getClassLoader());
        String nonce = identifier(extras.getString("nonce"));
        Bundle result;
        switch (method) {
            case "open": result = CaptureBridgeService.open(nonce); break;
            case "status": result = CaptureBridgeService.status(nonce); break;
            case "end": result = CaptureBridgeService.end(nonce); break;
            case "capture":
                String id = identifier(extras.getString("requestId"));
                int windowId = extras.getInt("windowId", -1);
                Rect bounds = extras.getParcelable("bounds");
                ResultReceiver receiver = extras.getParcelable("receiver");
                if (windowId < 0 || bounds == null || bounds.isEmpty() || bounds.width() > 8192
                        || bounds.height() > 8192 || receiver == null)
                    throw new IllegalArgumentException("Missing bounded real window metadata or receiver");
                if (receiver.getClass() != ResultReceiver.class)
                    throw new IllegalArgumentException("Only a framework ResultReceiver proxy may cross the test bridge");
                result = CaptureBridgeService.capture(nonce, id, windowId, bounds, receiver);
                result.putString("receiverClass", receiver.getClass().getName());
                break;
            default: throw new UnsupportedOperationException("Unknown test bridge operation");
        }
        result.putInt("callingUid", Binder.getCallingUid());
        return result;
    }

    private static String identifier(String value) {
        if (value == null || value.length() != 36 || !UUID.fromString(value).toString().equals(value))
            throw new IllegalArgumentException("Canonical session identifier required");
        return value;
    }

    private UnsupportedOperationException denied() {
        requireCaller();
        return new UnsupportedOperationException("Only the fixed test call protocol is supported");
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
