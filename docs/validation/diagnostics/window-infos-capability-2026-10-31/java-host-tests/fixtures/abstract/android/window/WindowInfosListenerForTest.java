package android.window;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.IBinder;
import dev.agentos.compat.CapabilityHostState;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
public abstract class WindowInfosListenerForTest {
    static { CapabilityHostState.outerInit(); }
    public WindowInfosListenerForTest() { CapabilityHostState.constructors++;  }
    public void addWindowInfosListener(Consumer<?> listener) { forbidden(); }
    public void addWindowInfosListener(BiConsumer<?,?> listener) { forbidden(); }
    public void removeWindowInfosListener(Consumer<?> listener) { forbidden(); }
    public void removeWindowInfosListener(BiConsumer<?,?> listener) { forbidden(); }
    private static void forbidden() { CapabilityHostState.listenerCalls++; throw new AssertionError("listener must never be called"); }
    public static class WindowInfo {
        static { CapabilityHostState.windowInitialized++; }
        public final IBinder windowToken=null;
        public final String name="private-window-name";
        public final int displayId=7;
        public final Rect bounds=null;
        public final Region touchableRegion=null;
        public final Matrix transform=null;
        public final boolean isTrustedOverlay=false,isVisible=false,isTouchable=false,isFocusable=false,isDuplicateTouchToWallpaper=false,isWatchOutsideTouch=false;

    }
    public static class DisplayInfo {
        static { CapabilityHostState.displayInitialized++; }
        public final int displayId=7;
        public final Matrix transform=null;
    }
}
