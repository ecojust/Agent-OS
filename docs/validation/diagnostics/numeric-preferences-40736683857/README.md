# NumericPreferences 第三轮失败诊断（40736683857）

对应[原运行](https://github.com/ecojust/Agent-OS/actions/runs/40736683857)，源码 `3eb74b5d5119738ba8e3c560ed8b078df223bba1`，attempt 1。八档均有270例开始/完成，分母2160及原8个排除身份不变。**本轮2146通过、14失败，0 error/skip/缺失；全套仍未通过。** NumericPreferences六例×八档为34通过、14失败；其余原2112例本轮全部通过。

计数来自[不可变归档清单](https://github.com/ecojust/Agent-OS/blob/2293745e500e4dd01870447d8530abda580b8001/screenshots/40736683857-1/manifest.json)的逐案记录及采集端JUnit摘要，并与八档原始开始/完成行和失败打印核对。本目录没有独立下载原JUnit XML，也不把日志摘录等同于完整截图载荷审计。

[manifest.json](manifest.json) 记录原ZIP及各摘录的源成员、连续行号、大小和SHA256。八份 `api-*-failure-report.original.txt` 均从对应成员的首个 `Report:` 到最后 `Parsed` 连续复制原字节；没有拼接、过滤、重排、补写栈或改行尾。

| API | 本轮失败 | 原报告 |
| --- | --- | --- |
| 30 | 六例均在Provider根选择阶段失败 | [六项原报告](api-30-failure-report.original.txt) |
| 31–36 | 各一例：`realSafExportWritesTheCapturedFourFieldSnapshotWithoutConfigurationAuthority` 的目录就绪条件失败 | [31](api-31-failure-report.original.txt) · [32](api-32-failure-report.original.txt) · [33](api-33-failure-report.original.txt) · [34](api-34-failure-report.original.txt) · [35](api-35-failure-report.original.txt) · [36](api-36-failure-report.original.txt) |
| 37.0 | 同一export目录就绪失败，另有 `recreatedBrokerDoesNotReopenThePickerOrAdoptAnOrphanedTransfer` 失败 | [两项原报告](api-37.0-failure-report.original.txt) |

API30六例均未在原期限内选到目标Provider根，原字段为 `phase=PROVIDER_ROOT`、`preparation=NO_ACTIONABLE_ANCESTOR`、`rootsEntered=0`、`rootsReturned=0`、`rootsOutcome=NOT_QUERIED`。零计数只说明采样时当前Provider进程没有记录进入该 `queryRoots` 覆盖方法。目标应用UID视角的PM元数据显示 `providerInfo=PRESENT` 等固定字段，但这不是DocumentsUI自身UID的发现结果。六次有界历史日志均为 `COMPLETE`、524字节、`logAuthoritySeen=false`、`logFailureSeen=false`；`COMPLETE`只表示流读到EOF，不提供shell退出状态，未匹配也不能证明平台从未失败。上述观察尚未确定根因。

API31–37的七项export失败均为 `phase=DIRECTORY_READY`、`preparation=DIRECTORY_NOT_READY`，原栈经过 `awaitProviderDirectory` 第790行。当前Provider进程计数已有19–21次进入并返回，`rootsOutcome=RETURNED`；这些计数不等于当前目录界面已就绪，也不证明导出成功。原报告只证明目录与抽屉的既定准备条件未在期限内满足，不能据此认定具体哪个条件或生命周期环节导致失败。

API37.0另一个失败保留原断言 `No recreated transfer window may remain owned`，源码位置第220行。该失败不能归并为export目录就绪问题，原打印栈本身也未确定生命周期原因。本轮旧2112例全部通过，不代表历史失败的因果已被证明修复；前两轮失败分别保留在[第一轮](../numeric-preferences-40719564182/README.md)和[第二轮](../numeric-preferences-40728782554/README.md)。

[脚本测试原步骤](build-script-tests.original.txt) 完整保留同一CI运行的 `build/7_Test evidence collection boundaries.txt`：第19–21行实际报告 `Ran 336 tests in 62.467s`、`OK`，没有skip计数。该336项对应原314项与新增APK升级预检22项；原步骤在SDK准备之后执行，未打印逐案身份。本记录不是后续本地稀疏检出验证的结果，也不能替代失败的Android全量验收。
