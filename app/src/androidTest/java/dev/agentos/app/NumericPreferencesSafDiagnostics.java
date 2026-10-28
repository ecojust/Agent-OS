package dev.agentos.app;

import android.Manifest;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Build;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Failure-only installed metadata, viewed by the instrumented target UID, not DocumentsUI. */
final class NumericPreferencesSafDiagnostics {
    private static final String TARGET = "dev.agentos.app";
    private static final String TEST_PACKAGE = "dev.agentos.app.test";
    private static final String AUTHORITY = "dev.agentos.app.test.numericpreferences";
    private static final String PROVIDER = "dev.agentos.app.numericstest.NumericPreferencesDocumentsProvider";
    private static final String PROVIDER_PROCESS = TEST_PACKAGE + ":numeric_preferences_documents";
    private static final ComponentName COMPONENT = new ComponentName(TEST_PACKAGE, PROVIDER);
    private static final int MAX_OUTPUT_CHARS = 2400;
    private static final int MAX_LOG_BYTES = 64 * 1024;
    // Native -t tail selection precedes tag filtering; keep the finite existing ring window.
    private static final String LOG_COMMAND = "logcat -d -v brief -s ProvidersCache:V";

    private enum Status { PRESENT, NOT_VISIBLE_OR_MISSING, NOT_RESOLVED, SECURITY_DENIED,
        LOOKUP_FAILED, NOT_OBSERVED, INVALID_OBSERVED_ID }
    private enum Candidate { AOSP_DOCUMENTS_UI, GOOGLE_DOCUMENTS_UI, OTHER_OBSERVED_PACKAGE,
        NOT_OBSERVED }

    private NumericPreferencesSafDiagnostics() { }

    /**
     * The caller supplies the last package already verified from its real SAF root; we do not
     * acquire a new UI root, query documents/roots, refresh caches, or impersonate the SAF UID.
     * PM calls have no application-enforced Binder timeout. This is one failure-only sample,
     * with bounded output and no retries; it is not a same-time observation of SAF focus.
     */
    static String sample(Context targetContext, String observedSystemSafPackage, UiAutomation automation) {
        try {
            Facts facts = new Facts();
            facts.add("installView", "TARGET_UID_NOT_SAF_DISCOVERY");
            facts.add("installApi", Build.VERSION.SDK_INT);
            facts.add("installSamplerUid", Process.myUid());
            facts.add("installTargetContext", targetContext != null
                    && TARGET.equals(targetContext.getPackageName())
                    && targetContext.getApplicationInfo().uid == Process.myUid());
            if (targetContext == null) return "installDiagnostic=CONTEXT_UNAVAILABLE";
            PackageManager pm = targetContext.getPackageManager();
            sampleProvider(pm, facts);
            sampleSaf(pm, observedSystemSafPackage, facts);
            sampleLog(automation, facts);
            String result = facts.value.toString();
            return result.length() <= MAX_OUTPUT_CHARS ? result : "installDiagnostic=OUTPUT_BOUND";
        } catch (RuntimeException | AssertionError failed) {
            // Neither exception messages nor arbitrary framework objects cross this boundary.
            return "installDiagnostic=LOOKUP_FAILED";
        }
    }

