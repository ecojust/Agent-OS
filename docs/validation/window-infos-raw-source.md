# 原始窗口字段与输入法身份来源

本步骤完成了原始字段和窗口身份链的源码核查，选定下一项独立实验：在专用 API37 模拟器的 shell 进程中验证原始 `WindowInfosListener`。固定源码明确将 shell 列为注册位置，原始 `InputWindowHandle` 同时提供过滤所需字段、包名、UID、类型和真实 IWindow Binder。无需先改造真实输入法，便可定义一个可核验的输入法窗口候选。

**这是一项来源可行性结论，尚未实测 shell 访问、注册或回调。** [上一项 API37 探查](window-infos-capability.md)只验证了 instrumentation 中的 ForTest 声明与构造。原 Notes 32 项本轮没有重跑，仍为 **24 通过、8 失败**；本次没有修改原 v4、隐藏判定或预算。

## 固定来源与可获得的信息

以下依据 Android 17 r1 的固定提交：frameworks/base `94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`、frameworks/native `ae266dcb706d083868578cfedce381ef44488a07`、ART `b753cf97923c3695338d21466fa14c57b480a59a`；proto schema 另固定 external/perfetto `ece66975738007dd0978b911d8a2077e49b8f31e`。每个文件的 URL、字节数和 SHA-256 见[源码索引](diagnostics/window-infos-raw-source-2026-10-31/source-index.json)；[复核记录](diagnostics/window-infos-raw-source-2026-10-31/source-audit.json)单列来源检查与结论。没有证明 CI 系统镜像与这些提交逐字一致。

| 需要的信息 | 固定源码中的来源 | 结论边界 |
| --- | --- | --- |
| 完整输入标志、矩形、触摸区域 | `InputWindowHandle.inputConfig/frame/touchableRegion`；JNI 从 SF `WindowInfo` 复制 | 是进入 Populator 前的 SF 元数据；frame 不是 ForTest 再变换的 bounds，也不保证等于 WMS 最初提交的矩形 |
| 包、UID、类型、显示器 | `packageName/ownerUid/layoutParamsType/displayId` | 包名来自 WMS LayoutParams，UID 来自调用者 Session；不靠窗口标题推测，仍不是独立的 IME 服务组件证明 |
| 同进程窗口对象身份 | `getWindowToken()`；JNI 将 `WindowInfo.windowToken` 转为 Java IBinder | 可在同一接收进程保有并比较真实 Binder；不比较跨进程字符串、hash 或原生指针 |
| 四项过滤条件 | Populator 的 NOT_VISIBLE、CLONE、空 touchableRegion、空 frame 检查 | 能对新采集行计算条件；不证明旧失败时刻 Populator 收到了这行或因此丢弃它 |

关键链路：`Session` 从 `Binder.getCallingUid()` 固定 UID；`WindowState` 将 Session UID、`mAttrs.packageName/type` 写入 handle；`InputMonitor` 将 `mClient.asBinder()` 写入 **windowToken**，另将输入通道写入 **token**。这两个 token 不是同一个标识。JNI 把上述属性及 IWindow Binder 交给原始 Java 回调。

ForTest 的 `buildParams()` 遍历原始 handles，保留 `getWindowToken()`，变换 bounds 并派生部分布尔值。**ForTest 不是 Populator 过滤后的列表**。raw 与 ForTest 的 Binder 相等可以验证同一窗口对象，不能直接证明过滤发生；两个监听器也没有在 Java 接口中暴露共同的 SF 更新编号，时间接近不构成同一原子快照。

## 为什么选择独立 shell 诊断

`WindowInfosListener` 是隐藏类，Java 的 `public` 修饰符不等于公共 SDK 或 TestApi。已核对的 SDK、SystemApi、TestApi 以及 InputManager、WindowManager、SurfaceControl 接口没有提供可供当前普通 APK 使用的等价原始枚举入口。继续调用 ForTest 本身不会补齐五个缺口。

