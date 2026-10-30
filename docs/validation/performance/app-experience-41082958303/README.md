# 第二轮正式应用体验诊断数据

本目录是[运行 41082958303](https://github.com/ecojust/Agent-OS/actions/runs/41082958303)的派生表及独立复核摘要，被测源码固定为 `f45fbb152ebf1b3c1335bb28744e81d8837dc50c`。[原始证据](../../../../screenshots/41082958303-1/manifest.json)保持不变；[实验口径、失败历史和结论](../../performance-app-experience.md)另列。本目录不代表真机、性能预算或完整 API 回归验收。

| 数据 | 内容与分母 |
| --- | --- |
| [启动](app-experience-41082958303-startup.csv) | 32 个 API／冷热／A/B 场景，各 100 个有效 TTID，中位数与 nearest-rank P95。 |
| [启动配对](app-experience-41082958303-startup-pairs.csv) | 16 组各 100 对；配对 B−A 分布与两组分位数之差分列。 |
| [启动顺序分组](app-experience-41082958303-startup-orders.csv)及[顺序内配对](app-experience-41082958303-startup-order-pairs.csv) | AB／BA 子组各 50 个样本，不报告 P95。 |
| [CPU](app-experience-41082958303-cpu.csv) | 各 API／A/B/C 六个任务；目标与 Agent 分开，保留累计 CPU、实际墙钟和单核口径百分比。 |
| [PSS](app-experience-41082958303-pss.csv) | 各进程任务前后端点及差值，不是稳态或峰值。 |
| [资源配对](app-experience-41082958303-resource-pairs.csv) | 同轮 B−A／C−A 成本差；不同进程端点采样时刻并不相同。 |
| [帧](app-experience-41082958303-frames.csv) | 各 API／状态 18 条非首绘报告，计数、质量、耗时与超限分母分列。API 30 名义周期与其他档 DEADLINE 不混合。 |
| [就绪观察](app-experience-41082958303-readiness.csv) | 各 API 400 次启动；共 3,201 次观察，保留一次真实首次未就绪。观察在启动 marker 外。 |
| [表包清单](app-experience-41082958303-audit.json) | 原运行、源码、清单与工具 SHA，分母、范围和逐表字节数／SHA-256。 |
| [独立数值复核](independent-raw-audit-summary.json)及[表格复核](independent-statistics-audit.json) | 原始整数、Fraction／Decimal 独立计算与 P95 第 95 位样本定位，不把字段检查次数当设备样本数。 |
| [CI 与清理核验](ci-log-audit.json) | 本次 800 项宿主通过＋1 项 opt-in 跳过、每档 1 个专项、环境恢复；本地 801 项无跳过回归另见实验报告。 |

CSV 空字段表示 unavailable，不补零。显示表的小数舍入不修改这些派生值或原始样本。FrameMetrics 的主证据独立于资源阶段辅助 ftrace；后者的尾部覆盖限制见实验报告，不宣称 trace 覆盖每次资源绘制。

使用 f45 版本的 `scripts/summarize_app_experience.py` 及其依赖可从归档重建完整摘要；工具摘要记录在表包清单中。输出必须是尚不存在、位于证据目录之外的新文件：

```sh
python3 scripts/summarize_app_experience.py screenshots/41082958303-1 \
  --source f45fbb152ebf1b3c1335bb28744e81d8837dc50c \
  --output /tmp/agent-os-experience-41082958303.json
```

正式主场景、中位数／P95、配对差及 CPU/PSS/帧摘要均可从该输出核对。就绪表的额外原始聚合绑定每份 launch 分片及其前后守卫；唯一重读的[原始样本](../../../../screenshots/41082958303-1/app-experience/api-34/launch-288.json)完整保留两次观察，不能用最终 ready 覆盖首次 false。
