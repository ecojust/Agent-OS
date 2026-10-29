# 首轮旋转专项 API35 原始失败诊断

- 被测源码：`a6112fe73a094735168a4e751ecb790ffb3ce7f7`。
- 原运行：[40816128028](https://github.com/ecojust/Agent-OS/actions/runs/40816128028)，attempt 1，API35作业 `123257492234`。
- 只读提取运行：[40816374793](https://github.com/ecojust/Agent-OS/actions/runs/40816374793)，检查作业 `123258292596`。
- `annotations.json`保留GitHub Checks返回的有界原始诊断分片；`failure.txt`按分片序号连接其消息，不改写断言或原因。

实际错误位于 `old_observation_rejected`：派发0，旧计划已返回明确未提交的 `STALE_OBSERVATION`；随后REJECT_CLICK合成模型查找横屏不可见的`counter_value`而失败。这份日志只定位API35，不将未提取其他API的完整原因补写为相同，也不把整个用例标为通过。八档结果和实际采集由独立归档保留。
