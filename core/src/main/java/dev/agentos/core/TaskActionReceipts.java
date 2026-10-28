package dev.agentos.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded, immutable local receipts. Never retains arguments, page data or model text. */
public final class TaskActionReceipts {
    public static final int MAX_ENTRIES = 200;
    public enum Action { LAUNCH_APP, CLICK, TYPE_TEXT, SCROLL, BACK, HOME }
    public enum Stage { WAITING_RESULT, RETURNED, NO_FINAL_RESULT }
    public enum Fact { YES, NO, UNKNOWN }
    /** A device accepting an action does not verify the user's goal. */
    public enum Verification { UNVERIFIED, UNKNOWN }

    public static final class Entry {
        /** Counts distinct recorded requests, not successful actions or platform submissions. */
        public final int ordinal;
        public final long executionEpoch, elapsedMillis;
        public final Action action;
        public final Stage stage;
        public final Fact submitted, accepted;
        public final Verification verification;
        /** Fixed allowlisted code, never a device/model error message. */
        public final String code;

        private Entry(int ordinal, long epoch, long elapsedMillis, Action action, Stage stage,
                      Fact submitted, Fact accepted, Verification verification, String code) {
            this.ordinal = ordinal; this.executionEpoch = epoch; this.elapsedMillis = elapsedMillis;
            this.action = action; this.stage = stage; this.submitted = submitted;
            this.accepted = accepted; this.verification = verification; this.code = code;
        }

        private Entry unresolved() {
            return new Entry(ordinal, executionEpoch, elapsedMillis, action, Stage.NO_FINAL_RESULT,
                    Fact.UNKNOWN, Fact.UNKNOWN, Verification.UNKNOWN, code);
        }

        private Entry returned(JsonObject metadata, boolean interrupted, long elapsed) {
            String safeCode = code(metadata.get("code"));
            if (interrupted) return new Entry(ordinal, executionEpoch, elapsed, action,
                    Stage.NO_FINAL_RESULT, Fact.UNKNOWN, Fact.UNKNOWN, Verification.UNKNOWN, safeCode);
            Fact submitted = fact(metadata.get("dispatchSubmitted"));
            Fact accepted = fact(metadata.get("dispatchAccepted"));
            Verification verification = verification(metadata.get("verification"));
            // Missing/ill-typed facts and contradictory acceptance never become
            // a definitive rejection or success. In particular, ok is not evidence.
            if (submitted == Fact.UNKNOWN || accepted == Fact.UNKNOWN
                    || submitted == Fact.NO && accepted == Fact.YES || "OUTCOME_UNKNOWN".equals(safeCode)) {
                submitted = Fact.UNKNOWN; accepted = Fact.UNKNOWN; verification = Verification.UNKNOWN;
            }
            return new Entry(ordinal, executionEpoch, elapsed, action, Stage.RETURNED,
                    submitted, accepted, verification, safeCode);
        }
    }

    /** Random local owner binding only. It must not be displayed or exported. */
    public final String taskId;
    /** Monotonic within one dispatcher; consumers reject an older publication. */
    public final long version;
    public final List<Entry> entries;
    public final int omittedCount;
    public final boolean frozen;

    private TaskActionReceipts(String taskId, long version, List<Entry> entries, int omittedCount, boolean frozen) {
        this.taskId = taskId; this.version = version;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.omittedCount = omittedCount; this.frozen = frozen;
    }

    public static TaskActionReceipts empty(String taskId) {
        Objects.requireNonNull(taskId, "taskId");
        if (!UUID.fromString(taskId).toString().equals(taskId)) throw new IllegalArgumentException("Invalid local task identity");
        return new TaskActionReceipts(taskId, 0, Collections.emptyList(), 0, false);
    }

    /** Freeze the last known evidence; an unfinished receipt does not prove non-submission. */
    public TaskActionReceipts freeze() {
        if (frozen) return this;
        List<Entry> fixed = new ArrayList<>(entries.size());
        for (Entry entry : entries) fixed.add(entry.stage == Stage.WAITING_RESULT ? entry.unresolved() : entry);
        return new TaskActionReceipts(taskId, version + 1, fixed, omittedCount, true);
    }

    TaskActionReceipts started(String tool, long epoch, long elapsed) {
        Action action = action(tool);
        if (action == null || frozen) return this;
        List<Entry> next = new ArrayList<>(entries);
        next.add(new Entry(omittedCount + entries.size() + 1, epoch, elapsed, action,
                Stage.WAITING_RESULT, Fact.UNKNOWN, Fact.UNKNOWN, Verification.UNKNOWN, "NOT_RECORDED"));
        int omitted = omittedCount;
        if (next.size() > MAX_ENTRIES) { next.remove(0); omitted++; }
        return new TaskActionReceipts(taskId, version + 1, next, omitted, false);
    }

    TaskActionReceipts returned(int ordinal, JsonObject metadata, boolean interrupted, long elapsed) {
        if (ordinal <= 0 || frozen) return this;
        List<Entry> next = new ArrayList<>(entries);
        for (int i = 0; i < next.size(); i++) {
            Entry previous = next.get(i);
            if (previous.ordinal == ordinal && previous.stage == Stage.WAITING_RESULT) {
                next.set(i, previous.returned(metadata, interrupted, elapsed));
                return new TaskActionReceipts(taskId, version + 1, next, omittedCount, false);
            }
        }
        return this;
    }

    private static Action action(String tool) {
        if (tool == null) return null;
        switch (tool) {
            case "launch_app": return Action.LAUNCH_APP;
            case "click": return Action.CLICK;
            case "type_text": return Action.TYPE_TEXT;
            case "scroll": return Action.SCROLL;
            case "back": return Action.BACK;
            case "home": return Action.HOME;
            default: return null;
        }
    }
    private static Fact fact(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean() ? Fact.YES : Fact.NO : Fact.UNKNOWN;
    }
    private static Verification verification(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && "unverified".equalsIgnoreCase(value.getAsString()) ? Verification.UNVERIFIED : Verification.UNKNOWN;
    }
    private static String code(JsonElement value) {
        if (value == null || value.isJsonNull()) return "NOT_RECORDED";
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? TaskDiagnostics.safeTerminalCode(value.getAsString()) : "OTHER";
    }
}
