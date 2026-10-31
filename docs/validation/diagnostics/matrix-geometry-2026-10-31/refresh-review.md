# API30 自有控制窗刷新候选：独立只读审查

日期：2026-10-31 UTC。只读审查现有产品与测试代码、历史矩阵原件说明及固定 Android 11 源码；未修改仓库，未执行设备实验。当前不建议直接将刷新候选加入生产路径，本轮先保存调查结论。

## 已证实的机制

1. Android 11 的 `WindowStateAnimator.computeShownFrameLocked()` 在旋转动画中以 `1 + 2/w`、`1 + 2/h` 放大窗口。`WindowState.getTransformationMatrix()` 将相应 `mDsDx/mDsDy` 带入变换，`AccessibilityController.computeWindowRegionInScreen()` 将 touchable region 转至屏幕坐标并截为整数。此机制与历史 17/18 次 right/bottom 各 +2px 一致，但不能据静态源码唯一认定原运行的具体时序。
2. `AccessibilityManagerService.sendAccessibilityEvent()` 对可合法分派、含已注册窗口 ID 的 `TYPE_WINDOW_STATE_CHANGED`，在窗口跟踪开启时调用 `WindowManagerInternal.computeWindowsForAccessibility()`。后者以 `forceSend=true` 重新计算窗口，并在事件交给服务之前更新平台窗口路径。因此一次真实自有窗口事件具有促使平台重算的源码依据；这不是公开的“缓存刷新成功”返回契约。
3. `getWindows()` 对已有窗口列表并不强制重算；`ensureWindowsAvailableTimedLocked()` 发现列表已有值即返回。重复公开读取因此可能继续得到同一份旧几何。
4. `ScreenRotationAnimation.onAnimationEnd()` 结束旋转、请求更新；该方法没有直接调用 accessibility 窗口重算。但仅检查此方法不能证明其他后续路径必然不会更新。

## 必须修正的假设

产品未显式发送窗口状态事件，不代表窗口从未发送事件。固定 API30 的 `ViewRootImpl` 在 `changedVisibility || regainedFocus` 时自动执行 `host.sendAccessibilityEvent(TYPE_WINDOW_STATE_CHANGED)`；新 overlay 的首次可见也在此路径。现有问题可能是**早期已发事件后几何再次变化**，不能描述成“产品完全漏发窗口事件”。

尚未区分两个原因：

- accessibility 窗口列表保留了旋转时的变换结果，而 WMS 当前 `mDsDx/mDsDy` 已恢复单位矩阵；
- WMS 自身仍保留旋转时矩阵，重新计算窗口列表仍会得到同一扩大边界。

历史记录中的超时后 `mAnimRunning=false` 不能替代拒绝当时的矩阵值。静态源码与 +2px 的吻合不能证明第一种原因。候选事件如果在动画未结束时发送，也可能重算出同样的扩大边界；因此简单“每 identity 只发一次”不保证有效。

## 真实事件的影响和实施风险

- 必须从当前实际已登记的 overlay View 使用正常 `sendAccessibilityEvent()`，让框架设置 source/window/package。不得自行填入发现的窗口 ID、伪造系统 `TYPE_WINDOWS_CHANGED`、抢焦点、修改窗口位置或放宽边界。
- `View.sendAccessibilityEvent()` 会初始化来源并填充自身及后代文本，事件会传给其他已启用的辅助服务。重复发布一个未变化窗口的状态可能产生重复朗读；它不是无副作用的纯读取。
- 产品自己的 `isCurrentOwnedCosmeticEvent()` 不把 `TYPE_WINDOW_STATE_CHANGED` 视为纯装饰事件，故自身收到事件时会失效 visual generation，且可能触发审阅关闭及准备检查。不能将这一事件加入装饰白名单仅为使测试通过，必须保留现有保护。
- API30 `AccessibilityWindowManager.updateActiveAndAccessibilityFocusedWindowLocked()` 在已跟踪窗口时，不因这个事件直接把非焦点 overlay 设为活动窗口；仍须实测前台/焦点不变。不得用事件成功返回推断窗口更新成功或控制状态有效。
- 失配发生于工作线程；View 操作只能在主线程执行。队列任务必须绑定原 `RunControl`、epoch、registration、attachment、token、callback generation 和本地 exact bounds，并在实际发送前重检原任务与期限；停止、暂停、重建、重新发布或期限到达后必须丢弃，不转投新窗口。
- 若未来生产启用，原 snapshot 必须仍返回 `STALE_OBSERVATION`、不沿用旧节点；不能增加同步等待、额外 getWindows、独立重试次数或延长原两秒/六次预算。API30 分支、排队合并、次数上限和停止后的排队清理均需可审核。

