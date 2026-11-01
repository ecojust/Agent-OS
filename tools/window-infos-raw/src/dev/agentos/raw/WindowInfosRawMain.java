package dev.agentos.raw;

import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.Pair;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/** Shell-only diagnostic trampoline: no hidden platform class is linked before admission. */
public final class WindowInfosRawMain {
    public static final String READY = "WINDOW_INFOS_RAW_READY_V1 ";
    public static final String RESULT = "WINDOW_INFOS_RAW_RESULT_V1 ";
    public static final long OBSERVATION_MS = 12000;
    public static final long ADMISSION_MS = 5000;
    public static final int MAX_MARKER_BYTES = 131072;
    public static final String[] ADMISSION_IDS = {
        "class.raw", "class.handle", "class.display", "class.inputConfig", "constructor.raw",
        "method.raw.register", "method.raw.unregister", "method.raw.callback",
        "method.handle.getWindowToken", "field.handle.inputConfig", "field.handle.frame",
        "field.handle.touchableRegion", "field.handle.layoutParamsType", "field.handle.ownerUid",
        "field.handle.packageName", "field.handle.displayId", "constant.inputConfig.NOT_VISIBLE",
        "constant.inputConfig.CLONE"
    };

    private final JSONObject source;
    private final String imePackage;
    private final int imeUid, displayId;
    private final long started = SystemClock.elapsedRealtime();
    private final String[] admission = new String[ADMISSION_IDS.length];
    private final RawCollector collector;
    private String construction = "NOT_ATTEMPTED", registration = "NOT_ATTEMPTED";
    private String unregistration = "NOT_ATTEMPTED", resultStatus = "ADMISSION_FAILED";
    private int constructAttempts, registerAttempts, unregisterAttempts;
    private Long constructedAt, registeredAt, readyAt, deadlineAt, freezeAt, unregisteredAt;

    private WindowInfosRawMain(String[] args) throws Exception {
        if (args.length != 6 || !args[0].matches("[0-9a-f]{40}")
                || !args[1].matches("[1-9][0-9]{0,17}") || !args[2].matches("[1-9][0-9]{0,8}")
                || !args[3].matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
                || args[3].length() > 128 || !args[4].matches("[1-9][0-9]{0,8}")
                || !"0".equals(args[5])) throw new IllegalArgumentException();
        source = new JSONObject().put("repository", "ecojust/Agent-OS").put("commit", args[0])
                .put("runId", Long.parseLong(args[1])).put("attempt", Integer.parseInt(args[2]));
        imePackage = args[3]; imeUid = Integer.parseInt(args[4]); displayId = 0;
        collector = new RawCollector(imePackage, imeUid, displayId);
        for (int i = 0; i < admission.length; i++) admission[i] = "NOT_ATTEMPTED";
    }

    public static void main(String[] args) {
        try {
            WindowInfosRawMain probe = new WindowInfosRawMain(args);
            probe.run();
            probe.emitResult();
            System.out.flush();
            System.exit("RECORDED".equals(probe.resultStatus) ? 0 : 1);
        } catch (Throwable error) {
            // Never disclose arbitrary exceptions, paths, arguments or platform window values.
            System.out.println("WINDOW_INFOS_RAW_FATAL_V1");
            System.out.flush();
            System.exit(2);
        }
    }

