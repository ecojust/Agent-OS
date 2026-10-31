package dev.agentos.compat;

import android.app.UiAutomation;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Post-failure, test-only observations. Nothing here supplies authority for an action/capture. */
final class NotesImeAssociationDiagnostic {
    static final String MARKER = "NOTES_IME_ASSOCIATION_DIAGNOSTIC_V1 ";
    static final int MAX_CHARS = 16 * 1024;
    static final int MAX_BYTES = 256 * 1024;
    // A decoded capture cannot contain more UTF-16 units than its retained byte ceiling.
    // Scan complete attributes within that existing input bound; never accept at the first type.
    static final int MAX_ATTRS_SCAN_CHARS = MAX_BYTES;
    static final int COMMAND_MILLIS = 3000, TOTAL_MILLIS = 9000;
    private static final String[] IDS = {"WMS_WINDOWS", "WMS_A11Y", "ACCESSIBILITY"};
    private static final String[] COMMANDS = {"dumpsys -t 2 window -a windows",
            "dumpsys -t 2 window a11y", "dumpsys -t 2 accessibility"};
    private static final String BINDER = "android\\.os\\.Binder(?:Proxy)?@[0-9a-fA-F]{1,16}";
    // A timed-out binder/read can outlive its caller. Do not accumulate blocked workers.
    private static final AtomicBoolean WORKER_ACTIVE = new AtomicBoolean();

    private NotesImeAssociationDiagnostic() { }

    static void attach(Throwable original, UiAutomation automation, int uid, String component) {
        try {
            String message;
            try {
                message = MARKER + collect(automation, uid, component).toString();
                if (message.length() > MAX_CHARS) message = MARKER
                        + "{\"diagnosticTruncated\":true,\"untruncatedChars\":" + message.length() + "}";
            } catch (Throwable unavailable) {
                message = MARKER + "{\"diagnosticSerializationFailed\":true}";
            }
            AssertionError diagnostic = new AssertionError(message);
            diagnostic.setStackTrace(new StackTraceElement[0]);
            original.addSuppressed(diagnostic);
        } catch (Throwable unavailable) {
            // Even allocating/attaching the optional fallback can fail. Keep the original error.
        }
    }

    private static JSONObject collect(UiAutomation automation, int uid, String component) throws Exception {
        long started = SystemClock.elapsedRealtime(), deadline = started + TOTAL_MILLIS;
        if (uid < 0 || component == null || !component.matches("[A-Za-z0-9_.]{1,180}/[A-Za-z0-9_.$]{1,180}"))
            return new JSONObject().put("diagnosticIdentityUnavailable", true);
        String packageName = component.substring(0, component.indexOf('/'));
        JSONArray commands = new JSONArray();
        String token = null, stopped = null;
        for (int i = 0; i < COMMANDS.length; i++) {
            JSONObject command;
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (stopped != null || remaining <= 0) {
                command = emptyCommand(i, stopped == null ? "SKIPPED_AFTER_TIMEOUT" : stopped);
            } else {
                command = runCommand(automation, i, uid, packageName, token,
                        Math.min(COMMAND_MILLIS, remaining));
                String status = command.getString("status");
                if ("TIMEOUT".equals(status) || "SERVICE_TIMEOUT".equals(status) || "INTERRUPTED".equals(status))
                    stopped = "SKIPPED_AFTER_TIMEOUT";
                if ("SKIPPED_COLLECTOR_BUSY".equals(status)) stopped = "SKIPPED_COLLECTOR_BUSY";
                if (i == 0 && "COMPLETE".equals(status)) {
                    JSONObject parsed = command.getJSONObject("parsed");
                    if ("SELECTED".equals(parsed.optString("status"))) token = parsed.getString("mClient");
                }
            }
            commands.put(command);
        }
        return new JSONObject().put("schemaVersion", 1).put("phase", "POST_FAILURE_BEFORE_CAPTURE_CLEANUP")
                .put("clock", "SystemClock.elapsedRealtime").put("startedElapsedMillis", started)
                .put("endedElapsedMillis", SystemClock.elapsedRealtime())
                .put("perCommandTimeoutMillis", COMMAND_MILLIS).put("totalTimeoutMillis", TOTAL_MILLIS)
                .put("maxOutputBytesPerCommand", MAX_BYTES)
                .put("configuredIme", new JSONObject().put("ownerUid", uid).put("packageName", packageName)
                        .put("component", component).put("identitySource", "FROZEN_CONFIRMATION"))
                .put("commands", commands).put("association", association(commands, token))
                .put("diagnosticTruncated", false);
    }

    private static JSONObject emptyCommand(int index, String status) throws Exception {
        return new JSONObject().put("ordinal", index + 1).put("id", IDS[index]).put("command", COMMANDS[index])
                .put("startedElapsedMillis", JSONObject.NULL).put("endedElapsedMillis", JSONObject.NULL)
                .put("status", status).put("exitStatus", "NOT_EXPOSED")
                .put("outputBytes", 0).put("outputSha256", JSONObject.NULL).put("hashScope", "UNAVAILABLE")
                .put("outputComplete", false).put("parsed", unavailable("SOURCE_UNAVAILABLE"));
    }

