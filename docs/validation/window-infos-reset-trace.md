# API37 输入法重建调用路径的有界Trace诊断

本轮延续[离线时间线](window-infos-timeline.md)，核查输入法迟到有效区域之前的重建从哪里触发。原aee08实验与原Notes验收独立保留；原Notes仍24通过/8失败，5秒从确认准备开始，不能把独立fixture的show轴代入它。

## 从旧原件得到的新约束

旧协调器在A raw进程完成（设备elapsed 46458ms）后写入设置，在B raw进程启动（48018ms）前完成写入和读回。B的RESET_NEW_CONFIGURATION追踪起点为57083ms，所以设置写入到该追踪起点的间隔被夹在 **9065–10625ms**。这个范围来自执行顺序及两端设备单调时钟，没有用宿主UTC减设备elapsed。[独立原件复核](diagnostics/window-infos-reset-trace-2026-11-03/existing-evidence-review.json)保留推导与边界。

固定frameworks/base源码显示，ContentService可能把后台UID的ContentObserver通知延迟10000ms派发；是否延迟由通知时的进程状态与NOTIFY_NO_DELAY决定，到期不重新判断。SettingsProvider所用的旧notifyChange重载仅传入NOTIFY_SYNC_TO_NETWORK。已观测时间区间包含10秒，因此这是具体候选机制，但旧原件没有接收UID当时状态、通知入队身份和派发记录，不能认定它就是根因。

Tracker的RESET_NEW_CONFIGURATION只覆盖重建中部分show/hide分支。没有该reason不证明没有重建；一条reason也不能证明只调用过一次reset。两臂后置Configuration相同仍只是两个快照。

## 可支持的调用路径判据

固定源码有同步的IMS.resetStateForNewConfiguration、IMS.initViews、IMS.onComputeInsets Trace事件。需要纠正普通Service的类比：InputMethodService继承WindowProviderService，常规配置链是WindowContextInfoChangeItem → WindowTokenClient → WindowProviderService → InputMethodService，外层事务Trace为clientTransactionExecuted；普通configChanged不能当作它的默认入口。

本轮仅在同一IME进程的主线程、完整且一致的实际parent链中，重建slice被完整clientTransactionExecuted祖先包含时，标记 **configuration_dispatch_supported**。这一解释依赖所审固定源码及没有嵌套消息循环的边界，尚未逐字核验镜像与源码一致。没有该祖先、缺失或冲突时保持 **unknown**，不反推SettingsObserver。主Looper的Trace tag默认0，不能假定必有能识别ContentObserver Runnable的专属父事件。

[固定源码审阅](diagnostics/window-infos-reset-trace-2026-11-03/source-review.json)记录19份文件的URL、摘要、调用链及判据。原生IME tracing的where和配置快照也没有直接记录这两个reset调用者，因此不将它们当作入口标签。固定LatinIME的onComputeInsets先调用父类，再修改自己的几何与触摸区域；因此IMS.onComputeInsets slice只覆盖父类阶段，不表示最终区域已更新。实际区域仍以原raw回调和后置状态分别核对。

## 预先固定的采集范围

使用独立API37 x86_64模拟器，保持原A→B设置对照、同一fixture/probe字节、每臂一次show、一次注册/注销、12秒raw窗口和原四项有效区域条件。新wrapper在原比较开始前启动一次Perfetto，在原设置恢复后停止；不在原READY与show之间插入新操作。Trace观测有额外开销，不能把它视为与旧运行完全相同。

Trace最长120秒、原文件最多32MiB、缓冲共8MiB，固定am/wm/view和进程元数据；所有命令、输出、查询与文件总量另有界限。只停止记录了PID、进程开始时间和命令身份的自有采集进程，不使用root、全局kill或清空已有trace。提前结束、丢失、覆盖不足和来源不明分别保留；不追加显示请求、延长原raw窗口或重复执行来补结果。

采集状态、原A/B对照结果和调用路径证据分别记录。采集recorded不等于对照通过，也不等于已查明根因；B没有有效回调时仍保留失败。恢复设置以后产生的reset只记录为原观察窗口外事件，不能冒充B的迟到原因。

离线解析使用固定Perfetto v59.1处理器，保留查询原件及摘要。只有clock_snapshot/时钟元数据明确支持BOOTTIME映射时，才与原raw/fixture elapsed时钟关联；缺失映射时可以保留同trace的祖先关系，跨源时差与所属窗口保持未知。进程UID/包名、主线程、生命周期和采集覆盖均需核对，不能把进程名、token文本或Trace事件当作Binder身份。

## 实施与验证状态

[采集wrapper](../../scripts/window_infos_reset_trace.py)、[离线解析](../../scripts/window_infos_reset_trace_analysis.py)及[独立归档](../../scripts/window_infos_reset_trace_archive.py)已完成。[本地验证](diagnostics/window-infos-reset-trace-2026-11-03/local-validation.json)的新模块72项（23采集/23解析/26归档）、既有196项回归及workflow语法检查通过；[实现独立审查](diagnostics/window-infos-reset-trace-2026-11-03/implementation-review.json)核对最终源码、原21份执行源码未改、未知和恢复边界。[归档集成](diagnostics/window-infos-reset-trace-2026-11-03/archive-local-validation.json)实际调用prepare和临时Git来源校验，绑定26份生产源文件及15份Python执行文件；损坏派生分析保留invalid，不丢原Trace/对照，也不升级结论。

