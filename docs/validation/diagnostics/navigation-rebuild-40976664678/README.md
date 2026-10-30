# 已知未授权来源在控制窗重建后的失败前对照

源码 `74090a97192e9b6dcf90c94f8457c5cc66fc86e6`，原运行 [40976664678](https://github.com/ecojust/Agent-OS/actions/runs/40976664678)，attempt 1，固定 scope 为 `navigation-origin-rebuild-diagnostic`。每档只选择 `NavigationObservationGuardInstrumentedTest#aKnownUnscopedSettingsOriginStillPausesAfterControlWindowRecreation`；API30–37.0 共 8 案，`fullSuiteAcceptance=false`，不能替代完整矩阵或原 `navigation-origin-diagnostic`。

这份源码只加入了该回归、选择器及文档。已逐树核对 `app/src/main` 和 `core/src/main`，两者与基线 `8e8e00518b11f55cca4c7c24dd74c49a0e35cd21` 相同，树对象见 [manifest.json](manifest.json)。这里确认的是这两份生产源码树相同，不是 APK 字节相同，也不表示历史运行的窗口与事件状态相同。

结果为 **0 项合同通过、8 项原始失败、0 项未验证**。8 案均完成真实前置并成为目标反例，不能将“成功复现”改记为测试通过：先成功观察真实、未授权且无其他遮挡的 Settings 窗口，再真实移除和重新添加控制窗；核验新身份与 lease、旧身份撤销、同一 owner/profile/configuration/epoch 及 Settings 焦点后，不重新观察，执行一次 `launch_app`。8 案都到达 `recreationVerified=true`、`phase=launch_app_after_recreation`，不是初始化失败。

各档主消息均记录 `outcome=RETURNED`、`return.code=STALE_OBSERVATION`，`ok/submitted/dispatchSubmitted/dispatchAccepted` 为字符串 `FALSE`，`accepted=MISSING`。`epochBefore=epochAfter=1`、`paused=false`、`cancelled=false`、`inFlight=false`；`approvals/httpRequests/dispatchAttempts` 均为 0。来源中断合同要求 `TASK_PAUSED` 并进入暂停；仅返回未提交的 stale 不满足合同。原断言仍为 failure，不存在实际自动启动成功的证据。

`trace.scope=CALL_TO_FAILURE`、`traceOverwritten=0`，8 案均为 `localStale=1`，`finalEvents/finalCompleted/ownedMismatch/revisionMismatch/dispatchEvents` 全为 0。这是本次调用至断言失败区间的固定计数；没有记录某阶段事件不能单独证明所有相关阶段均未开始。`beforeFocus/recreatedFocus/failureFocusBeforeCleanup` 每档相等，均为 `SETTINGS`、`focusedCount=1`、`type=1`，且根与窗口 ID 一致。

| API | 原失败作业 | 只读提取运行 | 前后 Settings windowId | 原文与派生诊断 |
| --- | --- | --- | --- | --- |
| 30 | [123806283373](https://github.com/ecojust/Agent-OS/actions/runs/40976664678/job/123806283373) | [40977006190](https://github.com/ecojust/Agent-OS/actions/runs/40977006190) | 9 | [failure.txt](api-30/failure.txt) · [diagnostic.json](api-30/diagnostic.json) |
| 31 | [123806283377](https://github.com/ecojust/Agent-OS/actions/runs/40976664678/job/123806283377) | [40977015539](https://github.com/ecojust/Agent-OS/actions/runs/40977015539) | 10 | [failure.txt](api-31/failure.txt) · [diagnostic.json](api-31/diagnostic.json) |
| 32 | [123806283560](https://github.com/ecojust/Agent-OS/actions/runs/40976664678/job/123806283560) | [40977228586](https://github.com/ecojust/Agent-OS/actions/runs/40977228586) | 13 | [failure.txt](api-32/failure.txt) · [diagnostic.json](api-32/diagnostic.json) |
| 33 | [123806283507](https://github.com/ecojust/Agent-OS/actions/runs/40976664678/job/123806283507) | [40977190156](https://github.com/ecojust/Agent-OS/actions/runs/40977190156) | 12 | [failure.txt](api-33/failure.txt) · [diagnostic.json](api-33/diagnostic.json) |
| 34 | [123806283463](https://github.com/ecojust/Agent-OS/actions/runs/40976664678/job/123806283463) | [40977118899](https://github.com/ecojust/Agent-OS/actions/runs/40977118899) | 11 | [failure.txt](api-34/failure.txt) · [diagnostic.json](api-34/diagnostic.json) |
| 35 | [123806283506](https://github.com/ecojust/Agent-OS/actions/runs/40976664678/job/123806283506) | [40977196401](https://github.com/ecojust/Agent-OS/actions/runs/40977196401) | 12 | [failure.txt](api-35/failure.txt) · [diagnostic.json](api-35/diagnostic.json) |
| 36 | [123806283599](https://github.com/ecojust/Agent-OS/actions/runs/40976664678/job/123806283599) | [40977201666](https://github.com/ecojust/Agent-OS/actions/runs/40977201666) | 12 | [failure.txt](api-36/failure.txt) · [diagnostic.json](api-36/diagnostic.json) |
| 37.0 | [123806283332](https://github.com/ecojust/Agent-OS/actions/runs/40976664678/job/123806283332) | [40977125141](https://github.com/ecojust/Agent-OS/actions/runs/40977125141) | 11 | [failure.txt](api-37.0/failure.txt) · [diagnostic.json](api-37.0/diagnostic.json) |

每档目录保留四份材料：`annotations.json` 是 Checks API 原始注释响应；`failure.txt` 按 `Android job diagnostic N` 的编号连接各分片，不修剪或改写文字、空格及字段。该文本是现有只读检查工作流从原 JUnit 提取的有界片段，包含报告器原有的堆栈裁剪标记，不是完整日志或原始 XML。`diagnostic.json` 是从主消息 `navigationDiagnostic=` 解析并格式化的派生对象；其字段、类型和值与原文相同，不是另一次设备采样。`inspection-run.json` 是 Actions API 的检查运行元数据，以格式化 JSON 保存；8 次只读提取均成功，不执行设备用例。[manifest.json](manifest.json) 是派生索引，记录四类文件共 32 份、182,114 B 的字节数与 SHA-256，并与保存于工作区的原材料逐字节比对。

唯一归档运行 [40977233884](https://github.com/ecojust/Agent-OS/actions/runs/40977233884) 成功，证据提交为 `269358107d566d62ffa4ed00a325d465ba39e50d`。独立的[设备证据目录](../../../../screenshots/40976664678-1/)包含 **32 payload、25,212 B、0 PNG、8 blocked、0 secure-window 临时例外**；这里的诊断材料不计入那 32 个 payload。已核对源码、attempt、固定 scope、各 API 作业及 artifact 上传区间，全部 payload 的字节数、SHA-256 和实际文件集合；单案 JSON 与汇总 JSON 一致，原 JUnit 汇总为 8 tests/8 failures/0 errors/0 skipped/0 unreadable/0 unexpected。blocked 保留为未取得截图，不能推定系统拒绝了受保护窗口截图。性能附件为 8 项 `invalid`，`budgetAcceptance=false`，不能算性能验收。

本次受控前对照支持“已知未授权来源遇到控制窗重建时未按合同暂停”的确定缺陷；它不单独证明所有历史导航失败或 API30 旋转失败均有同一根因，也不证明修正后的行为已通过。原运行和全部失败保留，不追加设备重跑，不关闭未完成验收。
