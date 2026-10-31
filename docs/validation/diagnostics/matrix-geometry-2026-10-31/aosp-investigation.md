# API30 旋转后窗口边界 +2 px：只读源码调查

调查日期：2026-10-31 UTC。仓库基线 `d23e4babd`；未修改生产或测试实现，未运行新的设备用例。

## 结论范围

已确认 AOSP Android 11 存在一个可精确生成现有 +2 px 模式的旋转变换路径；尚不能证明历史拒绝时是该变换仍有效、服务端窗口列表未重新计算、客户端窗口缓存未更新，还是这些路径的组合。**不建议把 ±2 px 容差或直接改写窗口边界作为修复**。三轮原失败、精确几何、原 2 秒／6 次观察预算和零派发要求均保持。

固定版本参考为 `android-11.0.0_r1`。这里只证明该 AOSP 版本中的机制，不宣称已证明原模拟器镜像逐字采用该版本源码。

## 已确认的源码机制

1. [WindowStateAnimator.java:676](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/wm/WindowStateAnimator.java#676) 的 `computeShownFrameLocked()` 仅当存在旋转动画、`isAnimating()` 为真且窗口参与旋转时进入变换分支；`isRotating()` 时在第 703 行明确执行 `setScale(1 + 2/w, 1 + 2/h, w/2, h/2)`，原注释说明为避免旋转窗口之间出现缝隙而把宽高各扩大两像素。随后把矩阵的缩放/斜切项存入 `mDsDx/mDsDy/mDtDx/mDtDy`。
2. [WindowState.java:5206](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/wm/WindowState.java#5206) 的 `getTransformationMatrix()` 读取这些缩放/斜切值，但平移部分取实际 surface position 加 surface insets／父窗口偏移；不直接复制上一步以中心为支点的平移项。因此简单无斜切、无全局缩放情形可表现为 left/top 不变、right/bottom 各加 2。
3. [AccessibilityController.java:1452](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/wm/AccessibilityController.java#1452) 的 `computeWindowRegionInScreen()` 取得原 touchable region、相对本地 frame 原点平移后应用此矩阵，最后用整型坐标组成 `WindowInfo.regionInScreen`。这条路径产生的是平台报告的屏幕区域，而本应用 `AgentAccessibilityService.controlBounds()` 使用 `getLocationOnScreen + View.width/height`；两者并非总在同一旋转变换状态。
4. [AbstractAccessibilityServiceConnection.java:1491](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java#1491) 的 `ensureWindowsAvailableTimedLocked()` 在已有 window list 时直接返回。因此仅再次调用服务 `getWindows()` 不是强制重算 WMS 变换区域的保证。
5. [AccessibilityManagerService.java:684](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java#684) 在获准分发 `TYPE_WINDOW_STATE_CHANGED`、事件携带已登记窗口 ID、显示屏有效且正在跟踪窗口时，先调用 `wm.computeWindowsForAccessibility(displayId)` 再通知服务。源码注释明确用于避免客户端事件先到、WMS 延迟计算窗口状态。
6. [ScreenRotationAnimation.java:710](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/wm/ScreenRotationAnimation.java#710) 的动画结束路径调用 `kill()` 和 `updateRotation(false, false)`，此处没有直接调用无障碍窗口重算。但这不排除后续 surface/layout/layer/focus 等间接通知，不能用“没有直接调用”证明历史缓存必然陈旧。

## 与原记录的对应及未证明部分

原三轮 API30 共 18 次失配，其中 17 次为 `actual=[110,36,532,150]`、`expected=[110,36,530,148]`，即本地宽高 420×112 对应平台 422×114。一次早期读数还有更大的高度差，不能统一归为 +2 px 机制。

第一轮终止后的非原子窗口诊断还出现 Fixture 640×320 对应平台 642×322、系统栏 640×24 对应平台 642×26。对上述固定公式按 float32 运算，三组宽高结果分别恰为 422×114、642×322、642×26。这是源码公式的离线推导，不是新的设备采样，也不证明后采样状态等于拒绝瞬间。

原日志未在每次拒绝瞬间同时保存 WMS `mDsDx/mDsDy`、当前 frame/surface position 和服务端无障碍列表生成时点；`mAnimRunning=false` 是更晚的独立观察。现有证据不足以区分动画仍有效、WMS 仍持有旧缩放矩阵、服务端列表或客户端缓存残留。

## 当前 overlay 已有的事件与刷新风险

本仓库的 `AgentAccessibilityService` 没有显式调用 `sendAccessibilityEvent(TYPE_WINDOW_STATE_CHANGED)` 或设置 pane title；状态文字使用 polite live region。但**不等于首显未发窗口状态事件**：[ViewRootImpl.java:3052](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/android/view/ViewRootImpl.java#3052) 在 `(viewVisibilityChanged || mFirst) && visible` 或重新获得焦点时，默认由真实 host 发送 `TYPE_WINDOW_STATE_CHANGED`。首次 attach/display 已可能发出早期通知。

[View.java:8272](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/android/view/View.java#8272) / [View.java:8308](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/android/view/View.java#8308) 会检查 accessibility enabled、真实 View shown 状态，初始化并分发事件；[View.java:8504](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/android/view/View.java#8504) 用 `event.setSource(this)` 绑定实际 View。[ViewRootImpl.java:8578](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/android/view/ViewRootImpl.java#8578) 还会先冲刷待发 content-changed 事件，随后向 AccessibilityManager 发送。因此重复事件会影响所有已启用辅助服务，并可能填充当前窗口文本；本应用自身的 `isCurrentOwnedCosmeticEvent()` 不把该类型视为 cosmetic，会推进 visual generation。

稳定本地布局不是系统旋转动画已结束的证明。在 first mismatch 后每身份只通知一次，也可能恰在动画期间刷新出另一份放大区域，随后同身份继续失败。不能在缺少实验的情况下称为稳定修复，也不能将事件调用返回视为窗口已刷新或已可操作。

## 后续独立、只读为主的 test-only 实验建议

先做一个专用合成设备实验，生产行为保持不变，与原旋转验收独立编号：

- 持有原任务、RunControl epoch、配置许可、真实 overlay attachment、window token、registration/window ID 及本地 bounds。保留默认动画设置，不修改窗口几何、清缓存、重接服务或重放业务动作。
- 固定有限样本和时限，分别记录主线程本地 bounds、服务和独立 UiAutomation 所见窗口 bounds、原窗口 WMS frame/surface position 和 `mDsDx/mDsDy/mDtDx/mDtDy`。所有样本带独立时间，明确跨进程观察非原子；未取到字段即 unavailable。
- 只有在预声明观察阶段结束且原 owner/epoch/attachment/current identity 仍有效时，从原真实 root 调用一次公开 `sendAccessibilityEvent(TYPE_WINDOW_STATE_CHANGED)`；使用框架设置 source，不手工合成其他窗口 ID。此通知本身不是输入/点击、不是产品验收通过条件。
- 同一固定时限内再次读取相同身份与几何的窗口报告。如果发前 WMS 矩阵已恢复 identity 而报告仍 +2、发后同 ID 报告恢复精确值，会加强“列表更新不足”证据；如果 WMS 矩阵仍放大或发后不变，必须保留该结果，不追加事件直到成功。
- 不能把此独立实验的额外观察纳入原观察预算，不能让原失败用例在拒绝后继续使用旧节点。原 273 项矩阵仍按原合同判断。

后续若实验支持生产候选，也须独立审查：只在已验证为原自有窗口的几何失配处请求；原尝试仍返回 STALE；队列有界合并；主线程投递前再核对原 owner、epoch、配置、token、注册身份、本地 bounds 和绝对期限；过期或替换丢弃；不能新增 getWindows、放宽几何、延长原 2 秒／6 次预算或恢复旧写。如何限制事件次数且避免“首次通知仍处于动画期间”的问题，需要在明确实验结果后决定，本轮不实施。

## 固定参考文件摘要

以下 SHA-256 均重新从上述固定 tag 下载并核对。旧 scratch 同名文件若不同，保留原件，将 r1 副本另存 `*-r1.java`；本表链接和行号全部指向 r1。

| 官方文件 | SHA-256 |
| --- | --- |
| [WindowState.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/wm/WindowState.java) | `4be6906341c4b7c44c6a26ea28fe241e4d7239bf6aec8aaec8907cd045e4efdd` |
| [WindowStateAnimator.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/wm/WindowStateAnimator.java) | `cc55a3066512d6d09e105e4a539c6cb454a23f9800ed3a3b7a7a8fcbc26c2fa7` |
| [AccessibilityController.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/wm/AccessibilityController.java) | `07fe05e9274f57cd85178a62135f3c410c742192c3af26c919e9a70603c8a02a` |
| [WindowAnimator.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/wm/WindowAnimator.java) | `e5575a9dcaf8ce9830fb588dcf77ee82fc49075ad3561ab8a75fe5dd4c7dc137` |
| [ScreenRotationAnimation.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/wm/ScreenRotationAnimation.java) | `655247d361b44279fadc4f473d4dcc1bfbf4be2ad06700596b22df976bb35869` |
| [DisplayContent.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/wm/DisplayContent.java) | `477fb73af137e4b5dadd3b0f52d47c52bbdfab164975981dae47bca356cab419` |
| [AccessibilityManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java) | `5f7b720f10d4f44a738fe72d02d533e9ae09c11b0c07aa855e8ce6de79734f22` |
| [AbstractAccessibilityServiceConnection.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java) | `d002954683251a2f32bdef956e44841236e9dc30edf4b2503a753be2814f193e` |
| [View.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/android/view/View.java) | `299781fbbe0ca1c096a0606fb9a8dcad8c3722b59d5130ad658693f22cd8ca66` |
| [ViewRootImpl.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/android/view/ViewRootImpl.java) | `4f4ed7eeb959c7f1dcd29240a3c9bd94582c24ec42b61240d14733c2610ea5d1` |
