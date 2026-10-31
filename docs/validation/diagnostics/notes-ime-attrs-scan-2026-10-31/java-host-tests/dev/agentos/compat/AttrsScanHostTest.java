package dev.agentos.compat;

import android.app.UiAutomation;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Random;
import org.json.JSONObject;

/** Complete captured-attribute parsing, separate from application behavior and old evidence. */
public final class AttrsScanHostTest {
    private static final int CAP = NotesImeAssociationDiagnostic.MAX_BYTES;
    private static final String RECORD = "  Window #0 Window{abc u0 private-window-title}:\n"
            + "    mDisplayId=0 mSession=Session{private-session} mClient=android.os.BinderProxy@abc123\n"
            + "    mOwnerUid=10012 showForAllUsers=false package=com.android.inputmethod.latin appop=NONE\n    mAttrs=";
    private static final String PREFIX = "WINDOW MANAGER WINDOWS (dumpsys window windows)\n"
            + "  mInputMethodWindow=Window{abc u0 private-window-title}\n" + RECORD;
    private static int checks, compared, changedSizeDomain, preservedOutsideDomain;
    private static final Method SCAN;
    private static final Field TYPE, REASON;
    static {
        try {
            SCAN = NotesImeAssociationDiagnostic.class.getDeclaredMethod("windowType", String.class); SCAN.setAccessible(true);
            TYPE = SCAN.getReturnType().getDeclaredField("type"); TYPE.setAccessible(true);
            REASON = SCAN.getReturnType().getDeclaredField("reason"); REASON.setAccessible(true);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static JSONObject parse(String dump) throws Exception {
        return NotesImeAssociationDiagnostic.parseWms(dump, 10012, "com.android.inputmethod.latin");
    }
    private static void compare(String record) throws Exception {
        LegacyAttrsScanOracle.WindowTypeResult old = LegacyAttrsScanOracle.windowType(record);
        Object result = SCAN.invoke(null, record);
        String current = (String) TYPE.get(result), reason = REASON.get(result).toString();
        compared++;
        if (!old.reason.toString().equals("ATTRS_SIZE_LIMIT")) {
            preservedOutsideDomain++;
            if (!Objects.equals(old.type, current) || !old.reason.toString().equals(reason))
                throw new AssertionError("Unexpected change outside declared legacy size-limit domain, case " + compared);
        } else if (!Objects.equals(old.type, current) || !old.reason.toString().equals(reason)) changedSizeDomain++;
    }
    private static JSONObject expectReason(String attributes, String reason) throws Exception {
        JSONObject parsed = parse(PREFIX + attributes);
        check(parsed.getString("status").equals("UNAVAILABLE") && parsed.getString("reason").equals("WMS_ATTRIBUTES_INCOMPLETE"),
                "Incomplete attrs no longer retain refusal");
        JSONObject diagnostic = parsed.getJSONObject("attrsDiagnostics");
        check(diagnostic.getInt("schemaVersion") == 2 && diagnostic.getInt("scanLimitChars") == CAP && diagnostic.getInt("inputLimitChars") == CAP,
                "New complete-input scan version/limits were not explicit");
        JSONObject entry = diagnostic.getJSONArray("entries").getJSONObject(0);
        check(entry.getString("reason").equals(reason) && entry.getInt("scanChars") <= CAP, "Unexpected bounded attrs reason");
        return entry;
    }
    private static void differential() throws Exception {
        String[] types = {"INPUT_METHOD", "2011", "APPLICATION", "", "-2011", "0x7db", "private-value", "A".repeat(64), "A".repeat(65)};
        for (int padding = 8170; padding <= 8210; padding++) {
            for (String type : types) {
                compare(RECORD + "{" + " ".repeat(padding) + "ty=" + type + "}");
                compare(RECORD + "{ty=INPUT_METHOD " + " ".repeat(padding) + "ty=" + type + "}");
                compare(RECORD + "{" + " ".repeat(padding) + "ty=" + type);
            }
        }
        Random random = new Random(0x35519);
        for (int i = 0; i < 1500; i++) {
            String type = types[random.nextInt(types.length)];
            String body = "{" + "{".repeat(random.nextInt(10)) + "ty=" + type + "}".repeat(random.nextInt(10))
                    + (random.nextBoolean() ? "}" : "") + (random.nextInt(6) == 0 ? "\n    mAttrs={ty=INPUT_METHOD}" : "");
            compare(RECORD + body);
        }
        check(changedSizeDomain > 0 && preservedOutsideDomain > 0, "Differential fixture did not exercise both declared domains");
    }
    private static void completeAndRefusedBoundaries() throws Exception {
        for (int padding : new int[] {8170, 8192, 8193, 12000, 32000}) {
            JSONObject selected = parse(PREFIX + "{ty=INPUT_METHOD " + " ".repeat(padding) + "}");
            check(selected.getString("status").equals("SELECTED") && selected.getInt("attributesUnparsedCount") == 0,
                    "Balanced captured attrs remained limited by the old independent 8192 cursor");
            expectReason("{ty=INPUT_METHOD " + " ".repeat(padding) + "ty=2011}", "TOP_LEVEL_TYPE_DUPLICATE");
            expectReason("{ty=INPUT_METHOD " + " ".repeat(padding), "ATTRS_UNCLOSED");
            expectReason("{ty=INPUT_METHOD " + " ".repeat(padding) + "{".repeat(8) + "}".repeat(8) + "}", "DEPTH_LIMIT");
            expectReason("{ty=INPUT_METHOD " + " ".repeat(padding) + "}\n    mAttrs={ty=INPUT_METHOD}", "ATTRS_ANCHOR_DUPLICATE");
        }
        JSONObject nested = parse(PREFIX + "{ty=INPUT_METHOD " + " ".repeat(12000)
                + "paramsForRotation: {ty=APPLICATION sim={adjust=RESIZE}}}");
        check(nested.getString("status").equals("SELECTED"), "Nested type was mistaken for a top-level duplicate");
        expectReason("{sim={ty=INPUT_METHOD} " + " ".repeat(12000) + "}", "TOP_LEVEL_TYPE_MISSING");
        expectReason("{ty=INPUT_METHOD " + " ".repeat(12000) + "ty=-2011}", "TYPE_VALUE_UNRECOGNIZED");
        String start = PREFIX + "{ty=INPUT_METHOD ";
        String exact = start + " ".repeat(CAP - start.length() - 1) + "}";
        check(exact.length() == CAP, "Exact input-ceiling fixture is not exact");
        JSONObject atCeiling = parse(exact);
        check(atCeiling.getString("status").equals("SELECTED"), "Full balanced input at existing capture ceiling was rejected");
        JSONObject unclosed = parse(exact.substring(0, exact.length() - 1) + " ");
        check(unclosed.getString("status").equals("UNAVAILABLE")
                && unclosed.getJSONObject("attrsDiagnostics").getJSONArray("entries").getJSONObject(0).getString("reason").equals("ATTRS_UNCLOSED"),
                "Full captured input without closing brace was accepted early");
        JSONObject over = parse(exact + "x");
        check(over.length() == 2 && over.getString("status").equals("UNAVAILABLE") && over.getString("reason").equals("WMS_INPUT_LIMIT"),
                "Oversized direct input reached parsing or allocated diagnostic records");
        JSONObject giant = parse("x".repeat(CAP * 4));
        check(giant.length() == 2 && giant.getString("reason").equals("WMS_INPUT_LIMIT"), "Giant direct input lacked the early guard");
        JSONObject identity = NotesImeAssociationDiagnostic.parseWms(PREFIX + "{ty=INPUT_METHOD " + " ".repeat(12000) + "}", 10013, "com.android.inputmethod.latin");
        check(identity.getString("status").equals("UNAVAILABLE") && identity.getString("reason").equals("WMS_IDENTITY_MISMATCH"),
                "A longer complete scan relaxed configured identity checks");
        JSONObject otherUnknown = parse(PREFIX + "{ty=INPUT_METHOD " + " ".repeat(12000)
                + "}\n  Window #1 Window{def u0 private-other-title}:\n    mAttrs={missing-type}");
        check(otherUnknown.getString("status").equals("UNAVAILABLE") && otherUnknown.getInt("attributesUnparsedCount") == 1,
                "A selected-looking IME ignored another unreadable record");
    }
    private static void truncatedCaptureCannotSelect() throws Exception {
        String valid = PREFIX + "{ty=INPUT_METHOD}";
        String oversized = valid + " ".repeat(CAP + 1 - valid.length());
        UiAutomation automation = new UiAutomation(command -> new ParcelFileDescriptor(new ByteArrayInputStream(
                (command.equals("dumpsys -t 2 window -a windows") ? oversized : "").getBytes(StandardCharsets.UTF_8))));
        AssertionError original = new AssertionError("original hidden failure");
        NotesImeAssociationDiagnostic.attach(original, automation, 10012, "com.android.inputmethod.latin/.LatinIME");
        JSONObject data = new JSONObject(original.getSuppressed()[0].getMessage().substring(NotesImeAssociationDiagnostic.MARKER.length()));
        JSONObject wms = data.getJSONArray("commands").getJSONObject(0);
        check(wms.getString("status").equals("BYTE_LIMIT") && !wms.getBoolean("outputComplete")
                && wms.getString("hashScope").equals("RETAINED_PREFIX") && wms.getInt("outputBytes") == CAP,
                "Output cap or its retained-prefix label changed");
        check(wms.getJSONObject("parsed").getString("status").equals("UNAVAILABLE")
                && !wms.getJSONObject("parsed").has("attrsDiagnostics") && data.getJSONObject("association").isNull("tokenCandidate"),
                "A complete-looking prefix of a truncated output became a candidate");
        check(data.getInt("perCommandTimeoutMillis") == 3000 && data.getInt("totalTimeoutMillis") == 9000
                && data.getInt("maxOutputBytesPerCommand") == CAP && data.getJSONArray("commands").length() == 3,
                "Full-input parsing changed collection authority or quotas");
    }
    public static void main(String[] args) throws Exception {
        CollectorHostTest.main(args);
        differential(); completeAndRefusedBoundaries(); truncatedCaptureCannotSelect();
        System.out.println("attrs_scan_differential_cases=" + compared);
        System.out.println("preserved_outside_size_domain=" + preservedOutsideDomain);
        System.out.println("changed_declared_size_domain=" + changedSizeDomain);
        System.out.println("attrs_scan_boundary_checks=" + checks);
        System.out.println("attrs_scan_host_tests=passed");
    }
}
