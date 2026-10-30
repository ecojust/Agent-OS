# 固定稳定性样本第一轮：API 30 原始失败诊断

- 原始完整矩阵：[run 40975741234](https://github.com/ecojust/Agent-OS/actions/runs/40975741234)，attempt `1`，测试源 `8e8e00518b11f55cca4c7c24dd74c49a0e35cd21`。
- 原作业：[emulator-tests (30), job 123803209636](https://github.com/ecojust/Agent-OS/actions/runs/40975741234/job/123803209636)。
- 只读提取：[inspect run 40977345589](https://github.com/ecojust/Agent-OS/actions/runs/40977345589)，检查作业 `123808562745`；提取工作流源 `74090a97192e9b6dcf90c94f8457c5cc66fc86e6`，不是测试源。
- `annotations.json` 保留 GitHub Checks API 原响应；`failure.txt` 按 `Android job diagnostic N` 数字顺序原样连接 message，没有改写原始错误。诊断来自原 JUnit，含一条 failure/error；本目录不代替完整矩阵分母。

原失败为 `AgentControllerInstrumentedTest#realRotationRejectsOldApprovalAndKeepsControlsReachable`。阶段 `old_observation_rejected`，任务已因 `STALE_OBSERVATION` 失败，旧 `TYPE_TEXT` 的 submitted/accepted 都为 NO，派发为 0。新的 completed-event trace 保留 63 条、被覆盖事件 0，记录六次观察及六次 `OWNED_WINDOW_MISMATCH`、一次 `LOCAL_CONTROL_STALE`、零次 `OBSERVATION_REVISION_MISMATCH`；随后原 owner 的 `checkExecutionEpoch` 抛出任务停止异常。各 snapshot/trace/窗口字段是先后采样，不能当作原子状态；这些字段仍不能单独确定平台窗口失配的根因。

| 文件 | bytes | SHA-256 |
| --- | ---: | --- |
| `annotations.json` | 5651 | `5d5d797eb4717c9acbaf958ab28b9a73fc8e9bbac2917d5910d9f1a3ad462992` |
| `failure.txt` | 3831 | `412d957935a3b57e0c222dcde97bacc0bbda76a83148365f225924624c31bf26` |