    private static final class ReadTask implements Runnable {
        final UiAutomation automation;
        final int index, uid;
        final String packageName, token;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CountDownLatch finished = new CountDownLatch(1);
        volatile JSONObject result;
        volatile int bytes;
        ReadTask(UiAutomation automation, int index, int uid, String packageName, String token) {
            this.automation = automation; this.index = index; this.uid = uid;
            this.packageName = packageName; this.token = token;
        }
        @Override public void run() {
            try {
                if (cancelled.get()) return;
                // Both the synchronous UiAutomation binder call and stream read are off the caller.
                try (ParcelFileDescriptor descriptor = automation.executeShellCommand(COMMANDS[index]);
                     InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                    if (cancelled.get()) return;
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    boolean complete = false;
                    while (!cancelled.get()) {
                        int read = input.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - output.size()));
                        if (read < 0) { complete = true; break; }
                        if (read == 0) continue;
                        int retained = Math.min(read, MAX_BYTES - output.size());
                        output.write(buffer, 0, retained); bytes = output.size();
                        if (retained < read) break;
                    }
                    if (cancelled.get()) return;
                    byte[] retained = output.toByteArray();
                    String dump = new String(retained, StandardCharsets.UTF_8);
                    String status = complete ? serviceStatus(dump) : "BYTE_LIMIT";
                    JSONObject parsed = "COMPLETE".equals(status)
                            ? index == 0 ? parseWms(dump, uid, packageName)
                            : index == 1 ? parsePopulator(dump, token) : parseAccessibility(dump, token)
                            : unavailable("SOURCE_UNAVAILABLE");
                    result = new JSONObject().put("status", status).put("outputBytes", retained.length)
                            .put("outputSha256", hex(MessageDigest.getInstance("SHA-256").digest(retained)))
                            .put("hashScope", complete ? "FULL_OUTPUT" : "RETAINED_PREFIX")
                            .put("outputComplete", complete).put("parsed", parsed);
                }
            } catch (Throwable unavailable) {
                // No exception message: remote output and arbitrary window titles are not evidence fields.
            } finally {
                WORKER_ACTIVE.set(false);
                finished.countDown();
            }
        }
    }

    private static JSONObject runCommand(UiAutomation automation, int index, int uid,
                                         String packageName, String token, long waitMillis) throws Exception {
        JSONObject command = emptyCommand(index, "SKIPPED_COLLECTOR_BUSY");
        if (!WORKER_ACTIVE.compareAndSet(false, true)) return command;
        ReadTask task;
        Thread worker;
        try {
            command.put("startedElapsedMillis", SystemClock.elapsedRealtime());
            task = new ReadTask(automation, index, uid, packageName, token);
            worker = new Thread(task, "notes-ime-post-failure");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable unavailable) {
            WORKER_ACTIVE.set(false);
            return command.put("status", "READ_FAILED").put("endedElapsedMillis", SystemClock.elapsedRealtime());
        }
        boolean completed = false;
        String status = "TIMEOUT";
        try { completed = task.finished.await(waitMillis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { status = "INTERRUPTED"; Thread.currentThread().interrupt(); }
        if (!completed) {
            task.cancelled.set(true);
            worker.interrupt();
            command.put("status", status).put("outputBytes", task.bytes);
        } else if (task.result == null) {
            command.put("status", "READ_FAILED").put("outputBytes", task.bytes);
        } else {
            for (String key : new String[] {"status", "outputBytes", "outputSha256", "hashScope", "outputComplete", "parsed"})
                command.put(key, task.result.get(key));
        }
        return command.put("endedElapsedMillis", SystemClock.elapsedRealtime());
    }

    private static String serviceStatus(String dump) {
        if (Pattern.compile("(?im)^.*(?:Permission Denial|Permission denied|requires android\\.permission\\.DUMP).*$").matcher(dump).find())
            return "PERMISSION_DENIED";
        if (Pattern.compile("(?im)^.*(?:DUMP TIMEOUT|dumpsys.*timed out).*$").matcher(dump).find()) return "SERVICE_TIMEOUT";
        if (Pattern.compile("(?im)^(?:Can't find service:|Unknown command:|Bad window command).*\\r?$").matcher(dump).find())
            return "COMMAND_UNAVAILABLE";
        return "COMPLETE"; // EOF only; this API does not expose stderr or an exit code.
    }

    private static JSONObject unavailable(String reason) throws Exception {
        return new JSONObject().put("status", "UNAVAILABLE").put("reason", reason);
    }

    static JSONObject parseWms(String dump, int uid, String packageName) throws Exception {
        // Direct parser callers get the same text-size ceiling before regex/split allocation.
        if (dump.length() > MAX_BYTES) return unavailable("WMS_INPUT_LIMIT");
        JSONObject parsed = unavailable("WMS_HEADER_UNRECOGNIZED");
        if (!dump.startsWith("WINDOW MANAGER WINDOWS")) return parsed;
        Matcher reference = Pattern.compile("(?m)^[\\t ]*mInputMethodWindow=Window\\{([0-9a-fA-F]{1,32})\\s+u0\\s+[^}\\r\\n]+\\}").matcher(dump);
        int references = 0;
        String stateToken = null;
        while (reference.find()) { references++; stateToken = reference.group(1); }
        parsed.put("referenceCount", references).put("reason", "WMS_REFERENCE_MISSING_OR_AMBIGUOUS");
        if (references != 1) return parsed;
        String selected = null;
        int imeCount = 0, attributesParsed = 0, attributesUnparsed = 0, recordCount = 0;
        JSONArray failures = new JSONArray();
        String[] records = dump.split("(?m)(?=^[\\t ]*Window #[0-9]+ )");
        for (int recordIndex = 0; recordIndex < records.length; recordIndex++) {
            String record = records[recordIndex];
            if (!record.trim().startsWith("Window #")) continue;
            recordCount++;
            WindowTypeResult attributes = windowType(record);
            String type = attributes.type;
            if (type == null) {
                attributesUnparsed++;
                if (failures.length() < 8) failures.put(attributes.diagnostic(record, recordCount,
                        recordIndex == records.length - 1 ? "DUMP_END" : "NEXT_WINDOW_HEADER", stateToken));
                continue;
            }
            attributesParsed++;
            if (!"INPUT_METHOD".equals(type) && !"2011".equals(type)) continue;
            imeCount++;
            if (Pattern.compile("^[\\t ]*Window #[0-9]+ Window\\{" + Pattern.quote(stateToken)
                    + "\\s+u0\\s+[^}\\r\\n]+\\}:").matcher(record).find()) selected = record;
        }
        parsed.put("inputMethodRecordCount", imeCount).put("selectedWindowStateToken", stateToken)
                .put("attributesParsedCount", attributesParsed).put("attributesUnparsedCount", attributesUnparsed)
                .put("attrsDiagnostics", new JSONObject().put("schemaVersion", 2)
                        .put("scanLimitChars", MAX_ATTRS_SCAN_CHARS).put("inputLimitChars", MAX_BYTES).put("maxRetained", 8)
                        .put("recordCount", recordCount).put("unparsedCount", attributesUnparsed)
                        .put("omittedCount", Math.max(0, attributesUnparsed - failures.length())).put("entries", failures))
                .put("reason", "WMS_RECORD_MISSING_OR_AMBIGUOUS");
        if (attributesUnparsed != 0) return parsed.put("reason", "WMS_ATTRIBUTES_INCOMPLETE");
        if (imeCount != 1 || selected == null) return parsed;
        boolean owner = one(selected, "mOwnerUid", Integer.toString(uid));
        boolean display = one(selected, "mDisplayId", "0");
        boolean pkg = Pattern.compile("(?m)^[\\t ]*mOwnerUid=" + uid
                + "[\\t ]+[^\\r\\n]*\\bpackage=" + Pattern.quote(packageName) + "(?:[\\t ]|$)").matcher(selected).find();
        parsed.put("ownerUid", uid).put("displayId", 0).put("ownerMatches", owner)
                .put("displayMatches", display).put("packageMatches", pkg).put("reason", "WMS_IDENTITY_MISMATCH");
        if (!owner || !display || !pkg) return parsed;
        Matcher client = Pattern.compile("(?m)^[\\t ]*mDisplayId=0[^\\r\\n]* mClient=(" + BINDER + ")[\\t ]*\\r?$").matcher(selected);
        if (!client.find()) return parsed.put("reason", "WMS_CLIENT_UNRECOGNIZED");
        String token = client.group(1);
        if (client.find() || count(selected, "\\bmClient=") != 1) return parsed.put("reason", "WMS_CLIENT_AMBIGUOUS");
        JSONObject fields = new JSONObject();
        for (String field : new String[] {"isOnScreen", "isVisible", "isReadyForDisplay()", "mLastHidden",
                "mEnterAnimationPending", "mAnimatingExit", "mHasSurface", "Surface: shown"}) {
            Matcher values = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(field) + "=([^\\s]+)").matcher(selected);
            int n = 0; String value = "MISSING";
            while (values.find()) {
                String current = "true".equals(values.group(1)) ? "TRUE" : "false".equals(values.group(1)) ? "FALSE" : "OTHER";
                value = n == 0 ? current : value.equals(current) ? value : "CONFLICT"; n++;
            }
            fields.put(field, new JSONObject().put("count", n).put("value", value));
        }
        return parsed.put("status", "SELECTED").put("reason", "UNIQUE_CONFIGURED_IME_TEXT_CANDIDATE")
                .put("mClient", token).put("fields", fields);
    }

    private enum AttrsReason {
        PARSED, ATTRS_ANCHOR_MISSING, ATTRS_ANCHOR_DUPLICATE, DEPTH_LIMIT,
        TYPE_VALUE_UNRECOGNIZED, TYPE_VALUE_FOLLOWED_BY_OPEN_BRACE,
        TOP_LEVEL_TYPE_MISSING, TOP_LEVEL_TYPE_DUPLICATE, ATTRS_SIZE_LIMIT, ATTRS_UNCLOSED
    }

    /** The type is internal only; failure receipts contain fixed structure, never field contents. */
    private static final class WindowTypeResult {
        final String type, typeLexicalClass;
        final AttrsReason reason;
        final int anchorCount, firstAnchor, secondAnchor, scanChars, maxDepth, typeFields;
        final Integer typeValueLength;
        final Boolean typeValueComplete;
        WindowTypeResult(String type, AttrsReason reason, int anchorCount, int firstAnchor, int secondAnchor,
                         int scanChars, int maxDepth, int typeFields, String invalidValue, Boolean completeValue) {
            this.type = type; this.reason = reason; this.anchorCount = anchorCount;
            this.firstAnchor = firstAnchor; this.secondAnchor = secondAnchor;
            this.scanChars = scanChars; this.maxDepth = maxDepth; this.typeFields = typeFields;
            typeValueLength = invalidValue == null ? null : invalidValue.length();
            typeLexicalClass = invalidValue == null ? null : lexicalClass(invalidValue);
            typeValueComplete = completeValue;
        }

        JSONObject diagnostic(String record, int ordinal, String recordEnd, String selectedToken) throws Exception {
            Matcher header = Pattern.compile("^[\\t ]*Window #[0-9]+ Window\\{([0-9a-fA-F]{1,32})\\s+u([0-9]{1,8})\\s+[^}\\r\\n]+\\}:").matcher(record);
            String relation = "UNRECOGNIZED";
            if (header.find()) relation = selectedToken.equals(header.group(1)) && "0".equals(header.group(2))
                    ? "MATCHES_SELECTED_REFERENCE" : "OTHER_RECOGNIZED_WINDOW";
            String[] headings = {"Hiding System Alert Windows:", "Windows force removing:",
                    "Windows waiting to destroy their surface:", "Windows waiting to resize:"};
            String[] kinds = {"HIDING_SYSTEM_ALERT_WINDOWS", "FORCE_REMOVING_WINDOWS", "DESTROY_SURFACE_WINDOWS", "RESIZING_WINDOWS"};
            int firstTail = -1;
            String firstKind = null;
            boolean tailBetweenAnchors = false;
            for (int i = 0; i < headings.length; i++) {
                Matcher tail = Pattern.compile("(?m)^[\\t ]*" + Pattern.quote(headings[i]) + "[\\t ]*\\r?$").matcher(record);
                while (tail.find()) {
                    int offset = tail.start();
                    if (firstTail < 0 || offset < firstTail) { firstTail = offset; firstKind = kinds[i]; }
                    if (firstAnchor >= 0 && secondAnchor >= 0 && offset > firstAnchor && offset < secondAnchor) tailBetweenAnchors = true;
                }
            }
            Object tail = firstTail < 0 ? JSONObject.NULL : new JSONObject().put("kind", firstKind).put("offset", firstTail);
            return new JSONObject().put("recordOrdinal", ordinal).put("recordEnd", recordEnd).put("headerRelation", relation)
                    .put("attrsAnchorCount", anchorCount).put("firstAttrsOffset", firstAnchor < 0 ? JSONObject.NULL : firstAnchor)
                    .put("secondAttrsOffset", secondAnchor < 0 ? JSONObject.NULL : secondAnchor)
                    .put("recordChars", record.length()).put("scanChars", scanChars).put("maxDepth", maxDepth)
                    .put("topLevelTypeCount", typeFields).put("reason", reason.name())
                    .put("typeLexicalClass", value(typeLexicalClass)).put("typeValueLength", value(typeValueLength))
                    .put("typeValueComplete", value(typeValueComplete)).put("firstKnownTailSection", tail)
                    .put("knownTailSectionBetweenAnchors", secondAnchor < 0 ? JSONObject.NULL : tailBetweenAnchors);
        }
    }

    private static String lexicalClass(String value) {
        if (value.isEmpty()) return "EMPTY";
        if (value.matches("[0-9]+")) return "DECIMAL";
        if (value.matches("[+-][0-9]+")) return "SIGNED_DECIMAL";
        if (value.matches("[+-]?0[xX][0-9a-fA-F]+")) return "HEX";
        if (value.matches("[A-Z_][A-Z_0-9]*")) return "UPPERCASE_SYMBOL";
        return "OTHER";
    }

    /** Complete captured attributes; type grammar, depth and Window # partitions stay strict. */
    private static WindowTypeResult windowType(String record) {
        Matcher attributes = Pattern.compile("(?m)^[\\t ]*mAttrs=\\{").matcher(record);
        int anchors = 0, start = -1, second = -1;
        while (attributes.find()) {
            if (anchors == 0) start = attributes.end() - 1;
            else if (anchors == 1) second = attributes.end() - 1;
            anchors++;
        }
        if (anchors == 0 || anchors > 1) return new WindowTypeResult(null,
                anchors == 0 ? AttrsReason.ATTRS_ANCHOR_MISSING : AttrsReason.ATTRS_ANCHOR_DUPLICATE,
                anchors, start, second, 0, 0, 0, null, null);
        int depth = 0, typeFields = 0, observedTypeFields = 0, scanChars = 0, maxDepth = 0;
        String type = null;
        for (int index = start; index < record.length() && index - start < MAX_ATTRS_SCAN_CHARS; index++) {
            scanChars = index - start + 1;
            char current = record.charAt(index);
            if (current == '{') {
                depth++; maxDepth = Math.max(maxDepth, depth);
                if (depth > 8) return new WindowTypeResult(null, AttrsReason.DEPTH_LIMIT,
                        anchors, start, second, scanChars, maxDepth, observedTypeFields, null, null);
            } else if (current == '}') {
                if (--depth == 0) return new WindowTypeResult(typeFields == 1 ? type : null,
                        typeFields == 1 ? AttrsReason.PARSED : typeFields == 0 ? AttrsReason.TOP_LEVEL_TYPE_MISSING : AttrsReason.TOP_LEVEL_TYPE_DUPLICATE,
                        anchors, start, second, scanChars, maxDepth, observedTypeFields, null, null);
            } else if (depth == 1 && (index == start + 1 || Character.isWhitespace(record.charAt(index - 1)))
                    && record.startsWith("ty=", index)) {
                observedTypeFields++;
                int end = index + 3;
                while (end < record.length() && end - index <= 67
                        && !Character.isWhitespace(record.charAt(end)) && record.charAt(end) != '}' && record.charAt(end) != '{') end++;
                String value = record.substring(index + 3, end);
                boolean completeValue = end == record.length() || Character.isWhitespace(record.charAt(end))
                        || record.charAt(end) == '}' || record.charAt(end) == '{';
                if (!value.matches("[A-Z_0-9]{1,64}") || end < record.length() && record.charAt(end) == '{')
                    return new WindowTypeResult(null, !value.matches("[A-Z_0-9]{1,64}")
                            ? AttrsReason.TYPE_VALUE_UNRECOGNIZED : AttrsReason.TYPE_VALUE_FOLLOWED_BY_OPEN_BRACE,
                            anchors, start, second, scanChars, maxDepth, observedTypeFields, value, completeValue);
                type = value; typeFields++;
                index = end - 1;
            }
        }
        return new WindowTypeResult(null, record.length() - start >= MAX_ATTRS_SCAN_CHARS ? AttrsReason.ATTRS_SIZE_LIMIT : AttrsReason.ATTRS_UNCLOSED,
                anchors, start, second, scanChars, maxDepth, observedTypeFields, null, null);
    }

    static JSONObject parsePopulator(String dump, String token) throws Exception {
        JSONObject result = unavailable("POPULATOR_HEADER_UNRECOGNIZED");
        if (count(dump, "(?m)^[\\t ]*AccessibilityWindowsPopulator[\\t ]*\\r?$") != 1) return result;
        Matcher notification = Pattern.compile("(?m)^[\\t ]*mWindowsNotificationEnabled: (true|false)[\\t ]*\\r?$").matcher(dump);
        if (!notification.find()) return result.put("reason", "POPULATOR_NOTIFICATION_UNRECOGNIZED");
        boolean enabled = Boolean.parseBoolean(notification.group(1));
        if (notification.find()) return result.put("reason", "POPULATOR_NOTIFICATION_AMBIGUOUS");
        result.put("notificationsEnabled", enabled);
        Matcher visible = Pattern.compile("(?m)^[\\t ]*([0-9]{1,3}) visible windows: (\\[[^\\r\\n]*\\])[\\t ]*\\r?$").matcher(dump);
        JSONObject visibleResult;
        int noVisible = count(dump, "(?m)^[\\t ]*No visible windows[\\t ]*\\r?$");
        if (visible.find()) {
            visibleResult = handles(visible.group(2), token, Integer.parseInt(visible.group(1)));
            if (visible.find() || noVisible != 0) return result.put("reason", "POPULATOR_VISIBLE_AMBIGUOUS");
        } else if (noVisible == 1) visibleResult = handles("[]", token, 0);
        else return result.put("reason", "POPULATOR_VISIBLE_UNRECOGNIZED");
        result.put("visible", visibleResult);
        Matcher group = Pattern.compile("(?m)^[\\t ]*([0-9]{1,2}) window handles on display\\(s\\):[\\t ]*\\r?$").matcher(dump);
        int noDisplays = count(dump, "(?m)^[\\t ]*No window handles on displays[\\t ]*\\r?$");
        JSONObject displays = new JSONObject();
        JSONArray matches = new JSONArray();
        int declared = 0, parsed = 0, matchCount = 0;
        boolean complete = true;
        if (group.find()) {
            declared = Integer.parseInt(group.group(1));
            int end = group.end();
            if (group.find() || noDisplays != 0 || declared > 32) return result.put("reason", "POPULATOR_DISPLAYS_AMBIGUOUS");
            String[] lines = dump.substring(end).split("\\r?\\n");
            Set<Integer> ids = new HashSet<>();
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                if (parsed == declared) {
                    if (line.trim().startsWith("Display #")) complete = false;
                    break;
                }
                Matcher display = Pattern.compile("^[\\t ]*Display #([0-9]{1,8}): (\\[.*\\])[\\t ]*$").matcher(line);
                if (!display.matches()) { complete = false; break; }
                int displayId = Integer.parseInt(display.group(1));
                if (!ids.add(displayId)) complete = false;
                JSONObject items = handles(display.group(2), token, -1);
                if (!items.getBoolean("complete")) complete = false;
                if (!items.isNull("matchCount")) matchCount += items.getInt("matchCount");
                JSONArray found = items.getJSONArray("matches");
                for (int j = 0; j < found.length(); j++) {
                    JSONObject item = found.getJSONObject(j);
                    if (item.getInt("displayId") != displayId) complete = false;
                    if (matches.length() < 4) matches.put(item);
                }
                parsed++;
            }
            if (parsed != declared) complete = false;
        } else if (noDisplays != 1) return result.put("reason", "POPULATOR_DISPLAYS_UNRECOGNIZED");
        displays.put("declaredDisplayCount", declared).put("parsedDisplayCount", parsed).put("complete", complete)
                .put("matchCount", complete && token != null ? matchCount : JSONObject.NULL).put("matches", matches);
        result.put("displays", displays);
        return result.put("status", complete && visibleResult.getBoolean("complete") ? "RECOGNIZED" : "UNAVAILABLE")
                .put("reason", complete && visibleResult.getBoolean("complete") ? "FILTERED_LISTS_PARSED" : "POPULATOR_LIST_INCOMPLETE");
    }

    private static JSONObject handles(String text, String token, int declared) throws Exception {
        // Require the complete fixed suffix and consume the entire array. Never count a partial
        // suffix or an arbitrary trailing entry as an absent candidate. Names are not retained.
        Pattern record = Pattern.compile("[^\\r\\n]*?, frame=\\[([^\\r\\n]*?)\\], touchableRegion=([^\\r\\n]*?), scaleFactor=([^,\\r\\n]+), transform=([^\\r\\n]*?), windowToken=(" + BINDER
                + "|null), displayId=(-?[0-9]{1,8}), isClone=(true|false), contentSize=[^\\r\\n]*?, alpha=([^,\\r\\n]+), canOccludePresentation=(true|false)");
        int parsed = 0, matchCount = 0;
        boolean complete = true;
        JSONArray matches = new JSONArray();
        int cursor = 1, limit = text.length() - 1;
        if (!text.startsWith("[") || !text.endsWith("]")) complete = false;
        while (complete && cursor < limit) {
            Matcher handles = record.matcher(text).region(cursor, limit);
            if (!handles.lookingAt()) { complete = false; break; }
            parsed++;
            JSONArray frame = rect(handles.group(1));
            String region = numericRegion(handles.group(2));
            String transform = numericTransform(handles.group(4));
            Double scale = number(handles.group(3)), alpha = number(handles.group(8));
            if (frame == null || region == null || transform == null || scale == null || alpha == null) complete = false;
            if (token != null && token.equals(handles.group(5))) {
                matchCount++;
                if (matches.length() < 4) matches.put(new JSONObject().put("token", token)
                        .put("displayId", Integer.parseInt(handles.group(6))).put("frame", value(frame))
                        .put("touchableRegion", value(region)).put("isClone", Boolean.parseBoolean(handles.group(7)))
                        .put("transform", value(transform)).put("scaleFactor", value(scale)).put("alpha", value(alpha)));
            }
            cursor = handles.end();
            if (cursor != limit) {
                if (text.startsWith(", ", cursor)) cursor += 2;
                else { complete = false; break; }
                if (cursor == limit) complete = false;
            }
        }
        int fields = count(text, "windowToken=");
        if (parsed != fields || parsed != count(text, ", frame=") || parsed > 256 || declared >= 0 && parsed != declared
                || parsed == 0 && !"[]".equals(text)) complete = false;
        return new JSONObject().put("declaredCount", declared < 0 ? JSONObject.NULL : declared)
                .put("parsedCount", parsed).put("tokenFieldCount", fields).put("complete", complete)
                .put("matchCount", complete && token != null ? matchCount : JSONObject.NULL).put("matches", matches);
    }

    static JSONObject parseAccessibility(String dump, String token) throws Exception {
        JSONObject result = unavailable("ACCESSIBILITY_PAIRED_FORMAT_UNRECOGNIZED");
        if (!dump.startsWith("ACCESSIBILITY MANAGER (dumpsys accessibility)")
                || count(dump, "(?m)^ACCESSIBILITY MANAGER \\(dumpsys accessibility\\)[\\t ]*\\r?$") != 1) return result;
        String[] lines = dump.split("\\r?\\n");
        int a11y = 0, infos = 0, pairs = 0, matchesCount = 0;
        boolean complete = true;
        Integer pendingId = null, displayId = null;
        JSONArray matches = new JSONArray();
        for (String raw : lines) {
            String line = raw.trim();
            Matcher display = Pattern.compile("^Display\\[([0-9]{1,8})\\] :[\\t ]*$").matcher(line);
            if (display.matches()) {
                if (pendingId != null) complete = false;
                pendingId = null; displayId = Integer.valueOf(display.group(1));
            } else if (line.startsWith("A11yWindow[")) {
                a11y++;
                if (pendingId != null) complete = false;
                Matcher id = Pattern.compile("^A11yWindow\\[AccessibilityWindowInfo\\[.*?\\bid=(-?[0-9]{1,10}),").matcher(line);
                pendingId = id.find() ? Integer.valueOf(id.group(1)) : null;
                if (pendingId == null || displayId == null || !line.endsWith("]]") || count(line, "\\bid=") != 1) complete = false;
            } else if (line.startsWith("WindowInfo[")) {
                infos++;
                Matcher info = Pattern.compile("^WindowInfo\\[WindowInfo\\[title=.*?, displayId=(-?[0-9]{1,8}), taskId=-?[0-9]{1,10}, type=(-?[0-9]{1,8}), layer=(-?[0-9]{1,10}), token=(" + BINDER
                        + "|null), region=([^\\r\\n]*?), bounds=(Rect\\([^)]*\\)), parent=(" + BINDER + "|null), focused=(?:true|false),").matcher(line);
                if (!info.find() || pendingId == null || displayId == null || !line.endsWith("]]") || count(line, ", token=") != 1) { complete = false; pendingId = null; continue; }
                JSONArray bounds = rect(info.group(6));
                String region = numericRegion(info.group(5));
                if (bounds == null || region == null || Integer.parseInt(info.group(1)) != displayId) complete = false;
                pairs++;
                if (token != null && token.equals(info.group(4))) {
                    matchesCount++;
                    if (matches.length() < 4) matches.put(new JSONObject().put("token", token)
                            .put("displayId", Integer.parseInt(info.group(1))).put("type", Integer.parseInt(info.group(2)))
                            .put("layer", Integer.parseInt(info.group(3))).put("accessibilityWindowId", pendingId)
                            .put("region", value(region)).put("bounds", value(bounds))
                            .put("parent", "null".equals(info.group(7)) ? JSONObject.NULL : info.group(7)));
                }
                pendingId = null;
            } else if (pendingId != null && !line.isEmpty()) { complete = false; pendingId = null; }
        }
        if (pendingId != null || a11y == 0 || a11y != infos || pairs != a11y) complete = false;
        return result.put("status", complete ? "RECOGNIZED" : "UNAVAILABLE")
                .put("reason", complete ? "REPORTED_WINDOW_PAIRS_PARSED" : "ACCESSIBILITY_PAIRS_INCOMPLETE_OR_LEGACY")
                .put("a11yWindowCount", a11y).put("windowInfoCount", infos).put("pairCount", pairs)
                .put("matchCount", complete && token != null ? matchesCount : JSONObject.NULL).put("matches", matches);
    }

    private static JSONObject association(JSONArray commands, String token) throws Exception {
        JSONObject result = new JSONObject().put("status", "INCONCLUSIVE").put("systemServerLifetime", "NOT_VERIFIED")
                .put("atomicSnapshot", false).put("key", "WMS_MCLIENT_TO_WINDOW_TOKEN")
                .put("reason", "SOURCE_UNAVAILABLE")
                .put("tokenCandidate", value(token)).put("populatorVisibleMatchCount", JSONObject.NULL)
                .put("populatorDisplayMatchCount", JSONObject.NULL).put("finalMatchCount", JSONObject.NULL);
        JSONObject pop = commands.getJSONObject(1).getJSONObject("parsed"), end = commands.getJSONObject(2).getJSONObject("parsed");
        if (token == null || !"RECOGNIZED".equals(pop.optString("status")) || !"RECOGNIZED".equals(end.optString("status"))) return result;
        int visible = pop.getJSONObject("visible").getInt("matchCount"), displays = pop.getJSONObject("displays").getInt("matchCount");
        int finalCount = end.getInt("matchCount");
        boolean attributes = candidateAttributes(pop.getJSONObject("visible").getJSONArray("matches"), false)
                && candidateAttributes(pop.getJSONObject("displays").getJSONArray("matches"), false)
                && candidateAttributes(end.getJSONArray("matches"), true);
        boolean unique = visible <= 1 && displays <= 1 && finalCount <= 1;
        return result.put("populatorVisibleMatchCount", visible).put("populatorDisplayMatchCount", displays)
                .put("finalMatchCount", finalCount).put("status", unique && attributes ? "TEXT_CANDIDATE_ONLY" : "INCONCLUSIVE")
                .put("reason", !attributes ? "CANDIDATE_ATTRIBUTES_MISMATCH" : !unique ? "CANDIDATE_NOT_UNIQUE" : "ORDERED_TEXT_COUNTS_ONLY");
    }

    private static boolean candidateAttributes(JSONArray matches, boolean finalService) throws Exception {
        for (int i = 0; i < matches.length(); i++) {
            JSONObject item = matches.getJSONObject(i);
            if (item.getInt("displayId") != 0 || finalService && item.getInt("type") != 2011) return false;
        }
        return true;
    }

    private static boolean one(String text, String field, String expected) {
        Matcher match = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(field) + "=([^\\s]+)").matcher(text);
        return match.find() && expected.equals(match.group(1)) && !match.find();
    }
    private static int count(String text, String pattern) {
        Matcher match = Pattern.compile(pattern).matcher(text); int count = 0;
        while (match.find()) count++;
        return count;
    }
    private static JSONArray rect(String value) {
        Matcher match = Pattern.compile("^Rect\\((-?[0-9]{1,8}), (-?[0-9]{1,8}) - (-?[0-9]{1,8}), (-?[0-9]{1,8})\\)$").matcher(value);
        if (!match.matches()) return null;
        return new JSONArray().put(Integer.parseInt(match.group(1))).put(Integer.parseInt(match.group(2)))
                .put(Integer.parseInt(match.group(3))).put(Integer.parseInt(match.group(4)));
    }
    private static String numericRegion(String value) {
        return value.length() <= 512 && value.matches("SkRegion\\([0-9() ,;-]*\\)") ? value : null;
    }
    private static String numericTransform(String value) {
        return "null".equals(value) || value.length() <= 512 && value.matches("Matrix\\{[0-9eE+., \\[\\]()-]*\\}") ? value : null;
    }
    private static Double number(String value) {
        if (value.length() > 32 || !value.matches("-?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) return null;
        double number = Double.parseDouble(value);
        return Double.isFinite(number) ? number : null;
    }
    private static Object value(Object value) { return value == null ? JSONObject.NULL : value; }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.US, "%02x", value & 255));
        return result.toString();
    }

    static void verifyParsers() throws Exception {
        String token = "android.os.BinderProxy@abc123";
        String wms = "WINDOW MANAGER WINDOWS (dumpsys window windows)\n  Window #0 Window{123 u0 private-title}:\n"
                + "    mDisplayId=0 mSession=Session{private} mClient=" + token + "\n"
                + "    mOwnerUid=10012 showForAllUsers=false package=com.android.inputmethod.latin appop=NONE\n"
                + "    mAttrs={(0,0)(fillxwrap) ty=INPUT_METHOD}\n"
                + "    isOnScreen=true isVisible=false isReadyForDisplay()=false\n"
                + "    mLastHidden=false mEnterAnimationPending=false mHasSurface=true Surface: shown=true\n"
                + "  mInputMethodWindow=Window{123 u0 private-title}\n";
        JSONObject selected = parseWms(wms, 10012, "com.android.inputmethod.latin");
        SmokeHarness.require("SELECTED".equals(selected.getString("status")) && token.equals(selected.getString("mClient"))
                && !selected.toString().contains("private-title"), "Post-failure WMS candidate extraction failed");
        SmokeHarness.require("UNAVAILABLE".equals(parseWms(wms, 10013, "com.android.inputmethod.latin").getString("status")), "Foreign IME owner was selected");
        SmokeHarness.require("UNAVAILABLE".equals(parseWms(wms + "  mInputMethodWindow=Window{456 u0 title}\n", 10012,
                "com.android.inputmethod.latin").getString("status")), "Duplicate IME reference was selected");
        SmokeHarness.require("UNAVAILABLE".equals(parseWms(wms.replace("private-title", "ty=INPUT_METHOD").replace("ty=INPUT_METHOD}", "ty=APPLICATION}"),
                10012, "com.android.inputmethod.latin").getString("status")), "A type-shaped title became IME attributes");
        String nested = wms.replace("ty=INPUT_METHOD}", "sim={state=HIDDEN adjust=RESIZE} ty=INPUT_METHOD\n"
                + "      fl=NOT_FOCUSABLE LAYOUT_IN_SCREEN\n"
                + "      paramsForRotation:\n        ROTATION_0={(0,0)(fillxwrap) sim={adjust=RESIZE} ty=APPLICATION}\n    }");
        JSONObject nestedSelected = parseWms(nested, 10012, "com.android.inputmethod.latin");
        SmokeHarness.require("SELECTED".equals(nestedSelected.getString("status"))
                && nestedSelected.getInt("attributesParsedCount") == 1 && nestedSelected.getInt("attributesUnparsedCount") == 0
                && token.equals(nestedSelected.getString("mClient")), "Nested soft-input or rotation attributes hid the top-level IME type");
        SmokeHarness.require("SELECTED".equals(parseWms(nested.replace("ty=INPUT_METHOD", "ty=2011"), 10012,
                "com.android.inputmethod.latin").getString("status")), "Numeric INPUT_METHOD type alias was lost");
        String longAttributes = wms.replace("ty=INPUT_METHOD}", "ty=INPUT_METHOD\n"
                + new String(new char[8192]).replace('\0', ' ') + "}");
        JSONObject longSelected = parseWms(longAttributes, 10012, "com.android.inputmethod.latin");
        SmokeHarness.require("SELECTED".equals(longSelected.getString("status"))
                && longSelected.getJSONObject("attrsDiagnostics").getInt("schemaVersion") == 2
                && longSelected.getJSONObject("attrsDiagnostics").getInt("scanLimitChars") == MAX_BYTES,
                "Complete captured attributes were still cut off at the old parser-only bound");
        SmokeHarness.require("WMS_ATTRIBUTES_INCOMPLETE".equals(parseWms(longAttributes.replace("        }", "        ty=APPLICATION}"),
                10012, "com.android.inputmethod.latin").getString("reason")), "A type beyond the old bound was ignored");
        for (String attributes : new String[] {
                "{(0,0)(fillxwrap) sim={ty=INPUT_METHOD}}",
                "{(0,0)(fillxwrap) ty=INPUT_METHOD ty=INPUT_METHOD}",
                "{(0,0)(fillxwrap) ty=INPUT_METHOD ty=APPLICATION}",
                "{(0,0)(fillxwrap) sim={state=HIDDEN} ty=INPUT_METHOD",
                "{(0,0)(fillxwrap) ty=INPUT_METHOD{unexpected}}",
                "{(0,0)(fillxwrap) sim={{{{{{{{state=HIDDEN}}}}}}}} ty=INPUT_METHOD}"}) {
            JSONObject refused = parseWms(wms.replace("{(0,0)(fillxwrap) ty=INPUT_METHOD}", attributes), 10012,
                    "com.android.inputmethod.latin");
            SmokeHarness.require("UNAVAILABLE".equals(refused.getString("status"))
                    && "WMS_ATTRIBUTES_INCOMPLETE".equals(refused.getString("reason"))
                    && refused.getInt("attributesUnparsedCount") == 1,
                    "Incomplete, nested-only, duplicate or excessive attributes became an IME record");
        }
        SmokeHarness.require("WMS_ATTRIBUTES_INCOMPLETE".equals(parseWms(wms.replace("    mAttrs=", "    mAttrs={ty=INPUT_METHOD}\n    mAttrs="),
                10012, "com.android.inputmethod.latin").getString("reason")), "Duplicate mAttrs block was accepted");
        SmokeHarness.require("WMS_ATTRIBUTES_INCOMPLETE".equals(parseWms(wms + "  Window #1 Window{456 u0 private-title}:\n    mAttrs={missing-type}\n",
                10012, "com.android.inputmethod.latin").getString("reason")), "An unreadable second window still allowed unique IME selection");
        String handle = "private-name, frame=[Rect(0, 100 - 320, 640)], touchableRegion=SkRegion((0,100,320,640)), scaleFactor=1.0, transform=null, windowToken="
                + token + ", displayId=0, isClone=false, contentSize=Size(320, 540), alpha=1.0, canOccludePresentation=false";
        String pop = "AccessibilityWindowsPopulator\n  mWindowsNotificationEnabled: true\n  1 visible windows: [" + handle
                + "]\n  1 window handles on display(s):\nDisplay #0: [" + handle + "]\n";
        JSONObject parsedPop = parsePopulator(pop, token);
        SmokeHarness.require("RECOGNIZED".equals(parsedPop.getString("status"))
                && parsedPop.getJSONObject("visible").getInt("matchCount") == 1
                && parsedPop.getJSONObject("displays").getInt("matchCount") == 1
                && !parsedPop.toString().contains("private-name"), "Populator duplicate presentations were conflated");
        SmokeHarness.require("UNAVAILABLE".equals(parsePopulator(pop.replace("1 visible", "2 visible"), token).getString("status")), "Incomplete populator list was accepted");
        SmokeHarness.require("UNAVAILABLE".equals(parsePopulator(pop.replace("private-name", "private windowToken=" + token), token).getString("status")),
                "A token-shaped name bypassed handle field counts");
        SmokeHarness.require("UNAVAILABLE".equals(parsePopulator(pop.replace("canOccludePresentation=false]", "canOccludePresentation=false, malformed-entry]"), token).getString("status")),
                "A malformed trailing handle was treated as a complete list");
        SmokeHarness.require("UNAVAILABLE".equals(parsePopulator(pop + "Display #0: []\n", token).getString("status")),
                "An extra display group escaped the declared count");
        JSONObject empty = parsePopulator("AccessibilityWindowsPopulator\n mWindowsNotificationEnabled: false\n No visible windows\n No window handles on displays\n", token);
        SmokeHarness.require("RECOGNIZED".equals(empty.getString("status")) && empty.getJSONObject("visible").getInt("matchCount") == 0,
                "Explicit empty populator format was lost");
        String end = "ACCESSIBILITY MANAGER (dumpsys accessibility)\nDisplay[0] :\nA11yWindow[AccessibilityWindowInfo[title=private-title, displayId=0, id=12, taskId=-1, type=TYPE_INPUT_METHOD, layer=9]]\n"
                + "WindowInfo[WindowInfo[title=private-title, displayId=0, taskId=-1, type=2011, layer=9, token=" + token
                + ", region=SkRegion((0,100,320,640)), bounds=Rect(0, 100 - 320, 640), parent=null, focused=false, children=null]]\n";
        JSONObject parsedEnd = parseAccessibility(end, token);
        SmokeHarness.require("RECOGNIZED".equals(parsedEnd.getString("status")) && parsedEnd.getInt("matchCount") == 1
                && parsedEnd.getJSONArray("matches").getJSONObject(0).getInt("accessibilityWindowId") == 12
                && !parsedEnd.toString().contains("private-title"), "Final service window pairing failed");
        SmokeHarness.require("UNAVAILABLE".equals(parseAccessibility(end.replace("A11yWindow[", "Window["), token).getString("status")),
                "Legacy unpaired accessibility dump became an absent token");
        SmokeHarness.require("UNAVAILABLE".equals(parseAccessibility(end.replace(", token=", ", token=" + token + ", token="), token).getString("status")),
                "Ambiguous final token was accepted");
        SmokeHarness.require("UNAVAILABLE".equals(parseAccessibility(end.replace("title=private-title", "title=private, token=" + token), token).getString("status")),
                "A token-shaped title bypassed final field counts");
        SmokeHarness.require("UNAVAILABLE".equals(parseAccessibility(end.replace("Display[0]", "Display[1]"), token).getString("status")),
                "Final window display context mismatch was accepted");
        SmokeHarness.require("UNAVAILABLE".equals(parseAccessibility(end.substring(end.indexOf("Display[")), token).getString("status")),
                "Headerless final pairs became a complete service list");
        SmokeHarness.require("UNAVAILABLE".equals(parseAccessibility(end.substring(0, end.length() - 3), token).getString("status")),
                "Truncated final pair became a complete service list");
        JSONArray commands = new JSONArray().put(new JSONObject().put("parsed", selected))
                .put(new JSONObject().put("parsed", parsedPop)).put(new JSONObject().put("parsed", parsedEnd));
        SmokeHarness.require("TEXT_CANDIDATE_ONLY".equals(association(commands, token).getString("status")), "Unique textual candidate was not retained");
        parsedEnd.getJSONArray("matches").getJSONObject(0).put("type", 1);
        SmokeHarness.require("INCONCLUSIVE".equals(association(commands, token).getString("status")), "Foreign final type became an IME association");
        SmokeHarness.require("UNAVAILABLE".equals(parsePopulator("Bad window command: a11y", token).getString("status")),
                "Unsupported populator command became an empty list");
    }
}
