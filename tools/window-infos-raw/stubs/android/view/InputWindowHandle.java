package android.view;

import android.graphics.Rect;
import android.graphics.Region;
import android.os.IBinder;

/** Compile-only ABI subset, fixed AOSP17 base 94b4c163; never packaged in the diagnostic dex. */
public final class InputWindowHandle {
    public int inputConfig, layoutParamsType, ownerUid, displayId;
    public String packageName;
    public final Rect frame = null;
    public final Region touchableRegion = null;
    public IBinder getWindowToken() { throw new UnsupportedOperationException("compile-only"); }
}
