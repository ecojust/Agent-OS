# MainActivity 字体完整回归：原始设置恢复失败

源码 `b7bb92b438c5d8d3ebc0d72deec8c33e7689aab6`（`master`）的[完整回归 40643561197](https://github.com/ecojust/Agent-OS/actions/runs/40643561197)，attempt 1，范围为 `full`。build 通过，API30、31、34、35、36、37.0 六档通过，API32、33 两档失败。

原 `Exercise Android execution boundaries` 日志每档记录 `Starting 247 tests` 和 `Finished 247 tests`，共 1976 项。API32、33 的 `Print Android failure details` 各从 1 份 Android 测试报告打印 1 条 `failure`，其他六档打印零失败/错误，合计 2 条 failure、0 条 error。这里没有下载或重新解析原 JUnit XML，也不是完整 JUnit 报告；跳过、缺失和逐案身份仍需归档记录独立核验。本轮没有完整通过结论。

两个失败的原 `Test` 字段均指向 `MainActivityFontScaleInstrumentedTest.realSystemFontScaleRecreatesSecureActivityAndRetainsOnlyInMemoryDraft`。原 `Suite` 字段却是 `dev.agentos.app.AgentControllerInstrumentedTest`，摘录按原字节保留，未改写该字段。

原异常为字体设置恢复等待超时：`expectedRaw=absent,expectedScale=1.0,last={raw=1.0,serviceScale=1.0,applicationScale=1.0}`。精确源码对应 `OriginalFont.restore` 第198行调用 `awaitFont`，由测试 `finally` 清理的第152行调用。服务和应用的有效字体比例已观察为 `1.0`，但在原十秒恢复期限内，设置行仍为 `1.0`，未达到原始行缺失这一恢复目标。两项目标必须分别验证，资源比例恢复不能代替原设置行恢复。本目录不据此断言异步写回的具体来源或时序，也不将清理失败改写为通过。

本目录两份 `api*-failure.txt` 从原 ZIP 对应日志成员的 `Suite` 行连续保留到 `Parsed` 计数行，不含环境变量区。原时间戳、Suite 字段、调用链、内容、行末空白和换行按字节保留；它们是原 CI 脚本打印的连续片段，不是独立解析的原 XML。

[manifest.json](manifest.json)记录精确 source、scope、运行与 job、整次 ZIP 及日志成员的大小/SHA-256、成员内起止行，以及摘录大小/SHA-256。`sourceTrailingWhitespaceLinesPreserved` 指向原成员行号；`Kind` 行末尾空格保持原样。仓库仅为本目录原始txt增加保留字节与行末空格的精确属性，不修剪原证据。