首版冻结源码为 `9ee0233a0db1fd79402f26eb13b80e40992828ae`。其唯一API37运行[41579567857](https://github.com/ecojust/Agent-OS/actions/runs/41579567857)在前置检查失败，[原始清单](../../screenshots/window-infos-reset-trace-41579567857-1/manifest.json)及[独立审核](diagnostics/window-infos-reset-trace-2026-11-03/first-attempt-audit.json)明确：只有6条只读命令，`perfetto --help`完成并输出4425字节完整Usage，正常退出码为1，采集器却要求0。A/B均为NOT_EXECUTED，设置未读写，Trace未启动，没有新调用路径结论。15索引文件65,261字节，含manifest共16个Git原件69,792字节；归档41579936245、提交fc9a81123bdf964b5706f8a1902ed3b1fceaea91，原字节保留。

修正仅为help命令识别完整Usage并接受退出0/1，运行准入与证据校验共用相同判据；其他命令仍要求0，15秒启动时限、12秒raw窗口和区域四谓词不变。[修正审阅](diagnostics/window-infos-reset-trace-2026-11-03/help-exit-fix-review.json)绑定实际原help输出及窄修正；真实help字节也作为独立测试fixture归档。修正后[79项宿主测试](diagnostics/window-infos-reset-trace-2026-11-03/help-fix-local-validation.json)通过（30采集/23解析/26归档）；该修正冻结为70b231c54af1ca10fd60de575aa9a58c2d9f0e78，已分别执行一次API37与整体演示。首版失败不替换、不计作完成的A/B实验。

首版同源API35整体[41579571277](https://github.com/ecojust/Agent-OS/actions/runs/41579571277)通过13阶段/8确认，[37.752秒录像](../../screenshots/demos/41579571277-1/demo.mp4)及[审核](diagnostics/window-infos-reset-trace-2026-11-03/overall-video-audit-41579571277.json)记录30载荷、22PNG、511帧完整核验。仅Fixture/本地测试模型，不代替未执行的专项或原Notes24/8。

## 第二版实际结果与配置输入问题

70b源码唯一API37运行[41580632157](https://github.com/ecojust/Agent-OS/actions/runs/41580632157)仍失败，[原始清单](../../screenshots/window-infos-reset-trace-41580632157-1/manifest.json)保留11条命令：完整help退出1已通过，794字节配置成功传入并核对SHA256，但start_trace在38毫秒后退出1，原输出为`The TraceConfig is empty`。A/B均NOT_EXECUTED，输入法设置没有读写，没有本地Trace或调用路径分析。配置文件已写入设备，未取得PID，因此没有发送停止信号或确认远端文件清理；也没有启动后的文件检查，不能把“本地无Trace”扩写成已证明远端文件不存在。归档41581046848、提交eea69017dfeda2e6b6201f6c137f2c2d629bde7c，[独立审核](diagnostics/window-infos-reset-trace-2026-11-03/second-attempt-audit.json)核对16个Git原件74,293字节、26份源码，失败原字节保持。

固定Perfetto实现先读配置，再后台化，不能归因为后台化先关闭stdin。旧成功工具通过ADB管道传入配置；本版使用远端shell重定向，继承的是普通文件描述符。现有记录没有SELinux AVC，不能确认具体拒读原因；下一修正将只替换配置输入传输并验证完整输入/EOF及原超时边界，不更改配置内容或原A/B实验。

70b同源API35[整体演示41580635370](https://github.com/ecojust/Agent-OS/actions/runs/41580635370)完成13阶段/8确认，[36.916秒视频](../../screenshots/demos/41580635370-1/demo.mp4)为421,635字节、501帧。[独立审核](diagnostics/window-infos-reset-trace-2026-11-03/overall-video-audit-41580635370.json)核对30载荷1,164,860字节、22PNG与31Git原件；暂停画面保留控制条，后段视频确认浮条消失。仅Fixture/本地测试模型、安全标志保持，不替代尚未执行的API37 A/B或原Notes24/8。

追加[真实处理器预检](diagnostics/window-infos-reset-trace-2026-11-03/real-processor-preflight.json)：固定v59.1对旧应用体验Trace的5,097,777字节原件执行当前六条SQL，均退出0且CSV结构解析通过，共98,307字节。该检查只核验处理器接口，没有加载A/B上下文或做重建归因，不能替代本轮专项。

[原生接口与PIPE修正独立审阅](diagnostics/window-infos-reset-trace-2026-11-03/native-interface-and-pipe-review.json)已完成，绑定固定Perfetto、政策与参考kernel源码；镜像逐字对应及本次SELinux拒读原因仍未验证。配置输入修正已完成：start直接调用ADB的Perfetto命令，通过专用有界管道传入同一794字节配置，记录配置摘要、宿主实际写入量和EOF；仅exit0且完整输入才继续核对PID。宿主写入记录不冒充设备消费确认。原push/digest、15秒启动、12秒raw和身份/停止/恢复合同均保持，未增加失败回退。[88项整合测试](diagnostics/window-infos-reset-trace-2026-11-03/pipe-local-validation.json)通过（39采集/23解析/26归档），包含真实子进程的完整字节/EOF、超时、输出上限、取消、BrokenPipe及父进程先退出时的自有组清理。接着冻结该修正，分别执行一次API37及整体录像；原两份失败保持原源码与结果。
