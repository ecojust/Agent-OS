package dev.agentos.raw;

import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Pair;
import android.view.InputWindowHandle;
import android.window.WindowInfosListener;

/** This class is loaded only after metadata admission succeeds. */
public final class RawListenerSession extends WindowInfosListener implements RawSession {
    private final RawCollector collector;
    public RawListenerSession(RawCollector value) { super(); collector = value; }
    @Override public void registerAndCaptureInitial() {
        Pair<InputWindowHandle[], DisplayInfo[]> value = super.register();
        collector.initial(new Reader(value == null ? null : value.first), SystemClock.elapsedRealtime());
    }
    @Override public void unregisterOnce() { super.unregister(); }
    @Override public void onWindowInfosChanged(InputWindowHandle[] handles, DisplayInfo[] displays) {
        collector.callback(new Reader(handles), SystemClock.elapsedRealtime());
    }
    private static final class Reader implements RawCollector.Reader {
        private final InputWindowHandle[] handles;
        Reader(InputWindowHandle[] value) { handles = value; }
        public int size() { return handles == null ? -1 : handles.length; }
        public boolean present(int i) { return handles[i] != null; }
        public int type(int i) { return handles[i].layoutParamsType; }
        public int display(int i) { return handles[i].displayId; }
        public int uid(int i) { return handles[i].ownerUid; }
        public String packageName(int i) { return handles[i].packageName; }
        public Object token(int i) { return handles[i].getWindowToken(); }
        public int inputConfig(int i) { return handles[i].inputConfig; }
        public int[] frame(int i) {
            Rect rect = handles[i].frame;
            return rect == null ? null : new int[] {rect.left, rect.top, rect.right, rect.bottom};
        }
        public boolean regionEmpty(int i) { return handles[i].touchableRegion.isEmpty(); }
    }
}
