# MainActivity 字体专项：原始保留对象观察失败

源码 `2691e5d782c88dbefcc23bd93b627ea9e80bdf72`（`master`）的[专项运行 40641436998](https://github.com/ecojust/Agent-OS/actions/runs/40641436998)，attempt 1，范围为 `activity-font-scale-diagnostic`。build 通过，API30、31、32、33、34、35、36、37.0 八档均失败。

原 `Exercise Android execution boundaries` 日志每档记录 `Starting 1 tests` 和 `Finished 1 tests`，共 8 项。每档 `Print Android failure details` 从 1 份 Android 测试报告打印 1 条 `failure`，合计 8 条 failure、0 条 error。这里没有下载或重新解析原 JUnit XML，也不是完整 JUnit 报告；跳过和缺失数量留待归档摘要、逐案记录独立核验。本专项不计作完整回归通过。

八档失败位置一致：`phase=real_configuration_recreation,requests=0,dispatchAttempts=0`，原始 cause 为 `AssertionError`，调用点是 `assertDraft(MainActivityFontScaleInstrumentedTest.java:219)`。对照该精确源码，此处在等待实际新 Activity 达到 resumed、窗口就绪和异步设置绑定完成后，读取 `getLastNonConfigurationInstance()` 并要求非 null；实际读到 null。原连续报告均没有 suppressed 异常。

该位置之前的 UI 草稿值、执行模式、展开面板、密钥遮罩、无探测 owner 和编辑控件禁用状态保存等断言已经通过。因此不能将这条失败等同于 UI 草稿丢失。同时，这也不代表全部后续验收通过：保留对象身份与字段检查在此中断，后面的文字尺寸、可达性、finish 后新开回到 saved 等检查尚未完成。本目录仅保留实际观察时点的失败；框架清理非配置对象引用的生命周期时序需单独审查，不能通过删除原失败记录来替代验证。

本目录八份 `api*-failure.txt` 均从对应原 ZIP 日志成员的 `Suite` 行连续保留到 `Parsed` 计数行，未收录前面的环境变量区。原时间戳、内容、行末空白和换行按字节保留；日志原有的 `... 33 trimmed` 等内容也保持原样，不能把该输出称为未裁剪的完整运行时栈。

[manifest.json](manifest.json)记录精确 source、scope、运行与 job、原整次 ZIP 及日志成员的大小/SHA-256、成员内起止行，以及每份摘录的大小/SHA-256。`sourceTrailingWhitespaceLinesPreserved` 指向原成员行号；其中 `Kind` 行的末尾空格属于原证据，未作格式修整。仓库仅为本目录原始txt增加保留字节与行末空格的精确属性，不修剪原证据。
