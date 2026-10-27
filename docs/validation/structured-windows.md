# 结构化焦点窗口阶段验收

本记录于2026-10-27公开固定已有设计规则与测试证据的映射，不将这份映射倒称为测试前冻结的清单。它验收当前已开放结构化路径中的输入焦点选择、旧目标拒绝、实际控制窗口身份、平台已公开的遮挡及最终检查；对应设计规则R1–R4。

测试源码为 `a0b7e49b976aa080f768cf44f385f2cf4d070aee`，[第46轮CI](https://github.com/ecojust/Agent-OS/actions/runs/40489790843)整套1720项通过。下列18项在API30、31、32、33、34、35、36、37.0各有通过记录，共144条；这些是原1720项的子集，没有重复计作新增执行。逐项JSON的大小及SHA-256与完整归档清单一致。

| 既有用例 | API34逐项证据（其余七档同名用例亦通过） |
| --- | --- |
| `DeviceToolsInstrumentedTest.initialObservationFromAgentScreenCanLaunchAnAuthorizedApp` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-initialObservationFromAgentScreenCanLaunchAnAuthorizedApp-7fce9338.json) |
| `DeviceToolsInstrumentedTest.observationsAndWritesFollowTheInputFocusedWindowAcrossNavigation` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-observationsAndWritesFollowTheInputFocusedWindowAcrossNavigation-cee21a48.json) |
| `DeviceToolsInstrumentedTest.showingTheKeyboardDoesNotSwitchObservationToAnOldApplicationWindow` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-showingTheKeyboardDoesNotSwitchObservationToAnOldApplicationWindow-55027df9.json) |
| `DeviceToolsInstrumentedTest.ordinarySystemBarsDoNotPreventBackOrHome` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-ordinarySystemBarsDoNotPreventBackOrHome-30358081.json) |
| `DeviceToolsInstrumentedTest.anUnrecognizedOverlayStillBlocksTheTargetAndPausesBeforeDispatch` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-anUnrecognizedOverlayStillBlocksTheTargetAndPausesBeforeDispatch-14ea6acb.json) |
| `DeviceToolsInstrumentedTest.theCompactStopControlsStillPreventClickingACoveredFixtureTarget` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-theCompactStopControlsStillPreventClickingACoveredFixtureTarget-262b593a.json) |
| `DeviceToolsInstrumentedTest.changingActivitiesInvalidatesTheOldTarget` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-changingActivitiesInvalidatesTheOldTarget-44a337ec.json) |
| `DeviceToolsInstrumentedTest.staleRevisionCannotRepeatACompletedClick` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-staleRevisionCannotRepeatACompletedClick-d515098f.json) |
| `OwnedControlWindowInstrumentedTest.ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-7dfec3b9.json) |
| `OwnedControlWindowInstrumentedTest.matchingTitleAndPackageCannotClaimAnUnregisteredOverlay` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-matchingTitleAndPackageCannotClaimAnUnregisteredOverlay-627112ba.json) |
| `OwnedControlWindowInstrumentedTest.detachingAndRecreatingControlsCannotReviveAnOldIdentityOrLease` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-detachingAndRecreatingControlsCannotReviveAnOldIdentityOrLease-f51a4cad.json) |
| `OwnedControlWindowInstrumentedTest.absentOrNotYetLaidOutControlsCannotIssueAWindowIdentity` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-absentOrNotYetLaidOutControlsCannotIssueAWindowIdentity-45e6cf4f.json) |
| `OwnedControlWindowInstrumentedTest.changedPresentationAndCallbacksStillRevokeTheActualControlOwner` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-changedPresentationAndCallbacksStillRevokeTheActualControlOwner-c0816d3e.json) |
| `SlowFinalReadInstrumentedTest.pauseDuringTheApprovedFinalReadRemainsResponsiveAndInvalidatesTheOldEpoch` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-pauseDuringTheApprovedFinalReadRemainsResponsiveAndInvalidatesTheOldEpoch-759ef896.json) |
| `SlowFinalReadInstrumentedTest.stopDuringTheApprovedFinalReadRemainsResponsiveAndNeverDispatches` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-stopDuringTheApprovedFinalReadRemainsResponsiveAndNeverDispatches-b7ede3af.json) |
| `SlowFinalReadInstrumentedTest.publishedConfigurationInvalidationRejectsAnApprovedFinalReadBeforeItsCancelListenerRuns` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-publishedConfigurationInvalidationRejectsAnApprovedFinalReadBeforeItsCancelListenerRuns-b5043e70.json) |
| `SlowFinalReadInstrumentedTest.invalidatingTheControlOwnerDuringFinalReadRejectsAnOtherwiseUnchangedPlan` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-invalidatingTheControlOwnerDuringFinalReadRejectsAnOtherwiseUnchangedPlan-b7f24e9c.json) |
| `SlowFinalReadInstrumentedTest.repeatedIdenticalControlPublicationsDuringFinalReadKeepOneApprovedActionValid` | [实际记录](../../screenshots/40489790843-1/api-34/screenshots/api-34-repeatedIdenticalControlPublicationsDuringFinalReadKeepOneApprovedActionValid-3827da42.json) |

此阶段覆盖：从本应用启动已授权目标、应用切换后选择输入焦点窗口、键盘出现时不误用旧应用、标准系统栏、实际未知浮层与本方控制条遮挡、旧版本拒绝，以及最终慢读取期间的暂停、停止、配置与控制身份变更。自有窗口不能仅凭标题或包名认领，同值状态发布不应自行撤销未改变的合法控制身份。

验收边界继续保留：普通APK依赖系统公开的窗口元数据；历史IME未报告的差异尚未定位根因，未报告的窗口不能被描述为已可靠识别。本记录不涵盖厂商真机、独立旋转/多窗口/画中画验收、生产截图或坐标手势，也不证明所有第三方页面可操作。相关检查点继续分别跟踪。

连续导航首阶段已有另外176条回归证据，范围仅为已授权启动、滚动、主页与逐次重新观察；点击、输入和返回仍需要独立确认。更广的`continuous_actions`检查点保持进行中。


## 公开输入法信息冲突的保守拒绝

第五十一轮API31显示实际键盘可见，但无障碍窗口清单没有IME；同一服务的公开`WindowMetrics`却报告IME可见及246像素底部区域。原失败及截图保存在[该轮证据](../../screenshots/40504724795-1/manifest.json)，不重新标为通过。

后续实现复用每次完整快照已经取得的WindowMetrics/WindowInsets：明确可见或任一IME边距为正时，必须在同一新鲜窗口清单中找到默认显示器上、有效ID及正面积且与显示器相交的真实输入法窗口。找不到就返回固定的`CAPABILITY_UNAVAILABLE`及“系统报告输入法可见但窗口信息不完整”，不发布观察数据，不合成窗口或借Insets推测输入法身份。最终检查保留该原因，不转成可自动重新观察的STALE结果；IME采样也参与原窗口版本。

false/零边距不是“没有键盘”的证明，实际报告的IME继续经过原遮挡检查。没有新增根读取、轮询、权限或跨应用窗口操作；厂商未报告且没有公开正信号的窗口仍是已知局限。恢复由用户实际收起键盘后重新观察，不自动操作键盘规避拒绝。

原仪器用例继续区分实际完整窗口与明确拒绝两个路径，保留焦点/遮挡、旧计划零确认和零派发检查，并验证用户BACK后的恢复。只有真实执行到拒绝分支才算该分支的设备证据；正常路径通过不能冒充缺窗分支已测试。第五十三轮八档已实跑通过；API30/31/32/33/35/36/37实际触发新拒绝，API34为正常路径，八档均有用户BACK后恢复新观察的断言。包含短时窗口发布差异，不能等同于七档持续5秒漏报；实际绘制动画与读取不同步，截图不是原子状态证明。
