# 应用体验诊断的原始 trace 复核

本目录保留 f45 源码的第四轮试跑 `41081724144` 与第二轮正式运行 `41082958303` 的补充复核；[测量合同与结论](../../performance-app-experience.md)另列。原 trace、采集状态及派生结果仍在对应的 `screenshots/<run>-1/app-experience/`，本目录不修改它们。

- [试跑尾部](trace-tail-pilot-41081724144.json)与[正式尾部](trace-tail-formal-41082958303.json)绑定原文件长度、SHA-256、时钟前提、每 CPU 最后 ftrace 事件、服务元数据及最后资源绘制水位。
- [正式实际容量](capacity-formal-41082958303.json)保留每档字节数及冻结上限；一次未触及上限不保证其他运行不会超限。
- [试跑官方启动重放](startup-replay-pilot-41081724144.json)、[正式官方启动重放](startup-replay-formal-41082958303.json)与[独立查询](startup-replay.sql)记录固定官方解析器的关联复核。
- 正式摘要另列八档各一个最后资源帧的同 PID UI／RenderThread 关联及[查询模板](last-resource-frame-replay-template.sql)。这只补核八个末帧，不扩大为全资源绘制覆盖声明。

尾部审计是补充覆盖检查，不新增“每条资源 FrameMetrics 都必须再有 ftrace 对应”的验收门槛。试跑部分资源末尾存在辅助 slice 覆盖缺口，具体机制未确定；不能仅由 `trace_bounds` 推断原始文件物理截断。正式轮末 ftrace 均晚于最后资源绘制，也不因此宣称每一次绘制都被 trace 收录。

下列只读脚本使用 Python 标准库解压、核验原 SHA 和 protobuf 包边界。只有时钟域、单位和增量标志满足已记录前提时才计算时间差，否则保留未知。完整包边界无法排除在合法包边界提前复制文件的可能性，成功解析也不是所有 producer 事件齐全的证明。

从仓库根目录运行，输出放在证据目录外：

```sh
python3 docs/validation/performance/app-experience-trace-audit/audit_trace_tail.py \
  --evidence-root screenshots/41082958303-1/app-experience \
  --run-id 41082958303 \
  --source-commit f45fbb152ebf1b3c1335bb28744e81d8837dc50c \
  --output /tmp/agent-os-experience-tail-41082958303.json
```

输出绑定脚本 SHA；比较时应使用同一脚本版本与仓库相对输入路径。官方 TTID 仍由冻结的 Perfetto v59.1 标准库决定，不能用本脚本的尾部标量代替启动关联、FrameMetrics 或停止状态验证。
