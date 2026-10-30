# 目标进程与启动／完整帧诊断

本任务补采受控 Fixture 自身的 CPU、PSS、启动 TTID 与目标窗口 HWUI 帧耗时，属于[性能后续任务](../PERFORMANCE.md#性能后续任务)。本文记录冻结的采集合同及两轮试跑结果；已取得部分资源测量，但启动与正式诊断尚未通过，不能据此更新为已完成。最近更新时间：**2026-10-30 14:18:24 UTC**。

测量对象为独立测试 Fixture 页面，保留现有 Fixture 主页面和生产 APK 行为。八档专用模拟器分别为 API 30、31、32、33、34、35、36、37.0。结果始终为 `diagnosticOnly=true`、`budgetAcceptance=false`、`fullSuiteAcceptance=false`，不代表真实第三方应用、真机、厂商 ROM、实际屏幕呈现或性能预算已经验收。

## 固定配置与分母

只接受以下两个命名配置；`experienceProfile` 为 `pilot` 或 `formal`，不得从任意样本数参数派生正式配置，也不得按剩余时间动态减少任务。

| 配置 | 专项 scope | 每 API 启动计划 | 每 API 目标资源／帧计划 | 八档总计划 |
| --- | --- | --- | --- | --- |
| 合同试跑 `pilot` | `app-experience-pilot` | 冷／热各 2 对 A/B，共 8 次启动 | A/B/C 各 1 次，共 3 个任务 | 64 次启动、24 个任务 |
| 正式诊断 `formal` | `app-experience-diagnostic` | 冷／热各 100 对 A/B，共 400 次启动 | A/B/C 各 6 次，共 18 个任务 | 3,200 次启动、144 个任务 |

scope、配置、设备标签及完整计划数量必须由设备报告和宿主校验器双向核对。试跑通过只证明采集合同在该次运行中可执行，不能合并进正式样本、生成正式 P95 验收或关闭本任务。正常路径为八档试跑、定位及修复失败、固定源码正式八档运行，分别保留每次来源与结论。

启动与资源／帧为两组独立实验，不共用计时区间，不把 400 次启动当作 400 个资源任务。失败、未执行和未取得证据始终留在各自计划分母中；不得重试某个失败样本后仅保留成功替代项。修复后的新运行保留自己的源码、配置与分母，不能覆盖旧结果。

## 启动：A/B 冷／热 TTID

A 为 Agent 服务关闭；B 为服务开启、已连接且没有任务。服务状态准备及验证在启动标记之外完成。冷、热分别统计，每种模式的配对顺序固定交替为 AB、BA；正式各 100 对，AB／BA 各 50 对，每个状态各 100 个计划样本。试跑各 2 对，顺序为 AB、BA。首对保留，不删除所谓预热或异常慢样本。

冷启动先 force-stop 目标包并确认没有目标进程，再发启动请求；在此之前不得查询目标 ContentProvider，因为查询本身会启动目标进程。启动后记录新进程标识、Activity 实例标识、PID 与生命周期证据。这里的“冷”是目标进程不存在，不表示已清除文件系统缓存、ART 编译结果或系统其他缓存。

热启动先建立同一目标 Activity，将其移到后台并确认暂停／停止和不在前台，解析实际默认 HOME 应用并等待其获得焦点、目标确已停止；随后固定等待 1,000 毫秒并再次核验，才启动恢复。这些准备全部位于启动标记之外，两种状态一致；要求同一目标进程与 Activity 实例以及实际 resume／绘制证据。不得把重建 Activity 的暖启动归为热启动，也不得以已在前台的重复 Intent 充数。准备热启动所需的首次启动不进入测量分母，但须与测量启动可区分。

### TTID 来源与关联

主指标采用官方 Perfetto SQL 标准库 `android.startup.startups` 与 `android.startup.time_to_display`，不新增 Macrobenchmark 模块。宿主固定 [`trace_processor` v59.1](https://commondatastorage.googleapis.com/perfetto-luci-artifacts/v59.1/linux-amd64/trace_processor_shell)，二进制 SHA-256 为 `2434129a3af4dc9e2034a476f7b76447b490975213e301769310d3bd0d5d6458`；实际版本为 `Perfetto v59.1-1e9c61e98`，对应源码 `1e9c61e9844affd7dccd3d42d9ea9f3a3d10cf6d`。工具实际版本输出、下载来源和校验结果进入运行清单，不得在正式运行中隐式改用最新版本或修改官方 SQL 来补出缺失测量。新版派生 schema 2 固定增加 `--full-sort`，旧 schema 1 的原命令及原失败结论继续可复验。

该工具的 TTID 定义为系统启动起点至目标首个 UI 帧关联的 RenderThread `DrawFrame` 结束。它是可复核的 Android 启动渲染指标，不是屏幕物理呈现时间；不得把它描述为已测到 display present 或完整端到端显示响应。

每个测量请求都有闭合的驱动 trace 标记 `aos.experience.launch.<id>`，并在原始记录中绑定配置、API、模式、pair、顺序、状态、目标包、预期 PID、进程／Activity 实例标识和生命周期。宿主要求官方 startup、目标进程及首帧关联唯一且符合预期冷／热类型。首帧绘制结束必须落在本请求标记内，并且早于下一次启动；不能借用后续启动的帧补齐本次 TTID。仅按同包名取最近一条数据不足以通过关联校验。

`am start -W` 的原始 `Status`、`LaunchState`、`TotalTime`、`WaitTime` 和可取得的 `Displayed` 日志只作旁证。热启动复用路径可能直接返回 `TotalTime: 0`，命令返回也不保证 RenderThread 已完成产帧；这些值不能代替主 TTID。缺失标记、无匹配 startup／帧、PID 或类型不符、trace 丢失等均保留明确原因，不补零、不以命令成功当测量成功。

### 启动统计

按 **API × 冷／热 × A/B** 独立报告原始样本数量、中位数和 P95。每个所报告场景至少取得 100 个有效原始 TTID 才能报告其 P95；正式计划刚好达到此下限，因此任一缺失或无效样本都不能从分母删除后继续宣称正式采集完整。试跑只报告中位数，P95 明确待验收。不得跨 API、混合冷／热、拼接不同源码或合并试跑来凑数。

P95 使用升序原始值的 nearest-rank，索引为 `ceil(0.95 × n)`，并记录方法。A/B 的中位数差与 P95 差分别计算，配对 B−A 的分布另外报告；“配对差的 P95”不是“两组 P95 之差”。同时保留 AB／BA 分组以显示顺序效应，不报告缺乏样本依据的 P99，也不凭模拟器差值宣布预算通过。

## 目标进程：A/B/C 三写入任务

每个任务从独立页面的相同初始状态开始，依次输入固定文本、保存文本、将计数增加一次，最后独立核对输入、保存结果与计数。A 和 B 都通过真实无障碍节点执行相同的三次写入，区别仅为 Agent 服务关闭或空闲；C 使用真实 Controller、生产授权／确认／派发路径及固定本地测试模型完成相同目标。平台接受次数、模型确认写入次数与最终 UI 验证分别记录，不混作同一指标。

正式六轮顺序固定为 **ABC、BCA、CAB、ACB、CBA、BAC**，每个状态在各位置各出现两次；试跑仅执行 ABC。每次均重新准备相同初始目标及配置，保留第一轮。三次写入不重放未知结果，不关闭确认、不清除安全窗口标志、不强制 GC；沿用并记录专用模拟器统一的 1.0 动画基线，不按状态关闭或调整动画来改善结果。

Fixture 通过受限测试 provider 在自身进程读取 `Process.getElapsedCpuTime()` 与 `Debug.getMemoryInfo()`；请求验证实际 Binder 调用方 UID、固定 authority、会话标识和操作白名单，数据及分页有明确上限。Agent 仪器进程与目标 Fixture 进程分开保存端点，不把 Agent 自身测量或 shell 进程 CPU 冒作目标进程成本。

目标开始端点先读取 PSS，再取 CPU／时间标记；结束端点先取 CPU／时间标记，再读取 PSS。昂贵 PSS 读取在任务 CPU 窗之外，探针与 Binder 的剩余开销仍须披露。记录采样时间和读取耗时、PID 与进程标识；进程在任务内变化、累计 CPU 倒退或时钟不一致则该区间无效。

CPU 主指标为每任务累计 CPU 毫秒及实际任务墙钟毫秒；需要百分比时采用 `100 × CPU毫秒差 / 墙钟毫秒差`，即一个逻辑核心满载为 100%。保留有效的零量化 CPU 差。不同状态耗时不同时，不能把百分比差直接解释为净开销；累计 CPU 差、实际耗时、PSS 端点及配对差分别展示。

PSS 仅为任务前、后端点，另记可取得的 Java／Native／图形等分类，不能把相互重叠分类重复相加。端点不是稳态、瞬时峰值或完整内存曲线，负差值也不是节省内存的证明。Fixture 探针、Agent 仪器、本地模型、服务残留、缓存／GC 和主机噪声均属于本次诊断条件，尚未建立产品净成本的因果结论。

## 目标窗口 HWUI 帧与刷新率

任务前在专用 HandlerThread 注册 `Window.OnFrameMetricsAvailableListener`；回调只复制原始标量，避免在回调内执行 JSON、日志、PSS、IPC 或统计。FrameMetrics 对象由平台复用，不能保留可变引用后再读取。每条原始报告保留本次会话／窗口／任务标识、接收时间、两个 VSYNC 时间戳、下表所列耗时字段、首绘标记、丢报告数、应用当前刷新率及 active display mode 刷新率。

| 字段 | 本专项口径 |
| --- | --- |
| `TOTAL_DURATION` | HWUI 报告的 `FrameCompleted−IntendedVsync`。API 30 的完成点不保证 GPU 已完成；全部 API 均不等于实际 display present。 |
| `GPU_DURATION` | API 31+ 的平台 GPU 完成相关时长；不是 GPU 忙碌时间，平台版本间起点存在变化，不跨 API 混合统计。API 30 为 unavailable。 |
| `DEADLINE` | API 31+ 每帧获分配的预算，单列 `TOTAL_DURATION−DEADLINE`。该派生值不命名为 Macrobenchmark `frameOverrunMs`。API 30 为 unavailable。 |
| `FIRST_DRAW_FRAME` | 新窗口的首绘标记，单独统计并从非首绘耗时／慢帧比例中排除，不据此计算 TTID。 |
| VSYNC 时间戳 | 使用 `System.nanoTime()` 的单调时钟域，相邻时间戳之差不是帧渲染耗时。不得直接减 `elapsedRealtimeNanos()`。 |
| `dropCountSinceLastInvocation` | 丢失的指标报告数，不是屏幕显示丢帧数。 |
| 每帧应用刷新率 | 平台报告的当前应用刷新率快照，另保留 mode 刷新率与变更记录；不是实测面板物理频率。 |

刷新率变更带单调时间戳保留；存在跨帧变更或无法唯一归属的区间须标明不确定性，不能统一套用 16.67 毫秒。API 31+ 优先使用实际每帧 DEADLINE；API 30 可另列基于当时报告刷新率的名义周期超限比例，但不得冒称同口径 deadline jank。平台不支持字段明确为 unavailable，不补零或当作达标。

三次写入可能合并到同一帧，也可能伴随光标、布局等额外帧，因此按实际帧数报告；18 个任务不是 18 帧。本项衡量三写入工作窗口内目标 Window 的 HWUI 帧，不覆盖持续滚动、视频、整机合成或所有交互场景。

公开绘制时钟采用 `View.getDrawingTime()`：在自然 OnDraw 中记下版本和单调时间，待本次遍历结束后，通过一次异步 Handler 的 `postAtFrontOfQueue` 回调读取平台已更新的 drawingTime，再唯一匹配 FrameMetrics 的原始 VSYNC 毫秒部分。回调跨到后续 OnDraw、匹配歧义或缺失均保留不完整，不使用隐藏 API。该固定大小回调不生成新绘制、不自行重复调度，但会先于已积压的下一帧执行；所有状态使用相同探针，目标 CPU 包含其调度与读取开销。序号失配及非正时间分别计数，原有零未关联门槛保持。

任务窗口以自然绘制证据关联：被动记录 trial generation、UI 状态版本及对应 VSYNC；在自然绘制包含最终目标状态时冻结结束水位，再等待对应 FrameMetrics 报告及有界回调排空。语义状态核验完成不等于最终帧已完成，不能用主线程回调 CPU 耗时替代整帧。不得通过新增 `invalidate()` 补绘后宣称原任务末帧齐全；若采用后置采集标记，必须先冻结任务末帧，标记帧与额外 CPU 明确移出任务统计。

不以“收够 N 条回调”为结束条件；全部缓冲、分页、等待及排空有上限。丢报告、缓冲溢出、末帧水位未到或排空超时均记为采集不完整，保留已取得帧及失败原因。帧耗时、收到报告的帧数、首绘数、丢报告数及统计分母分别公开，不能用已收到部分的低慢帧比例证明全部窗口完整。

## 时间、trace 与证据容量

正式计划使用 2,400 秒专项 watchdog 与 55 分钟设备作业上限，为安装、启动模拟器、落盘及归档留出空间；这不是已证明的运行时长。2,400 秒除以 400 次启动仅为每次 6 秒，尚未扣除 18 个资源任务、trace 落盘、准备及环境恢复；例如这些固定成本合计 600 秒时，启动均值须不超过 4.5 秒才可能在剩余窗口内完成。

试跑必须记录各档启动／准备、资源任务、trace 落盘／提取及清理的实际耗时和字节数，按最慢档推算正式容量并预留余量。不得把八档并行的总墙钟时间当作单 API 作业预算。如果试跑表明上限不足，应在正式运行前明确调整、记录原因并冻结；不得在正式运行中减少样本、延长个别失败样本或静默改变采集配置来制造通过。

新版每 API 整轮使用有界 streaming trace，两个 buffer 采用 `RING_BUFFER`，同时严格检查 overwrite／packet loss／解析错误，配置 `write_into_file`、定期 `file_write_period_ms`、8 MiB 内存缓冲及 64 MiB 原始文件上限。记录完整配置及实际字节数；达到文件上限、trace 丢包、缓冲覆盖或截断时报告不完整，不能以环形覆盖较早启动后仅解析尾部。压缩只改变归档方式，原始 trace 内容、压缩前后长度及 SHA-256 保持可核验；每 API gzip 附件计划上限 12 MiB，必须用试跑实测校准后固定。

新版配置仅采集 `am`、`view`、两应用 APP 标记及进程元数据。Android 11–16 发布源码和 main 的 HWUI 编译标签为 `ATRACE_TAG_VIEW`，因此真实 `DrawFrame` 不依赖 `gfx`；删除 `gfx/wm` 可避免大量 SF/GL 旁路事件，仍必须由各档实测确认必要 slice 齐全。旧两轮 `am/wm/view/gfx` 配置原样保留，不用新配置倒推旧数据。API 31+ 同时启用 `android.surfaceflinger.frametimeline`，为官方标准库提供相应帧关联数据；API 30 使用其实际可用的旧版帧路径，不伪造 FrameTimeline。数据源或关联不足时保留无效原因，不能用自造时间戳绕过官方 TTID。

目前尚无八档实际容量依据，不能预先宣称 35／55 分钟或上述大小一定足够。如果试跑显示上限需要调整，须在正式运行前记录并冻结。新增 app-experience 证据使用独立上限：每 API 16 MiB、整批 160 MiB；既有证据类别的上限不变。达到上限时先定位 trace 配置及真实数据规模，不截取 trace、删除原始样本或只归档统计结果。运行文档与清单记录独立附件的来源、名称、长度、校验和及可取得位置；附件失效或原始 trace 缺失时，明确标记可复核证据不完整。

根清单只引用有界原始文件，任务及启动样本逐项落盘，保存已提交前缀；初始为 incomplete，完整采集、宿主关联验证、统计复算和环境恢复通过后才有资格形成正式诊断通过结论。进程被终止时，未提交部分为未取得证据，不能推断从未执行。trace 停止失败或提前退出时仍尽力有界保存可取得的 partial 内容及错误，partial 永不参与完整采集验收。工具退出成功、设备测试成功与测量证据完整分别记录，任一失败都不能被另一项成功覆盖。

设备／系统构建、显示／刷新率、应用与测试 APK、源码 SHA、run／attempt／job、配置、实际命令、工具版本及 UTC 日期随证据保留。原始 TTID、CPU/PSS 端点、FrameMetrics 行和 trace 关联字段可独立复算；每 API 分别报告完整分母、无效原因与 AB／BA 或三态顺序分组。

只采序列前后和失败现场截图；同源普通操作演示另行录制并保留真实来源，不在正式计时期间运行 screenrecord，不冒称短录像覆盖全部采样。保护窗口、截图 blocked 和录制失败按原证据保留，不能为录像清除安全标志。

## 首轮试跑：启动前置失败

精确源码 `b993ed9ca7695e00b257d58189aa070fe73fd080` 的 [pilot 41065314486](https://github.com/ecojust/Agent-OS/actions/runs/41065314486) 为 failure，build 作业通过，八档设备作业均在 Perfetto 命令直接返回 1 后结束。没有执行仪器用例，没有目标进程／启动／帧样本，也没有 PNG；计划 **64 次启动、24 个任务全部未取得数据**。不能将这次前置失败解释为应用性能失败或八档设备断言失败。

[归档 41065888657](https://github.com/ecojust/Agent-OS/actions/runs/41065888657) 成功，原始提交 `eac67772d501cfb1fce0936138f7a34226677046` 的父提交为被测源码。[证据清单](../../screenshots/41065314486-1/manifest.json)的 **32 份载荷、32,000 字节**全部完成文件集合、长度、SHA-256 与来源核验。八档均为 `pid=null`、`ready=false`、`trace=null`；API 30–32 使用 `--background`，API 33–37.0 使用 `--background-wait`。原始错误只保存了退出码与命令摘要，没有 stdout/stderr，具体拒绝文本无法追溯。

[官方配置说明](https://perfetto.dev/docs/concepts/config#android)指出 Android 12 之前因 SELinux 限制应通过标准输入传配置；Android 12 起支持目录为 `/data/misc/perfetto-configs`。下一版统一改用 `perfetto --txt -c -` 并通过进程 stdin 传入固定配置，保存有界 stdout/stderr 和退出码。此修正有官方依据，但旧证据不足以断言八档均为同一 SELinux 根因；必须由新源码试跑验证。V2 采集清单保留旧 V1 失败证据的可读性，不重写首轮错误。修正版 759 项宿主检查全部通过，Fixture／AndroidTest 编译与 app lint 通过；第二轮设备试跑尚待执行。

独立审核还修正两项统计边界：API 31+ 的未知 DEADLINE 哨兵及非正预算不进入可比较分母，各自保留原因；结束后晚到回调携带的丢报告数单独保存，分页累计值取最大值，不能重复相加。原有晚到报告仍判采集失败，验收标准未放宽。本轮没有原始帧，无法据此判断上述边界实际发生过。

下一步运行修正源码的八档 pilot；成功后根据真实耗时和 trace 体积确认正式计划。首轮不能提供容量或性能估计，也不计入正式 P95 样本。

## 第二轮试跑：部分资源通过，启动诊断仍失败

精确源码 `5be02c1c4c2ddf0fe60e0a2c7e2eb5190eec03d8` 的 [pilot 41067237848](https://github.com/ecojust/Agent-OS/actions/runs/41067237848) 为 failure，build 通过，八档作业均未通过。API 36 在系统镜像安装时报 `ZipFile unknown archive`，未启动测试；后续端口连接失败是清理未启动模拟器时的现象，不能归因为应用行为。

其余七档实际记录 **56/64 次启动、21/24 个资源任务**。21 个三写入资源任务全部通过，84 个 Agent／目标进程 PSS 端点、63 个非首绘帧已独立复算；已查看 API 32 的结束图和 API 35 的失败现场，保存文字及 Count 1 与报告一致。63 帧中未观察到未知／零 DEADLINE 或晚到 drop；API 31、37.0 各有 1/9 帧超过平台 DEADLINE，其余支持档为 0/9；API 30 名义刷新周期为 0/9，不能与 DEADLINE 混作同一口径。

启动有效数仍为 **0/64**。设备端 35 个启动通过辅助检查、21 个 unavailable；后者最终均有真实自然绘制与 FrameMetrics，拒收原因是绘制时间回调未能关联更早的一次 OnDraw（20 个冷启动、API 33 的 1 个热启动），不能继续概括成“没有新帧”。原报告的笼统原因保留，新版分别记录真实新帧和观测失败，拆出序号失配与非正绘制时间计数，保持原门槛。源码与实际 trace 显示，首帧耗时数百毫秒后，积压的下一 VSYNC 可能先于普通异步 post 执行；新版通过上述一次队首回调修正读取顺序，不强制绘制。

七档 trace 均取得，但两个 `DISCARD + write_into_file` buffer 均被官方解析器标为 `config_write_into_file_discard` 数据丢失风险；API 31、35 另有排序错误。新版按官方建议改为 `RING_BUFFER`，仍要求实际零丢失；固定 `--full-sort` 的独立重放消除了后两档排序错误，启动起点和帧端点保持一致，旧结果不改为通过。API 33 的另一个热启动在官方启动区间结束后约 2 毫秒才 resume，官方类型为 null，`am` 为 UNKNOWN；虽为同一 Activity 恢复，也未强写 HOT。新版在 HOME 后增加上面的焦点与停止稳定准备，再按原要求核验官方类型。

旧宽类别 trace 原始体积每档 **6,156,835–28,276,362 字节**，不能直接扩大到 400 次启动。逐包分析发现大头为 SF、SystemUI、Launcher 的 ftrace print；新版基于 HWUI 的 VIEW 编译标签删除 `gfx/wm`，实际减量和完整性仍需第三轮 pilot 确认，不以截断或删除旧 trace 控制体积。

[归档 41067994168](https://github.com/ecojust/Agent-OS/actions/runs/41067994168) 成功，固定提交 `9bc6a6f91ac2455f15fea186590afadf608e16c7` 的父提交为上述源码。[清单](../../screenshots/41067237848-1/manifest.json)的 **165 份载荷、24,181,164 字节、15 张 PNG**全部核验；6 条 blocked 原样保留。成功 PNG 明确未清除安全标志，blocked 记录缺少该字段，不将缺字段冒称逐项 false。独立审计复算 18,886 个结构及派生字段一致，不把这些字段数当作实测样本数。原版摘要与所有原始失败均保留。

新版报告／探针 schema 2、采集清单 schema 3（configVersion 2）及派生 schema 2 明确区分修正前后合同；前两轮仍按旧版本严格复验，不修改原始载荷。第三轮修正版 767 项宿主检查全部通过，Fixture／AndroidTest 编译及 app lint 通过，原 273 个完整回归身份不变。下一步第三轮八档 pilot，随后才可冻结正式容量并执行 3,200 次启动与 144 个资源任务。

## 当前进度与完成条件

当前已实现独立 Fixture 采样、固定 pilot/formal 设备驱动、有界 Perfetto 录制与官方 TTID 提取、严格分片校验及归档；原完整回归的 273 个测试身份不变。首版 `b993ed9ca` 的 750 项本地宿主检查全部通过（含固定官方解析器实际合成 trace 测试），Fixture／AndroidTest 编译与 app lint 通过；这些本地检查不算设备采样。八档试跑、容量确认、正式 3,200 次启动及 144 个任务、独立复算与实际证据归档仍待完成。下一步先验证冷／热生命周期与 Perfetto 一对一关联、自然末帧水位、实际刷新率及目标自采样，再依据试跑结果执行固定正式计划。

只有正式八档全部原始数据、100 样本／启动场景、帧采集质量、生命周期、三写入目标、来源链和恢复条件通过，才可将本项“受控 Fixture 目标进程／启动／HWUI 帧诊断”标记完成。试跑通过、设备端断言通过或已有其他性能专项通过均不能替代这些条件。真实第三方应用启动、实际 presentation、持续复杂绘制、目标稳态／峰值 PSS、真机功耗温升及原性能预算继续单独验收。

## 官方依据

- [Perfetto 启动识别](https://github.com/google/perfetto/blob/main/src/trace_processor/perfetto_sql/stdlib/android/startup/startups.sql)与 [TTID／TTFD 定义](https://github.com/google/perfetto/blob/main/src/trace_processor/perfetto_sql/stdlib/android/startup/time_to_display.sql)：正式运行由上述固定工具版本决定实际 SQL，链接用于解释口径。
- [AndroidX 启动等待实现及 `am start -W` 限制](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/MacrobenchmarkScope.kt)。
- [FrameMetrics 定义](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/FrameMetrics.java)与[公开观察者 `waitForPresentTime=false`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/FrameMetricsObserver.java)。
- [Android 11 的 GPU fence 完成限制](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/libs/hwui/renderthread/CanvasContext.cpp#545)。
