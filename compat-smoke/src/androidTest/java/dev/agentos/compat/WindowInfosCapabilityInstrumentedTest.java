package dev.agentos.compat;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Independent nonvisual API37 probe; not a third-party app case or listener registration test. */
@RunWith(AndroidJUnit4.class)
public final class WindowInfosCapabilityInstrumentedTest {
    static final String TEST_CLASS = "dev.agentos.compat.WindowInfosCapabilityInstrumentedTest";
    static final String TEST_METHOD = "inspectPublicTestApiSurface";

    @Test public void inspectPublicTestApiSurface() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Bundle arguments = InstrumentationRegistry.getArguments();
        require("true".equals(arguments.getString("windowInfosCapability")), "Explicit capability opt-in is required");
        require(Build.VERSION.SDK_INT == 37 && "37".equals(arguments.getString("expectedApi")), "This capability sample is API37 only");
        JSONObject source = new JSONObject()
                .put("sourceSha", argument(arguments, "sourceSha", "[0-9a-f]{40}"))
                .put("runId", argument(arguments, "runId", "[1-9][0-9]{0,19}"))
                .put("runAttempt", argument(arguments, "runAttempt", "[1-9][0-9]{0,5}"))
                .put("expectedApi", "37")
                .put("deviceSerial", argument(arguments, "deviceSerial", "[A-Za-z0-9._:-]{1,80}"));
        Context target = instrumentation.getTargetContext(), test = instrumentation.getContext();
        require("dev.agentos.compat".equals(target.getPackageName()) && "dev.agentos.compat.test".equals(test.getPackageName()),
                "Unexpected instrumentation target or test package");
        JSONObject process = new JSONObject().put("uid", Process.myUid()).put("targetSdk", target.getApplicationInfo().targetSdkVersion)
                .put("targetPackage", target.getPackageName()).put("testPackage", test.getPackageName());
        String permission;
        try {
            permission = target.checkSelfPermission("android.permission.ACCESS_SURFACE_FLINGER") == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED";
        } catch (Throwable unavailable) { permission = "CHECK_FAILED"; }
        JSONObject build = buildMetadata();
        JSONObject report = WindowInfosCapabilityProbe.inspect(target.getClassLoader(), SystemClock::elapsedRealtime)
                .put("source", source).put("sdkInt", Build.VERSION.SDK_INT).put("build", build).put("process", process)
                .put("permissions", new JSONObject().put("accessSurfaceFlinger", permission));
        String message = WindowInfosCapabilityProbe.MARKER + report.toString();
        require(message.length() <= WindowInfosCapabilityProbe.MAX_MARKER_CHARS, "Capability diagnostic exceeded its fixed bound");
        Bundle status = new Bundle();
        status.putString("class", TEST_CLASS); status.putString("test", TEST_METHOD);
        status.putString("windowInfosCapabilityDiagnostic", message);
        instrumentation.sendStatus(2, status);
        require("COMPLETE".equals(report.getString("probeExecution")), "Capability reflection deadline was reached");
    }

    private static String argument(Bundle args, String key, String pattern) {
        String value = args.getString(key);
        require(value != null && value.matches(pattern), "Capability source arguments are missing or invalid");
        return value;
    }

    private static JSONObject buildMetadata() throws Exception {
        String id = token(Build.ID, 128), release = token(Build.VERSION.RELEASE, 32), fingerprint = null;
        try {
            if (Build.FINGERPRINT != null && Build.FINGERPRINT.length() <= 1024) {
                StringBuilder hex = new StringBuilder(64);
                for (byte value : MessageDigest.getInstance("SHA-256").digest(Build.FINGERPRINT.getBytes(StandardCharsets.UTF_8)))
                    hex.append(String.format(Locale.US, "%02x", value & 255));
                fingerprint = hex.toString();
            }
        } catch (Throwable unavailable) { /* Fixed unavailable status only, no exception text. */ }
        return new JSONObject().put("buildId", id == null ? JSONObject.NULL : id).put("valueRelease", release == null ? JSONObject.NULL : release)
                .put("fingerprintSha256", fingerprint == null ? JSONObject.NULL : fingerprint)
                .put("buildIdStatus", id == null ? "UNAVAILABLE" : "AVAILABLE")
                .put("releaseStatus", release == null ? "UNAVAILABLE" : "AVAILABLE")
                .put("fingerprintStatus", fingerprint == null ? "UNAVAILABLE" : "AVAILABLE");
    }

    private static String token(String value, int limit) {
        return value != null && value.length() <= limit && value.matches("[A-Za-z0-9._+-]+") ? value : null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
