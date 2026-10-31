# IME 窗口缺项：现有只读接口的可执行范围

2026-10-31 只读源码核对，未在设备采集、未修改项目或 v4 准入。结论是：**现有 shell 接口可提供区分若干来源边界的候选证据，但不能保证得到原回调的完整字段或强对象关联。** 应先做独立能力探查，不必先构建修改过的系统镜像。以下主要固定于 Android17 r1：frameworks/base `94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`、frameworks/native `ae266dcb706d083868578cfedce381ef44488a07`。公开 tag 不证明受测 API36/37 镜像逐字相同，实际输出格式/权限须重新核对。来源、大小、SHA 见 [固定源码索引](ime-interface-source-index.json)。

## 可以先用的接口

| 命令（每条独立执行） | 确实能够提供 | 不能据此声称 |
| --- | --- | --- |
| `adb shell dumpsys -t 2 window -a windows` | 单个 WindowState 的 `mClient`、display、owner/package、type、当前 WMS 状态；严格挑选唯一配置 IME后提取固定字段 | 现有原诊断的 `selectedToken` 是 WindowState 对象标记，不是 IWindow binder；后置 mClient 不能倒填原读取 |
| `adb shell dumpsys -t 2 window a11y` | Populator 通知开关、**已经筛选**的窗口清单及按 display 分组；InputWindowHandle 文本有 `windowToken`、frame、touchableRegion、transform、display、isClone、alpha 等 | 此文本不打印完整 inputConfig/type/owner；既不是过滤前回调全集，也没有逐窗口拒绝原因或原回调序号。缺项不能判定是哪项筛选 |
| `adb shell dumpsys -t 2 accessibility` | 当前服务端报告的 A11yWindow 及配对 WindowInfo；前者有无障碍窗口 ID，后者打印 `token`、type、display、region/bounds、parent 等 | 只覆盖最终被报告的窗口；未报告的 IME 没有这份映射。不是 Populator 的完整候选输出，也没有全量注册 token→ID 表 |
| `adb shell dumpsys -t 2 input`（第二阶段） | 当前 Input Dispatcher 保存的窗口 name/id/display、inputConfig、frame、touchableRegion、owner、输入 channel token 等 | 不打印独立 IWindow `windowToken`/layoutParamsType；native token 指针不可与 Java binder 文本比较。列表也经过 Dispatcher 自己的通道筛选，不是原 SF 回调全集 |
| `adb shell dumpsys -t 2 SurfaceFlinger --frontend`（第二阶段） | 当前 composition/input snapshot、layer hierarchy/offscreen hierarchy；snapshot 输出 layer/path 标识、visible/reason、条件性 bounds/input flags、触摸区域包围矩形、touchCropId 等 | 未直接输出 IWindow binder；层名字或相似矩形不足以证明是目标 IME。触摸包围矩形不是完整 region，input 块仅在 hasInputInfo 时出现；此处 bounds/touchableRegion 文本顺序为 left,top,bottom,right，不能按常见 Rect 顺序解析；没有对旧 v4 时刻的像素证明 |

固定 WMS/Input 服务入口检查 DUMP；固定 Shell 清单声明 DUMP，SF dump 明确允许 AID_SHELL，因此这些接口不要求先执行 `adb root`。项目原探针已使用 `dumpsys -t 2 accessibility` 读取服务状态。但实际镜像仍可能拒绝、无该子命令、超时或改变格式，必须原样记为不可用，不能把失败解析成“没有 IME”。以上均为读取，不启用 tracing、不强制刷新窗口、不发送事件或改变安全标志。

## 一个实际存在、但需要限定的关联链

固定 `InputMonitor.populateInputWindowHandle` 同时写入两种不同 token：`setToken(w.mInputChannelToken)` 与 **`setWindowToken(w.mClient.asBinder())`**。WMS 的 `WindowState.dump` 打印同一个 `mClient.asBinder()`；Populator 用 `getWindowToken()` 在 `mWindowMap` 中找 WindowState；`WindowState.getWindowInfo()` 又把 `WindowInfo.token` 设为这个 mClient。`dumpsys accessibility` 将该 WindowInfo 与对应 A11yWindow 一起打印。

所以可以增加一个独立诊断：在同一 system_server 生命周期内，先严格选择唯一配置 IME 的 WMS 记录，再用完整 `mClient` 文本在 Populator `windowToken` 与服务端 `WindowInfo.token` 中寻找唯一候选，保留匹配/缺失/重复/变化计数、display/type 和读取顺序。**这是有明确字段来源的文本关联，不是持有 Binder 强引用的等价保证。** Java对象打印值可能碰撞、对象可重建，跨进程指针不相等，跨运行/重启不可复用；任何不唯一或生命周期变化都应停止强关联。不能直接拿旧 `selectedToken`、owner UID、窗口名字或无障碍数字 ID 代替这个键。

