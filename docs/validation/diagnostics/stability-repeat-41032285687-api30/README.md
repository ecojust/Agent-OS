# 第三轮固定复测的 API30 旋转失败

测试源 `710e54e701edb37a32efa28845f0cd5ed6ace936`，原运行 [41032285687](https://github.com/ecojust/Agent-OS/actions/runs/41032285687)，attempt 1，API30 原作业 [123984630786](https://github.com/ecojust/Agent-OS/actions/runs/41032285687/job/123984630786)。本目录保留 `AgentControllerInstrumentedTest#realRotationRejectsOldApprovalAndKeepsControlsReachable` 的原失败，不补跑或替换该样本。

本次新增诊断已记录拒绝瞬间的具体条件：**六次 `OWNED_WINDOW_MISMATCH` 均为 `BOUNDS_MISMATCH`，每次实际边界 `[110,36][532,150]`、预期边界 `[110,36][530,148]`，右边与下边各多2像素**。六次拒绝开始时刻相对trace原点为120、265、532、915、1448、2082毫秒；这些坐标来自原拒绝点，不是事后窗口采样。trace保留65条、覆盖0条，六个已结束的观察span总耗时30922微秒、最大16172微秒；另有一次 `LOCAL_CONTROL_STALE`，`OBSERVATION_REVISION_MISMATCH` 为0。已结束的span不等于成功观察。

失败阶段为 `old_observation_rejected`，终态 `FAILED/STALE_OBSERVATION`，owner已取消、epoch2；原冻结且匹配的 `TYPE_TEXT` 回执为 `RETURNED:STALE_OBSERVATION:submitted=NO:accepted=NO`。派发计数0、HTTP请求1，尚未出现新审批。首个local stale与预期旧写拒绝相容；随后六次精确边界不匹配导致新的观察无法完成。

后续 `owned`、窗口和系统动画转储属于原文明确标注的非原子后采样。控制窗已脱离、根缺失或 `mAnimRunning=false` 不能倒推拒绝时的完整平台状态，也不能单凭+2像素形态证明具体动画/缓存根因。现有精确边界、原观察预算和未派发保护没有放宽。

唯一只读提取 [41034214658](https://github.com/ecojust/Agent-OS/actions/runs/41034214658) 成功，提取工作流源同为 `710e54e701edb37a32efa28845f0cd5ed6ace936`。`annotations.json` 保存Checks API原始响应；`failure.txt` 依编号连接3个 `Android job diagnostic N` 消息，不裁剪空格、换行或原字段。它是既有检查工作流从原JUnit提取的有界片段，包含报告器原有堆栈裁剪标记，不是全部原始XML或完整作业日志。原作业日志下载端点此次未能直接获取；未用空文件冒充日志。

`job.json` 与 `inspection-run.json` 保留对应Actions API响应；`diagnostic.json` 仅从同一原文整理固定字段和六个拒绝事件，不是另一次采样。各材料的字节数与SHA-256见 [manifest.json](manifest.json)。本失败诊断不替代全矩阵逐案归档和完整性核验。

本轮[完整归档](../../../../screenshots/41032285687-1/manifest.json)已核验为 **2183/2184**：仅本API30旋转案失败，其他七档各273项通过。三个导航用例各8/8、视觉用例8/8（高四档实际事件、低四档不支持拒绝）、SAF48/48；历史失败按本源保留，不以通过项替换。
