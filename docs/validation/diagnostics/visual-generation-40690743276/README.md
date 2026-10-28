# 原生表单源码中 API35 的原始窗口事件失败

源码`c5b804e757fcf156a9ab34f320b085c213b979ee`的[完整运行40690743276](https://github.com/ecojust/Agent-OS/actions/runs/40690743276)为2087/2088通过，唯一失败是API35的`VisualEventGenerationInstrumentedTest#actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications`。新增原生表单24项全部通过，整套仍为失败。

[api35-failure.txt](api35-failure.txt)是原`Print Android failure details`日志成员第21–28行的连续字节摘录，保留时间戳、换行和第22行末空格。[清单](manifest.json)记录源码、运行、job、原ZIP、成员及摘录的大小和摘要。它不是独立下载的JUnit XML或完整测试报告。

本次第2轮断言为37→38；匹配到的自身状态事件来自窗口805，实际失效事件来自窗口5，类型2048、内容变化标记4，经`onAccessibilityEvent:1469`进入失效路径，`eventMatchesVisual=false`。探针total=1、dropped=0、samplingFailed=false；控制条已附着、显示且布局完成，边界为`[12,36][308,148]`，身份与几何匹配检查通过。

窗口编号仅是本轮局部标识，不能把窗口5等同于此前API34运行中的窗口4或窗口2。来源未记录，不能归因于本次表单语义、控制条自身外观更新或某个系统组件；服务、设备适配器和失败测试本轮未改，也不足以排除整套时序或隔离影响。本轮API34通过不证明该问题已修复。

实际失败阶段PNG仅显示四色Fixture和系统栏，不能还原断言时的事件来源。本轮原样保留失败，不放宽视觉窗口规则或重跑覆盖结果。
