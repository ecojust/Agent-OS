# 数值偏好完整回归中的 API 30 原始失败

被测源码 `e923dd2697bfde3d4d57e46f216d5933a7f0b878`，原完整运行 [40898449432](https://github.com/ecojust/Agent-OS/actions/runs/40898449432)，attempt 1，API 30 作业 `123533882040`。只读提取运行 [40900989201](https://github.com/ecojust/Agent-OS/actions/runs/40900989201)，检查作业 `123542900160`。保留 Checks 原始分片 `annotations.json`，按 `Android job diagnostic N` 的编号连接原消息得到 `failure.txt`；没有另行下载原 XML。

原报告有三项失败，原文均保留：

- `AgentControllerInstrumentedTest#realRotationRejectsOldApprovalAndKeepsControlsReachable`：`phase=old_observation_rejected`，原因为 `TaskControlException: 任务已停止`。原诊断记录 `dispatchAttempts=0`、`requests=1`、`running=false`，控制窗身份已失效且面板未附着。该项单独计为旋转失败，不由 SAF 节点诊断解释。
- `NumericPreferencesInstrumentedTest#realSafExportWritesTheCapturedFourFieldSnapshotWithoutConfigurationAuthority`：在导出选根步骤失败，尚未点击保存。
- `NumericPreferencesInstrumentedTest#realSafImportPreviewsAndAppliesOnlyFourUnsavedNumericFields`：在导入选根步骤失败，尚未选择输入文件。

两个 SAF 失败均为 `PROVIDER_ROOT / UNKNOWN_ROOTS_LAYOUT`，`idleObserved=true`、`actionAttempted=false`。新增诊断均为 `rootsLayoutDepth=0`、`rootsLayoutVisited=0`、`rootsLayoutMask=0`、`rootsLayoutExit=REFRESH_FALSE`、`rootsLayoutResult=UNKNOWN`：等待事件空闲后，对选中节点副本的首次 `refresh()` 已返回 false，尚未检查祖先布局标识。因此 `Mask=0` 不证明 drawer/rail 标识缺失。

这与本轮 API 31 两个 SAF 失败的直接拒绝点一致，未证明平台节点失效原因，也未证明旧轮同类错误具有相同根因。Provider 的 18/18、21/21 查询计数属于进程历史；安装和历史日志诊断不替代失败当时的节点有效性。不能由同轮包含旋转失败推断旋转导致后续 SAF 失败，也不能以之后的修正或成功改写本轮结果。
