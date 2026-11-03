# API37 输入法重建调用路径的有界Trace诊断

本轮延续[离线时间线](window-infos-timeline.md)，核查输入法迟到有效区域之前的重建从哪里触发。原aee08实验与原Notes验收独立保留；原Notes仍24通过/8失败，5秒从确认准备开始，不能把独立fixture的show轴代入它。

## 从旧原件得到的新约束

旧协调器在A raw进程完成（设备elapsed 46458ms）后写入设置，在B raw进程启动（48018ms）前完成写入和读回。B的RESET_NEW_CONFIGURATION追踪起点为57083ms，所以设置写入到该追踪起点的间隔被夹在 **9065–10625ms**。这个范围来自执行顺序及两端设备单调时钟，没有用宿主UTC减设备elapsed。[独立原件复核](diagnostics/window-infos-reset-trace-2026-11-03/existing-evidence-review.json)保留推导与边界。

固定frameworks/base源码显示，ContentService可能把后台UID的ContentObserver通知延迟10000ms派发；是否延迟由通知时的进程状态与NOTIFY_NO_DELAY决定，到期不重新判断。SettingsProvider所用的旧notifyChange重载仅传入NOTIFY_SYNC_TO_NETWORK。已观测时间区间包含10秒，因此这是具体候选机制，但旧原件没有接收UID当时状态、通知入队身份和派发记录，不能认定它就是根因。

Tracker的RESET_NEW_CONFIGURATION只覆盖重建中部分show/hide分支。没有该reason不证明没有重建；一条reason也不能证明只调用过一次reset。两臂后置Configuration相同仍只是两个快照。

## 可支持的调用路径判据

固定源码有同步的IMS.resetStateForNewConfiguration、IMS.initViews、IMS.onComputeInsets Trace事件。需要纠正普通Service的类比：InputMethodService继承WindowProviderService，常规配置链是WindowContextInfoChangeItem → WindowTokenClient → WindowProviderService → InputMethodService，外层事务Trace为clientTransactionExecuted；普通configChanged不能当作它的默认入口。

本轮仅在同一IME进程的主线程、完整且一致的实际parent链中，重建slice被完整clientTransactionExecuted祖先包含时，标记 **configuration_dispatch_supported**。这一解释依赖所审固定源码及没有嵌套消息循环的边界，尚未逐字核验镜像与源码一致。没有该祖先、缺失或冲突时保持 **unknown**，不反推SettingsObserver。主Looper的Trace tag默认0，不能假定必有能识别ContentObserver Runnable的专属父事件。

[固定源码审阅](diagnostics/window-infos-reset-trace-2026-11-03/source-review.json)记录19份文件的URL、摘要、调用链及判据。原生IME tracing的where和配置快照也没有直接记录这两个reset调用者，因此不将它们当作入口标签。

## 预先固定的采集范围

使用独立API37 x86_64模拟器，保持原A→B设置对照、同一fixture/probe字节、每臂一次show、一次注册/注销、12秒raw窗口和原四项有效区域条件。新wrapper在原比较开始前启动一次Perfetto，在原设置恢复后停止；不在原READY与show之间插入新操作。Trace观测有额外开销，不能把它视为与旧运行完全相同。

Trace最长120秒、原文件最多32MiB、缓冲共8MiB，固定am/wm/view和进程元数据；所有命令、输出、查询与文件总量另有界限。只停止记录了PID、进程开始时间和命令身份的自有采集进程，不使用root、全局kill或清空已有trace。提前结束、丢失、覆盖不足和来源不明分别保留；不追加显示请求、延长原raw窗口或重复执行来补结果。

采集状态、原A/B对照结果和调用路径证据分别记录。采集recorded不等于对照通过，也不等于已查明根因；B没有有效回调时仍保留失败。恢复设置以后产生的reset只记录为原观察窗口外事件，不能冒充B的迟到原因。

离线解析使用固定Perfetto v59.1处理器，保留查询原件及摘要。只有clock_snapshot/时钟元数据明确支持BOOTTIME映射时，才与原raw/fixture elapsed时钟关联；缺失映射时可以保留同trace的祖先关系，跨源时差与所属窗口保持未知。进程UID/包名、主线程、生命周期和采集覆盖均需核对，不能把进程名、token文本或Trace事件当作Binder身份。

## 实施与验证状态

[采集wrapper](../../scripts/window_infos_reset_trace.py)、[离线解析](../../scripts/window_infos_reset_trace_analysis.py)及[独立归档](../../scripts/window_infos_reset_trace_archive.py)已完成。[本地验证](diagnostics/window-infos-reset-trace-2026-11-03/local-validation.json)的新模块72项（23采集/23解析/26归档）、既有196项回归及workflow语法检查通过；[实现独立审查](diagnostics/window-infos-reset-trace-2026-11-03/implementation-review.json)核对最终源码、原21份执行源码未改、未知和恢复边界。[归档集成](diagnostics/window-infos-reset-trace-2026-11-03/archive-local-validation.json)实际调用prepare和临时Git来源校验，绑定26份生产源文件及15份Python执行文件；损坏派生分析保留invalid，不丢原Trace/对照，也不升级结论。

冻结源码后只执行一次API37专项，并独立尝试一次同源API35整体演示。当前尚无本模块的新设备结果，不提前关闭旧Notes、API行为矩阵或就绪失败。后续以实际归档补充源码、运行、Trace完整性、逐事件归因和剩余工作。
