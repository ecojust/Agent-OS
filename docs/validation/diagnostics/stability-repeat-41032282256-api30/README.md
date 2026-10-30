# 固定三轮复测第2轮：API 30 原始旋转失败

原完整运行 [41032282256](https://github.com/ecojust/Agent-OS/actions/runs/41032282256)，attempt1，精确测试源 `710e54e701edb37a32efa28845f0cd5ed6ace936`；原作业 [emulator-tests (30)](https://github.com/ecojust/Agent-OS/actions/runs/41032282256/job/123984620618)。

只读提取 [run 41034169016](https://github.com/ecojust/Agent-OS/actions/runs/41034169016)、job `123990814563` 成功，提取工作流源码亦为710e；提取不是设备重跑。`annotations.json` 为原Checks响应，`failure.txt` 按分片数字序号连接原message，未改写空白或字段。`job-failure-excerpt.txt` 保存原job连续第879–891行（包含日志前缀）；其正文与Checks内容经比较一致，唯报告文件路径因提取到临时目录不同。这里只比较时规范化路径，两个原文均保持原样。诊断来自原JUnit提取报告，含原报告器的堆栈裁剪标记，不是完整JUnit XML。

API30 的6次拒绝均直接记录为 `BOUNDS_MISMATCH`。第1次实际边界为 `[110,36][532,244]`，其余5次为 `[110,36][532,150]`，预期始终为 `[110,36][530,148]`；先出现更高的窗口几何，之后仍有右/下各2像素的差异。原任务在6次观察尝试后以STALE结束，旧输入明确未提交，派发0、模型请求1。该记录确定了拒绝细类及瞬时数值，不单独确定平台动画或缓存为何保留差异，也不支持放宽精确边界。

`diagnostic.json` 是从以上原文提取的派生索引；它不是另一份设备采样。此目录保留失败，完整分母和截图另见 [整轮清单](../../../../screenshots/41032282256-1/manifest.json)。

| 文件 | bytes | SHA-256 |
| --- | ---: | --- |
| `annotations.json` | 6078 | `7147137006ca30f6b8e179fdc1b081c8fcb1aed9fe2c169f451c8eef7165b868` |
| `diagnostic.json` | 2568 | `5f9f2c350345351d2278f187dea445eb02e3975b57d082ca4ec1e2b75dd90dcf` |
| `failure.txt` | 4258 | `d33189d71fe92b1e8b28d0d52aabbafe155e0c7fb007533ec7f8f37bb53d6840` |
| `inspection-job.json` | 2221 | `bef64dae63564dd3cc5b274b035effed780071717ffa5336b66a2dedeba26a5d` |
| `inspection-run.json` | 11771 | `479f31833b187d432f93eae0b8a8491b4ad0de8da4c5fc95a96d9dd97af1499d` |
| `job-failure-excerpt.txt` | 5330 | `4f01132dfe61ef5faa7743c4b26545e1035a7f54eeca561dbd25641a23229e8b` |
