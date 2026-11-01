package dev.agentos.raw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Bounded memory-only collector. No Android APIs, I/O, waits, or Binder text conversion. */
public final class RawCollector {
    public static final int MAX_WINDOWS = 256;
    public static final int MAX_CANDIDATE_ROWS = 16;
    public static final int MAX_BINDER_IDS = 16;
    public static final int MAX_CALLBACKS = 128;
    public static final int TYPE_INPUT_METHOD = 2011;
    public static final int NOT_VISIBLE = 2;
    public static final int CLONE = 65536;

    public interface Reader {
        int size();
        boolean present(int index);
        int type(int index);
        int display(int index);
        int uid(int index);
        String packageName(int index);
        Object token(int index);
        int inputConfig(int index);
        int[] frame(int index);
        boolean regionEmpty(int index);
    }

    public static final class Row {
        public final int rowIndex, binderId, inputConfig, ownerUid, layoutParamsType, displayId;
        public final int[] frame;
        public final boolean regionEmpty, notVisible, isClone, frameEmpty, passesFourPredicates;
        Row(int index, int id, int config, int[] rect, boolean emptyRegion, int uid, int display) {
            rowIndex = index; binderId = id; inputConfig = config; frame = rect.clone();
            regionEmpty = emptyRegion; ownerUid = uid; displayId = display;
            layoutParamsType = TYPE_INPUT_METHOD;
            notVisible = (config & NOT_VISIBLE) != 0; isClone = (config & CLONE) != 0;
            frameEmpty = rect[0] >= rect[2] || rect[1] >= rect[3];
            passesFourPredicates = !notVisible && !isClone && !regionEmpty && !frameEmpty;
        }
    }

    public static final class Snapshot {
        public final String kind;
        public final int sequence;
        public final long elapsedRealtimeMs;
        public int rawWindowCount = -1, typeCount = -1, displayTypeCount = -1;
        public int uidTypeDisplayCount = -1, nullPackageCount = -1, candidateRowCount = -1;
        public int nullTokenCount = -1, distinctBinderCount = -1;
        public final List<String> issues = new ArrayList<>();
        public final List<Row> rows = new ArrayList<>();
        Snapshot(String kind, int sequence, long elapsed) {
            this.kind = kind; this.sequence = sequence; elapsedRealtimeMs = elapsed;
        }
        public boolean complete() { return issues.isEmpty(); }
        void issue(String value) { if (!issues.contains(value)) issues.add(value); }
    }

    private final String imePackage;
    private final int imeUid, displayId;
    private final IdentityHashMap<Object, Integer> binderIds = new IdentityHashMap<>();
    private final List<Snapshot> samples = new ArrayList<>();
    private Snapshot initial;
    private int callbacksSeen, callbacksDropped, callbacksOutsideWindow;
    private long deadlineElapsedMs = Long.MAX_VALUE;
    private boolean frozen;

    public RawCollector(String packageName, int uid, int display) {
        imePackage = packageName; imeUid = uid; displayId = display;
    }

    public synchronized void initial(Reader reader, long elapsed) {
        if (frozen || initial != null) return;
        initial = capture(reader, "INITIAL", 0, elapsed);
    }

    public synchronized void callback(Reader reader, long elapsed) {
        if (frozen) return; // In-flight callbacks after freeze are ignored, not included in coverage.
        if (callbacksSeen < Integer.MAX_VALUE) callbacksSeen++;
        if (elapsed >= deadlineElapsedMs) {
            if (callbacksOutsideWindow < Integer.MAX_VALUE) callbacksOutsideWindow++;
            return;
        }
        if (samples.size() >= MAX_CALLBACKS) {
            if (callbacksDropped < Integer.MAX_VALUE) callbacksDropped++;
            return;
        }
        samples.add(capture(reader, "CALLBACK", callbacksSeen, elapsed));
    }

    private Snapshot capture(Reader reader, String kind, int sequence, long elapsed) {
        Snapshot out = new Snapshot(kind, sequence, elapsed);
        try {
            int length = reader.size(); out.rawWindowCount = length;
            if (length < 0) { out.issue("NULL_ARRAY"); return out; }
            if (length > MAX_WINDOWS) { out.issue("HANDLES_LIMIT"); return out; }
            out.typeCount = out.displayTypeCount = out.uidTypeDisplayCount = 0;
            out.nullPackageCount = out.candidateRowCount = 0;
            int[] indexes = new int[MAX_CANDIDATE_ROWS];
            for (int i = 0; i < length; i++) {
                if (!reader.present(i)) { out.issue("NULL_HANDLE"); continue; }
                if (reader.type(i) != TYPE_INPUT_METHOD) continue;
                out.typeCount++;
                if (reader.display(i) != displayId) continue;
                out.displayTypeCount++;
                if (reader.uid(i) != imeUid) continue;
                out.uidTypeDisplayCount++;
                String candidatePackage = reader.packageName(i);
                if (candidatePackage == null) {
                    out.nullPackageCount++; out.issue("NULL_PACKAGE"); continue;
                }
                if (!imePackage.equals(candidatePackage)) continue;
                if (out.candidateRowCount < indexes.length) indexes[out.candidateRowCount] = i;
                out.candidateRowCount++;
            }
            if (out.candidateRowCount > MAX_CANDIDATE_ROWS) {
                out.issue("CANDIDATE_LIMIT"); return out;
            }
            Set<Object> distinct = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            out.nullTokenCount = 0;
            for (int i = 0; i < out.candidateRowCount; i++) {
                int index = indexes[i];
                Object token = reader.token(index);
                int id = 0;
                if (token == null) out.nullTokenCount++;
                else {
                    distinct.add(token);
                    Integer known = binderIds.get(token);
                    if (known != null) id = known;
                    else if (binderIds.size() < MAX_BINDER_IDS) {
                        id = binderIds.size() + 1; binderIds.put(token, id);
                    } else { id = -1; out.issue("IDENTITY_LIMIT"); }
                }
                int[] frame = reader.frame(index);
                if (frame == null || frame.length != 4) throw new IllegalArgumentException();
                out.rows.add(new Row(index, id, reader.inputConfig(index), frame,
                        reader.regionEmpty(index), reader.uid(index), reader.display(index)));
            }
            out.distinctBinderCount = distinct.size();
        } catch (Throwable error) {
            out.issue("FIELD_READ_FAILED");
            out.nullTokenCount = out.distinctBinderCount = -1;
        }
        return out;
    }

    public synchronized void deadline(long elapsed) { deadlineElapsedMs = elapsed; }
    public synchronized void freeze() { frozen = true; }
    public synchronized boolean frozen() { return frozen; }
    public synchronized Snapshot initial() { return initial; }
    public synchronized List<Snapshot> samples() { return new ArrayList<>(samples); }
    public synchronized int callbacksSeen() { return callbacksSeen; }
    public synchronized int callbacksDropped() { return callbacksDropped; }
    public synchronized int callbacksOutsideWindow() { return callbacksOutsideWindow; }
    public synchronized int binderIdsAssigned() { return binderIds.size(); }
    public synchronized boolean complete() {
        if (!frozen || initial == null || !initial.complete() || samples.isEmpty()
                || callbacksDropped != 0) return false;
        for (Snapshot sample : samples) if (!sample.complete()) return false;
        return true;
    }
}