    private void run() {
        RawSession session = null;
        try {
            if (Build.VERSION.SDK_INT != 37 || Process.myUid() != 2000) {
                resultStatus = "ENVIRONMENT_REJECTED"; return;
            }
            if (!admit()) return;
            resultStatus = "CONSTRUCTION_FAILED"; constructAttempts = 1;
            try {
                Class<?> implementation = Class.forName("dev.agentos.raw.RawListenerSession", true,
                        WindowInfosRawMain.class.getClassLoader());
                session = (RawSession) implementation.getConstructor(RawCollector.class).newInstance(collector);
                construction = "CONSTRUCTED"; constructedAt = SystemClock.elapsedRealtime();
            } catch (Throwable error) { construction = "FAILED"; return; }
            resultStatus = "REGISTRATION_FAILED"; registerAttempts = 1;
            try {
                session.registerAndCaptureInitial();
                registration = "REGISTERED"; registeredAt = SystemClock.elapsedRealtime();
            } catch (Throwable error) { registration = "FAILED"; return; }
            readyAt = SystemClock.elapsedRealtime(); deadlineAt = readyAt + OBSERVATION_MS;
            collector.deadline(deadlineAt);
            JSONObject ready = new JSONObject().put("schemaVersion", 1).put("source", source)
                    .put("imePackage", imePackage).put("imeUid", imeUid).put("displayId", displayId)
                    .put("registered", true).put("elapsedRealtimeMs", readyAt);
            System.out.println(READY + ready.toString()); System.out.flush();
            resultStatus = "OBSERVATION_INTERRUPTED";
            while (SystemClock.elapsedRealtime() < deadlineAt) {
                Thread.sleep(Math.max(1, deadlineAt - SystemClock.elapsedRealtime()));
            }
            resultStatus = "RECORDED";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            resultStatus = "EXECUTION_FAILED";
        } finally {
            collector.freeze(); freezeAt = SystemClock.elapsedRealtime();
            if (session != null && registerAttempts == 1) {
                unregisterAttempts = 1;
                try {
                    session.unregisterOnce();
                    unregistration = "RETURNED"; unregisteredAt = SystemClock.elapsedRealtime();
                } catch (Throwable error) {
                    unregistration = "FAILED";
                    if ("RECORDED".equals(resultStatus)) resultStatus = "CLEANUP_FAILED";
                }
            }
            if ("RECORDED".equals(resultStatus) && !collector.complete()) resultStatus = "INCOMPLETE";
        }
    }