## 后续固定 test-only 实验建议

先单独运行诊断实验，不修改正式旋转用例的准入或原观察预算：在专用合成设备持有原任务、原窗口/attachment 时，保存原失败和固定次数的同一次观测证据；在独立诊断阶段核对 WMS 当前矩阵、真实本地控件 bounds 与平台同 ID 窗口 bounds。若矩阵已恢复而平台仍 +2px，发送至多一次真实根窗口状态事件，再读取一个预声明的后样本，核对 source ID、attachment、token、local bounds、前台/焦点、派发次数和原代次均未改变。

诊断额外读取必须作为独立实验明确列出，不能倒填到原两秒/六次失败分母，不能把实验后的通过替换原失败。若刷新仍保持 +2px，保留失败并转查 WMS 矩阵恢复；若变为精确相等，也只证明该次刷新效果，随后才设计有界生产策略及固定回归，不立即称为稳定修复。

## 固定官方源码

以下均为 `android-11.0.0_r1`，不是浮动主分支：

- [WindowStateAnimator.java:676](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/wm/WindowStateAnimator.java#676)：旋转缩放，重点 693–732。
- [WindowState.java:5206](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/wm/WindowState.java#5206)：窗口变换矩阵。
- [AccessibilityController.java:1452](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/wm/AccessibilityController.java#1452)：touchable region 映射；1261–1363 为计算与回调。
- [AccessibilityManagerService.java:641](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java#641)：真实来源校验，684–709 为重算后再分派。
- [AbstractAccessibilityServiceConnection.java:1491](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java#1491)：已有窗口列表不强制刷新。
- [ViewRootImpl.java:3052](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/android/view/ViewRootImpl.java#3052)：框架在首次可见/重新取得焦点时自动发窗口状态事件。
- [View.java:8210](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/android/view/View.java#8210)：事件来源与文本填充；8308–8328 为发送路径，8504 为 setSource(this)。
- [AccessibilityWindowManager.java:1256](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityWindowManager.java#1256)：窗口跟踪已启用时事件不直接抢活动窗口。
- [ScreenRotationAnimation.java:710](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/wm/ScreenRotationAnimation.java#710)：动画结束处理。

## 本地已审查原件校验值

目录 `/workspace/scratch/rotation-aosp11`。两份旧同名参考文件与固定 tag 不同，以下使用重新下载并复核的 `*-r1.java` 原件；上文行号也已按 r1 核对。这里只证明固定 AOSP 版本机制，不断言原模拟器镜像逐字使用此版本源码。

| 文件 | SHA-256 |
| --- | --- |
| `AccessibilityManagerService.java` | `5f7b720f10d4f44a738fe72d02d533e9ae09c11b0c07aa855e8ce6de79734f22` |
| `AccessibilityController-r1.java` | `07fe05e9274f57cd85178a62135f3c410c742192c3af26c919e9a70603c8a02a` |
| `AccessibilityWindowManager.java` | `044400c4e588e05d0ff4bb05961800321d616cb184a35fa0f9b0930bb8aa9911` |
| `WindowStateAnimator-r1.java` | `cc55a3066512d6d09e105e4a539c6cb454a23f9800ed3a3b7a7a8fcbc26c2fa7` |
| `WindowState.java` | `4be6906341c4b7c44c6a26ea28fe241e4d7239bf6aec8aaec8907cd045e4efdd` |
| `ScreenRotationAnimation.java` | `655247d361b44279fadc4f473d4dcc1bfbf4be2ad06700596b22df976bb35869` |
| `View.java` | `299781fbbe0ca1c096a0606fb9a8dcad8c3722b59d5130ad658693f22cd8ca66` |
| `ViewRootImpl.java` | `4f4ed7eeb959c7f1dcd29240a3c9bd94582c24ec42b61240d14733c2610ea5d1` |
| `AbstractAccessibilityServiceConnection.java` | `d002954683251a2f32bdef956e44841236e9dc30edf4b2503a753be2814f193e` |
