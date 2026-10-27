# 第 52 轮性能报告复核

本记录直接读取主仓库不可变归档提交，没有重新运行测试、克隆仓库或修改生产代码。八份原始报告均为 `diagnosticOnly: true`、`budgetAcceptance: false`；`status: passed` 表示合成任务、停止检查和采集通过，不表示性能预算验收通过。

- 归档提交：`3ea87e5cf19ac33d8257290abe1340b2dc0b829e`。
- 被测源码：`8dfceaf94fe64fc2a5d95873e227eacb2dfd88cc`。
- CI：[`40507386749`，attempt 1](https://github.com/ecojust/Agent-OS/actions/runs/40507386749)。
- 环境：API 30–37 的 x86_64 模拟器，debug APK，归档配置 `default_x86_64_swiftshader_animation_one`。
- 逐份核对 `capture.json` 中的源码、运行编号、原始 JSON 字节数和 SHA-256，八份均吻合。

## 已观测数值

以下是每档这一次运行中记录到的最大值或单次停止值，不是发布用 P95/P99。主线程执行区分 `MAIN_CALLBACK` 与 `UI_PUBLICATION_APPLIED`；排队列合并 `MAIN_QUEUE`、`UI_PUBLICATION_QUEUE`，不将等待时间算作执行时间。PSS 差值取各阶段七个采样值的均值，单位 MiB。

| API | 主线程回调最大 ms | UI 发布最大 ms | 排队最大 ms | 停止请求→状态应用 ms | 空闲 B−A PSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| 30 | 1.493 | 2.867 | 25.090 | 34.594 | −0.021 |
| 31 | 1.471 | 5.240 | 19.485 | 22.428 | −0.077 |
| 32 | 1.064 | 4.945 | 18.968 | 18.739 | −0.090 |
| 33 | 0.754 | 3.284 | 16.823 | 27.853 | −0.020 |
| 34 | 2.298 | 6.273 | 19.656 | 15.792 | +0.011 |
| 35 | 2.204 | 7.275 | 21.736 | 11.647 | −0.204 |
| 36 | 1.480 | 6.420 | 16.970 | 9.290 | +0.055 |
| 37.0 | 4.392 | 6.493 | 17.705 | 19.079 | −2.483 |

八份报告共 1,212 条事件，记录器未覆盖丢失，采样未触及上限；A/B 阶段的执行事件列表均为空。26 次最终写前快照及 24 次动作提交尝试全部在工作线程。已覆盖的主线程执行段没有超过 8 ms，但 API 34 的 75 段中有 5 段、API 35 的 72 段中有 4 段超过 4 ms，不能据此宣布“主线程 P95 ≤ 4 ms”已达标；这些阶段也不覆盖整个主线程或完整帧。

停止均在等待确认时触发，每档一次。方法进入→代次失效为 0.013–0.033 ms，完整请求入队→本地状态应用为表中数值；随后 500 ms 内没有新的派发记录，合成目标计数保持 0。它不是物理触屏到屏幕呈现延迟，也不代表繁忙读取或已进入系统调用时的停止性能。

## 仍存在的快照长尾

| API | 阶段 | 耗时 ms | 节点数 | 快照 JSON 字节 | 截断 |
| --- | --- | ---: | ---: | ---: | --- |
| 34 | `SNAPSHOT_FINAL_MUTATION` | 184.587 | 12 | 4,222 | 是 |
| 35 | `SNAPSHOT_PREPARE` | 185.525 | 12 | 4,222 | 是 |
| 36 | `SNAPSHOT_FINAL_MUTATION` | 183.687 | 12 | 4,222 | 是 |
| 37.0 | `SNAPSHOT_PREPARE` | 184.550 | 13 | 4,534 | 是 |

四次都在工作线程，未与本进程 PSS 采样调用区间重叠。每次截断之后，记录均显示新的观察、准备及未截断的最终快照，然后才有派发，没有直接沿用该截断快照提交动作。移出主线程保留了控制响应，但查询长尾没有消失。仅凭墙钟事件不能将原因归为 Binder 等待、线程调度、GC 或具体计算，也不能为减少耗时移除最终校验。

## 内存、CPU 与验收限制

- 全部已取得样本的总 PSS 为 58.026–75.366 MiB。表中 B−A 短窗差值未显示持续空闲增长；负值同样反映固定顺序、自然回收与测量噪声，不能称为节省或用来证明没有泄漏。
- 按原始首末样本 `100 × ΔCPU毫秒 / Δ墙钟毫秒` 计算，B−A 单核等效 CPU 差为 −0.149 至 +0.132 个百分点。包含插桩与采样开销，不能当作独立产品净成本。一次 PSS 采集最长 127.615 ms，发生在非主线程。
- A 是服务关闭但进程驻留；B 是服务开启且无任务、控制条或主动观察。C 使用真实 Controller、本地 HTTP 模型和合成目标，验证三次实际写操作及最终输入、保存、计数结果。各状态是真实切换，但 C 的本地模型与采样器在被测进程内，fixture 和系统进程未计入 CPU/PSS；服务绑定及 fixture 准备也在采样窗口之外。
- 每 API 只有一次固定 `A_B_C_STOP` 顺序：A/B 各约 6 秒、各 7 点；C 约 0.457–1.438 秒、仅 2–3 点；STOP 仅 2 点。A 未手动执行与 C 等价的目标任务，因此不能用 C−A 判定完整活跃任务增量。1 秒采样的最大值也不是瞬时峰值。
- 没有足量交替配对、完整帧耗时/慢帧占比、冷/热启动 TTID、队列深度或全部 Looper 回调、相关进程资源、30 分钟压力、繁忙停止数值、真实设备资源档、温度和功耗证据。不能据此判定 24 MiB 图像实际峰值、空闲/活跃 PSS、CPU、响应 P95 或整机性能预算已通过，也不能推广为真机、ROM 或第三方应用验收。

复核结论：本轮短记录中未见明显主线程失控排队或空闲主动执行循环；应继续追踪高 API 的工作线程快照长尾和 UI 发布开销，并保持 `budgetAcceptance=false`。

## 原始报告相对路径

以下路径均相对于仓库根目录，读取时固定在上述归档提交。每份报告同目录的 `capture.json` 保存对应来源与文件摘要。

- [API 30 原始报告](../../screenshots/40507386749-1/performance/api-30/three-state-diagnostics.json)
- [API 31 原始报告](../../screenshots/40507386749-1/performance/api-31/three-state-diagnostics.json)
- [API 32 原始报告](../../screenshots/40507386749-1/performance/api-32/three-state-diagnostics.json)
- [API 33 原始报告](../../screenshots/40507386749-1/performance/api-33/three-state-diagnostics.json)
- [API 34 原始报告](../../screenshots/40507386749-1/performance/api-34/three-state-diagnostics.json)
- [API 35 原始报告](../../screenshots/40507386749-1/performance/api-35/three-state-diagnostics.json)
- [API 36 原始报告](../../screenshots/40507386749-1/performance/api-36/three-state-diagnostics.json)
- [API 37.0 原始报告](../../screenshots/40507386749-1/performance/api-37.0/three-state-diagnostics.json)

测量定义及目标分别见 [测量定义](../PERFORMANCE_DIAGNOSTICS.md)、[性能预算](../PERFORMANCE.md)；原始采集实现为 `app/src/androidTest/java/dev/agentos/app/PerformanceDiagnosticsInstrumentedTest.java`。
