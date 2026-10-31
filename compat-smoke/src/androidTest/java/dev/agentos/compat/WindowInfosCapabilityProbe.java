package dev.agentos.compat;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Fixed public-member capability observations; never registers or reads a window listener. */
final class WindowInfosCapabilityProbe {
    static final String MARKER = "WINDOW_INFOS_CAPABILITY_V1 ";
    static final int MAX_MARKER_CHARS = 16 * 1024;
    static final int DEADLINE_MILLIS = 5000;
    private static final String FOR_TEST = "android.window.WindowInfosListenerForTest";
    private static final String[] CLASS_NAMES = {FOR_TEST, FOR_TEST + "$WindowInfo", FOR_TEST + "$DisplayInfo"};

    interface Clock { long now(); }

    private enum Kind { CLASS, CONSTRUCTOR, METHOD, FIELD, GAP }

    private static final class Spec {
        final String id, name;
        final Kind kind;
        final int owner;
        final Class<?> type, parameter;
        Spec(String id, Kind kind, int owner, String name, Class<?> type, Class<?> parameter) {
            this.id = id; this.kind = kind; this.owner = owner; this.name = name;
            this.type = type; this.parameter = parameter;
        }
    }

    private static final Spec[] CATALOG = {
            new Spec("class.forTest", Kind.CLASS, 0, FOR_TEST, null, null),
            new Spec("class.windowInfo", Kind.CLASS, 1, CLASS_NAMES[1], null, null),
            new Spec("class.displayInfo", Kind.CLASS, 2, CLASS_NAMES[2], null, null),
            new Spec("constructor.forTest", Kind.CONSTRUCTOR, 0, "<init>", null, null),
            new Spec("method.add.consumer", Kind.METHOD, 0, "addWindowInfosListener", void.class, Consumer.class),
            new Spec("method.add.biConsumer", Kind.METHOD, 0, "addWindowInfosListener", void.class, BiConsumer.class),
            new Spec("method.remove.consumer", Kind.METHOD, 0, "removeWindowInfosListener", void.class, Consumer.class),
            new Spec("method.remove.biConsumer", Kind.METHOD, 0, "removeWindowInfosListener", void.class, BiConsumer.class),
            new Spec("field.windowInfo.windowToken", Kind.FIELD, 1, "windowToken", IBinder.class, null),
            new Spec("field.windowInfo.name", Kind.FIELD, 1, "name", String.class, null),
            new Spec("field.windowInfo.displayId", Kind.FIELD, 1, "displayId", int.class, null),
            new Spec("field.windowInfo.bounds", Kind.FIELD, 1, "bounds", Rect.class, null),
            new Spec("field.windowInfo.touchableRegion", Kind.FIELD, 1, "touchableRegion", Region.class, null),
            new Spec("field.windowInfo.transform", Kind.FIELD, 1, "transform", Matrix.class, null),
            new Spec("field.windowInfo.isTrustedOverlay", Kind.FIELD, 1, "isTrustedOverlay", boolean.class, null),
            new Spec("field.windowInfo.isVisible", Kind.FIELD, 1, "isVisible", boolean.class, null),
            new Spec("field.windowInfo.isTouchable", Kind.FIELD, 1, "isTouchable", boolean.class, null),
            new Spec("field.windowInfo.isFocusable", Kind.FIELD, 1, "isFocusable", boolean.class, null),
            new Spec("field.windowInfo.isDuplicateTouchToWallpaper", Kind.FIELD, 1, "isDuplicateTouchToWallpaper", boolean.class, null),
            new Spec("field.windowInfo.isWatchOutsideTouch", Kind.FIELD, 1, "isWatchOutsideTouch", boolean.class, null),
            new Spec("field.displayInfo.displayId", Kind.FIELD, 2, "displayId", int.class, null),
            new Spec("field.displayInfo.transform", Kind.FIELD, 2, "transform", Matrix.class, null),
            // Source-unlisted queries: no invented expected types and no instance field reads.
            new Spec("gap.windowInfo.inputConfig", Kind.GAP, 1, "inputConfig", null, null),
            new Spec("gap.windowInfo.isClone", Kind.GAP, 1, "isClone", null, null),
            new Spec("gap.windowInfo.frame", Kind.GAP, 1, "frame", null, null),
            new Spec("gap.windowInfo.layoutParamsType", Kind.GAP, 1, "layoutParamsType", null, null),
            new Spec("gap.windowInfo.ownerUid", Kind.GAP, 1, "ownerUid", null, null)
    };

