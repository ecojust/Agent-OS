# Notes API 37：无障碍窗口来源的有界源码核对

2026-10-31。只读核对四个固定 AOSP 源文件；未修改项目、测试或准入条件。实际运行证据以 `notes-diagnostic-findings.md` 的原读分析为准；本文是平台机制说明，不新增运行观测。

官方存在 `android-17.0.0_r1`，本报告直接核对该版本，并用 Android 16 r1 对照；没有把 16 当作 17。frameworks/base 固定提交：16 r1 为 `99b01a65cc4c104933788b3143285ab6bae65827`，17 r1 为 `94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`。**固定公开 tag 不证明受测 API 37 模拟器镜像与该提交完全相同**；具体镜像/build fingerprint 对应关系尚未建立。

## 已确认的路径

1. 两版本 `AccessibilityWindowsPopulator` 继承 `WindowInfosListener`。`onWindowInfosChanged` 接收 `InputWindowHandle[]` 与 display 信息；类内注释和注册路径把其来源描述为 SurfaceFlinger 回调。这不是每次直接遍历 WMS 的 `isOnScreen/isVisible/isReadyForDisplay`。
2. 两版本均先要求：`(inputConfig & NOT_VISIBLE) == 0`、`(inputConfig & CLONE) == 0`、`touchableRegion` 非空、`frame` 非空，全部满足才进入 `mVisibleWindows`。**该循环没有 IME 豁免**。空 touchableRegion 与 `NOT_TOUCHABLE` 位不是同一条件；后者由另一个 `isTouchable()` 方法检查，不是此循环的第四/第五过滤项。
3. 已筛窗口按 `displayId` 存入列表，再逐个转成 `AccessibilityWindow`；`getWindowToken()` 到 `mWindowMap` 查找关联 `WindowState`，`layoutParamsType` 和 `inputConfig` 保留自输入句柄。此处不要求 WMS 三个可见谓词与输入句柄一致。不存在关联 WindowState 时也有 windowless 转换分支，故不能把所有回调条目等同于 WMS 窗口。
4. `WindowsForAccessibilityObserver.computeChangedWindows` 使用 Populator 列表，然后调用 `onAccessibilityWindowsChanged`。Android 17 另有 feature flag 控制焦点来自 input 上报或 WMS，并在无焦点 token 时提前返回；此事实不证明受测镜像 flag 开启或当次提前返回。Controller 中 `isOnScreen() && isVisible()` 的窗口遍历以及 `TYPE_INPUT_METHOD` 特殊区域处理属于放大区域计算，不能当成此 observer 的 IME 纳入规则。
5. Populator 保存回调所得列表并经 Handler 通知；源码含约 35 ms 稳定窗口和 450 ms 通知上限机制。其读取与另一次 WMS dump 没有原子一致性承诺；这些常量既不是当次延迟观测，也不能给现有准备阶段增加时间或读取额度。

以上只能证明：**WMS 自身谓词为 true 不足以推出该窗口必然进入这个无障碍候选列表**。本次范围止于四个 Java 文件，没有核对原生侧如何计算 `NOT_VISIBLE`，因此不能进一步声称它必然表示父 leash 隐藏、合成 alpha/crop、实际遮挡或零可见像素。也未审查 `onAccessibilityWindowsChanged` 之后的全部服务端筛选，不把此候选列表冒称最终客户端清单。

## 对当前原读证据的限制

本轮 API 37 off/idle 的原决策清单、四次隐藏前清单和最终清单都没有 IME，完整扫描且无 `BELOW_FOCUSED`；原 WMS 四次则匹配配置身份并报告 onScreen/visible/ready/hasSurface/shown 为 true。**现有清单没有原始 InputWindowHandle、具体过滤结果或跨来源共同 token**，所以无法从这组结果判定是上游未提供、上述任一筛选、按 display 分组、回调时序还是后续处理导致缺项。WMS `selectedToken` 的输出形式也没有被证明就是 `InputWindowHandle.getWindowToken()` 的可直接比较标识。owner/package/display 匹配不能代替对象关联。