    private boolean admit() {
        long deadline = SystemClock.elapsedRealtime() + ADMISSION_MS;
        boolean complete = true;
        for (int i = 0; i < admission.length; i++) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                admission[i] = "DEADLINE_EXCEEDED"; complete = false; break;
            }
            try {
                check(i);
                if (SystemClock.elapsedRealtime() >= deadline) {
                    admission[i] = "DEADLINE_EXCEEDED"; complete = false; break;
                }
                admission[i] = "MATCHED";
            }
            catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException error) {
                admission[i] = "NOT_FOUND_OR_FILTERED"; complete = false;
            } catch (SecurityException | IllegalAccessException error) {
                admission[i] = "ACCESS_DENIED"; complete = false;
            } catch (ShapeMismatch error) {
                admission[i] = "TYPE_MISMATCH"; complete = false;
            } catch (LinkageError error) {
                admission[i] = "LINKAGE_ERROR"; complete = false;
            } catch (Throwable error) {
                admission[i] = "ERROR"; complete = false;
            }
        }
        return complete;
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, WindowInfosRawMain.class.getClassLoader());
    }
    private static void shape(boolean condition) throws ShapeMismatch { if (!condition) throw new ShapeMismatch(); }
    private static final class ShapeMismatch extends Exception { }
    private static void publicInstance(int modifiers) throws ShapeMismatch {
        shape(Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers));
    }
    private static void check(int slot) throws Exception {
        if (slot == 0) {
            Class<?> raw = load("android.window.WindowInfosListener");
            shape(Modifier.isPublic(raw.getModifiers()) && Modifier.isAbstract(raw.getModifiers()) && !raw.isInterface()); return;
        }
        if (slot == 1) {
            Class<?> handle = load("android.view.InputWindowHandle");
            shape(Modifier.isPublic(handle.getModifiers()) && Modifier.isFinal(handle.getModifiers()) && !handle.isInterface()); return;
        }
        if (slot == 2) {
            Class<?> display = load("android.window.WindowInfosListener$DisplayInfo");
            shape(Modifier.isPublic(display.getModifiers()) && Modifier.isStatic(display.getModifiers())
                    && Modifier.isFinal(display.getModifiers())
                    && display.getDeclaringClass() == load("android.window.WindowInfosListener")); return;
        }
        if (slot == 3) { shape(Modifier.isPublic(load("android.os.InputConfig").getModifiers())); return; }
        if (slot == 4) {
            Class<?> raw = load("android.window.WindowInfosListener");
            Constructor<?> c = raw.getConstructor();
            shape(c.getDeclaringClass() == raw && Modifier.isPublic(c.getModifiers()) && !c.isVarArgs()); return;
        }
        if (slot >= 5 && slot <= 8) {
            Class<?> owner = load(slot == 8 ? "android.view.InputWindowHandle" : "android.window.WindowInfosListener");
            String name = slot == 5 ? "register" : slot == 6 ? "unregister" : slot == 7 ? "onWindowInfosChanged" : "getWindowToken";
            Method method = slot == 7 ? owner.getMethod(name, Array.newInstance(load("android.view.InputWindowHandle"), 0).getClass(), Array.newInstance(load("android.window.WindowInfosListener$DisplayInfo"), 0).getClass()) : owner.getMethod(name);
            publicInstance(method.getModifiers());
            shape(method.getDeclaringClass() == owner && !method.isVarArgs());
            shape(method.getReturnType() == (slot == 5 ? Pair.class : slot == 8 ? IBinder.class : void.class));
            shape(slot != 7 || Modifier.isAbstract(method.getModifiers())); return;
        }
        if (slot >= 9 && slot <= 15) {
            String[] names = {"inputConfig", "frame", "touchableRegion", "layoutParamsType", "ownerUid", "packageName", "displayId"};
            Class<?>[] types = {int.class, Rect.class, Region.class, int.class, int.class, String.class, int.class};
            Class<?> handle = load("android.view.InputWindowHandle");
            Field field = handle.getField(names[slot - 9]); publicInstance(field.getModifiers());
            shape(field.getDeclaringClass() == handle && field.getType() == types[slot - 9]);
            shape(Modifier.isFinal(field.getModifiers()) == (slot == 10 || slot == 11)); return;
        }
        Class<?> config = load("android.os.InputConfig");
        Field field = config.getField(slot == 16 ? "NOT_VISIBLE" : "CLONE");
        shape(field.getDeclaringClass() == config && field.getType() == int.class
                && Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers())
                && Modifier.isFinal(field.getModifiers()));
        shape(field.getInt(null) == (slot == 16 ? RawCollector.NOT_VISIBLE : RawCollector.CLONE));
    }

    private JSONObject makeResult(List<RawCollector.Snapshot> samples, boolean truncated) throws Exception {
        JSONArray admitted = new JSONArray();
        for (int i = 0; i < admission.length; i++) admitted.put(new JSONObject().put("id", ADMISSION_IDS[i]).put("status", admission[i]));
        JSONArray values = new JSONArray(); for (RawCollector.Snapshot sample : samples) values.put(snapshot(sample));
        long finished = SystemClock.elapsedRealtime();
        return new JSONObject().put("schemaVersion", 1).put("source", source)
                .put("imePackage", imePackage).put("imeUid", imeUid).put("displayId", displayId)
                .put("process", new JSONObject().put("sdk", Build.VERSION.SDK_INT).put("uid", Process.myUid())
                        .put("startedElapsedMs", started).put("finishedElapsedMs", finished)
                        .put("fingerprintSha256", sha(Build.FINGERPRINT)))
                .put("limits", new JSONObject().put("admissionMs", ADMISSION_MS).put("observationMs", OBSERVATION_MS)
                        .put("maxWindows", RawCollector.MAX_WINDOWS).put("maxCandidateRows", RawCollector.MAX_CANDIDATE_ROWS)
                        .put("maxBinderIds", RawCollector.MAX_BINDER_IDS).put("maxCallbacks", RawCollector.MAX_CALLBACKS)
                        .put("maxMarkerBytes", MAX_MARKER_BYTES))
                .put("admission", admitted).put("constructionStatus", construction).put("registrationStatus", registration)
                .put("unregistrationStatus", unregistration).put("resultStatus", resultStatus)
                .put("constructAttempts", constructAttempts).put("registerAttempts", registerAttempts).put("unregisterAttempts", unregisterAttempts)
                .put("times", new JSONObject().put("constructedElapsedMs", nullable(constructedAt))
                        .put("registeredElapsedMs", nullable(registeredAt)).put("readyElapsedMs", nullable(readyAt))
                        .put("observationDeadlineElapsedMs", nullable(deadlineAt)).put("freezeElapsedMs", nullable(freezeAt))
                        .put("unregisteredElapsedMs", nullable(unregisteredAt)))
                .put("collectionFrozen", collector.frozen()).put("callbacksSeen", collector.callbacksSeen())
                .put("callbacksStored", collector.samples().size()).put("callbacksDropped", collector.callbacksDropped())
                .put("callbacksOutsideWindow", collector.callbacksOutsideWindow()).put("binderIdsAssigned", collector.binderIdsAssigned())
                .put("outputTruncated", truncated).put("outputOmittedCallbacks", collector.samples().size() - samples.size())
                .put("initial", collector.initial() == null ? JSONObject.NULL : snapshot(collector.initial())).put("samples", values);
    }
    private void emitResult() throws Exception {
        List<RawCollector.Snapshot> samples = new ArrayList<>(collector.samples());
        boolean truncated = false;
        while (true) {
            String line = RESULT + makeResult(samples, truncated).toString();
            if (line.getBytes(StandardCharsets.UTF_8).length + 1 <= MAX_MARKER_BYTES) {
                System.out.println(line); return;
            }
            if (samples.isEmpty()) throw new IllegalStateException();
            samples.remove(samples.size() - 1); truncated = true;
            if ("RECORDED".equals(resultStatus)) resultStatus = "INCOMPLETE";
        }
    }
    private static JSONObject snapshot(RawCollector.Snapshot sample) throws Exception {
        JSONArray rows = new JSONArray();
        for (RawCollector.Row row : sample.rows) {
            JSONArray rect = new JSONArray(); for (int edge : row.frame) rect.put(edge);
            rows.put(new JSONObject().put("rowIndex", row.rowIndex).put("binderId", row.binderId)
                    .put("inputConfig", row.inputConfig).put("frame", rect).put("regionEmpty", row.regionEmpty)
                    .put("notVisible", row.notVisible).put("isClone", row.isClone).put("frameEmpty", row.frameEmpty)
                    .put("passesFourPredicates", row.passesFourPredicates).put("ownerUid", row.ownerUid)
                    .put("layoutParamsType", row.layoutParamsType).put("displayId", row.displayId));
        }
        JSONArray issues = new JSONArray(); for (String issue : sample.issues) issues.put(issue);
        return new JSONObject().put("kind", sample.kind).put("sequence", sample.sequence)
                .put("elapsedRealtimeMs", sample.elapsedRealtimeMs).put("rawWindowCount", sample.rawWindowCount)
                .put("typeCount", sample.typeCount).put("displayTypeCount", sample.displayTypeCount)
                .put("uidTypeDisplayCount", sample.uidTypeDisplayCount).put("nullPackageCount", sample.nullPackageCount)
                .put("candidateRowCount", sample.candidateRowCount).put("nullTokenCount", sample.nullTokenCount)
                .put("distinctBinderCount", sample.distinctBinderCount).put("complete", sample.complete())
                .put("issues", issues).put("rows", rows);
    }
    private static Object nullable(Long value) { return value == null ? JSONObject.NULL : value; }
    private static String sha(String text) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(64);
        for (byte value : hash) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
