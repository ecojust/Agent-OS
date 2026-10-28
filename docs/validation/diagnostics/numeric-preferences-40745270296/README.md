# NumericPreferences 第四轮失败诊断（40745270296）

对应[原运行](https://github.com/ecojust/Agent-OS/actions/runs/40745270296)，源码 `eee60b1b273e458db92f3ed2391ee5a84d63e2fb`，attempt 1。八档原日志均有270例开始/完成，共2160，分母及原8个排除身份不变。**本轮2154通过、6失败，0 error/skip/缺失；全套仍未通过。** NumericPreferences六例×八档为43通过、5失败；其余原2112例为2111通过、1失败，失败属于既有VisualEventGeneration用例。

计数已从[不可变归档清单](https://github.com/ecojust/Agent-OS/blob/6bd8fcd130f830472fa56095500fd04411f58c0e/screenshots/40745270296-1/manifest.json)及八档逐案账本核对，每档完整270个身份与冻结源码集合一致。归档工作流为40748128968，归档提交 `6bd8fcd130f830472fa56095500fd04411f58c0e`，parent恰为测试源码 `eee60b1b273e458db92f3ed2391ee5a84d63e2fb`。JUnit数字来自归档的采集端解析摘要，本目录没有独立下载原JUnit XML；总载荷与PNG完整性由另项审计负责，不以这里的日志摘录替代。

[manifest.json](manifest.json) 记录原ZIP及每份材料的源成员、连续行号、大小和SHA256。失败档从首个 `Report:` 到最后 `Parsed` 原字节连续复制；零失败档没有 `Report` 块，仅保留其原 `Parsed 1 … found 0 …` 行。没有拼接、过滤、补栈或改变行尾。

| API | 原失败打印 | 原报告 |
| --- | --- | --- |
| 30 | 3项Numeric：`cancelledProviderIo…`、`malformedOversizedAndCancelledImports…`、`realSafImport…` | [原文](api-30-report.original.txt) |
| 31 | 1项Numeric：`malformedOversizedAndCancelledImports…` | [原文](api-31-report.original.txt) |
| 32、33、34 | 各0项 | [32](api-32-report.original.txt) · [33](api-33-report.original.txt) · [34](api-34-report.original.txt) |
| 35 | 1项既有Visual：`actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications` | [原文](api-35-report.original.txt) |
| 36 | 1项Numeric：`malformedOversizedAndCancelledImports…` | [原文](api-36-report.original.txt) |
| 37.0 | 0项 | [原文](api-37.0-report.original.txt) |

API30三项失败现位于 `phase=IMPORT_DOCUMENT`、`preparation=NO_ACTIONABLE_ANCESTOR`，Provider当前进程已记录6、10、17次进入并返回，`rootsOutcome=RETURNED`。原诊断同时记录 `safQueryAllRequested=false`、`safQueryAllGranted=false`，固定测试包dump解析为 `testForceDeclared=false`、`testForceOverride=true`。这些是本轮采样事实，不是DocumentsUI有效可见性的完整证明，也不能据跨轮差异直接宣布此前根发现问题的因果已确定。

API31与36的失败为 `phase=PROVIDER_ROOT`、`preparation=READY`，单次SAF点击调用返回false，原断言为 `The single SAF click returned false`。这与API30的导入文档目标准备超时不是同一失败条件；不据返回false推断具体平台内部原因。

API35保留原Visual诊断：第3轮原generation为34，断言时为35；探针仅记录1次失效且无丢弃。已匹配的控制条事件为windowId859/content2，失效记录为windowId4/content4、`eventMatchesVisual=false`，当时控制条仍attached/shown/laidOut且几何匹配。它说明另一个窗口ID的事件触发了保守失效；记录没有窗口4的身份或产生事件的原因，不能把该事件归为控制条自身变化。

[API30安装至测试开始原段](api-30-install-through-start.original.txt) 连续保留Exercise第293–424行：固定“仅安装测试APK、force-queryable”说明、`Performing Streamed Install`、`Success`，以及后续 `Starting 270 tests`。完整adb参数没有在该段回显；固定命令来自[同一测试源码的脚本第129–140行](https://github.com/ecojust/Agent-OS/blob/eee60b1b273e458db92f3ed2391ee5a84d63e2fb/scripts/run_android_tests.sh#L129)，其作用对象是 `app-debug-androidTest.apk`，不是生产APK。未向原段补写命令。

[脚本测试完整原步骤](build-script-tests.original.txt) 第19–21行实际报告 `Ran 342 tests in 58.431s`、`OK`，且原进度为342个点、没有skip计数；这属于本次CI，不是此前本地336项稀疏目录验证。

九个job均完成：build及API32/33/34/37.0成功，API30/31/35/36失败；见[原jobs快照](source-jobs.original.json)与[原run快照](source-run.original.json)。九份 `*-sparse-checkout.original.txt` 分别保留各job实际 `fetch --filter=blob:none --depth=1`、八目录 `sparse-checkout set` 和最终源码SHA的连续原段（[build示例](build-sparse-checkout.original.txt)）。这证明本轮实际执行了该检出配置；原时间戳保留，但没有进行受控性能比较，不宣称固定节时或因果加速结论。

此前失败仍保留在[第三轮](../numeric-preferences-40736683857/README.md)、[第二轮](../numeric-preferences-40728782554/README.md)和[第一轮](../numeric-preferences-40719564182/README.md)。本目录不将本次未复现当成历史原因已修复。