    private static void sampleProvider(PackageManager pm, Facts facts) {
        ProviderInfo info = null;
        Status providerStatus;
        try {
            info = pm.getProviderInfo(COMPONENT, PackageManager.MATCH_DISABLED_COMPONENTS);
            providerStatus = info == null ? Status.NOT_VISIBLE_OR_MISSING : Status.PRESENT;
        } catch (PackageManager.NameNotFoundException missing) {
            providerStatus = Status.NOT_VISIBLE_OR_MISSING;
        } catch (SecurityException denied) {
            providerStatus = Status.SECURITY_DENIED;
        } catch (RuntimeException failed) {
            providerStatus = Status.LOOKUP_FAILED;
        }
        facts.add("providerInfo", providerStatus);
        ApplicationInfo app = info == null ? null : info.applicationInfo;
        facts.add("providerComponentMatches", info == null ? null
                : TEST_PACKAGE.equals(info.packageName) && PROVIDER.equals(info.name));
        facts.add("providerAuthorityMatches", info == null ? null : AUTHORITY.equals(info.authority));
        facts.add("providerEnabled", info == null ? null : info.enabled);
        facts.add("providerExported", info == null ? null : info.exported);
        facts.add("providerGrants", info == null ? null : info.grantUriPermissions);
        facts.add("providerReadManage", info == null ? null
                : Manifest.permission.MANAGE_DOCUMENTS.equals(info.readPermission));
        facts.add("providerWriteManage", info == null ? null
                : Manifest.permission.MANAGE_DOCUMENTS.equals(info.writePermission));
        facts.add("providerProcessMatches", info == null ? null : PROVIDER_PROCESS.equals(info.processName));
        facts.add("providerAppEnabled", app == null ? null : app.enabled);
        facts.add("providerAppStopped", app == null ? null : (app.flags & ApplicationInfo.FLAG_STOPPED) != 0);
        facts.add("providerAppSuspended", app == null ? null : (app.flags & ApplicationInfo.FLAG_SUSPENDED) != 0);
        facts.add("providerUid", app == null ? null : app.uid);
        facts.add("providerUidSeparate", app == null ? null : app.uid != Process.myUid());
        facts.add("providerSameUser", app == null ? null
                : UserHandle.getUserHandleForUid(app.uid).equals(Process.myUserHandle()));
        facts.add("providerMinSdk", app == null ? null : app.minSdkVersion);
        facts.add("providerTargetSdk", app == null ? null : app.targetSdkVersion);

        try {
            ProviderInfo resolved = pm.resolveContentProvider(AUTHORITY, 0);
            facts.add("authorityResolve", resolved == null ? Status.NOT_RESOLVED : Status.PRESENT);
            facts.add("authorityComponentMatches", resolved == null ? null
                    : TEST_PACKAGE.equals(resolved.packageName) && PROVIDER.equals(resolved.name));
            facts.add("authorityUidMatches", resolved == null || resolved.applicationInfo == null || app == null
                    ? null : resolved.applicationInfo.uid == app.uid);
        } catch (SecurityException denied) {
            facts.add("authorityResolve", Status.SECURITY_DENIED);
        } catch (RuntimeException failed) {
            facts.add("authorityResolve", Status.LOOKUP_FAILED);
        }
        try {
            PackageInfo installed = pm.getPackageInfo(TEST_PACKAGE, 0);
            facts.add("providerPackage", Status.PRESENT);
            facts.add("providerVersion", installed.getLongVersionCode());
            facts.add("providerPackageUidMatches", installed.applicationInfo == null || app == null
                    ? null : installed.applicationInfo.uid == app.uid);
        } catch (PackageManager.NameNotFoundException missing) {
            facts.add("providerPackage", Status.NOT_VISIBLE_OR_MISSING);
        } catch (SecurityException denied) {
            facts.add("providerPackage", Status.SECURITY_DENIED);
        } catch (RuntimeException failed) {
            facts.add("providerPackage", Status.LOOKUP_FAILED);
        }
        try {
            facts.add("providerComponentSetting", enabledSetting(pm.getComponentEnabledSetting(COMPONENT)));
        } catch (RuntimeException failed) {
            facts.add("providerComponentSetting", Status.LOOKUP_FAILED);
        }
        try {
            facts.add("providerAppSetting", enabledSetting(pm.getApplicationEnabledSetting(TEST_PACKAGE)));
        } catch (RuntimeException failed) {
            facts.add("providerAppSetting", Status.LOOKUP_FAILED);
        }
    }

