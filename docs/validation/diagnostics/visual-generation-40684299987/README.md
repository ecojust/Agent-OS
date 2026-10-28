# Token用量源码中 API34 的原始窗口事件失败

源码`52cecfc0da8ff8d94962948a9dcc488070466fdd`的[完整运行40684299987](https://github.com/ecojust/Agent-OS/actions/runs/40684299987)为2063/2064通过，唯一失败是API34的`VisualEventGenerationInstrumentedTest#actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications`。新增Token用量24项全部通过，不能据此将整套标为通过。

[api34-failure.txt](api34-failure.txt)是原`Print Android failure details`日志成员第21–28行连续字节摘录，保留时间戳、换行和第22行末空格。[清单](manifest.json)记录源码、运行、job、原ZIP和成员的摘要以及摘录大小、行号和摘要。它不是独立下载的JUnit XML或完整测试报告。

本次第1轮断言为35→36；匹配到的自身状态事件来自窗口798，实际失效事件来自窗口4，类型2048、内容变化标记4，经`onAccessibilityEvent:1469`进入失效路径，`eventMatchesVisual=false`。探针total=1、dropped=0、samplingFailed=false；控制条附着、几何及最终匹配检查通过。

这不是上一轮的33→35、窗口2现场。窗口编号仅是本轮局部标识，不能跨运行推定同一窗口或触发源。来源未记录，不能归因于Token统计、控制条自身外观更新或某个系统组件；服务、设备适配器和失败测试本轮未改，也不足以排除整套时序或隔离影响。失败阶段PNG只有四色Fixture，不能用于还原断言时的事件。本轮原样保留失败，不放宽视觉窗口规则或重跑覆盖结果。