若 WMS 与 Populator 的唯一候选都在，但最终服务端无该 token，可以把调查范围缩到后续窗口报告/注册及各次读取间变化；不能断言具体规则。固定 AccessibilityWindowManager 后续还处理触摸性、可见 region、嵌入层级、允许类型、注册 window ID，并可能在空间全部计算完且焦点已加入后停止迭代。若 Populator 本身缺项，仍无法区别上游没提供、四项预筛选、display分组或缓存/通知时序。

`dumpsys input` 的 token 是另一种输入通道键，输出为 native binder 进程内指针。SF snapshot的输入 id取自其uniqueSequence，但 clone与普通layer路径存在差别；本次没有完成到 WMS/Java binder 的共同映射，不能将数字或名字相同当作跨来源身份已证实。若三份Java dump仍不能关联，应先保存这种缺口，再决定是否需要专门的平台 listener 诊断。

## 建议的最小独立采集

第一阶段只做上述三条 Java 服务 dump；失败原回执冻结后、既有清理/截图前采一次，各自保存单调开始/结束时刻、顺序、命令状态、截断/超时与来源指纹。这是**失败后的诊断**，即使得到完整映射，也不能回填此前 BACK 决策或四次原 WMS。若要研究当次准备过程，须另立明确版本/预算的诊断运行，原 v4 32项不混合。

建议预声明最多三条、每条 dumpsys 服务等待2秒，主机额外设总超时和字节上限；第二阶段的 input/SF 两条另计，不临时循环直到出现想要的状态。同步读取会影响调度，不宣称零时序影响。完整 dump 可能包含任意窗口标题、事件队列等；仅在专用合成模拟器执行，归档提取固定目标字段、错误与缺失计数，不无界保留标题/队列。`input` 只解析当前 `Input Dispatcher State:`，排除历史 `Input Dispatcher State at time of last ANR:`。不以任何诊断结果直接放行 BACK、确认或截图。

当前尚未采集上述数据。它们可降低“清单为何缺项”的不确定性，不能单凭 dump 声称 IME 已隐藏、子表面像素消失、原 v4失败已修复或平台存在 bug。

## 关键固定引用

- [WMS dump入口/权限](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/wm/WindowManagerService.java#7565)、[a11y分支](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/wm/WindowManagerService.java#7659)
- [mClient输出](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/wm/WindowState.java#4305)、[WindowInfo.token来源](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/wm/WindowState.java#4684)、[两种token分别赋值](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/wm/InputMonitor.java#253)
- [InputWindowHandle真实文本字段](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/view/InputWindowHandle.java#222)、[Populator dump](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/wm/AccessibilityWindowsPopulator.java#567)
- [服务端最终窗口和WindowInfo配对输出](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/accessibility/java/com/android/server/accessibility/AccessibilityWindowManager.java#1052)、[后续过滤/ID条件](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/accessibility/java/com/android/server/accessibility/AccessibilityWindowManager.java#505)
- [Input原生输出](https://android.googlesource.com/platform/frameworks/native/+/ae266dcb706d083868578cfedce381ef44488a07/libs/gui/WindowInfo.cpp#302)、[Dispatcher后续筛选](https://android.googlesource.com/platform/frameworks/native/+/ae266dcb706d083868578cfedce381ef44488a07/services/inputflinger/dispatcher/InputDispatcher.cpp#5627)
- [SF shell权限/--frontend入口](https://android.googlesource.com/platform/frameworks/native/+/ae266dcb706d083868578cfedce381ef44488a07/services/surfaceflinger/SurfaceFlinger.cpp#6636)、[frontend列表与层级](https://android.googlesource.com/platform/frameworks/native/+/ae266dcb706d083868578cfedce381ef44488a07/services/surfaceflinger/SurfaceFlinger.cpp#6952)、[snapshot实际输出](https://android.googlesource.com/platform/frameworks/native/+/ae266dcb706d083868578cfedce381ef44488a07/services/surfaceflinger/FrontEnd/LayerSnapshot.cpp#324)
- [DUMP判定](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/com/android/internal/util/DumpUtils.java#98)、[Shell声明DUMP](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/packages/Shell/AndroidManifest.xml#172)
