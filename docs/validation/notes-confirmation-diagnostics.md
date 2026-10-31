# Notes 创建确认的原读取诊断

本项推进[真实 APK 日常使用检查点](third-party-smoke.md)。历史 v4 八档固定 32 项有 8 个 Notes 失败：API31/32 发送一次已接受的 BACK 后耗尽四次隐藏采样；API36/37 未发送 BACK，也耗尽四次隐藏采样。现有明细来自失败后的截图检查，不能替代原四次准备读取，因此本次先补齐缺失的原时序证据。

## 本次固定范围

仍为官方 Calculator 1.4.0 和 Notes 1.7.0、API30–37 八档、Agent 关闭与绑定空闲两态，共 32 项。APK、签名、样本清单、v4 JSON/schema、四个实际方法、准入条件及失败结论均保持。诊断只修改 compat-smoke 测试 APK，不改 Agent 生产行为和第三方 APK。

创建确认仍共用原 5 秒期限，可见/隐藏各最多 4 次 WMS 读取，最多一次 BACK 和一次确认；原异包、身份、完全无遮挡及正向隐藏门槛不变。不增加 getWindows、getRoot、shell 或输入事件。新增元数据记录与时钟读取存在测试开销，仍计入原期限，不宣称时序零影响。

## 原读取记录与失败冻结

每次原有隐藏读取保存 WMS 解析阶段、固定身份核对、八个布尔字段的出现次数和固定值枚举、采样序号与读前/读后时刻。字段为 isOnScreen、isVisible、isReadyForDisplay()、mLastHidden、mEnterAnimationPending、mAnimatingExit、mHasSurface、Surface: shown；缺失、冲突和非布尔值明确标记，不保存任意原字段、窗口标题或正文。

复用同一次无障碍窗口清单，记录 IME 的窗口号、显示屏与层级，并按现有过滤顺序标为 SAME_WINDOW、OTHER_DISPLAY、BELOW_FOCUSED 或 CANDIDATE_REQUIRES_IDENTITY。最后一种仅说明需要继续验证身份，不是已授权 IME。每份最多保留 8 条，另记实际总数与未保留数。保留 BACK 决策清单、每个 WMS 样本之前的清单及最后一次清单；不同读取按序号和 SystemClock.elapsedRealtime 时刻分开，不声称原子快照。

失败时，在原耗时记账后、外层截图和清理前，冻结一条 `NOTES_CONFIRMATION_DIAGNOSTIC_V1` suppressed AssertionError。原 Throwable、主错误文本和 v4 JSON 不改；诊断最多 16 KiB，超限或序列化失败保留明确标记。实际数据通过原 instrumentation.json 完整输出归档，不能把诊断缺失补成通过。

## 验证与下一步

本地检查覆盖原异常/JSON/动作权限不变、4 次原样本与固定期限、完整八字段和最大数值容量、显式截断、冻结后不受修改影响，以及宿主归档保留完整 suppressed 输出。独立审查通过；compat-smoke 与测试 APK 构建、lint 通过，891 项完整宿主回归无跳过且全部通过（118.732 秒），其中相关归档检查为 34 项。Android 初始化边界检查仍需随实际八档运行确认。

完成本地检查后，在同一源码执行一次原八档 32 项；另独立执行一次 API35 四项真实 APK 录像与一次整体操作录像。各运行分母独立，录像不并入八档通过率，失败不替换。先依据拒绝当时的字段与层级判断原因，再决定修复；不根据历史失败后的采样降低隐藏门槛。

## 本轮运行与后续 CI 调度

固定源码 `ad0f0a723e2efbb146a06e173a0ebdecc6706ee6` 已启动[八档32项41161477724](https://github.com/ecojust/Agent-OS/actions/runs/41161477724)、[API35真实APK录像41161479506](https://github.com/ecojust/Agent-OS/actions/runs/41161479506)及[整体录像41161481513](https://github.com/ecojust/Agent-OS/actions/runs/41161481513)。八档仍在执行，原批次按启动时的三档并行计划继续，不取消、不替换。API35真实APK录像4/4及整体录像13阶段/8确认均通过；两段实际视频、完整载荷及解码[审核已归档](diagnostics/notes-confirmation-41161477724/video-audit.json)，各分母分别保留。

后续第三方工作流的构建/探针改为只获取构建与验证所需目录，保留全部五个Gradle模块、根配置、脚本、文档及锁文件，排除历史screenshots；归档作业仍完整取出并切换到原测试源码。允许八个独立runner并行，固定八档矩阵、fail-fast=false、每档四方法及录像仅API35保持。已在真正的稀疏检出中通过100项第三方检查、31项录像检查及进度一致性检查，actionlint通过；未来运行耗时仍以实际结果为准。

## Android12/12L 源码调查

已核对固定 AOSP 版本的[隐藏字段与表面生命周期](diagnostics/notes-confirmation-41161477724/ime-hidden-research-summary.md)，并保存40份文件的[源码链接及摘要](diagnostics/notes-confirmation-41161477724/ime-hidden-source-index.json)。`isOnScreen`、`isVisible`、`isReadyForDisplay()`检查不同条件；Insets父层隐藏与子表面`shown=true`可以并存，非fullscreen的表面移除还有条件性的5000毫秒延迟。这为历史API31/32字段组合提供可检验机制，不证明测试镜像逐字采用该版本、当次进入缓存分支或屏幕已经没有键盘影像。

原诊断没有BACK按下/抬起的精确时间戳，只记录准备开始、清单与WMS读起止，不能计算精确“BACK后耗时”或倒推缓存计时已开始。本轮仍按原v4判定，下一步依据归档原四次样本分别核对过滤、身份、字段与时序；如需验证父层/Insets控制目标及延后移除，另立有界诊断，不增加本轮动作或改写原32项。
