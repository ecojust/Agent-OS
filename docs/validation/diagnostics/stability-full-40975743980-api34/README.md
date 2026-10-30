# 固定两轮验证的第二轮：API34 导航来源拒绝失败

被测源码 `8e8e00518b11f55cca4c7c24dd74c49a0e35cd21`；原完整运行 [40975743980](https://github.com/ecojust/Agent-OS/actions/runs/40975743980)，attempt 1，矩阵标签 `34`，原作业 [123803217131](https://github.com/ecojust/Agent-OS/actions/runs/40975743980/job/123803217131)。本档执行原271项，一项失败；[完整归档](../../../../screenshots/40975743980-1/manifest.json)为2166/2168，另一项为API30旋转失败。

`job.json`为GitHub原作业API响应。`failure.txt`直接保留该作业原日志中`Print Android failure details`从`Report:`到`Parsed 1 Android reports; found 1 failure/error entries.`的连续原字节，即原日志第1082–1095行，保留作业、步骤、时间戳、原空白、主消息、cause和suppressed，未另行裁剪行或重写内容。原输出自身的`trimmed`文本照录。原日志由`gh run view --job 123803217131 --log`取得；完整日志SHA-256为`c550658ad91f21e2b64e14865b0f0fae192f87e6549dcbd474f2ee0911522aff`，本目录只保存所需原始段落。只读检查 [40977404920](https://github.com/ecojust/Agent-OS/actions/runs/40977404920)（作业 `123808749834`）随后成功，实际输入来源已核对，返回的主消息诊断与原作业日志一致；这里保存的原文仍来自源作业日志，不重标为检查注释或独立下载的原XML。

失败身份为 `NavigationObservationGuardInstrumentedTest#anUnscopedSettingsWindowCannotBeReplacedByAnAutomaticLaunch`。主消息诊断实际保留：`phase=launch_app/outcome=RETURNED`，返回`STALE_OBSERVATION`；`ok`、`submitted`、`dispatchSubmitted`、`dispatchAccepted`均为`FALSE`，`accepted=MISSING`。执行代次1→1，`paused/cancelled/inFlight=false`，派发、审批和HTTP请求均为0。调用前与清理前失败采样均分类为Settings，唯一焦点窗口及根窗口ID均为506。

trace限定为`CALL_TO_FAILURE`，`finalEvents=1/finalCompleted=0`，`localStale/ownedMismatch/revisionMismatch/dispatchEvents`均为0，覆盖计数0。不存在的`accepted`不是观察到的false；`finalEvents`也不是成功终检次数。失败正文仍是“未收到原合同要求的来源中断”，只有`TASK_PAUSED`及其后全部业务断言成立才能通过。因此本次明确未提交的普通stale返回仍计失败；采样的前后焦点不是连续录像，不能替代提交证据或证明期间任何瞬时状态。现有字段尚不唯一定位stale的具体触发条件，也不自动证明旧API37或更早API35失败经过相同路径。

以下摘要是原文件的派生完整性记录，不是额外设备结果：

| 文件 | 字节 | SHA-256 |
| --- | ---: | --- |
| `job.json` | 4126 | `9be9c229e2464daca3714cd9499abeb765565065f57b88a8405eea534b2a1ef8` |
| `failure.txt` | 4023 | `7fa8e03239d74e9d04b12997f6c18d92a77c2e810835b14b4b6f0d9f6f53884a` |
