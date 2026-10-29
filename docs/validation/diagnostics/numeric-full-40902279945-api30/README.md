# 数值偏好修正后完整回归中的 API 30 原始失败

被测源码 `42db0baf3528ff33dd1beaa4da1cb32dc54c16f4`，原完整运行 [40902279945](https://github.com/ecojust/Agent-OS/actions/runs/40902279945)，attempt 1，API 30 作业 `123547507356`。只读提取运行 [40904925894](https://github.com/ecojust/Agent-OS/actions/runs/40904925894)，检查作业 `123556966364`。保留 Checks 原始分片 `annotations.json`，按 `Android job diagnostic N` 的编号连接原消息得到 `failure.txt`；没有另行下载原 XML。

原报告仅有一项失败：`AgentControllerInstrumentedTest#realRotationRejectsOldApprovalAndKeepsControlsReachable`。本次摘录没有 SAF 用例失败条目，不能据此推断 SAF 的重新观察分支是否被实际触发；该问题需要逐案恢复标记与最终结果共同核对。

旋转失败仍处于 `phase=old_observation_rejected`，原异常为 `TaskControlException: 任务已停止`，`dispatchAttempts=0`、`requests=1`。新增失败分类保留以下原值：

- `failureSnapshot=NON_ATOMIC_BEFORE_WINDOW_DIAGNOSTICS`；各对象分别读取，不能视为失败断言瞬间的原子快照。
- `resultKind=FAILED`、`resultCode=STALE_OBSERVATION`、`statusClass=FAILED`、`summaryClass=OTHER`。
- `ownerCancelled=true`、`ownerEpoch=2`、`testThreadInterrupted=false`。
- `receiptOwnerMatches=true`、`receiptFrozen=true`、`receiptCount=1`。
- `lastReceipt=TYPE_TEXT:RETURNED:STALE_OBSERVATION:submitted=NO:accepted=NO`。

后续窗口诊断记录控制窗身份失效、面板未附着。上述记录确认本轮失败时任务终态和未提交回执，不能补造实际终止时序或把它解释为 SAF 节点失效原因。旋转仍计失败，原失败不因之后的修正或成功被覆盖。
