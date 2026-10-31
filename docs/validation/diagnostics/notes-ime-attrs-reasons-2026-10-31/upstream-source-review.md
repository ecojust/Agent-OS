# API37：Populator 筛选前观察接口的源码核对

本次是只读源码调查，未启动监听器、未运行设备探针、未改隐藏API策略。固定基线为AOSP17 r1：frameworks/base `94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`、frameworks/native `ae266dcb706d083868578cfedce381ef44488a07`。实际API37 CI镜像与这些源码逐字一致尚未建立。文件大小、摘要及来源见[索引](upstream-source-index.json)。

结论：**存在值得独立探查的TestApi读取入口，但现有公开/TestApi组合未满足“完整inputConfig、CLONE、原始frame、touchableRegion及可核验的配置IME IWindow关联”这组要求。** 不应先假设必须编译系统镜像，也不能把授予shell权限等同于开放全部隐藏API。已有API37后置结果只证明：在本轮可读清单中，唯一WMS文本候选未出现在Populator与最终窗口清单；尚未证明具体筛选项或原失败时刻的情况。

## 两层监听器实际提供的内容

| 接口 | 固定源码能够提供 | 缺口或变换 |
| --- | --- | --- |
| `WindowInfosListenerForTest` | `@TestApi`；`WindowInfo.windowToken`、displayId、touchableRegion、transform、bounds；由inputConfig派生的isVisible/isTouchable/isFocusable/isTrustedOverlay等boolean | **没有公开的完整inputConfig或isClone/CLONE字段**；没有layoutParamsType、ownerUid；bounds由原始frame经display transform映射并取整，不是原始frame |
| `WindowInfosListener` | 原始`InputWindowHandle[]`含inputConfig、frame、touchableRegion、getWindowToken等，正是Populator收到的数据类型 | 仅`@hide`，不在TestApi签名表；普通APK现有编译SDK和默认测试访问不构成该私有接口的支持承诺 |
| `dumpsys window a11y` | 已经经过Populator筛选的清单 | 不能倒推出筛选前完整inputConfig或每条拒绝原因 |
| `dumpsys input`/SF frontend | 另一套当前输入/层诊断字段 | 既有方案未建立到IWindow的共同强关联；输入channel token、名字或相似矩形不可代替IWindow token |

ForTest的`buildParams`直接遍历传入的handle数组，没有复用Populator的四项过滤；所以它可作为**Populator过滤前的有限观察入口**。但底层`WindowInfosListener`本身明确只保证返回SF可用窗口的一个子集，至少包括已知在屏幕上的handle；屏幕关闭时可为空。不能称其为所有WMS窗口或所有SF layer的全集。

Populator源码的四项条件为`NOT_VISIBLE`未置位、`CLONE`未置位、touchableRegion非空、frame非空。ForTest保留了`isVisible`和touchableRegion，却丢掉CLONE位且改变了frame的坐标/取整；不能用它完整重放这四项判断。

