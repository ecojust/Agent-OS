# API35 连续导航来源拒绝用例的原始失败

[完整运行40698819944](https://github.com/ecojust/Agent-OS/actions/runs/40698819944)为主分支源码`31e14e233d79d56de8b16f0ee702193abe5cd266`的首次手动完整回归。[API35任务](https://github.com/ecojust/Agent-OS/actions/runs/40698819944/job/122852752289)报告`NavigationObservationGuardInstrumentedTest#anUnscopedSettingsWindowCannotBeReplacedByAnAutomaticLaunch`失败，源码第163行文案为`Automatic launch replaced an unknown focused window`。

[原始摘录](api35-failure.txt)直接取自整次运行日志ZIP中的`emulator-tests (35)/10_Print Android failure details.txt`连续第19–26行，保留原字节、换行和第22行行尾空格。904字节，SHA-256为`e78e2fc062e2d877ea3eb3837df2c521681e5f2da65b54ee04d95ac45d0e119b`；来源和完整性信息见[清单](manifest.json)。这是CI打印的原JUnit失败信息，不冒称另行下载并解析了原JUnit XML。

## 这条失败能证明什么

测试在`tools.execute("launch_app", ...)`正常返回后无条件调用`fail`，只在抛出`TaskControlException`时检查`TASK_PAUSED`。本轮结果说明预期的暂停异常没有出现；测试未保存返回JSON，所以无法区分成功提交、普通未提交拒绝或`STALE_OBSERVATION`等返回。紧随其后的暂停状态、零派发、焦点不变、trace与零HTTP请求断言均未执行，不能凭失败文案认定实际发生了自动启动或越界派发。

测试曾请求打开系统设置，但前置观察只要求包名非空、不是Fixture也不是Agent，并与独立焦点包一致；没有核对实际解析的设置包名。默认桌面也可满足该谓词，且生产连续导航允许从系统解析的默认桌面启动授权应用。日志未记录本次具体包名，故既不能确认此时是设置页，也不能推断一定是桌面。

本用例没有失败PNG。外层规则在资源清理后记录`stage=failed`、`foregroundPackage=com.android.settings`，因`foreground_is_not_an_approved_synthetic_test_screen`标为`blocked`。这是清理后的采集记录，不能直接作为调用时的焦点证明。下一步应补充原调用返回值、实际前台身份及独立派发计数的有界诊断，再判断是测试前置状态、拒绝路径还是生产执行问题；当前不放宽来源保护、不重放动作，也不把这次失败改写为通过。

本轮导航测试和`AndroidDeviceTools`未修改。任务输入生命周期的改动在`MainActivity`及Controller的内存标记，现有日志不足以建立它与本次失败的因果关系。此前API35窗口事件失败与本次不同，保留各自原始结果，不套用旧诊断。

完整归档逐项核验为2111/2112通过，新增任务输入三项在八档24/24通过；本失败保留在分母。首次归档40701793532因主分支模式误传开发分支专用参数而在来源验证阶段失败；更正调用参数后，40702377809成功归档同一次原始测试，没有重跑测试或修改生产代码。
