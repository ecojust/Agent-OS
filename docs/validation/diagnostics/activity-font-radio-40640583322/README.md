# MainActivity 字体专项：原始基线准备失败

源码 `8b97d5dda743224e4998a1349d29015f217a24b8`（`master`）的[专项运行 40640583322](https://github.com/ecojust/Agent-OS/actions/runs/40640583322)，attempt 1，范围为 `activity-font-scale-diagnostic`。build 通过，API30、31、32、33、34、35、36、37.0 八档均失败。

原 `Exercise Android execution boundaries` 日志每档记录 `Starting 1 tests` 和 `Finished 1 tests`，共 8 项。每档 `Print Android failure details` 从 1 份 Android 测试报告打印 1 条 `failure`，合计 8 条 failure、0 条 error。这里没有下载或重新解析原 JUnit XML，也不是完整 JUnit 报告；跳过和缺失数量留待归档摘要、逐案记录独立核验。本专项不计作完整回归通过。

八档失败位置一致：`phase=baseline,requests=0,dispatchAttempts=0`，原始 cause 为 `AssertionError`，调用点是 `seedDraft(MainActivityFontScaleInstrumentedTest.java:191)`。该行断言连续导航 RadioButton 的 `performClick()` 返回值。原连续报告均没有 suppressed 异常。失败发生在初始草稿准备，尚未到达后续系统字体 `1.3` 的真实 Activity 重建检查，因此本次结果不能说明该重建或草稿保留的生产行为失败。返回值与实际选中状态的关系需另行审查；本目录保留原失败，不改写为通过。

本目录八份 `api*-failure.txt` 均从对应原 ZIP 日志成员的 `Suite` 行连续保留到 `Parsed` 计数行，未收录前面的环境变量区。原时间戳、内容、行末空白和换行按字节保留；日志原有的 `... 33 trimmed` 等内容也保持原样，不能把该输出称为未裁剪的完整运行时栈。

[manifest.json](manifest.json)记录精确 source、scope、运行与 job、原整次 ZIP 及日志成员的大小/SHA-256、成员内起止行，以及每份摘录的大小/SHA-256。`sourceTrailingWhitespaceLinesPreserved` 指向原成员行号；其中 `Kind` 行的末尾空格属于原证据，未作格式修整。仓库仅为本目录原始txt增加保留字节与行末空格的精确属性，不修剪原证据。
