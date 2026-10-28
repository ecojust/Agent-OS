# NumericPreferences 第二轮失败诊断（40728782554）

对应 [原运行](https://github.com/ecojust/Agent-OS/actions/runs/40728782554)，源码 `300c4c7a1e0bd634f5c6b474ed2c50b057734172`，attempt 1。八档均有270例开始/完成和完整JUnit，分母2160不变、原8个排除身份未变。**本轮2143通过、17失败，0 error/skip/缺失；全套仍未通过。** 新增NumericPreferences六例×八档为31通过、17失败；其余原2112例本轮全部通过。

[manifest.json](manifest.json) 记录原ZIP及每份摘录的entry、连续行范围、字节数和SHA256。八份 `api-*-failure-report.original.txt` 都从对应报告的首个 `Report:` 到最后 `Parsed` 连续复制原字节，没有重排、过滤、补写栈或改行尾。

[API30原报告](api-30-failure-report.original.txt) 的六项均在 `phase=PROVIDER_ROOT` 失败；原日志使用字段 `rootsEntered=0`、`rootsReturned=0`、`rootsOutcome=NOT_QUERIED`（对应Provider状态计数 `rootsQueryEntered` / `rootsQueryReturned`）。它们只说明采样时**当前Provider进程**尚未记录进入 `queryRoots` 覆盖方法；不能据此断定DocumentsUI未发现Provider、系统缓存、包可见性或调用身份就是根因。

其余11项都报取消后MainActivity复合就绪条件超时：

| 原用例阶段 | API | 失败数 |
| --- | --- | ---: |
| `oldPickerResults…` 第172行：Main重建后的第一次picker取消，等待替代Main就绪 | 31、32 | 2 |
| `oldPickerResults…` 第193行：较新owner的第二次picker取消后等待Main就绪 | 33、34、36 | 3 |
| `malformedOversizedAndCancelledImports…` 第151行：实际picker取消后等待Main就绪 | 31、32、34、35、36、37.0 | 6 |

`awaitMain` 第422行检查host存在、resumed、未destroyed、有窗口焦点、ready、非busy、存储未损坏及 `numericTransfer == null`。这些原失败只表明复合条件未在期限内成立，没有逐字段现场，不能直接认定是哪一项未满足；第一次与第二次取消的阶段不能混为同一路径。

本轮没有旧API37 `ResolveNodeAtInstrumentedTest.uniqueActionCandidatesAreReadOnlyAndASelectedClickStillRequiresConfirmation` 失败；这不证明上轮 `STALE_OBSERVATION` 的原因已修复。上轮API32的连续启动超时也未由本次通过旧用例证明其历史因果。原失败仍保留在[上一轮诊断](../numeric-preferences-40719564182/README.md)。本目录只归档本轮实测失败，不宣称后续修复通过。
