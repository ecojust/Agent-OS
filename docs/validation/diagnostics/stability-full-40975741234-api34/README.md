# 固定稳定性样本第一轮：API 34 原始失败诊断

- 原始完整矩阵：[run 40975741234](https://github.com/ecojust/Agent-OS/actions/runs/40975741234)，attempt `1`，测试源 `8e8e00518b11f55cca4c7c24dd74c49a0e35cd21`。
- 原作业：[emulator-tests (34), job 123803209460](https://github.com/ecojust/Agent-OS/actions/runs/40975741234/job/123803209460)。
- 只读提取：[inspect run 40977474358](https://github.com/ecojust/Agent-OS/actions/runs/40977474358)，检查作业 `123808975976`；提取工作流源 `74090a97192e9b6dcf90c94f8457c5cc66fc86e6`，不是测试源。
- `annotations.json` 保留 GitHub Checks API 原响应；`failure.txt` 按 `Android job diagnostic N` 数字顺序原样连接 message，没有改写原始错误。诊断来自原 JUnit，含一条 failure/error；本目录不代替完整矩阵分母。

原失败为 `VisualEventGenerationInstrumentedTest#actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications`：第 2 轮期望 generation 33，实际 35。被匹配的自有事件 windowId 866/type 2048/content 2，控制窗口及附件匹配；两条实际失效事件的 windowId 为 3，content 分别为 2、4，均 `eventMatchesVisual=false`，使 generation 从 33→34→35。诊断未丢条且采样未失败；这不证明这些外部事件的具体平台来源或根因，也不证明应忽略外部事件。

| 文件 | bytes | SHA-256 |
| --- | ---: | --- |
| `annotations.json` | 3907 | `6e05aff2506f89594f1e0f030036b22df44fc2d197f74164a4ea2221c0e34c6e` |
| `failure.txt` | 2366 | `4dc6b3b6001da176651f4efb2869e64aade28db8911dc0a42ee2dac63b4f1dfe` |
