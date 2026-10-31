# Notes 创建确认的原读取诊断

本项推进[真实 APK 日常使用检查点](third-party-smoke.md)。历史 v4 八档固定 32 项有 8 个 Notes 失败：API31/32 发送一次已接受的 BACK 后耗尽四次隐藏采样；API36/37 未发送 BACK，也耗尽四次隐藏采样。现有明细来自失败后的截图检查，不能替代原四次准备读取，因此本次先补齐缺失的原时序证据。

## 本次实际结果

两批分别保留原源码与固定32项分母，没有相互补值。

| 原运行 / 源码 | 通过 | 失败 | 未验证 | 结论 |
| --- | ---: | ---: | ---: | --- |
| [41161477724 / ad0f](../../screenshots/third-party-41161477724-1/manifest.json) | 22 | 6 | 4 | API36磁盘不足未启动，其余六项Notes失败取得24次原隐藏读取 |
| [41163144860 / 435d](../../screenshots/third-party-41163144860-1/manifest.json) | 23 | 9 | 0 | 八档全执行；原八项Notes隐藏失败取得32次原读取，另API30标题输入前包含检查失败 |

本轮完成原八项隐藏失败的原读取诊断，尚未完成稳定修复或应用检查点验收。API35独立真实APK录像4/4与普通整体录像13阶段/8确认已归档，不并入上表。

## 本次固定范围

仍为官方 Calculator 1.4.0 和 Notes 1.7.0、API30–37 八档、Agent 关闭与绑定空闲两态，共 32 项。APK、签名、样本清单、v4 JSON/schema、四个实际方法、准入条件及失败结论均保持。诊断只修改 compat-smoke 测试 APK，不改 Agent 生产行为和第三方 APK。

创建确认仍共用原 5 秒期限，可见/隐藏各最多 4 次 WMS 读取，最多一次 BACK 和一次确认；原异包、身份、完全无遮挡及正向隐藏门槛不变。不增加 getWindows、getRoot、shell 或输入事件。新增元数据记录与时钟读取存在测试开销，仍计入原期限，不宣称时序零影响。

## 原读取记录与失败冻结

每次原有隐藏读取保存 WMS 解析阶段、固定身份核对、八个布尔字段的出现次数和固定值枚举、采样序号与读前/读后时刻。字段为 isOnScreen、isVisible、isReadyForDisplay()、mLastHidden、mEnterAnimationPending、mAnimatingExit、mHasSurface、Surface: shown；缺失、冲突和非布尔值明确标记，不保存任意原字段、窗口标题或正文。

复用同一次无障碍窗口清单，记录 IME 的窗口号、显示屏与层级，并按现有过滤顺序标为 SAME_WINDOW、OTHER_DISPLAY、BELOW_FOCUSED 或 CANDIDATE_REQUIRES_IDENTITY。最后一种仅说明需要继续验证身份，不是已授权 IME。每份最多保留 8 条，另记实际总数与未保留数。保留 BACK 决策清单、每个 WMS 样本之前的清单及最后一次清单；不同读取按序号和 SystemClock.elapsedRealtime 时刻分开，不声称原子快照。

失败时，在原耗时记账后、外层截图和清理前，冻结一条 `NOTES_CONFIRMATION_DIAGNOSTIC_V1` suppressed AssertionError。原 Throwable、主错误文本和 v4 JSON 不改；诊断最多 16 KiB，超限或序列化失败保留明确标记。实际数据通过原 instrumentation.json 完整输出归档，不能把诊断缺失补成通过。

## 验证与下一步

本地检查覆盖原异常/JSON/动作权限不变、4 次原样本与固定期限、完整八字段和最大数值容量、显式截断、冻结后不受修改影响，以及宿主归档保留完整 suppressed 输出。独立审查通过；compat-smoke 与测试 APK 构建、lint 通过，891 项完整宿主回归无跳过且全部通过（118.732 秒），其中相关归档检查为 34 项。第一批七档进入原四方法并执行初始化检查；API36在模拟器启动前失败，该档不能记成初始化或流程通过。第二批八档均实际执行，结果见下表。

本地检查后已在同一源码执行原八档32项，另独立执行API35四项真实APK录像与整体操作录像。各运行分母独立，录像不并入八档通过率，失败不替换。以下根据原拒绝读取分析；未降低隐藏门槛。

## 本轮运行与后续 CI 调度

