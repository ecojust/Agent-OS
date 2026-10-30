# Busy-stop schema v2 frame association review

Reviewed at 2026-10-30 17:40:47 UTC. Read-only independent source review; no Android build or instrumentation execution was performed by this reviewer.

## Freeze decision

No remaining source-review blocker was identified after the Android probe and host validator added strict `drawStartNanoTime > vsyncNanoTime`, and the summary renamed its evidence field to `firstStoppedTraversalFrameMetricsEvidence`. The source can be frozen for the next fixed pilot. This is permission to test the method, not a passing pilot, formal acceptance, or a claim that every stopped traversal is observable.

The supported conclusion for a passing MODEL sample is narrowly defined: the first frozen natural stopped traversal's UI FrameInfo was passed to HWUI and a corresponding FrameMetrics report satisfied the causal, identity, uniqueness, and quality checks. It does not prove successful buffer swap, enqueue, GPU completion on every API, or physical screen presentation.

## Draw-start reconstruction and ordering

`INTENDED_VSYNC + UNKNOWN_DELAY + INPUT_HANDLING + ANIMATION + LAYOUT_MEASURE` telescopes to the original native `DRAW_START`. Java `FrameMetrics.getMetric` subtracts the raw endpoints without clamping. Signed checked addition must retain negative raw stages for diagnosis; selected UI reports require each stage nonnegative and no arithmetic overflow.

Official source references:

- [Android 12 FrameMetrics duration table](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/core/java/android/view/FrameMetrics.java#268) and [getMetric](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/core/java/android/view/FrameMetrics.java#336).
- [Android 15 FrameMetrics duration table](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/view/FrameMetrics.java#271) and [getMetric](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/view/FrameMetrics.java#339).

API31 and API35 both execute `dispatchOnDraw`, assign `mDrawingTime`, check the drawing conditions, then invoke `ThreadedRenderer.draw`. That method records `mViewFrameInfo.markDrawStart()` before building the display list, obtains the updated FrameInfo, and calls `syncAndDrawFrame`. `populateFrameInfo` copies this just-recorded draw-start value; it does not substitute a timestamp recorded before OnDraw.

- API31: [ViewRootImpl OnDraw](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/core/java/android/view/ViewRootImpl.java#4349), [ThreadedRenderer.draw](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/core/java/android/view/ThreadedRenderer.java#613), [getUpdatedFrameInfo](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/core/java/android/view/ViewRootImpl.java#480), [ViewFrameInfo.populateFrameInfo](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/core/java/android/view/ViewFrameInfo.java#41), [markDrawStart](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/core/java/android/view/ViewFrameInfo.java#59).
- API35: [ViewRootImpl OnDraw](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/view/ViewRootImpl.java#5572), [ThreadedRenderer.draw](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/view/ThreadedRenderer.java#795), [getUpdatedFrameInfo](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/view/ViewRootImpl.java#687).

Consequently, an actual UI draw has `OnDraw <= DRAW_START <= posted-front read`, and DRAW_START occurs strictly after that traversal's VSYNC. The probe retains the same Window, decor, task, draw sequence, and VSYNC-millisecond checks as well as unique matching, zero loss/overflow/ambiguity, valid completion ordering, and callback draining. It never advances the frozen stopped draw to obtain a more convenient report.

## RT-only reports and unavailable identities

The AOSP RT-only builder sets intermediate UI timestamps, including DRAW_START, to its synthetic VSYNC. Therefore merely checking four nonnegative durations is insufficient. The additional strict `DRAW_START > VSYNC` rejects that known synthetic path directly, without relying on an assumed minimum difference between two timestamps that happen to share a millisecond.

- [Android 11 UiFrameInfoBuilder.setVsync](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/libs/hwui/FrameInfo.h#78).
- [Android 15 UiFrameInfoBuilder.setVsync](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/libs/hwui/FrameInfo.h#98).
- [Android 11 CanvasContext.prepareAndDraw](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/libs/hwui/renderthread/CanvasContext.cpp#613).
- [Android 15 CanvasContext.prepareAndDraw](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/libs/hwui/renderthread/CanvasContext.cpp#936).

This does not guarantee availability. ViewRootImpl dispatches OnDraw before its actual drawing gate. CanvasContext can import the UI tree and skip that pulse; a later RT-only invocation can overwrite the original UI timing via `importUiThreadInfo` while using the stopped content. Such a report cannot establish the original traversal identity. A missing association must remain unavailable/failed, even if a later frame is visible.

- [Android 11 draw gate after OnDraw](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/android/view/ViewRootImpl.java#4032).
- [Android 11 CanvasContext import/skip logic](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/libs/hwui/renderthread/CanvasContext.cpp#313).
- [Android 11 FrameInfo.importUiThreadInfo](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/libs/hwui/FrameInfo.cpp#50).
- [Android 12 FrameInfo.importUiThreadInfo](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/libs/hwui/FrameInfo.cpp#38).

The review did not identify another AOSP RT-synthetic path that passes the strict greater-than rule plus the frozen bracket, same-window/sequence/VSYNC checks, and quality constraints. This is a source-based conclusion about these reference implementations, not an attestation of the exact emulator binaries or all vendor implementations.

## Report completion is not buffer submission

On API30, `reportFrameMetrics` is called outside the `if (didSwap)` block. Thus a report can exist even without successful buffer swap. API35 reporting is gated by `requireSwap`, which likewise does not establish `didSwap=true`.

- [Android 11 CanvasContext report after the didSwap block](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/libs/hwui/renderthread/CanvasContext.cpp#563).
- [Android 15 CanvasContext didSwap and requireSwap/report paths](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/libs/hwui/renderthread/CanvasContext.cpp#752).

The FrameMetrics completion arithmetic remains `INTENDED_VSYNC_TIMESTAMP + TOTAL_DURATION`. Callback receipt is a distinct timestamp. These boundaries justify the precise `firstStoppedTraversalFrameMetricsEvidence` name; `firstSubmittedFrameEvidence` would invite a stronger buffer-submission interpretation than the evidence supports.

## Reference-file integrity

The four local Java references used to resolve the API31/API35 ordering question were downloaded again from the pinned official tags during this review and compared byte-for-byte with the existing local copies. All matched:

| Pinned reference | SHA-256 |
| --- | --- |
| android-12.0.0_r1 ViewRootImpl.java | `a57342269bd3ac73721a9b02b68264e0dd2069ad9c0da4a89e6f83f65d1701e4` |
| android-12.0.0_r1 ThreadedRenderer.java | `dd00a023bc8f0a5dbc19a53701a37f6e78c2b33b16685df400c1ed3f7862040b` |
| android-15.0.0_r1 ViewRootImpl.java | `62b40f6df0f2d8a54efb7678afbe4cbe12f9b2ee8bd276ee5709d6853d568753` |
| android-15.0.0_r1 ThreadedRenderer.java | `f953d79586bd49e06c1916e23a219637cf4d092f1b8b2cb231dab65034191a66` |