原始监听器的注册注释明确限定 system_server 与 shell，并要求 `ACCESS_SURFACE_FLINGER`；固定 Shell manifest 声明该权限。非 zygote 的 `app_process` 进入 `RuntimeInit`，固定 ART 的 HiddenApiPolicy 默认值为 disabled。这解释了为何 shell 工具值得验证，但不等于设备已经授权或实际访问成功。`AndroidRuntime` 还读取 `dalvik.vm.extra-opts`，实际运行必须保存启动参数与该属性的有界检查结果，不能仅凭命令存在推断执行环境。

下一实验使用正常 shell 工具进程，不修改系统设置、不添加 VMRuntime 豁免、hidden-api 启动选项或替换 boot classpath，不以 root 身份运行。该诊断域与普通生产 APK 的能力分别报告；上一轮应用进程的 SF 权限拒绝也不能直接外推为 shell 拒绝。

## 候选选择与身份判定

在指定用户和显示器下，先读取当前配置 IME 组件、包和实际 UID，并保留读取时刻。原始回调只按 **包名相等、UID 相等、类型为 TYPE_INPUT_METHOD（2011）、displayId 相等** 收集候选。它的准确名称是“本次 SF 回调中匹配配置 IME 包与 UID 的输入法窗口候选”，不是“配置服务组件的 IWindow 已独立证明”。同包多个服务、旧窗口残留及采样间配置变化仍有边界。

必须分别记录候选行数、空 token 数、不同非空 Binder 数。只有完整读取、身份来源有效、配置前后检查一致、无空 token 且只有一个不同 Binder 时，才可称为唯一窗口候选。**一个 Binder 可对应多行或克隆层，全部保留。** 多 Binder、空 token、零候选、超限或配置变化分别报告，不能用名字或最近一行消歧。前后配置相同也不能证明中间从未切换。

身份选择不得提前剔除 NOT_VISIBLE、CLONE、空区域或空 frame，恰好这些行可能解释新的诊断现象。记录每行原始 inputConfig 和四项谓词，不能只保存“通过过滤”的行。原始监听本身仍只保证 SF 的窗口子集；零候选不能证明 WMS 中不存在输入法窗口，初始缓存为空也不能这样解释。

若使用 ForTest 做身份正对照，同一进程直接比较仍持有的实际 Binder，分别保存两边回调时刻和匹配数。导出只使用本次进程内分配的候选编号，不导出 token 文本或把编号当跨运行身份。它不连接旧 WMS 文本，不补写旧矩阵的判定证据。

## 已排除的替代路径

| 候选路径 | 已核实的断点 |
| --- | --- |
| InputDispatcher 的 token 文本 → WMS IWindow | native dump 的 token 是进程内输入通道指针；真正的转换在 WMS LocalService/WindowManagerInternal，未通过已检查的 IWindowManager AIDL 导出 |
| AccessibilityManager 的 token → windowId | 服务端要求 SYSTEM_UID；shell 权限委托不能变成该 UID |
| 从最终无障碍 windowId 查询 token | 最终清单不能提供遗漏窗口的 ID；即便内部存在注册 ID，查询仍要求最终 A11yWindow 存在，不能取回该遗漏窗口 |
| `getSurfaceControlInputClientToken(surface)` | 只查本进程已注册 receiver；既不枚举外部 IME，也不提供它的 SurfaceControl |
| TaskOrganizer 的 IME layering target | 返回目标任务的 WindowContainerToken，不是 IME 自身 IWindow |
| WMS/SF proto 或 layerId 拼接 | 已检查 proto 没有同时导出完整原始条件与 IWindow；layerId、包或形状相似均不补成 Binder 身份 |

协作测试 IME 可以从已附着的 decor view 通过公共 `getWindowToken()` 取得自身 IWindow，并经受限 Binder IPC 交出，作为另一种强身份正对照。但当前真实输入法没有已知协作出口；更换输入法产生的是新实验，不能证明原输入法失败的原因。原始包/UID/类型路径无需将这一改造作为前提。