    private static void sampleSaf(PackageManager pm, String observedPackage, Facts facts) {
        Candidate candidate = observedPackage == null ? Candidate.NOT_OBSERVED
                : "com.android.documentsui".equals(observedPackage) ? Candidate.AOSP_DOCUMENTS_UI
                : "com.google.android.documentsui".equals(observedPackage) ? Candidate.GOOGLE_DOCUMENTS_UI
                : Candidate.OTHER_OBSERVED_PACKAGE;
        facts.add("lastObservedSafCandidate", candidate);
        if (observedPackage == null) { facts.add("safPackage", Status.NOT_OBSERVED); return; }
        if (observedPackage.length() > 255 || !observedPackage.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) {
            facts.add("safPackage", Status.INVALID_OBSERVED_ID); return;
        }
        try {
            PackageInfo installed = pm.getPackageInfo(observedPackage, 0);
            ApplicationInfo app = installed.applicationInfo;
            facts.add("safPackage", Status.PRESENT);
            facts.add("safSystem", app == null ? null : (app.flags
                    & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);
            facts.add("safEnabled", app == null ? null : app.enabled);
            facts.add("safSameUser", app == null ? null
                    : UserHandle.getUserHandleForUid(app.uid).equals(Process.myUserHandle()));
            facts.add("safTargetSdk", app == null ? null : app.targetSdkVersion);
            facts.add("safVersion", installed.getLongVersionCode());
        } catch (PackageManager.NameNotFoundException missing) {
            facts.add("safPackage", Status.NOT_VISIBLE_OR_MISSING);
        } catch (SecurityException denied) {
            facts.add("safPackage", Status.SECURITY_DENIED);
        } catch (RuntimeException failed) {
            facts.add("safPackage", Status.LOOKUP_FAILED);
        }
        try {
            facts.add("safManageDocuments", pm.checkPermission(Manifest.permission.MANAGE_DOCUMENTS,
                    observedPackage) == PackageManager.PERMISSION_GRANTED);
        } catch (RuntimeException failed) {
            facts.add("safManageDocuments", Status.LOOKUP_FAILED);
        }
    }

    private enum LogStatus { COMPLETE, BYTE_BOUND, IO_ERROR, UNAVAILABLE }

    private static void sampleLog(UiAutomation automation, Facts facts) {
        // Historical, filtered log window, not an atomic record of this failing operation.
        // executeShellCommand and FD reads have no local hard timeout. No polling/retry/thread.
        // COMPLETE means stream EOF only: this API supplies no shell exit status.
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        LogStatus status = LogStatus.UNAVAILABLE;
        if (automation != null) {
            try {
                ParcelFileDescriptor descriptor = automation.executeShellCommand(LOG_COMMAND);
                if (descriptor != null) {
                    try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                        byte[] buffer = new byte[4096];
                        while (captured.size() < MAX_LOG_BYTES) {
                            int count = input.read(buffer, 0, Math.min(buffer.length, MAX_LOG_BYTES - captured.size()));
                            if (count == -1) { status = LogStatus.COMPLETE; break; }
                            if (count == 0) { status = LogStatus.IO_ERROR; break; }
                            captured.write(buffer, 0, count);
                        }
                        if (captured.size() == MAX_LOG_BYTES) status = LogStatus.BYTE_BOUND;
                    }
                }
            } catch (IOException failed) {
                status = LogStatus.IO_ERROR;
            } catch (RuntimeException | AssertionError failed) {
                status = LogStatus.UNAVAILABLE;
            }
        }
        LogClassifier observed = LogClassifier.classify(
                new String(captured.toByteArray(), StandardCharsets.UTF_8), status == LogStatus.COMPLETE);
        facts.add("providerLogWindow", "HISTORICAL_BOUNDED_NOT_ATOMIC");
        facts.add("providerLogStatus", status);
        facts.add("providerLogBytes", captured.size());
        facts.add("logAuthoritySeen", observed.authoritySeen);
        facts.add("logFailureSeen", observed.failureSeen);
        facts.add("logProviderInfoMissing", observed.providerInfoMissing);
        facts.add("logNotExported", observed.notExported);
        facts.add("logNoUriGrants", observed.noUriGrants);
        facts.add("logManageMismatch", observed.manageMismatch);
        facts.add("logLoadFailed", observed.loadFailed);
        facts.add("logSecurityClass", observed.securityClass);
        facts.add("logDeadObjectClass", observed.deadObjectClass);
        facts.add("logRemoteClass", observed.remoteClass);
        facts.add("logIllegalArgumentClass", observed.illegalArgumentClass);
        facts.add("logIllegalStateClass", observed.illegalStateClass);
        facts.add("logFileNotFoundClass", observed.fileNotFoundClass);
        facts.add("logOtherExceptionClass", observed.otherExceptionClass);
    }

