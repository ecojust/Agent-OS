package dev.agentos.app.numericstest;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Fixed synthetic SAF documents, owned by the test APK's separate provider process. */
public final class NumericPreferencesDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "dev.agentos.app.test.numericpreferences";
    public static final String ROOT_ID = "numeric-root";
    public static final String ROOT_TITLE = "Agent numeric test documents";
    public static final String INPUT_NAME = "numeric-import.json";
    public static final String OUTPUT_NAME = "numeric-export.json";
    public static final String MIME_TYPE = "application/json";
    private static final String TARGET = "dev.agentos.app";
    private static final int MAX_BYTES = 4097;
    private static final long BLOCK_MILLIS = 30_000;
    private static final String VALID = "{\"format\":\"agent-os-numeric-preferences\",\"schemaVersion\":1,"
            + "\"maxOutputTokens\":777,\"maxModelRounds\":6,\"maxToolCalls\":9,\"maxActiveSeconds\":45}";
    private static final String NUMERIC_EXPORT = "\\{\"format\":\"agent-os-numeric-preferences\",\"schemaVersion\":1,"
            + "\"maxOutputTokens\":[0-9]{1,10},\"maxModelRounds\":[0-9]{1,10},"
            + "\"maxToolCalls\":[0-9]{1,10},\"maxActiveSeconds\":[0-9]{1,10}\\}";
    private static final String[] ROOT_COLUMNS = {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES};
    private static final String[] DOCUMENT_COLUMNS = {Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};

    // Process-lifetime observations, deliberately independent of configure/reset:
    // DocumentsUI may query/cache roots before a synthetic session is configured.
    private static final Object ROOT_DIAGNOSTICS_LOCK = new Object();
    private static int rootsQueryEntered, rootsQueryReturned;
    private static boolean rootsQueryCountersSaturated;
    private static RootQueryDiagnostic latestRootsQuery, completedRootsQuery;
    private enum RootsOutcome { NOT_QUERIED, IN_PROGRESS, RETURNED, REJECTED, ERROR }
    private enum CallerCheck {
        NOT_CHECKED, TARGET_ALLOWED, SPECIAL_UID_REJECTED, MANAGE_PERMISSION_REJECTED,
        NO_VISIBLE_PACKAGES, NO_SYSTEM_PACKAGE, SYSTEM_ALLOWED, LOOKUP_ERROR
    }
    private static final class RootQueryDiagnostic {
        RootsOutcome outcome = RootsOutcome.IN_PROGRESS;
        CallerCheck callerCheck = CallerCheck.NOT_CHECKED;
        int packageCount = -1, applicationInfoCount, lookupMissCount;
        boolean rejected;
    }

    private final Object lock = new Object();
    private Handler closeHandler;
    private File inputFile, outputFile;
    private Session session;
    private long generation;

    private static final class Session {
        final long generation;
        final int mode;
        final String inputId, outputId;
        final CountDownLatch gate;
        final int inputBytes;
        int openEntered, openReturned, openClosed, openFailed, activeOpens;
        int inputOpenEntered, inputOpenClosed, exportOpenEntered, exportOpenClosed;
        int createCount, closeErrors;
        int lastOpenCallerUid = -1;
        boolean lastOpenHadGrant, gateReleased, gateTimedOut, cancelObserved;
        int blockedWaiting;
        CancellationSignal blockedSignal;

        Session(long generation, int mode, int inputBytes) {
            this.generation = generation; this.mode = mode; this.inputBytes = inputBytes;
            // Do not reuse document IDs, including after provider process death.
            String id = UUID.randomUUID().toString();
            inputId = "input-" + id; outputId = "export-" + id;
            gate = new CountDownLatch(mode == 4 ? 1 : 0);
        }
    }

    @Override public boolean onCreate() {
        synchronized (lock) {
            // The control bridge initializes this same local provider before configuring it.
            // Later SAF acquisition must retain that session, not clear its synthetic files.
            if (closeHandler != null) return true;
            // Never derive a filesystem path from a document ID, requested name or call argument.
            inputFile = new File(getContext().getFilesDir(), "numeric-test-import.json");
            outputFile = new File(getContext().getFilesDir(), "numeric-test-export.json");
            if (!removeFiles()) return false;
            closeHandler = new Handler(Looper.getMainLooper());
            return true;
        }
    }

    private int targetUid() {
        try {
            int uid = getContext().getPackageManager().getPackageUid(TARGET, 0);
            if (uid == Process.myUid()) throw new SecurityException("A separate test UID is required");
            return uid;
        } catch (PackageManager.NameNotFoundException missing) {
            throw new SecurityException("Instrumented target is not installed");
        }
    }

    private void requireTargetCaller() {
        if (Binder.getCallingUid() != targetUid())
            throw new SecurityException("Only the instrumented target UID may control synthetic documents");
    }

    private void requireAllowedCaller() {
        requireAllowedCaller(null);
    }

    private void requireAllowedCaller(RootQueryDiagnostic diagnostic) {
        int uid = Binder.getCallingUid();
        try {
            if (uid == targetUid()) {
                if (diagnostic != null) diagnostic.callerCheck = CallerCheck.TARGET_ALLOWED;
                return;
            }
            if (uid == 0 || uid == 2000) {
                rejectRootCaller(diagnostic, CallerCheck.SPECIAL_UID_REJECTED);
                throw new SecurityException("Only the target or system SAF may access synthetic documents");
            }
            if (getContext().checkPermission(Manifest.permission.MANAGE_DOCUMENTS,
                    Binder.getCallingPid(), uid) != PackageManager.PERMISSION_GRANTED) {
                rejectRootCaller(diagnostic, CallerCheck.MANAGE_PERMISSION_REJECTED);
                throw new SecurityException("Only the target or system SAF may access synthetic documents");
            }
            String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            if (diagnostic != null) diagnostic.packageCount = packages == null ? 0 : packages.length;
            if (packages != null) for (String name : packages) {
                try {
                    if (diagnostic != null) diagnostic.applicationInfoCount++;
                    ApplicationInfo info = getContext().getPackageManager().getApplicationInfo(name, 0);
                    if ((info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
                        if (diagnostic != null) diagnostic.callerCheck = CallerCheck.SYSTEM_ALLOWED;
                        return;
                    }
                } catch (PackageManager.NameNotFoundException ignored) {
                    if (diagnostic != null) diagnostic.lookupMissCount++;
                    // Preserve the original rejection of unresolved identities.
                }
            }
            rejectRootCaller(diagnostic, packages == null || packages.length == 0
                    ? CallerCheck.NO_VISIBLE_PACKAGES : CallerCheck.NO_SYSTEM_PACKAGE);
            throw new SecurityException("System SAF installation identity is required");
        } finally {
            // Unexpected original lookup exceptions propagate unchanged. Record
            // only a fixed category, never their message, cause or package value.
            if (diagnostic != null && diagnostic.callerCheck == CallerCheck.NOT_CHECKED)
                diagnostic.callerCheck = CallerCheck.LOOKUP_ERROR;
        }
    }

    private static void rejectRootCaller(RootQueryDiagnostic diagnostic, CallerCheck check) {
        if (diagnostic != null) { diagnostic.callerCheck = check; diagnostic.rejected = true; }
    }

    private boolean hasGrant(String documentId, int flags) {
        Uri uri = DocumentsContract.buildDocumentUri(AUTHORITY, documentId);
        return getContext().checkUriPermission(uri, Binder.getCallingPid(), Binder.getCallingUid(), flags)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requireDocumentCaller(String documentId, int flags) {
        requireAllowedCaller();
        // The platform transport also enforces grants. The control call never grants a URI.
        if (Binder.getCallingUid() == targetUid() && !hasGrant(documentId, flags))
            throw new SecurityException("A real SAF document grant is required");
    }

    @Override public Bundle call(String authority, String method, String arg, Bundle extras) {
        if (!AUTHORITY.equals(authority)) throw new SecurityException("Unexpected document authority");
        return call(method, arg, extras);
    }

    @Override public Uri canonicalize(Uri uri) {
        requireAllowedCaller();
        return super.canonicalize(uri);
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        requireAllowedCaller();
        if (method == null || !method.startsWith("android:"))
            throw new UnsupportedOperationException("Synthetic control uses its separate provider");
        // Retain DocumentsProvider's URI/authority and read/write permission checks.
        return super.call(method, arg, extras);
    }

    /** Local bridge only, called after restoring the original target's Binder identity. */
    final Bundle controlCall(String method, String arg, Bundle extras) {
        requireTargetCaller();
        if (method == null) throw new IllegalArgumentException("A fixed document operation is required");
        if (arg != null) throw new IllegalArgumentException("Document control has no string argument");
        if ("configure".equals(method)) {
            if (extras == null || extras.size() != 1 || !extras.containsKey("mode"))
                throw new IllegalArgumentException("Only a fixed synthetic mode may be configured");
            String mode = extras.getString("mode");
            int selected;
            if ("valid".equals(mode)) selected = 1;
            else if ("malformed".equals(mode)) selected = 2;
            else if ("oversize".equals(mode)) selected = 3;
            else if ("blocked-open".equals(mode)) selected = 4;
            else throw new IllegalArgumentException("Unknown synthetic mode");
            configure(selected);
            notifyDocumentsChanged();
        } else {
            if (extras != null && !extras.isEmpty())
                throw new IllegalArgumentException("This document operation has no extras");
            synchronized (lock) {
                switch (method) {
                    case "reset":
                        requireNoOpen();
                        if (!removeFiles()) throw new IllegalStateException("Synthetic document cleanup failed");
                        session = null;
                        break;
                    case "release":
                        if (session != null) { session.gateReleased = true; session.gate.countDown(); }
                        break;
                    case "state": break;
                    case "export": return exportBytes();
                    default: throw new UnsupportedOperationException("Unknown document control operation");
                }
            }
            if ("reset".equals(method)) notifyDocumentsChanged();
        }
        synchronized (lock) { return state(); }
    }

    private void configure(int mode) {
        synchronized (lock) {
            requireNoOpen();
            byte[] bytes = (mode == 2 ? "{\"format\":" : VALID).getBytes(StandardCharsets.UTF_8);
            if (mode == 3) {
                int length = bytes.length;
                bytes = Arrays.copyOf(bytes, MAX_BYTES);
                Arrays.fill(bytes, length, bytes.length, (byte) ' ');
            }
            try {
                if (!removeFiles()) throw new IOException();
                session = null;
                try (FileOutputStream out = new FileOutputStream(inputFile)) { out.write(bytes); }
                session = new Session(++generation, mode, bytes.length);
            } catch (IOException failed) {
                session = null; removeFiles();
                throw new IllegalStateException("Synthetic document setup failed");
            } finally { Arrays.fill(bytes, (byte) 0); }
        }
    }

    private void requireNoOpen() {
        if (session != null && session.activeOpens != 0)
            throw new IllegalStateException("Actual document close is still pending");
    }

    private boolean removeFiles() {
        boolean inputGone = !inputFile.exists() || inputFile.delete();
        boolean outputGone = !outputFile.exists() || outputFile.delete();
        return inputGone && outputGone;
    }

    private void notifyDocumentsChanged() {
        getContext().getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null);
        getContext().getContentResolver().notifyChange(
                DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT_ID), null);
    }

    private Bundle state() {
        Bundle out = new Bundle();
        synchronized (ROOT_DIAGNOSTICS_LOCK) {
            RootQueryDiagnostic observed = completedRootsQuery;
            RootsOutcome outcome = observed != null ? observed.outcome
                    : latestRootsQuery == null ? RootsOutcome.NOT_QUERIED : RootsOutcome.IN_PROGRESS;
            out.putInt("rootsQueryEntered", rootsQueryEntered);
            out.putInt("rootsQueryReturned", rootsQueryReturned);
            out.putBoolean("rootsQueryCountersSaturated", rootsQueryCountersSaturated);
            out.putString("rootsQueryLastOutcome", outcome.name());
            out.putString("rootsQueryLastCallerCheck", (observed == null
                    ? CallerCheck.NOT_CHECKED : observed.callerCheck).name());
            out.putInt("rootsQueryLastPackageCount", observed == null ? -1 : observed.packageCount);
            out.putInt("rootsQueryLastApplicationInfoCount", observed == null ? 0 : observed.applicationInfoCount);
            out.putInt("rootsQueryLastLookupMissCount", observed == null ? 0 : observed.lookupMissCount);
        }
        out.putInt("targetUid", targetUid()); out.putInt("providerUid", Process.myUid());
        out.putLong("generation", generation); out.putBoolean("configured", session != null);
        Session s = session;
        out.putInt("modeCode", s == null ? 0 : s.mode);
        out.putInt("inputBytes", s == null ? 0 : s.inputBytes);
        out.putInt("openEntered", s == null ? 0 : s.openEntered);
        out.putInt("openReturned", s == null ? 0 : s.openReturned);
        out.putInt("openClosed", s == null ? 0 : s.openClosed);
        out.putInt("openFailed", s == null ? 0 : s.openFailed);
        out.putInt("activeOpens", s == null ? 0 : s.activeOpens);
        out.putInt("inputOpenEntered", s == null ? 0 : s.inputOpenEntered);
        out.putInt("inputOpenClosed", s == null ? 0 : s.inputOpenClosed);
        out.putInt("exportOpenEntered", s == null ? 0 : s.exportOpenEntered);
        out.putInt("exportOpenClosed", s == null ? 0 : s.exportOpenClosed);
        out.putInt("closeErrors", s == null ? 0 : s.closeErrors);
        out.putInt("blockedWaiting", s == null ? 0 : s.blockedWaiting);
        out.putBoolean("gateReleased", s != null && s.gateReleased);
        out.putBoolean("gateTimedOut", s != null && s.gateTimedOut);
        if (s != null && s.blockedSignal != null && s.blockedSignal.isCanceled()) s.cancelObserved = true;
        out.putBoolean("cancelObserved", s != null && s.cancelObserved);
        out.putInt("lastOpenCallerUid", s == null ? -1 : s.lastOpenCallerUid);
        out.putBoolean("lastOpenHadGrant", s != null && s.lastOpenHadGrant);
        out.putInt("createCount", s == null ? 0 : s.createCount);
        out.putLong("exportBytes", s == null || s.createCount == 0 ? 0 : Math.min(MAX_BYTES + 1L, outputFile.length()));
        out.putBoolean("exportReady", s != null && s.createCount == 1 && s.exportOpenClosed > 0 && s.activeOpens == 0);
        return out;
    }

    private Bundle exportBytes() {
        requireNoOpen();
        if (session == null || session.createCount != 1 || session.exportOpenClosed == 0)
            throw new IllegalStateException("No closed synthetic export is available");
        long length = outputFile.length();
        if (length <= 0 || length > MAX_BYTES) throw new IllegalStateException("Synthetic export exceeds its fixed bound");
        byte[] bytes = new byte[(int) length];
        try (FileInputStream in = new FileInputStream(outputFile)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = in.read(bytes, offset, bytes.length - offset);
                if (count <= 0) throw new IOException();
                offset += count;
            }
            if (in.read() != -1 || !new String(bytes, StandardCharsets.UTF_8).matches(NUMERIC_EXPORT))
                throw new IOException();
            Bundle result = state(); result.putByteArray("bytes", bytes); return result;
        } catch (IOException invalid) {
            Arrays.fill(bytes, (byte) 0);
            throw new IllegalStateException("Only the fixed six-key synthetic numeric export may be read");
        }
    }

    @Override public Cursor queryRoots(String[] projection) {
        RootQueryDiagnostic diagnostic = new RootQueryDiagnostic();
        synchronized (ROOT_DIAGNOSTICS_LOCK) {
            if (rootsQueryEntered < Integer.MAX_VALUE) rootsQueryEntered++;
            else rootsQueryCountersSaturated = true;
            latestRootsQuery = diagnostic;
            completedRootsQuery = null;
        }
        boolean returned = false;
        try {
            requireAllowedCaller(diagnostic);
            MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
            MatrixCursor.RowBuilder row = cursor.newRow();
            column(row, cursor, Root.COLUMN_ROOT_ID, ROOT_ID); column(row, cursor, Root.COLUMN_DOCUMENT_ID, ROOT_ID);
            column(row, cursor, Root.COLUMN_TITLE, ROOT_TITLE);
            column(row, cursor, Root.COLUMN_FLAGS,
                    Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_LOCAL_ONLY);
            column(row, cursor, Root.COLUMN_MIME_TYPES, MIME_TYPE);
            column(row, cursor, Root.COLUMN_AVAILABLE_BYTES, 1024L * 1024);
            cursor.setNotificationUri(getContext().getContentResolver(), DocumentsContract.buildRootsUri(AUTHORITY));
            returned = true;
            return cursor;
        } finally {
            diagnostic.outcome = returned ? RootsOutcome.RETURNED
                    : diagnostic.rejected ? RootsOutcome.REJECTED : RootsOutcome.ERROR;
            synchronized (ROOT_DIAGNOSTICS_LOCK) {
                if (returned) {
                    if (rootsQueryReturned < Integer.MAX_VALUE) rootsQueryReturned++;
                    else rootsQueryCountersSaturated = true;
                }
                // A slower older query must not overwrite the newest query's
                // in-progress or completed snapshot. Published facts are final.
                if (latestRootsQuery == diagnostic) completedRootsQuery = diagnostic;
            }
        }
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        requireDocumentCaller(documentId, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        synchronized (lock) {
            MatrixCursor cursor = documents(projection);
            addDocument(cursor, documentId);
            return cursor;
        }
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        requireDocumentCaller(parentDocumentId, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!ROOT_ID.equals(parentDocumentId)) throw unavailable();
        synchronized (lock) {
            MatrixCursor cursor = documents(projection);
            if (session != null) addDocument(cursor, session.inputId);
            cursor.setNotificationUri(getContext().getContentResolver(),
                    DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT_ID));
            return cursor;
        }
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        requireDocumentCaller(parentDocumentId, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        synchronized (lock) {
            return ROOT_ID.equals(parentDocumentId) && session != null && (session.inputId.equals(documentId)
                    || session.createCount == 1 && session.outputId.equals(documentId));
        }
    }

    @Override public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        requireDocumentCaller(parentDocumentId, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (!ROOT_ID.equals(parentDocumentId) || !MIME_TYPE.equals(mimeType) || displayName == null
                || displayName.isEmpty() || displayName.length() > 100) throw unavailable();
        synchronized (lock) {
            if (session == null || session.createCount != 0) throw unavailable();
            try (FileOutputStream ignored = new FileOutputStream(outputFile)) { /* Fixed empty export. */ }
            catch (IOException failed) { throw unavailable(); }
            session.createCount++;
            return session.outputId;
        }
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        requireAllowedCaller();
        boolean reading = "r".equals(mode);
        if (!reading && !"w".equals(mode) && !"wt".equals(mode)) throw unavailable();
        int flags = reading ? Intent.FLAG_GRANT_READ_URI_PERMISSION : Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        requireDocumentCaller(documentId, flags);
        int callerUid = Binder.getCallingUid();
        boolean hadGrant = hasGrant(documentId, flags);
        final Session owner;
        synchronized (lock) {
            owner = session;
            if (owner == null || reading && !owner.inputId.equals(documentId)
                    || !reading && (owner.createCount != 1 || !owner.outputId.equals(documentId))) throw unavailable();
            owner.openEntered++; owner.activeOpens++;
            if (reading) owner.inputOpenEntered++; else owner.exportOpenEntered++;
            owner.lastOpenCallerUid = callerUid; owner.lastOpenHadGrant = hadGrant;
        }
        try {
            if (reading && owner.mode == 4) awaitRelease(owner, signal);
            int descriptorMode = reading ? ParcelFileDescriptor.MODE_READ_ONLY
                    : ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE;
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(reading ? inputFile : outputFile,
                    descriptorMode, closeHandler, error -> {
                        synchronized (lock) {
                            // This callback observes the real remote descriptor closing, not cancellation.
                            owner.openClosed++; owner.activeOpens--;
                            if (reading) owner.inputOpenClosed++; else owner.exportOpenClosed++;
                            if (error != null) owner.closeErrors++;
                        }
                    });
            synchronized (lock) { owner.openReturned++; }
            return descriptor;
        } catch (IOException failed) {
            synchronized (lock) { owner.openFailed++; owner.activeOpens--; }
            throw unavailable();
        }
    }

    private void awaitRelease(Session owner, CancellationSignal signal) throws FileNotFoundException {
        long deadline = SystemClock.elapsedRealtime() + BLOCK_MILLIS;
        boolean interrupted = false;
        synchronized (lock) { owner.blockedWaiting++; owner.blockedSignal = signal; }
        try {
            while (true) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) {
                    synchronized (lock) { owner.gateTimedOut = true; }
                    throw unavailable();
                }
                try {
                    if (owner.gate.await(remaining, TimeUnit.MILLISECONDS)) return;
                } catch (InterruptedException ignored) {
                    // The synthetic remote endpoint intentionally does not finish on client cancellation.
                    interrupted = true;
                }
            }
        } finally {
            synchronized (lock) {
                owner.blockedWaiting--;
                if (signal != null && signal.isCanceled()) owner.cancelObserved = true;
                if (owner.blockedSignal == signal) owner.blockedSignal = null;
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static MatrixCursor documents(String[] projection) {
        return new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
    }

    private void addDocument(MatrixCursor cursor, String documentId) throws FileNotFoundException {
        boolean root = ROOT_ID.equals(documentId);
        boolean input = session != null && session.inputId.equals(documentId);
        boolean output = session != null && session.createCount == 1 && session.outputId.equals(documentId);
        if (!root && !input && !output) throw unavailable();
        MatrixCursor.RowBuilder row = cursor.newRow();
        column(row, cursor, Document.COLUMN_DOCUMENT_ID, documentId);
        column(row, cursor, Document.COLUMN_DISPLAY_NAME, root ? ROOT_TITLE : input ? INPUT_NAME : OUTPUT_NAME);
        column(row, cursor, Document.COLUMN_MIME_TYPE, root ? Document.MIME_TYPE_DIR : MIME_TYPE);
        column(row, cursor, Document.COLUMN_FLAGS, root ? Document.FLAG_DIR_SUPPORTS_CREATE
                : output ? Document.FLAG_SUPPORTS_WRITE : 0);
        column(row, cursor, Document.COLUMN_SIZE, root ? null : input ? session.inputBytes
                : Math.min(MAX_BYTES + 1L, outputFile.length()));
        column(row, cursor, Document.COLUMN_LAST_MODIFIED, 0L);
    }

    private static void column(MatrixCursor.RowBuilder row, MatrixCursor cursor, String name, Object value) {
        if (cursor.getColumnIndex(name) >= 0) row.add(name, value);
    }

    private static FileNotFoundException unavailable() {
        return new FileNotFoundException("Synthetic numeric document is unavailable");
    }
}