## 下一项实验的验收合同

下一项是一个独立、有界的 **raw 注册与候选字段正对照**，元数据检查作为其准入阶段，不再将一次声明查找单独算作窗口诊断完成。实施时冻结可执行源码、固定成员目录、命令及数值预算，再运行一次；本文件没有声称这些代码已经实现。

1. 在专用 API37 模拟器中绑定源码、诊断 dex、实际 UID、构建指纹、启动参数和配置来源。先查必要签名与访问条件，包含容易遗漏的 `packageName`；缺失、拒绝、签名变化或环境不符合条件即停止，并归档所到阶段。
2. 所有准入条件满足后，在同一次预声明实验中至多注册一次原始监听器。初始缓存与自然回调分别记录；借助明确标注的受控编辑界面/软键盘展示提供正对照，不触发原 Notes v4 流程，也不增加其 BACK、读取或等待次数。构造会创建 native 对象，注册失败可能终止进程，缺失输出须作为失败而非空窗口保存。
3. 回调只复制固定字段和有界候选数据，立即返回；不能在回调里执行 shell、等待另一监听器或写大文件，以免阻塞 SF ACK。固定保存条数、单次 handles 上限、总时限和输出上限；任何溢出保持不完整，不在截断前缀上宣称唯一。必须保留实际耗时，期限不能保证中断已进入的同步 native 调用。handles 上限约束 Java 处理与归档；JNI 在回调前已转换数组，不能声称该上限限制了 JNI 分配。
4. 完整记录候选选择、原始四谓词、初始/回调差异、注销和退出结果。没有回调、无候选和字段不可用都可产生有效的失败诊断，不能重注册或反复操作至成功。若增加 ForTest，单列其注册预算与同窗正对照，不能将其输出当最终无障碍清单。
5. 首个实测通过仅验收这个 API37/构建/配置 IME 的诊断入口；之后才设计原失败冻结后的独立采集和 API36 适配。仍需新证据定位 Notes 原因、实施修复并复测，才能关闭原失败。

来源核查本身未运行 API37 设备探针，也未采集该实验的窗口数据或截图。本次独立 API35 整体录像已完成，来源与结果见下节；Fixture 演示不充当本页 shell 实验的证据。


## 本轮整体录像：已归档

本页来源审查提交 `dc9326735157345e94af5181cebe895b5b8586a1` 的[整体录像](../../screenshots/demos/41218742663-1/demo.mp4)来自[运行41218742663](https://github.com/ecojust/Agent-OS/actions/runs/41218742663)，归档运行41219142131。视频38.130秒、640×1280、451,726字节、513帧，13阶段/8确认通过；30载荷/1,185,549字节、22PNG与来源、摘要、完整解码及关键画面均已核验。详见[原清单](../../screenshots/demos/41218742663-1/manifest.json)、[来源/合同审核](diagnostics/window-infos-raw-source-2026-10-31/overall-video-audit.json)和[独立审计](diagnostics/window-infos-raw-source-2026-10-31/independent-video-audit.json)。

录像展示API35 Fixture及本地合成HTTP模型，没有运行本页API37 shell监听实验。历史cdea就绪失败保留，本次成功不构成稳定修复；原Notes32项本轮没有重跑。README每类保留最新实际影片，真实APK视频仍绑定72f源码。


## 后续实现

[有界原始窗口采集](window-infos-raw.md)已实现单次准入、注册、受控键盘请求、原始候选采集和清理；本地协议/构建验证与实际设备运行分别列在该页。后续2eeb源码首次API37注册与采集成功，但18条候选区域全部为空，正对照0通过/1失败；实测原件与时序见该页，未关闭原Notes失败。本页仍是固定源码可行性记录，不升级为设备证据。
