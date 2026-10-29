# 数值偏好准备修正后完整回归中的 API 37 原始失败

被测源码 `309c2f759f14fabd44e268ef40fa9cb070016614`，原完整运行 [40906944025](https://github.com/ecojust/Agent-OS/actions/runs/40906944025)，attempt 1，矩阵标签 `37.0`，作业 `123564191158`。只读提取运行 [40909744778](https://github.com/ecojust/Agent-OS/actions/runs/40909744778)，检查作业 `123574159115`。保留 Checks 原始分片 `annotations.json`，按 `Android job diagnostic N` 的编号连接原消息得到 `failure.txt`；没有另行下载原 XML。

原报告仅有一项失败：`NavigationObservationGuardInstrumentedTest#anUnscopedSettingsWindowCannotBeReplacedByAnAutomaticLaunch`。断言为 `Automatic launch returned without the required source interruption`，堆栈指向 `NavigationObservationGuardInstrumentedTest.java:184`。

这不是旋转或 SAF 用例失败。原摘录没有动作返回对象、派发次数或最终目标窗口，不能据此声称自动启动实际成功，也不能推断本轮数值偏好准备修改造成该结果。该原始失败没有 `rotationTrace` 或 SAF `roots*` 字段；字段不存在不能写成计数为零。对应用例继续计失败，其他用例的结果与新 SAF 标记须分别依据完整逐案归档核验。
