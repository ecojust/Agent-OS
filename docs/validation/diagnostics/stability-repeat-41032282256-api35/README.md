# 固定三轮复测第2轮：API 35 原始旋转失败

原完整运行 [41032282256](https://github.com/ecojust/Agent-OS/actions/runs/41032282256)，attempt1，精确测试源 `710e54e701edb37a32efa28845f0cd5ed6ace936`；原作业 [emulator-tests (35)](https://github.com/ecojust/Agent-OS/actions/runs/41032282256/job/123984620652)。

只读提取 [run 41034177450](https://github.com/ecojust/Agent-OS/actions/runs/41034177450)、job `123990841642` 成功，提取工作流源码亦为710e；提取不是设备重跑。`annotations.json` 为原Checks响应，`failure.txt` 按分片数字序号连接原message，未改写空白或字段。`job-failure-excerpt.txt` 保存原job连续第1558–1584行（包含日志前缀）；其正文与Checks内容经比较一致，唯报告文件路径因提取到临时目录不同。这里只比较时规范化路径，两个原文均保持原样。诊断来自原JUnit提取报告，含原报告器的堆栈裁剪标记，不是完整JUnit XML。

API35 失败发生在 `clickExpandedApproval` 第373行的 `isOwnedControlWindowCurrent(shown.identity)` 断言，尚未执行第378行的实际按钮 `performClick()`。任务仍运行、仍待确认，epoch1、派发0、模型请求1；回执仍为WAITING_RESULT，不能补记为已返回STALE或已执行。trace保留0事件，不是API30的六次观察耗尽。事后本地View与平台窗口边界不同，但这些采样不原子，不能单独认定身份在哪个回调失效或具体动画根因。

`diagnostic.json` 是从以上原文提取的派生索引；它不是另一份设备采样。此目录保留失败，完整分母和截图另见 [整轮清单](../../../../screenshots/41032282256-1/manifest.json)。

| 文件 | bytes | SHA-256 |
| --- | ---: | --- |
| `annotations.json` | 5727 | `a61405cac432c2e414a9e4ebb36e3e4dd12f7380c366dedb6fe2864ba893e0e5` |
| `diagnostic.json` | 1249 | `c5c1d95eddd8f4216cfe1bea46861f28f7bdbca0f62b2ded4d537c40aa78a342` |
| `failure.txt` | 3893 | `e014f4cdcb9e2dc4e16bb3146e69743f246a37703dc657aec083b88ad7fff024` |
| `inspection-job.json` | 2221 | `00c69a0d493917425f8a24d378da1264119477801302a70bd5c669ebbf14ea80` |
| `inspection-run.json` | 11771 | `eb206ed36a7de1386824fbeaf3b3edf542e52d70a39c61306c3f23c9fc380eec` |
| `job-failure-excerpt.txt` | 6071 | `c4829295408e510366897b4d7e4eea439299d7c07d146adb2b1750b58cfd6984` |
