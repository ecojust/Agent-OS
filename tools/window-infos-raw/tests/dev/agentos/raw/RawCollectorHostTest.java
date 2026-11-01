package dev.agentos.raw;

/** Synthetic tests of candidate identity and collection limits; these are not device evidence. */
public final class RawCollectorHostTest {
    private static int checks;
    private static void check(boolean value) {
        checks++; if (!value) throw new AssertionError("check " + checks);
    }
    private static final Object TOKEN = new Object() {
        @Override public String toString() { throw new AssertionError("Binder text must not be used"); }
        @Override public boolean equals(Object other) { throw new AssertionError("Use retained object identity"); }
        @Override public int hashCode() { throw new AssertionError("Do not export/use object hash"); }
    };
    private static final class Window {
        boolean present = true, regionEmpty;
        int type = 2011, display, uid = 10001, config;
        String pkg = "test.ime";
        Object token = TOKEN;
        int[] frame = {0, 600, 1000, 1000};
    }
    private static final class Reader implements RawCollector.Reader {
        final Window[] windows;
        int fieldReads, tokenReads;
        Reader(Window... value) { windows = value; }
        public int size() { return windows == null ? -1 : windows.length; }
        public boolean present(int i) { fieldReads++; return windows[i] != null && windows[i].present; }
        public int type(int i) { return windows[i].type; }
        public int display(int i) { return windows[i].display; }
        public int uid(int i) { return windows[i].uid; }
        public String packageName(int i) { return windows[i].pkg; }
        public Object token(int i) { tokenReads++; return windows[i].token; }
        public int inputConfig(int i) { return windows[i].config; }
        public int[] frame(int i) { return windows[i].frame; }
        public boolean regionEmpty(int i) { return windows[i].regionEmpty; }
    }
    private static RawCollector collector() { return new RawCollector("test.ime", 10001, 0); }
    private static RawCollector.Snapshot initial(Reader r) {
        RawCollector c = collector(); c.initial(r, 10); return c.initial();
    }
    public static void main(String[] args) {
        Window base = new Window();
        RawCollector.Snapshot one = initial(new Reader(base));
        check(one.complete() && one.candidateRowCount == 1 && one.distinctBinderCount == 1);
        check(one.rows.get(0).binderId == 1 && one.rows.get(0).passesFourPredicates);

        Window clone = new Window(); clone.config = 65536;
        Window invisible = new Window(); invisible.config = 2;
        Window noRegion = new Window(); noRegion.regionEmpty = true;
        Window noFrame = new Window(); noFrame.frame = new int[]{0, 0, 0, 4};
        RawCollector.Snapshot same = initial(new Reader(base, clone, invisible, noRegion, noFrame));
        check(same.complete() && same.candidateRowCount == 5 && same.distinctBinderCount == 1);
        check(same.rows.size() == 5 && same.rows.get(1).isClone && same.rows.get(2).notVisible);
        check(!same.rows.get(3).passesFourPredicates && !same.rows.get(4).passesFourPredicates);
        check(same.rows.get(4).frameEmpty && same.rows.get(3).regionEmpty);

        Window other = new Window(); other.token = new Object();
        RawCollector.Snapshot multiple = initial(new Reader(base, other));
        check(multiple.distinctBinderCount == 2 && multiple.rows.get(1).binderId == 2);
        Window nullToken = new Window(); nullToken.token = null;
        RawCollector.Snapshot nullIdentity = initial(new Reader(nullToken));
        check(nullIdentity.complete() && nullIdentity.nullTokenCount == 1 && nullIdentity.distinctBinderCount == 0);
        check(nullIdentity.rows.get(0).binderId == 0);

        Window noPackage = new Window(); noPackage.pkg = null;
        RawCollector.Snapshot ambiguous = initial(new Reader(base, noPackage));
        check(!ambiguous.complete() && ambiguous.nullPackageCount == 1 && ambiguous.candidateRowCount == 1);
        check(ambiguous.issues.contains("NULL_PACKAGE") && ambiguous.rows.size() == 1);

        Window badType = new Window(); badType.type = 1;
        Window badDisplay = new Window(); badDisplay.display = 1;
        Window badUid = new Window(); badUid.uid = 999;
        Window badPackage = new Window(); badPackage.pkg = "other.ime";
        RawCollector.Snapshot filter = initial(new Reader(base, badType, badDisplay, badUid, badPackage));
        check(filter.typeCount == 4 && filter.displayTypeCount == 3 && filter.uidTypeDisplayCount == 2);
        check(filter.candidateRowCount == 1 && filter.rows.get(0).rowIndex == 0);
        RawCollector.Snapshot nullHandle = initial(new Reader(base, null));
        check(!nullHandle.complete() && nullHandle.issues.contains("NULL_HANDLE"));
        check(nullHandle.candidateRowCount == 1 && nullHandle.rows.size() == 1);
        RawCollector.Snapshot nullArray = initial(new Reader((Window[]) null));
        check(!nullArray.complete() && nullArray.rawWindowCount == -1 && nullArray.candidateRowCount == -1);

        Reader tooMany = new Reader(new Window[257]);
        RawCollector.Snapshot limit = initial(tooMany);
        check(!limit.complete() && limit.issues.contains("HANDLES_LIMIT") && tooMany.fieldReads == 0);
        Window[] candidates = new Window[17]; for (int i = 0; i < 17; i++) candidates[i] = new Window();
        Reader candidateReader = new Reader(candidates);
        RawCollector.Snapshot candidateLimit = initial(candidateReader);
        check(!candidateLimit.complete() && candidateLimit.candidateRowCount == 17 && candidateLimit.rows.isEmpty());
        check(candidateLimit.nullTokenCount == -1 && candidateLimit.distinctBinderCount == -1 && candidateReader.tokenReads == 0);

        RawCollector identities = collector(); identities.initial(new Reader(), 1);
        for (int i = 0; i < 17; i++) {
            Window window = new Window(); window.token = new Object();
            identities.callback(new Reader(window), i + 2);
        }
        RawCollector.Snapshot finalIdentity = identities.samples().get(16);
        check(identities.binderIdsAssigned() == 16 && !finalIdentity.complete());
        check(finalIdentity.issues.contains("IDENTITY_LIMIT") && finalIdentity.rows.get(0).binderId == -1);
        identities.freeze(); check(!identities.complete());

        RawCollector c = collector(); c.initial(new Reader(), 1);
        for (int i = 0; i < 129; i++) c.callback(new Reader(base), i + 2);
        check(c.samples().size() == 128 && c.callbacksSeen() == 129 && c.callbacksDropped() == 1);
        c.freeze(); check(!c.complete());
        int count = c.callbacksSeen(); c.callback(tooMany, 1000);
        check(c.callbacksSeen() == count && tooMany.fieldReads == 0);

        RawCollector timed = collector(); timed.initial(new Reader(), 1); timed.deadline(1000);
        timed.callback(new Reader(base), 999); timed.callback(new Reader(other), 1000); timed.freeze();
        check(timed.callbacksSeen() == 2 && timed.samples().size() == 1 && timed.callbacksOutsideWindow() == 1);
        check(timed.complete() && timed.binderIdsAssigned() == 1);

        Window invalidFrame = new Window(); invalidFrame.frame = null;
        RawCollector.Snapshot failed = initial(new Reader(base, invalidFrame));
        check(!failed.complete() && failed.issues.contains("FIELD_READ_FAILED") && failed.rows.size() == 1);
        check(failed.nullTokenCount == -1 && failed.distinctBinderCount == -1);

        RawCollector empty = collector(); empty.initial(new Reader(), 1); empty.freeze();
        check(empty.initial().complete() && empty.initial().rawWindowCount == 0 && !empty.complete());
        RawCollector order = collector(); order.callback(new Reader(base), 9); order.initial(new Reader(), 10); order.freeze();
        check(order.complete() && order.samples().get(0).elapsedRealtimeMs < order.initial().elapsedRealtimeMs);

        Window[] exactly = new Window[16]; for (int i = 0; i < exactly.length; i++) exactly[i] = new Window();
        RawCollector.Snapshot sixteen = initial(new Reader(exactly));
        check(sixteen.complete() && sixteen.rows.size() == 16 && sixteen.distinctBinderCount == 1);
        Window highBits = new Window(); highBits.config = Integer.MIN_VALUE | 65536;
        highBits.frame = new int[]{-400, -300, -200, -100};
        RawCollector.Snapshot bits = initial(new Reader(highBits));
        check(bits.rows.get(0).inputConfig == (Integer.MIN_VALUE | 65536) && bits.rows.get(0).isClone);
        check(!bits.rows.get(0).frameEmpty && !bits.rows.get(0).notVisible);
        int old = bits.rows.get(0).frame[0]; highBits.frame[0] = 12;
        check(bits.rows.get(0).frame[0] == old);

        RawCollector stable = collector(); stable.initial(new Reader(base), 1); stable.callback(new Reader(base), 2);
        check(stable.initial().rows.get(0).binderId == stable.samples().get(0).rows.get(0).binderId);
        check(stable.binderIdsAssigned() == 1);
        System.out.println("RawCollector checks: " + checks);
    }
}
