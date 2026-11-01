package android.window;

import android.util.Pair;
import android.view.InputWindowHandle;

/** Compile-only ABI subset, fixed AOSP17 base 94b4c163; never packaged in the diagnostic dex. */
public abstract class WindowInfosListener {
    public WindowInfosListener() { throw new UnsupportedOperationException("compile-only"); }
    public abstract void onWindowInfosChanged(InputWindowHandle[] handles, DisplayInfo[] displays);
    public Pair<InputWindowHandle[], DisplayInfo[]> register() { throw new UnsupportedOperationException("compile-only"); }
    public void unregister() { throw new UnsupportedOperationException("compile-only"); }
    public static final class DisplayInfo { }
}
