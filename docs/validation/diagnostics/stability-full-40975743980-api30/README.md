# 固定两轮验证的第二轮：API30 旋转失败

被测源码 `8e8e00518b11f55cca4c7c24dd74c49a0e35cd21`；原完整运行 [40975743980](https://github.com/ecojust/Agent-OS/actions/runs/40975743980)，attempt 1，矩阵标签 `30`，原作业 [123803217181](https://github.com/ecojust/Agent-OS/actions/runs/40975743980/job/123803217181)。本档执行原271项，一项失败；[完整归档](../../../../screenshots/40975743980-1/manifest.json)为2166/2168，另一项为API34导航失败，不能相互替代。

只读检查运行 [40977365340](https://github.com/ecojust/Agent-OS/actions/runs/40977365340)，检查作业 `123808626236`，已成功并核对实际输入为本运行/API30。`annotations.json`保留Checks API的原始响应；`failure.txt`按`Android job diagnostic N`顺序连接原消息，未删改空格、堆栈或字段。原输出自身的`trimmed`文本照录，不代表本次额外裁剪；未另外下载原JUnit XML。

失败身份为 `AgentControllerInstrumentedTest#realRotationRejectsOldApprovalAndKeepsControlsReachable`，阶段 `old_observation_rejected`。原报告直接记录：旧`TYPE_TEXT`回执为`RETURNED/STALE_OBSERVATION/submitted=NO/accepted=NO`，派发0、模型请求1；任务已`FAILED/STALE_OBSERVATION`，原owner取消。新增trace保留63条、覆盖0条，记录6个完成的`SNAPSHOT_OBSERVE` span、6次`OWNED_WINDOW_MISMATCH`、1次`LOCAL_CONTROL_STALE`，`OBSERVATION_REVISION_MISMATCH=0`。观察耗时合计32666微秒、最长15702微秒；顺序和时间按原字段解释。

这些直接字段把本次失败细分到重新观察期间的窗口匹配拒绝；它们尚未记录每次不匹配的具体矩形、根节点或身份字段。6个完成span不代表6次成功观察。事后本地控件已脱离、缓存窗口边界和WMS动画采样均是失败后的非原子快照，不能反推最初拒绝条件，更不能仅凭`mAnimRunning=false`断言缓存根因已确定。本案继续计失败，未增大原预算、添加容差或重试运行。

下列摘要为对上述原始文件的派生校验信息，不是新增设备测量：

| 文件 | 字节 | SHA-256 |
| --- | ---: | --- |
| `annotations.json` | 5657 | `5e03ae2f6c2cb7031c254993e019e43658a2142ee606a02a0dfcf3b0b778fcac` |
| `failure.txt` | 3837 | `b033e8a893fcf90ca18e2ac77a8b73ffa3abc8ae10f637f850a688ab3048ddc7` |
