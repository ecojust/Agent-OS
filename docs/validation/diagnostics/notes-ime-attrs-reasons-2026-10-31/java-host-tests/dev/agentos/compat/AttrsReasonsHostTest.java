package dev.agentos.compat;

import android.app.UiAutomation;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact legacy outcomes plus independently asserted fixed metadata; no live Android claim. */
public final class AttrsReasonsHostTest {
    private static final String TOKEN = "android.os.BinderProxy@abc123";
    private static int differentialCases, metadataChecks;
    private static final Method NEW_TYPE;
    private static final Field RESULT_TYPE;
    static {
        try {
            NEW_TYPE = NotesImeAssociationDiagnostic.class.getDeclaredMethod("windowType", String.class);
            NEW_TYPE.setAccessible(true);
            RESULT_TYPE = NEW_TYPE.getReturnType().getDeclaredField("type"); RESULT_TYPE.setAccessible(true);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void check(boolean condition, String message) {
        metadataChecks++; if (!condition) throw new AssertionError(message);
    }
    private static void sameAsLegacy(String record) throws Exception {
        String legacy = LegacyWindowTypeOracle.windowType(record);
        String current = (String) RESULT_TYPE.get(NEW_TYPE.invoke(null, record));
        differentialCases++;
        if (!Objects.equals(legacy, current)) throw new AssertionError("Legacy type outcome changed for synthetic case " + differentialCases);
    }
    private static String record(String token, String attrs) {
        return "  Window #0 Window{" + token + " u0 private-window-title}:\n"
                + "    mDisplayId=0 mSession=Session{private-session} mClient=" + TOKEN + "\n"
                + "    mOwnerUid=10012 showForAllUsers=false package=com.android.inputmethod.latin appop=NONE\n"
                + "    mAttrs=" + attrs + "\n";
    }
    private static JSONObject parse(String records) throws Exception {
        return NotesImeAssociationDiagnostic.parseWms("WINDOW MANAGER WINDOWS (dumpsys window windows)\n" + records
                + "  mInputMethodWindow=Window{abc u0 private-window-title}\n", 10012, "com.android.inputmethod.latin");
    }
    private static JSONObject failure(String record, String reason) throws Exception {
        JSONObject parsed = parse(record);
        check(parsed.getString("status").equals("UNAVAILABLE") && parsed.getString("reason").equals("WMS_ATTRIBUTES_INCOMPLETE"),
                "Attribute failure changed original refusal");
        JSONObject details = parsed.getJSONObject("attrsDiagnostics");
        check(details.getInt("unparsedCount") == parsed.getInt("attributesUnparsedCount")
                && details.getInt("recordCount") == parsed.getInt("attributesParsedCount") + parsed.getInt("attributesUnparsedCount"),
                "Attribute accounting disagrees with original counters");
        JSONObject item = details.getJSONArray("entries").getJSONObject(0);
        check(item.getString("reason").equals(reason), "Unexpected fixed rejection reason");
        check(!item.toString().contains("private-window-title") && !item.toString().contains("private-session")
                && !item.toString().contains("com.android.inputmethod.latin"), "Arbitrary record text escaped");
        return item;
    }
    private static void differential() throws Exception {
        String[] types = {"INPUT_METHOD", "2011", "APPLICATION", "2038", "", "-2011", "+2011", "0x7db", "private-value", "UNKNOWN_TYPE",
                "A".repeat(64), "A".repeat(65), "A".repeat(80)};
        for (String type : types) {
            for (int depth = 0; depth <= 10; depth++) {
                String attrs = "{" + "{".repeat(depth) + "ty=" + type + "}".repeat(depth) + "}";
                sameAsLegacy(record("abc", attrs));
                sameAsLegacy(record("abc", "{sim={adjust=RESIZE} ty=" + type + "\n paramsForRotation: {ty=APPLICATION}}"));
                sameAsLegacy(record("abc", "{ty=" + type + " ty=" + type + "}"));
            }
        }
        for (int padding = 8170; padding <= 8200; padding++) {
            for (String type : types) {
                sameAsLegacy(record("abc", "{" + " ".repeat(padding) + "ty=" + type + "}"));
                sameAsLegacy(record("abc", "{" + " ".repeat(padding) + "ty=" + type));
            }
        }
        Random random = new Random(0x9b86);
        for (int i = 0; i < 2000; i++) {
            String type = types[random.nextInt(types.length)];
            String attrs = "{" + " ".repeat(random.nextInt(20)) + (random.nextBoolean() ? "sim={adjust=RESIZE} " : "")
                    + "ty=" + type + (random.nextBoolean() ? "}" : "")
                    + (random.nextInt(4) == 0 ? "\n    mAttrs={ty=APPLICATION}" : "");
            String candidate = record("abc", attrs);
            if (random.nextInt(7) == 0) candidate = candidate.replace("mAttrs=", "mOtherAttrs=");
            sameAsLegacy(candidate);
        }
    }
    private static void reasonsAndLexicalBounds() throws Exception {
        JSONObject missing = failure(record("abc", "{ty=INPUT_METHOD}").replace("mAttrs=", "mOtherAttrs="), "ATTRS_ANCHOR_MISSING");
        check(missing.getInt("attrsAnchorCount") == 0 && missing.isNull("firstAttrsOffset") && missing.isNull("secondAttrsOffset")
                && missing.getInt("scanChars") == 0 && missing.getInt("maxDepth") == 0, "Missing anchor metadata is inconsistent");
        failure(record("abc", "{nested={ty=INPUT_METHOD}}"), "TOP_LEVEL_TYPE_MISSING");
        JSONObject duplicate = failure(record("abc", "{ty=INPUT_METHOD ty=2011}"), "TOP_LEVEL_TYPE_DUPLICATE");
        check(duplicate.getInt("topLevelTypeCount") == 2, "Duplicate type count omitted a field");
        JSONObject deep = failure(record("abc", "{".repeat(9) + "}".repeat(9)), "DEPTH_LIMIT");
        check(deep.getInt("maxDepth") == 9, "Depth-limit observation not retained");
        JSONObject unclosed = failure(record("abc", "{ty=INPUT_METHOD"), "ATTRS_UNCLOSED");
        check(unclosed.getInt("topLevelTypeCount") == 1 && unclosed.getInt("scanChars") < 8192, "Unclosed attribute extent incorrect");
        JSONObject limit = failure(record("abc", "{" + " ".repeat(8200) + "ty=INPUT_METHOD}"), "ATTRS_SIZE_LIMIT");
        check(limit.getInt("scanChars") == 8192, "Ordinary size-limit scan extent changed");
        JSONObject open = failure(record("abc", "{ty=INPUT_METHOD{bad}}"), "TYPE_VALUE_FOLLOWED_BY_OPEN_BRACE");
        check(open.getString("typeLexicalClass").equals("UPPERCASE_SYMBOL") && open.getInt("typeValueLength") == 12
                && open.getBoolean("typeValueComplete") && open.getInt("topLevelTypeCount") == 1,
                "Unexpected-open-brace type metadata is incorrect");
        String[][] values = {{"", "EMPTY"}, {"-2011", "SIGNED_DECIMAL"}, {"0x7db", "HEX"}, {"private-value", "OTHER"},
                {"A".repeat(65), "UPPERCASE_SYMBOL"}, {"1".repeat(65), "DECIMAL"}};
        for (String[] value : values) {
            JSONObject item = failure(record("abc", "{ty=" + value[0] + "}"), "TYPE_VALUE_UNRECOGNIZED");
            check(item.getString("typeLexicalClass").equals(value[1]) && item.getInt("typeValueLength") == value[0].length()
                    && item.getBoolean("typeValueComplete") && item.getInt("topLevelTypeCount") == 1,
                    "Invalid type metadata is not a fixed lexical classification");
            check(!item.toString().contains("private-value"), "Unknown type plaintext escaped");
        }
        JSONObject prefix = failure(record("abc", "{ty=" + "A".repeat(80) + "}"), "TYPE_VALUE_UNRECOGNIZED");
        check(prefix.getInt("typeValueLength") == 65 && !prefix.getBoolean("typeValueComplete"), "Bounded type prefix became a complete value");
    }
    private static void tailAndRetention() throws Exception {
        String[] headings = {"Hiding System Alert Windows:", "Windows force removing:", "Windows waiting to destroy their surface:", "Windows waiting to resize:"};
        String[] kinds = {"HIDING_SYSTEM_ALERT_WINDOWS", "FORCE_REMOVING_WINDOWS", "DESTROY_SURFACE_WINDOWS", "RESIZING_WINDOWS"};
        for (int i = 0; i < headings.length; i++) {
            String text = record("abc", "{ty=INPUT_METHOD}") + "  " + headings[i] + "\n  Resizing #0 Window{abc u0 private-window-title}:\n    mAttrs={ty=INPUT_METHOD}\n";
            JSONObject item = failure(text, "ATTRS_ANCHOR_DUPLICATE");
            check(item.getInt("attrsAnchorCount") == 2 && item.getInt("scanChars") == 0 && item.getInt("maxDepth") == 0
                    && item.getInt("topLevelTypeCount") == 0 && item.getBoolean("knownTailSectionBetweenAnchors")
                    && item.getJSONObject("firstKnownTailSection").getString("kind").equals(kinds[i]), "Known tail heading did not remain structural metadata");
            int tail = item.getJSONObject("firstKnownTailSection").getInt("offset");
            check(item.getInt("firstAttrsOffset") < tail && tail < item.getInt("secondAttrsOffset")
                    && item.getString("recordEnd").equals("DUMP_END") && item.getString("headerRelation").equals("MATCHES_SELECTED_REFERENCE"),
                    "Tail/anchor ordering or original header relation changed");
            check(parse(text).getString("status").equals("UNAVAILABLE"), "Known tail heading relaxed duplicate-attribute refusal");
        }
        JSONObject noHeading = failure(record("def", "{ty=INPUT_METHOD}\n    mAttrs={ty=INPUT_METHOD}"), "ATTRS_ANCHOR_DUPLICATE");
        check(noHeading.isNull("firstKnownTailSection") && !noHeading.getBoolean("knownTailSectionBetweenAnchors")
                && noHeading.getString("headerRelation").equals("OTHER_RECOGNIZED_WINDOW"), "Absent heading or other header relation was guessed");
        JSONObject titleHeading = failure(record("abc", "{ty=INPUT_METHOD}\n    private-title Windows waiting to resize: more-private\n    mAttrs={ty=INPUT_METHOD}"), "ATTRS_ANCHOR_DUPLICATE");
        check(titleHeading.isNull("firstKnownTailSection"), "Heading-shaped arbitrary text matched a fixed complete heading line");
        JSONObject unknownHeader = failure(record("abc", "{bad}").replace("Window{abc u0 private-window-title}", "MalformedHeader"), "TOP_LEVEL_TYPE_MISSING");
        check(unknownHeader.getString("headerRelation").equals("UNRECOGNIZED"), "Unrecognized header became unrelated");
        StringBuilder many = new StringBuilder(record("abc", "{ty=INPUT_METHOD}"));
        for (int i = 1; i <= 20; i++) many.append(record(Integer.toHexString(i + 100), "{ty=private-value}"));
        JSONObject parsed = parse(many.toString());
        JSONObject diagnostics = parsed.getJSONObject("attrsDiagnostics");
        JSONArray entries = diagnostics.getJSONArray("entries");
        check(diagnostics.getInt("recordCount") == 21 && diagnostics.getInt("unparsedCount") == 20 && entries.length() == 8
                && diagnostics.getInt("omittedCount") == 12 && diagnostics.getInt("maxRetained") == 8, "Failure list retention/accounting exceeded its bound");
        for (int i = 0; i < entries.length(); i++) check(entries.getJSONObject(i).getInt("recordOrdinal") == i + 2
                && entries.getJSONObject(i).getString("recordEnd").equals("NEXT_WINDOW_HEADER"), "Failure list was not the ordered prefix");
        check(diagnostics.toString().length() < 8192 && !diagnostics.toString().contains("private-value"), "Bounded detail list leaked field contents or exceeded expected fixture size");
        String dump = "WINDOW MANAGER WINDOWS (dumpsys window windows)\n" + many + "  mInputMethodWindow=Window{abc u0 private-window-title}\n";
        UiAutomation automation = new UiAutomation(command -> new ParcelFileDescriptor(new ByteArrayInputStream(
                (command.equals("dumpsys -t 2 window -a windows") ? dump : "").getBytes(StandardCharsets.UTF_8))));
        AssertionError original = new AssertionError("original timeout");
        NotesImeAssociationDiagnostic.attach(original, automation, 10012, "com.android.inputmethod.latin/.LatinIME");
        String marker = original.getSuppressed()[0].getMessage();
        JSONObject attached = new JSONObject(marker.substring(NotesImeAssociationDiagnostic.MARKER.length()));
        check(marker.length() <= NotesImeAssociationDiagnostic.MAX_CHARS && !attached.getBoolean("diagnosticTruncated")
                && attached.getJSONArray("commands").getJSONObject(0).getJSONObject("parsed").getJSONObject("attrsDiagnostics").getInt("omittedCount") == 12,
                "Retained failure fixture overflowed or changed the main supplemental marker");
    }
    public static void main(String[] args) throws Exception {
        CollectorHostTest.main(args);
        differential(); reasonsAndLexicalBounds(); tailAndRetention();
        System.out.println("legacy_type_differential_cases=" + differentialCases);
        System.out.println("attrs_metadata_checks=" + metadataChecks);
        System.out.println("attrs_reason_host_tests=passed");
    }
}