    /** Pure classifier: no Android calls, and no original line survives in the returned facts. */
    static final class LogClassifier {
        private static final Pattern LINE = Pattern.compile(
                "^[VDIWEF]/ProvidersCache\\(\\s*([0-9]{1,10})\\): ?(.*)$");
        private static final Pattern OUR_AUTHORITY = Pattern.compile(
                "(?<![A-Za-z0-9_.])" + Pattern.quote(AUTHORITY) + "(?![A-Za-z0-9_.])");
        private static final Pattern EXCEPTION = Pattern.compile(
                "(?:Caused by: |Suppressed: )?((?:java|android)\\.[A-Za-z0-9_.$]+)(?::.*)?");
        boolean authoritySeen, failureSeen, providerInfoMissing, notExported, noUriGrants,
                manageMismatch, loadFailed, securityClass, deadObjectClass, remoteClass,
                illegalArgumentClass, illegalStateClass, fileNotFoundClass, otherExceptionClass;

        static LogClassifier classify(String text, boolean complete) {
            LogClassifier result = new LogClassifier();
            // A partial last line could hide a suffix such as '.control'; never classify it.
            if (!complete) text = text.substring(0, text.lastIndexOf('\n') + 1);
            String activePid = null;
            for (String line : text.split("\n")) {
                Matcher parsed = LINE.matcher(line);
                if (!parsed.matches()) { activePid = null; continue; }
                String pid = parsed.group(1), message = parsed.group(2).trim();
                if (OUR_AUTHORITY.matcher(message).find()) result.authoritySeen = true;
                boolean knownFailure = false;
                if (message.equals("Failed to get provider info for " + AUTHORITY)) {
                    result.providerInfoMissing = true; knownFailure = true;
                } else if (message.equals("Provider is not exported. Failed to load roots for " + AUTHORITY)) {
                    result.notExported = true; knownFailure = true;
                } else if (message.equals("Provider doesn't grantUriPermissions. Failed to load roots for " + AUTHORITY)) {
                    result.noUriGrants = true; knownFailure = true;
                } else if (message.equals("Provider is not protected by MANAGE_DOCUMENTS. Failed to load roots for " + AUTHORITY)) {
                    result.manageMismatch = true; knownFailure = true;
                } else if (message.equals("Failed to load some roots from " + AUTHORITY)) {
                    result.loadFailed = true; knownFailure = true;
                }
                if (knownFailure) { result.failureSeen = true; activePid = pid; continue; }
                if (!pid.equals(activePid)) { activePid = null; continue; }
                Matcher exception = EXCEPTION.matcher(message);
                if (exception.matches()) {
                    switch (exception.group(1)) {
                        case "java.lang.SecurityException": result.securityClass = true; break;
                        case "android.os.DeadObjectException": result.deadObjectClass = true; break;
                        case "android.os.RemoteException": result.remoteClass = true; break;
                        case "java.lang.IllegalArgumentException": result.illegalArgumentClass = true; break;
                        case "java.lang.IllegalStateException": result.illegalStateClass = true; break;
                        case "java.io.FileNotFoundException": result.fileNotFoundClass = true; break;
                        default: result.otherExceptionClass = true; break;
                    }
                } else if (!message.startsWith("at ") && !message.matches("\\.\\.\\. [0-9]+ more")) {
                    activePid = null;
                }
            }
            return result;
        }
    }

    private static int enabledSetting(int setting) {
        return setting >= PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                && setting <= PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED ? setting : -1;
    }

    private static final class Facts {
        final StringBuilder value = new StringBuilder();
        // Every caller passes a source-code key and only a fixed enum, boolean, integer or null.
        void add(String key, Object fact) {
            if (value.length() != 0) value.append(',');
            value.append(key).append('=').append(fact == null ? "UNKNOWN" : fact);
        }
    }
}
