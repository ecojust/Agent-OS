package dev.agentos.fixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Test-only Binder control that stays responsive while the fixture UI waits on a root reply. */
public final class SlowReadControlProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String token, Bundle extras) {
        if (!"dev.agentos.app".equals(getCallingPackage())) throw new SecurityException("Synthetic instrumentation caller required");
        if (token == null || !token.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid barrier token");
        switch (method) {
            case "arm": return SlowReadBarrier.arm(token);
            case "status": return SlowReadBarrier.status(token);
            case "release": return SlowReadBarrier.release(token);
            case "finish": return SlowReadBarrier.finish(token);
            case "clear": return SlowReadBarrier.clear(token);
            default: throw new IllegalArgumentException("Unsupported fixture control");
        }
    }

    @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
