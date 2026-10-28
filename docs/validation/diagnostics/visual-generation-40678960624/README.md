# API34 窗口事件测试的原始失败

配置恢复源码`28a410c7e6098a8bb116fde0c8b17827e472e340`的[完整运行40678960624](https://github.com/ecojust/Agent-OS/actions/runs/40678960624)为2039/2040通过，唯一失败是API34的`VisualEventGenerationInstrumentedTest#actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications`。新增配置恢复48项全部通过，但整套仍未通过。

[api34-failure.txt](api34-failure.txt)保留原`Print Android failure details`成员中从Test字段到测试调用位置的连续字节，包括时间戳、换行和行末空格。[清单](manifest.json)记录原ZIP、成员、精确行号、字节数及SHA-256；该摘录不是独立下载的JUnit XML或完整测试报告。

原第3轮断言为代次33→35。匹配到的自身状态事件来自窗口782；两次实际失效来自窗口2，分别为文字与内容描述变化，经`onAccessibilityEvent:1469`调用失效逻辑，`eventMatchesVisual=false`。探针记录total=2、dropped=0、samplingFailed=false，控制条附着与几何检查通过。

窗口2的归属和触发源没有记录，不能据此归因于控制条自身装饰性事件、配置页或某个系统组件。服务、设备执行器及该测试本轮未修改，配置准备已成功进入第3轮；目前无直接关联证据，但仍不能排除整套执行时序或隔离影响。历史API36同名测试仅有42→43断言，没有这次事件轨迹，不能认定同一根因。本轮不放宽窗口豁免，也不通过重跑覆盖此失败。
