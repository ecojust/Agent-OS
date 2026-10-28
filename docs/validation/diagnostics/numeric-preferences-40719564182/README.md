# NumericPreferences 首轮失败诊断（40719564182）

对应 [原运行](https://github.com/ecojust/Agent-OS/actions/runs/40719564182)，源码 `41e44b00069b0b58725f8e8de89afa5cb9bcf4f9`，attempt 1。固定分母仍为270例/API、8档共2160例，原8个排除身份未变。此次全套未通过。

两种统计口径分别保留：逐案证据账本记录 **2057通过、37失败、66个预期身份缺失**；可用 JUnit 仅覆盖API30/31/33/34/35/36/37.0，共1890例、17 failure、0 error/skip。API32没有JUnit，不能把其控制台进度合成为JUnit报告，也不能把66个缺失身份记作通过或跳过。新增六例跨八档的逐案记录为 **26通过、22失败**。

[manifest.json](manifest.json) 记录原ZIP和每份摘录的entry、连续行范围、字节数及SHA256。API30/31/34/35/36/37.0分别从首个 `Report:` 到末个 `Parsed` 连续提取原字节；API33无失败，不生成失败摘录。[API32超时片段](api-32-partial-timeout.original.txt) 是原exercise第688–949行的原字节复制；[66个缺失身份](expected-missing-40719564182.json) 也原字节保留。

API32在120/270、0失败的最近前置进度后，首先报告新broker重建用例的跨应用按键注入 `INJECT_EVENTS` 异常，随后19个具名失败均报MainActivity启动45秒超时。最后进度为204/270、20失败，随后触发1200秒期限并exit124；没有Finished行或XML。首错完整调用栈、失败清理状态和超时瞬间正在执行的用例均未由此控制台证明，后续启动超时的因果仍未知，不能仅据时间顺序归因。

[API37.0原报告](api-37.0-failure-report.original.txt) 还保留既有 `ResolveNodeAtInstrumentedTest.uniqueActionCandidatesAreReadOnlyAndASelectedClickStillRequiresConfirmation` 的 `STALE_OBSERVATION` 失败（resolvedNode:263、case:51）。本次材料没有修复或排除该失败，也不证明任何后续改动已通过。
