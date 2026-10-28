# 原窗口事件测试：API36 原始失败

源码 `3cff90d62fe181fdee9c83b6348ea4becd6ac121` 的[完整回归40633417256](https://github.com/ecojust/Agent-OS/actions/runs/40633417256)在 API30–37 八档各执行246项，共1968项。归档逐案记录与采集JUnit摘要核对为1967通过、1失败，零错误、跳过和缺失；本轮没有完整通过结论。

[api36-failure.txt](api36-failure.txt)是 API36 原 `Print Android failure details` 日志成员第19–28行的连续字节摘录，从Report行保留到解析计数行。时间戳、原始Suite字段、内容、换行及第22行末尾空格均保持原样。[清单](manifest.json)记录准确源码、运行、job URL、原ZIP及成员的大小／SHA-256、原行号和摘录校验值。这不是独立下载或重解析的XML，也不是完整JUnit报告。

失败的Test字段及调用位置指向 `VisualEventGenerationInstrumentedTest.actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications` 第85行：原应保持的generation为42，断言时为43。日志中的Suite字段照原样保留，不据此改写实际失败用例。

现有摘录没有记录导致generation变化的具体事件来源或发生时序，不能推断是本轮字体恢复或其他特定原因。字体新增用例在本轮八档均通过，但这不抵消API36原窗口事件用例的失败。后续诊断与修复须另行留存，本目录不覆盖此次原始失败。

仓库仅对本目录原始txt摘录设置保留字节和行末空格的窄范围属性；源码及其他文档的空白检查不变。