固定引用：[ForTest字段与构造](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/window/WindowInfosListenerForTest.java#48)、[frame变换及token传递](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/window/WindowInfosListenerForTest.java#310)、[真实TestApi签名表](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/api/test-current.txt#4968)、[原始监听器的子集/缓存契约](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/window/WindowInfosListener.java#29)、[Populator四项筛选](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/wm/AccessibilityWindowsPopulator.java#159)。

## 权限、TestApi访问与本项目实际启动方式

调用链是ForTest → `WindowInfosListener.register` → `android_window_WindowInfosListener.cpp` → `SurfaceComposerClient`/`WindowInfosListenerReporter` → `ISurfaceComposer.addWindowInfosListener` → SurfaceFlinger。这里的门槛为`ACCESS_SURFACE_FLINGER`，不是仅有DUMP或截图使用的READ_FRAME_BUFFER。固定SF实现允许SYSTEM/GRAPHICS UID或通过该权限检查的调用；Shell清单声明此权限。

`UiAutomation.adoptShellPermissionIdentity("android.permission.ACCESS_SURFACE_FLINGER")`走的是AMS权限委托；它不将应用进程UID改成shell，也不设置ART隐藏API豁免。权限恢复必须覆盖注销监听器，不能先drop权限再依赖有权限的remove调用。

固定`am instrument`实现默认`disableTestApiChecks=true`、`disableHiddenApiChecks=false`，启动时分别设置两个不同flag。本项目第三方探针的命令是`am instrument -w -r`，未额外指定`--no-hidden-api-checks`。因此ForTest有一个**沿用既有测试启动默认值**的TestApi候选路径；这不等于所有`@hide`成员都可用，也不保证实际镜像已有该类/字段。标准SDK编译jar不包含TestApi，后续能力探查应使用仅测试范围的TestApi签名或反射访问其公开TestApi成员，并如实记录类/方法/字段缺失；不借此反射私有成员或改变策略。

固定引用：[ForTest注册权限](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/window/WindowInfosListenerForTest.java#239)、[原生注册](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/jni/android_window_WindowInfosListener.cpp#142)、[SF权限门槛](https://android.googlesource.com/platform/frameworks/native/+/ae266dcb706d083868578cfedce381ef44488a07/services/surfaceflinger/SurfaceFlinger.cpp#10717)、[Shell权限](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/packages/Shell/AndroidManifest.xml#208)、[权限委托实现](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/app/UiAutomationConnection.java#394)、[Instrument默认值](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/cmds/am/src/com/android/commands/am/Instrument.java#94)、[flag传递](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/cmds/am/src/com/android/commands/am/Instrument.java#521)、[AMS分别处理两类访问](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/am/ActivityManagerService.java#15391)、[普通应用隐藏API策略](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/content/pm/ApplicationInfo.java#2642)。

## 相同IWindow来源不等于相同进程内文本

InputMonitor分别设置输入通道token和`windowToken=w.mClient.asBinder()`；原生到Java转换又通过`javaObjectForIBinder`把windowToken送入接收进程。ForTest确实保留这个IWindow Binder对象来源。

然而此前三份dump都在system_server内打印对象；新的监听器在测试进程内拿到本进程的BinderProxy。BinderProxy的缓存按进程内native Binder对象工作；`BinderProxy@...`的Java对象显示值不是跨进程稳定标识。**不能把监听回调的windowToken.toString与system_server的mClient文本相等/不等当作对象一致/缺失。** 需要在同一进程获得有可信来源的两个Binder引用后比较，或由同一服务提供明确映射。

ForTest又没有ownerUid和window type，不能仅靠其name、矩形、层次或窗口数量挑选并宣称配置IME身份已核验。隐藏的AccessibilityManagerService `getWindowToken(windowId,userId)`也先要求窗口存在于最终A11y清单；本次API37恰恰缺少IME窗口ID，不能靠该入口恢复缺失窗口的映射，更不能枚举猜测ID。

固定引用：[两种token来源](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/wm/InputMonitor.java#253)、[接收进程的Binder转换](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/jni/android_hardware_input_InputWindowHandle.cpp#283)、[BinderProxy进程内缓存](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/os/BinderProxy.java#476)、[最终清单限制的token查询](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java#2044)。

## 缓存、时序和观察成本

第一次register返回的是该进程Reporter已有的`mLastUpdate`；如果此前没有注册过，初始缓存可为空。ForTest会同步把这份初始状态交给consumer，再放开后续回调。只收到空初始值，不代表系统没有输入法，也不代表Populator收到空更新。

底层Reporter在分发Java回调之后才向SF确认收到对应vsyncId。慢回调可能影响更新合并/等待；ForTest不公开原始vsyncId/timestamp，不能将其当作与Populator同一次回调。注册观察不会主动刷新窗口或注入事件，但也不能宣称无调度影响。

固定Reporter在注册权限/服务失败时有native FATAL分支，不能保证全部失败都能由Java catch转成“不可用”。因此后续注册能力实验必须独立于原v4矩阵用例、单次且有主机进程时限；保留崩溃/超时，不自动重试。consumer应立即有限复制固定元数据，不能等待测试主线程或执行dump；退出时注销并恢复权限。只等待自然更新，不能通过合成事件、强制刷新或窗口变化来制造所需样本。

固定引用：[缓存和注册失败分支](https://android.googlesource.com/platform/frameworks/native/+/ae266dcb706d083868578cfedce381ef44488a07/libs/gui/WindowInfosListenerReporter.cpp#37)、[回调后确认](https://android.googlesource.com/platform/frameworks/native/+/ae266dcb706d083868578cfedce381ef44488a07/libs/gui/WindowInfosListenerReporter.cpp#86)、[SF未确认更新的处理](https://android.googlesource.com/platform/frameworks/native/+/ae266dcb706d083868578cfedce381ef44488a07/services/surfaceflinger/WindowInfosListenerInvoker.cpp#86)、[ForTest先初始状态再后续回调](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/window/WindowInfosListenerForTest.java#242)。

## 下一项可执行实验与仍明确的阻塞

可先冻结一个**独立API37能力探查**，保持现有测试启动方式和隐藏API策略：只检查ForTest类、公开TestApi构造/注册/注销方法及所需字段是否可访问，分别记录`CLASS_UNAVAILABLE`、`MEMBER_UNAVAILABLE`或`ACCESS_REJECTED`。检查不能把仅有的派生boolean补成完整inputConfig，也不能把逻辑bounds标成原始frame。该阶段不需要注册监听器，不读取任意窗口标题，也不影响原32项判定。

若之后确有必要验证有限监听能力，应另行声明一次注册、有限记录数/字节数及主机总时限，仅收初始值与期限内首个自然回调，明确区分二者；不因初始空或未收到回调追加事件。即使成功，这也仅验证有限TestApi观察入口，并不关闭当前IME根因。

完整筛选前诊断仍需要两个条件：一是受支持方式提供原始inputConfig/CLONE/frame及来源完整性；二是配置IME身份与同进程IWindow Binder引用的可核验映射。当前核对的公开/TestApi与shell dump尚未同时满足。原始`WindowInfosListener`、私有服务或新shell辅助程序属于另一个需要验证的实现方案；`app_process`这一命令本身不是私有API可用性或隐藏策略不变的证明。本次不使用`--no-hidden-api-checks`、VMRuntime豁免、root、策略修改或平台镜像构建来填补缺口。

所有结论均为源码可行性核对；实际镜像的TestApi访问、权限委托到SF、首次自然更新和可靠注销尚未在设备执行。
