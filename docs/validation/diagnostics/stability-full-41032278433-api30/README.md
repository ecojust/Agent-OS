# 固定三轮复测第1轮：API30旋转拒绝瞬间的边界失配

测试源为 `710e54e701edb37a32efa28845f0cd5ed6ace936`，完整运行 [41032278433](https://github.com/ecojust/Agent-OS/actions/runs/41032278433)，attempt 1；原失败作业 [123984610957](https://github.com/ecojust/Agent-OS/actions/runs/41032278433/job/123984610957)。此处保存 `AgentControllerInstrumentedTest#realRotationRejectsOldApprovalAndKeepsControlsReachable` 的原失败，不以重跑替换，也不改记为通过。

阶段为 `old_observation_rejected`，终态 `FAILED/STALE_OBSERVATION`。原任务已取消、epoch2，派发0、模型请求1；原冻结回执为 `TYPE_TEXT:RETURNED:STALE_OBSERVATION:submitted=NO:accepted=NO`。这表明旧写明确未提交；之后的有限重新观察未取得可用的新观察。

本轮新增分类确实在原拒绝点取得数据：六个 `OWNED_WINDOW_MISMATCH` 全部为 **`BOUNDS_MISMATCH`**，每次的 **actual `[110,36,532,150]`、expected `[110,36,530,148]`** 相同，右边和下边各多 **2 px**，左/上相同。这些数字来自每次拒绝瞬间保存的有界 trace，早于后续窗口诊断，不是根据清理后窗口反推。六次观察 span 均结束；trace retained65、overwritten0，另有一次 `LOCAL_CONTROL_STALE`、零次 `OBSERVATION_REVISION_MISMATCH`，观察总耗时33384微秒、单次最大17835微秒。

这次已确定直接失败条件为精确边界不一致；仍不能单凭这些坐标判定平台缓存、旋转动画或其后通知何者是上游原因。后面的 `owned`、窗口列表及 server dump 是非原子的后采样；其中控件已脱离或 `mAnimRunning=false` 等字段，不能倒推每次拒绝瞬间的状态。原几何要求、两秒/六次观察预算及零派发断言未放宽。

材料来源：

- [failure.txt](failure.txt) 是 `gh run view 41032278433 --job 123984610957 --log` 得到的原作业日志中连续第877–889行，完整保留GitHub步骤前缀、时间戳及原字节，共5331字节。完整本地原日志161575字节，SHA-256 `208fe685f29d11ff3fccf01ca271bcd97455f23f69ac5cd97653fe374c59942d`。这是JUnit报告器输出的连续日志片段，不是原始XML。
- 只读检查运行 [41034228874](https://github.com/ecojust/Agent-OS/actions/runs/41034228874) 成功。[annotations.json](annotations.json) 保存Checks API原始响应；[inspection-failure.txt](inspection-failure.txt) 按 `Android job diagnostic N` 顺序连接三个消息片段，不修剪文字。其主异常消息与原作业日志完全一致，检查过程没有重新执行设备用例。
- [job.json](job.json)、[inspection-run.json](inspection-run.json) 和 [inspection-job.json](inspection-job.json) 保存原API响应，用于核对运行与作业来源；测试源码不能由检查工作流源码代替。
- [diagnostic.json](diagnostic.json) 是从同一原主消息提取的固定字段及六次失配事件；坐标差值属于派生计算，不是新设备采样。[manifest.json](manifest.json) 记录这些材料的字节数与SHA-256，以及完整日志摘要和切片范围。

此目录与独立的设备截图归档分开统计。整轮结果及其他API以[设备清单](../../../../screenshots/41032278433-1/manifest.json)为准。