固定源码 `ad0f0a723e2efbb146a06e173a0ebdecc6706ee6` 的[八档32项41161477724](https://github.com/ecojust/Agent-OS/actions/runs/41161477724)已完成，原结果为 **22通过、6失败、4未验证**。[原始归档](../../screenshots/third-party-41161477724-1/manifest.json)保留50载荷、2,210,433字节、28张计算器PNG及28条Notes blocked，[来源/载荷/逐案审核](diagnostics/notes-confirmation-41161477724/archive-audit.json)通过。14次官方APK安装、28次安装身份与服务设置恢复已核验；8项Notes通过有新进程完整标题/正文读回。API36没有启动，不补成安装或业务通过。

API36原[作业日志](diagnostics/notes-confirmation-41161477724/api36-job-original.txt)在02:58:16 UTC明确报告创建userdata分区磁盘不足：可用5,144.23 MB、需要7,372.80 MB，随后03:08:18启动等待超时；该档四项均未验证。[环境归因](diagnostics/notes-confirmation-41161477724/api36-environment-failure.json)保留原日志哈希、行号及时间。它不同于Notes实际操作失败。

[API35真实APK录像41161479506](https://github.com/ecojust/Agent-OS/actions/runs/41161479506)4/4及[整体录像41161481513](https://github.com/ecojust/Agent-OS/actions/runs/41161481513)13阶段/8确认均通过；两段实际视频、完整载荷及解码[审核已归档](diagnostics/notes-confirmation-41161477724/video-audit.json)，各分母分别保留。

后续第三方工作流的构建/探针改为只获取构建与验证所需目录，保留全部五个Gradle模块、根配置、脚本、文档及锁文件，排除历史screenshots；归档作业仍完整取出并切换到原测试源码。允许八个独立runner并行，固定八档矩阵、fail-fast=false、每档四方法及录像仅API35保持。已在真正的稀疏检出中通过100项第三方检查、31项录像检查及进度一致性检查，actionlint通过；未来运行耗时仍以实际结果为准。

## Android12/12L 源码调查

已核对固定 AOSP 版本的[隐藏字段与表面生命周期](diagnostics/notes-confirmation-41161477724/ime-hidden-research-summary.md)，并保存40份文件的[源码链接及摘要](diagnostics/notes-confirmation-41161477724/ime-hidden-source-index.json)。`isOnScreen`、`isVisible`、`isReadyForDisplay()`检查不同条件；Insets父层隐藏与子表面`shown=true`可以并存，非fullscreen的表面移除还有条件性的5000毫秒延迟。这为历史API31/32字段组合提供可检验机制，不证明测试镜像逐字采用该版本、当次进入缓存分支或屏幕已经没有键盘影像。

原诊断没有BACK按下/抬起的精确时间戳，只记录准备开始、清单与WMS读起止，不能计算精确“BACK后耗时”或倒推缓存计时已开始。本轮仍按原v4判定，下一步依据归档原四次样本分别核对过滤、身份、字段与时序；如需验证父层/Insets控制目标及延后移除，另立有界诊断，不增加本轮动作或改写原32项。

## 原失败数据已经确认的范围

[逐字段及时间报告](diagnostics/notes-confirmation-41161477724/notes-diagnostic-findings.md)、[完整原诊断及提取分析](diagnostics/notes-confirmation-41161477724/notes-same-read-analysis.json)与[历史32组合对照](diagnostics/notes-confirmation-41161477724/notes-historical-comparison.json)均保留。六项失败各有一条未截断诊断、四个原WMS样本，共24次；身份匹配、解析结构与顺序检查无异常，不是由失败后的截图采样回填。

| Notes组合 | BACK决策时的原清单 | 四次原隐藏WMS结果 | 实际结论 |
| --- | --- | --- | --- |
| API31/32关闭、空闲，共4项 | 完整清单有一个IME候选，原前置检查通过，BACK按下/抬起均接受 | onScreen=true、visible/ready=false，hasSurface/shown=true，lastHidden=false | 原严格隐藏条件未满足；确认0，仍失败 |
| API37关闭、空闲，共2项 | 完整扫描imeCount=0，没有被层级过滤的IME条目，BACK=0 | onScreen/visible/ready及hasSurface/shown均true | 清单缺项与另次WMS可见记录并存；原因尚未证实，确认0 |
| API36关闭、空闲，共2项 | 模拟器未启动，无原准备数据 | 未采样 | 保留未验证，不从另一轮补值 |

所有六项的隐藏前和最后保留清单也都是完整扫描、无IME条目，无BELOW_FOCUSED记录。因此“只调整IME与层级过滤顺序”没有本轮证据支持。四次WMS额度在准备START后2,573毫秒内用完，随后按原期限结束；没有记录BACK准确时刻，不能换算为BACK后2,573毫秒或认定缓存分支被执行。WMS token与无障碍窗口号没有记录共同标识，两种读取也不是原子同刻。

## 环境修正后的独立复测

第一批API36的明确磁盘失败使原读取证据不完整，因此在源码 `435d91b794ddc4efca52de56b4be4f31997639a3` 执行独立完整[32项41163144860](https://github.com/ecojust/Agent-OS/actions/runs/41163144860)。生产代码、compat测试、原采集/归档器、APK锁与v4契约相对ad0f[没有改动](diagnostics/notes-confirmation-41163144860/retry-context.json)；采用上述稀疏检出及八档并行，排除历史screenshots工作树。原API35录像仍标注ad0f，不冒充435d录像。

本轮 **23通过、9失败、0未验证**，32项全部执行：Calculator16/16；Notes7/16。16次官方APK安装、32次身份核验和设置恢复、7次Notes新进程完整标题/正文读回均核验。[归档](../../screenshots/third-party-41163144860-1/manifest.json)保留56载荷、2,493,872字节、32张计算器PNG和32条Notes blocked；[来源、全部载荷及原仪器审核](diagnostics/notes-confirmation-41163144860/archive-audit.json)通过。

API36在03:12:29 UTC启动成功，03:12:31实际调用探针，四方法均执行，计算器两项通过、Notes两项隐藏失败。[独立启动证据](diagnostics/notes-confirmation-41163144860/api36-start-evidence.json)与[原job日志](diagnostics/notes-confirmation-41163144860/api36-job-original.txt)绑定本轮源、时间及原文件摘要。这证明本次环境已恢复执行，不是Notes通过或长期磁盘稳定证明；第一批四项未验证保持。

[第二批逐项报告](diagnostics/notes-confirmation-41163144860/notes-diagnostic-findings.md)、[原诊断/32次WMS](diagnostics/notes-confirmation-41163144860/notes-same-read-analysis.json)和[历史32组合对照](diagnostics/notes-confirmation-41163144860/notes-historical-comparison.json)已独立复核。原API31/32/36/37八项隐藏失败再次出现；八条诊断均完整、身份和解析无异常。31/32保持一次已接受BACK、visible2/hidden4；36/37原决策及四次隐藏前、最后保留清单全部完整且IME=0，WMS三可见谓词仍true，均无BELOW_FOCUSED记录。所有原四次采样包装最晚在START+2,556毫秒完成，不能称为连续WMS观测满五秒。

新增的第九项失败为API30 Notes关闭态：原堆栈停在标题`locked_note_title`的完整包含检查，发生在该次`ACTION_SET_TEXT`提交之前。确认准备为NOT_STARTED，BACK/visible/hidden/confirm/HOME均0；原件未保留该次应用/目标矩形，不能判定空矩形、越界、动画、快照变化或时序回归。[独立边界分析](diagnostics/notes-confirmation-41163144860/notes-extra-failure-analysis.json)保留错误、堆栈及冻结源码哈希。它与八项隐藏失败分别记录，也不与已有旋转失败合并归因。

## 已缩小的下一步

固定Android16/17的[窗口来源核对](diagnostics/notes-confirmation-41161477724/ime-window-list-source-note.md)说明，无障碍候选来自输入窗口回调，并检查NOT_VISIBLE、CLONE、空触摸区域和空frame；它不直接等同于WMS的三个可见谓词。这解释了为何两种来源可以不同，但原数据尚不能判定当次在哪一层缺项。四文件[固定源码索引](diagnostics/notes-confirmation-41161477724/ime-window-list-source-index.json)已核验；未证明测试镜像与公开tag逐字对应。

下一步先保存API30标题包含检查的同次原矩形、窗口身份和读取次序；对API36/37，核对可用平台诊断中的原回调、筛选及共同对象标识；对API31/32，验证父层/Insets与隐藏缓存的实际生命周期。新增读取必须另立有界诊断，不能放宽现有隐藏、完整包含和遮挡守卫，不能重放标题输入或追加BACK，也不能用后续样本补写这两批。Agent执行态、升级、更多应用、真实模型和真机验收仍未完成。

## 下一步已启动：保留标题失败当次矩形

按[原矩形诊断方案](notes-target-geometry-diagnostics.md)已完成33ac源码两轮独立32项41165280718/41165282816，各24通过、8失败。API30两态两轮未复现，没有实际几何失败矩形；原八项隐藏失败每轮都有32次原WMS，31/32与36/37的分类保持。独立API35录像4/4及整体13阶段/8确认已归档。原错误、v4判定与操作额度保持，上述历史批次不改；下一步单独执行已核验接口的IME窗口候选关联采集。