    private WindowInfosCapabilityProbe() { }

    static JSONObject inspect(ClassLoader loader, Clock clock) throws Exception {
        long started = clock.now(), deadline = started + DEADLINE_MILLIS;
        Class<?>[] classes = new Class<?>[CLASS_NAMES.length];
        Constructor<?> constructor = null;
        JSONArray lookups = new JSONArray();
        int attempts = 0;
        boolean skippedDeadline = false, requiredMatched = true;
        for (Spec spec : CATALOG) {
            JSONObject entry;
            if (clock.now() >= deadline) {
                skippedDeadline = true;
                entry = entry(spec.id, "SKIPPED_DEADLINE", false);
            } else if (spec.kind != Kind.CLASS && classes[spec.owner] == null) {
                entry = entry(spec.id, "SKIPPED_CLASS_UNAVAILABLE", false);
            } else {
                attempts++;
                try {
                    if (spec.kind == Kind.CLASS) {
                        Class<?> found = Class.forName(spec.name, false, loader);
                        int modifiers = found.getModifiers();
                        boolean type = spec.name.equals(found.getName()) && !Modifier.isAbstract(modifiers)
                                && !found.isInterface() && !found.isEnum() && !found.isAnnotation();
                        Class<?> declaring = found.getDeclaringClass();
                        boolean owner = spec.owner == 0 ? declaring == null : declaring != null && FOR_TEST.equals(declaring.getName());
                        boolean matched = Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) == (spec.owner != 0) && type && owner;
                        entry = found(spec.id, matched ? "FOUND_MATCHED" : "FOUND_MISMATCH", modifiers, "CLASS", type, owner);
                        if (matched) classes[spec.owner] = found;
                    } else if (spec.kind == Kind.CONSTRUCTOR) {
                        Constructor<?> found = classes[spec.owner].getConstructor();
                        int modifiers = found.getModifiers();
                        boolean type = found.getParameterTypes().length == 0;
                        boolean owner = found.getDeclaringClass() == classes[spec.owner];
                        boolean matched = Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers) && type && owner;
                        entry = found(spec.id, matched ? "FOUND_MATCHED" : "FOUND_MISMATCH", modifiers, "CONSTRUCTOR", type, owner);
                        if (matched) constructor = found;
                    } else if (spec.kind == Kind.METHOD) {
                        Method found = classes[spec.owner].getMethod(spec.name, spec.parameter);
                        int modifiers = found.getModifiers();
                        Class<?>[] parameters = found.getParameterTypes();
                        boolean type = found.getReturnType() == spec.type && parameters.length == 1 && parameters[0] == spec.parameter;
                        boolean owner = found.getDeclaringClass() == classes[spec.owner];
                        boolean matched = Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers) && type && owner;
                        entry = found(spec.id, matched ? "FOUND_MATCHED" : "FOUND_MISMATCH", modifiers,
                                typeName(found.getReturnType()), type, owner);
                    } else {
                        Field found = classes[spec.owner].getField(spec.name);
                        int modifiers = found.getModifiers();
                        Class<?> fieldType = found.getType();
                        boolean owner = found.getDeclaringClass() == classes[spec.owner];
                        if (spec.kind == Kind.GAP) {
                            entry = found(spec.id, "FOUND_SOURCE_UNLISTED", modifiers, typeName(fieldType), null, owner);
                        } else {
                            boolean type = fieldType == spec.type;
                            boolean matched = Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers)
                                    && Modifier.isFinal(modifiers) && type && owner;
                            entry = found(spec.id, matched ? "FOUND_MATCHED" : "FOUND_MISMATCH", modifiers, typeName(fieldType), type, owner);
                        }
                    }
                } catch (Throwable unavailable) {
                    entry = entry(spec.id, failureStatus(unavailable), true);
                }
            }
            if (spec.kind != Kind.GAP && !"FOUND_MATCHED".equals(entry.getString("status"))) requiredMatched = false;
            lookups.put(entry);
        }

        JSONObject construction = new JSONObject().put("status", "NOT_ATTEMPTED_LOOKUP_UNAVAILABLE").put("attempts", 0).put("completed", 0);
        if (clock.now() >= deadline) {
            skippedDeadline = true;
            construction.put("status", "SKIPPED_DEADLINE");
        } else if (constructor != null) {
            construction.put("attempts", 1);
            try {
                // This one explicitly allowed constructor initializes only the ForTest wrapper.
                // None of its listener methods is invoked and no WindowInfo/DisplayInfo is created.
                Object instance = constructor.newInstance();
                if (instance != null && instance.getClass() == classes[0]) {
                    construction.put("status", "CONSTRUCTED").put("completed", 1);
                } else construction.put("status", "REFLECTION_ERROR");
            } catch (InvocationTargetException targetFailure) {
                construction.put("status", "INVOCATION_FAILED");
            } catch (Throwable unavailable) {
                construction.put("status", failureStatus(unavailable));
            }
        }
        long ended = clock.now();
        boolean exceeded = skippedDeadline || ended >= deadline;
        return new JSONObject().put("schemaVersion", 1).put("kind", "window-infos-public-test-api-capability")
                .put("catalogVersion", 1).put("executionContext", "DEFAULT_INSTRUMENTATION_TEST_PROCESS")
                .put("reflectionPolicy", "PUBLIC_WHITELIST_ONLY_NO_ADDITIONAL_EXEMPTION_REQUEST")
                .put("clock", "SystemClock.elapsedRealtime").put("startedElapsedMillis", started).put("endedElapsedMillis", ended)
                .put("betweenOperationDeadlineMillis", DEADLINE_MILLIS).put("deadlineExceeded", exceeded)
                .put("probeExecution", exceeded ? "INCOMPLETE_DEADLINE" : "COMPLETE")
                .put("lookupLimit", CATALOG.length).put("lookupAttempts", attempts).put("lookups", lookups).put("constructor", construction)
                .put("capability", new JSONObject().put("requiredSurface", exceeded ? "UNVERIFIED_DEADLINE" : requiredMatched ? "MATCHED" : "NOT_FULLY_MATCHED")
                        .put("listenerRegistration", "NOT_ATTEMPTED").put("windowData", "NOT_READ"))
                .put("diagnosticTruncated", false);
    }

    private static JSONObject entry(String id, String status, boolean attempted) throws Exception {
        return new JSONObject().put("id", id).put("status", status).put("attempted", attempted)
                .put("public", JSONObject.NULL).put("static", JSONObject.NULL).put("final", JSONObject.NULL)
                .put("observedType", JSONObject.NULL).put("typeMatches", JSONObject.NULL).put("declaringClassMatches", JSONObject.NULL);
    }

    private static JSONObject found(String id, String status, int modifiers, String observedType,
                                    Boolean typeMatches, boolean declaringMatches) throws Exception {
        return entry(id, status, true).put("public", Modifier.isPublic(modifiers)).put("static", Modifier.isStatic(modifiers))
                .put("final", Modifier.isFinal(modifiers)).put("observedType", observedType)
                .put("typeMatches", typeMatches == null ? JSONObject.NULL : typeMatches).put("declaringClassMatches", declaringMatches);
    }

    private static String failureStatus(Throwable failure) {
        if (failure instanceof ClassNotFoundException || failure instanceof NoSuchMethodException || failure instanceof NoSuchFieldException)
            return "NOT_FOUND_OR_FILTERED";
        if (failure instanceof SecurityException || failure instanceof IllegalAccessException) return "ACCESS_DENIED";
        if (failure instanceof LinkageError) return "LINKAGE_ERROR";
        return "REFLECTION_ERROR";
    }

    private static String typeName(Class<?> type) {
        if (type == void.class) return "VOID";
        if (type == int.class) return "INT";
        if (type == boolean.class) return "BOOLEAN";
        if (type == String.class) return "STRING";
        if (type == IBinder.class) return "IBINDER";
        if (type == Rect.class) return "RECT";
        if (type == Region.class) return "REGION";
        if (type == Matrix.class) return "MATRIX";
        return "OTHER";
    }
}
