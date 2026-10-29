# 数值偏好完整回归中的 API 31 原始失败

被测源码 `e923dd2697bfde3d4d57e46f216d5933a7f0b878`，原完整运行 [40898449432](https://github.com/ecojust/Agent-OS/actions/runs/40898449432)，attempt 1，API 31 作业 `123533882608`。只读提取运行 [40900486492](https://github.com/ecojust/Agent-OS/actions/runs/40900486492)，检查作业 `123541112867`。保留 Checks 原始分片 `annotations.json`，按 `Android job diagnostic N` 的编号连接原消息得到 `failure.txt`；没有另行下载原 XML。

原报告有两项失败：

- `NumericPreferencesInstrumentedTest#malformedOversizedAndCancelledImportsLeaveTheOriginalDraftAndSavedStateUntouched`：堆栈指向取消分支的 `selectProviderRoot`，尚未调用 BACK。
- `NumericPreferencesInstrumentedTest#realSafExportWritesTheCapturedFourFieldSnapshotWithoutConfigurationAuthority`：在 `chooseExport` 的选根步骤失败，尚未点击保存。

两项均为 `PROVIDER_ROOT / UNKNOWN_ROOTS_LAYOUT`，`idleObserved=true`、`actionAttempted=false`。新增原始诊断均为 `rootsLayoutDepth=0`、`rootsLayoutVisited=0`、`rootsLayoutMask=0`、`rootsLayoutExit=REFRESH_FALSE`、`rootsLayoutResult=UNKNOWN`：等待事件空闲后，布局检查对选中节点副本的首次 `refresh()` 已返回 false，尚未检查任何祖先布局标识。因此 `Mask=0` 不证明 drawer/rail 标识缺失。

这确认本轮两案的直接拒绝点，未证明平台节点为何失效，也未证明旧 API 30/33 的同类错误具有相同根因。Provider 的 12/12、14/14 查询计数属于进程历史；安装及历史日志诊断不能替代失败当时的节点有效性。原失败保留，不将后续重新准备或成功结果写回本轮。