因此，本报告不能认定当次层已隐藏、IME 已隐藏或平台存在 bug；不恢复已被原读证据否定的应用层 `BELOW_FOCUSED` 假说，不降低 v4 正向隐藏门槛，不将本轮标为通过。

## 下一步最小诊断（未来工作，尚未读取）

在先确认受测镜像的实际源码/flag 与可用平台诊断权限后，另做有界 test-only 采集，关联同一个具体窗口，区分三个边界：

| 边界 | 最小字段与可判别结果 |
| --- | --- |
| 原 WindowInfosListener 回调，过滤之前 | 单调时刻/回调序号、具体 window token 与 WMS client/window 映射、displayId、layoutParamsType、完整 inputConfig、frame、touchableRegion。先回答原列表是否包含该 IME，再逐项计算四个过滤条件。 |
| Populator 缓存与候选输出 | 对应回调序号/时间、筛选后 token 列表与各 display 分组、转换是否找到 WindowState。该类现有 dump 主要显示已筛列表，**仅看 dump 的缺项不能反推出原回调缺项或过滤原因**。 |
| Controller 的实际回调 | 回调序号/时间、focused token/display、相关 flag 实际值、输出中的匹配 token/type。若在此存在而最终客户端不见，再单独调查后续边界。 |

若后续要主张实际合成/像素状态，须另有匹配层与祖先层的同序表面证据；此处没有此证据。上述采集应作为独立诊断，不能向现有四次 WMS 额度/准备期限中静默添加读取、刷新事件或 BACK，也不能用之后的样本补写原准备读取。

## 精确源码索引

以下链接固定 commit；下载文件 SHA-256、字节数和原路径详见配套 [ime-window-list-source-index.json](ime-window-list-source-index.json)。行号以该文件原始内容为准。

| 文件 | Android 16 r1 | Android 17 r1 |
| --- | --- | --- |
| Populator：类型/来源/时序常量 | [55–68](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/wm/AccessibilityWindowsPopulator.java#55) | [56–68](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/wm/AccessibilityWindowsPopulator.java#56) |
| Populator：原回调四项筛选 | [151–190](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/wm/AccessibilityWindowsPopulator.java#151) | [154–193](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/wm/AccessibilityWindowsPopulator.java#154) |
| Populator：注册、按 display 分组、通知 | [222–303](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/wm/AccessibilityWindowsPopulator.java#222) | [225–306](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/wm/AccessibilityWindowsPopulator.java#225) |
| Populator：token/type 转换 | [670–698](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/wm/AccessibilityWindowsPopulator.java#670) | [675–708](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/wm/AccessibilityWindowsPopulator.java#675) |
| Populator：已筛列表 dump | [564–594](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/wm/AccessibilityWindowsPopulator.java#564) | [567–597](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/wm/AccessibilityWindowsPopulator.java#567) |
| Controller：a11y observer 实际路径 | [1178–1220](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/wm/AccessibilityController.java#1178) | [1210–1273](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/wm/AccessibilityController.java#1210) |
| Controller：另一条放大区域 WMS 遍历 | [980–989](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/wm/AccessibilityController.java#980) | [1000–1008](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/wm/AccessibilityController.java#1000) |

补充精确定位：`NOT_TOUCHABLE` 的独立 accessor 在 [17 Populator 808–809](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/wm/AccessibilityWindowsPopulator.java#808)；IME 放大区域分支在 [17 Controller 874–878](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/wm/AccessibilityController.java#874)。

四文件 SHA-256：

- 16 Populator：`fd882bda3b5586ae0686c9b3dea0d3dbf83060e9e81bc3540816c48a538bce29`
- 16 Controller：`eec918b8a8ad9814dc26a91288308f2791e9361c9653614be0e3c2e6b4225286`
- 17 Populator：`2ca4293d7a18527b55047d672039fc378c1e342a563f91f34fc012fbf04f21c9`
- 17 Controller：`4970f77dbb5e01cac93b7b73d9d98ac3ecb9304367bcf2b07e38e1060a6798e7`
