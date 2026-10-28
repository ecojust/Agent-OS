# 公开 APK 的 API 行为覆盖盘点

当前完整通过基线为源码 `81f0548d3be3f2372f492b9dcdcb865bbbb8cfb6`。本表将公开 APK 能力与已有测试对应，是代码与覆盖范围盘点，不是新增的冻结验收合同，也不构成通过证明。具体结果须核对同一源码的原始测试结果、截图清单及运行范围；专项通过不能替代完整回归。

源码81f的完整[运行40636551126](https://github.com/ecojust/Agent-OS/actions/runs/40636551126)在 API30–37 八档各完成246项，**1968/1968通过，零失败、错误、跳过和缺失**。[原始归档](../../screenshots/40636551126-1/manifest.json)保留4232个载荷、45,840,596字节、2232张实际PNG（2228张320×640、4张831×1662）及1045条blocked记录。实际文件集合、大小、SHA-256、PNG解码、逐案结果、采集的JUnit摘要及准确源码的246个用例身份已独立核对；用例集合与先前3cff完整回归一致。

先前源码 `3cff90d62fe181fdee9c83b6348ea4becd6ac121` 的完整[运行40633417256](https://github.com/ecojust/Agent-OS/actions/runs/40633417256)仍记为**1967/1968通过，1失败，零错误、跳过和缺失**。[原始归档](../../screenshots/40633417256-1/manifest.json)保留4231个载荷、45,906,466字节、2231张实际PNG（2227张320×640、4张831×1662）及1046条blocked记录，不被本次通过覆盖。

原唯一失败为API36的 `VisualEventGenerationInstrumentedTest.actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications`：真实同高度状态事件后，视觉代次预期42、实际43。[原始连续日志](diagnostics/visual-generation-40633417256/)保持原样。源码81f仅新增默认关闭的有界失效记录及固定诊断范围，既有判定、三轮检查、原5秒期限和断言均未放宽。[专项40636096797](../../screenshots/40636096797-1/manifest.json)共8项通过，其中API30–33四项只验证不支持拒绝，API34–37四项执行实际正例；48个载荷、152,202字节、16张320×640 PNG已核验。专项与随后完整回归均未复现原失败，**具体根因仍未知，不能把诊断记录称为根因修复，也不能归因为字体恢复**。

更早的完整[运行40625516651](https://github.com/ecojust/Agent-OS/actions/runs/40625516651)及[原始清单](../../screenshots/40625516651-1/manifest.json)保留源码5c、八档各245项、**1960/1960通过，零失败、错误、跳过和缺失**的准确范围；它不包含后加的真实字体用例。各次运行独立记录，不拼接专项或不同源码的通过项。

工程检查点仍为45%，`api_matrix` 仍在进行中；百分比不是设备或用例覆盖率。它记录 Android 11—17 的 API 行为验证。真实模型、第三方应用日常使用、真机、性能及 ROM 分别验收；它们不因本表或模拟器结果而完成，也不全部并入此检查点。

同一81f源码的API35[操作录像](../../screenshots/demos/40636167819-1/demo.mp4)及[清单](../../screenshots/demos/40636167819-1/manifest.json)已核验：37.981秒、443,342字节、22张流程截图，使用本地合成HTTP模型并保持安全窗口标志，未安装失效记录观察器。它记录普通操作流程，不包含字体变化、实际熄屏或受保护诊断界面，也不证明真实模型或真机验收。此前[3cff录像](../../screenshots/demos/40633395073-1/demo.mp4)及[清单](../../screenshots/demos/40633395073-1/manifest.json)保留原源码标记。

## 当前能力与已有用例

| 公开 APK 契约 | 已有测试源与具体核对点 | 结果边界 |
| --- | --- | --- |
| 打开界面不自动开始任务 | [MainActivityInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/MainActivityInstrumentedTest.java)：`nativeLauncherOpensWithoutStartingAnAgentTask` | 不等于应用更新、进程回收与重启全流程验收 |
| 加密配置保留、损坏／未知版本拒绝、授权绑定安装身份 | [SettingsStoreInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/SettingsStoreInstrumentedTest.java)：`encryptedConfigurationSurvivesAStoreReopen`、`corruptedConfigurationIsNotSilentlyOverwritten`、`newerConfigurationVersionIsPreserved`、`applicationGrantMatchesTheInstalledIdentity` | 实际 Keystore／存储检查不替代正式签名连续性与升级验收 |
| 本地 HTTP 计划经确认后操作真实合成应用，并重新观察结果 | [AgentControllerInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/AgentControllerInstrumentedTest.java)：`approvedHttpPlansTypeSaveAndClickWithFreshDeviceObservations`；[DeviceToolsInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/DeviceToolsInstrumentedTest.java)：`entersTextAndVerifiesTheActualSavedFixtureState` | 验证执行链，不证明真实模型理解或任意第三方应用可操作 |
| 暂停、停止、撤权与配置失效后拒绝旧操作 | [AgentControllerInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/AgentControllerInstrumentedTest.java)：`pausingAfterConfirmationWhileLayoutIsPendingNeverDispatchesTheOldInput`、`stoppingAfterConfirmationWhileLayoutIsPendingRejectsLateApprovalAndNeverWrites`；[DeviceToolsInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/DeviceToolsInstrumentedTest.java)：`revokingAccessibilityCannotLeaveAUsableExecutor`；[ConfigurationLeaseControllerInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/ConfigurationLeaseControllerInstrumentedTest.java) | 直接调用暂停／停止不等于真实平台熄屏／锁屏；旧任务与替代任务仍需分别核对 |
| 实际熄屏中止待确认任务，唤醒后旧审批与恢复调用不能继续原任务 | [AgentControllerInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/AgentControllerInstrumentedTest.java)：`actualScreenOffStopsPendingApprovalAndWakeDoesNotResumeIt` | 真实非交互状态、同一任务终态、零派发与原计数不变在八档通过；不覆盖密码锁屏、模型等待／已提交动作中的熄屏或硬件停止时延 |
| 按实际输入焦点观察，拒绝未知遮挡和密码内容 | [DeviceToolsInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/DeviceToolsInstrumentedTest.java)：`observationsAndWritesFollowTheInputFocusedWindowAcrossNavigation`、`showingTheKeyboardDoesNotSwitchObservationToAnOldApplicationWindow`、`anUnrecognizedOverlayStillBlocksTheTargetAndPausesBeforeDispatch`、`passwordContentIsRedactedAndCannotBeModified` | 当前窗口、键盘及遮挡场景不扩展为旋转、多窗口或 PiP 全覆盖 |
| 控制条身份、精确边界、48dp目标与当前节点语义一致 | [OwnedControlWindowInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/OwnedControlWindowInstrumentedTest.java)：`ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow` 及原窗口失效用例；[VisualEventGenerationInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/VisualEventGenerationInstrumentedTest.java)：`actualControlGrowthAndRootVisibilityAndUnknownWindowsRevokeWithoutAnyDeviceAction`、`actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications` | 3cff的API36同高度状态事件失败在81f未复现，原因未知；该同高度用例低API只验证不支持拒绝，API30有明确缓存准备。24sp局部布局与节点标签不替代系统字体或实际读屏服务验证 |
| 真实系统字号1.0→1.3后服务控制窗重建，旧窗口失效，并恢复原字体设置与实际配置 | [FontScaleControlWindowInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/FontScaleControlWindowInstrumentedTest.java)：`realSystemFontScaleRebuildsControlsAndRevokesOldWindow` | 3cff专项及81f完整回归内该用例均8/8通过；仅该字号样本、服务控件与合成 `RunControl`，按钮 `performClick()` 不证明物理命中；不覆盖主Activity、旋转或实际读屏服务 |
| 主界面真实系统字体重建保留同profile内存草稿，主动关闭重开丢弃未保存内容 | [MainActivityFontScaleInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/MainActivityFontScaleInstrumentedTest.java)：`realSystemFontScaleRecreatesSecureActivityAndRetainsOnlyInMemoryDraft` | b7bb专项八档8/8；安全窗口始终保留，不尝试PNG；不覆盖进程死亡、所有字号、旋转或读屏服务 |
| 连续导航模式只允许已声明的普通导航省去逐次确认 | [ContinuousNavigationInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/ContinuousNavigationInstrumentedTest.java)：`launchScrollAndHomeChangeTheRealUiWithoutAnApproval`、`textClickAndBackStillRequireDistinctApprovalsInNavigationMode`、`rejectingInputCannotBeBypassedWithAnyNavigationTool` | 点击、输入与返回仍需确认，不代表任意连续自动操作 |
| 任务诊断由用户预览／复制，旧预览不能操作替代任务 | [TaskDiagnosticsInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/TaskDiagnosticsInstrumentedTest.java)：`activePreviewIsFrozenRedactedExplicitlyCopiedAndCanStopItsOriginalTask`、`completedCodeSurvivesButClearReplacementAndLeavingInvalidateOldDialogActions` | 清空前确认预览已真实获得焦点，不覆盖同帧快速显示／关闭；诊断与预算数字不代表性能预算或完成动作数量 |

## 实际熄屏停止：专项及完整套件中的八档用例通过

生产服务已有屏幕广播及交互／锁屏状态检查；先前 `DeviceHarness` 只在初始化和清理时唤醒、解除专用模拟器键盘锁。完整回归40620143610之后新增了 `actualScreenOffStopsPendingApprovalAndWakeDoesNotResumeIt`，复用现有控制器测试设施。此新增用例不属于先前1952项结果；新增源码 `ba5fbe29561bfca4471d18b6bffd3d7c04a796f7` 的[专项清单](../../screenshots/40622578371-1/manifest.json)记录八档8/8通过，后续5c、3cff及本次81f完整回归中，该用例也均在八档通过，实际覆盖如下：

1. 使用 `DeviceHarness`、本地 `REJECT_CLICK` 场景与真实保存配置，等待实际控制窗和待确认请求；记录原审批、原 Controller 的 `RunControl`、请求次数及 `Count: 0`，确认派发次数为零。
2. 确认实际亮屏后只发送一次 `input keyevent 223`，在固定期限内分别确认 `PowerManager.isInteractive()==false` 与任务停止。核对审批清除、原任务取消、`STOPPED/TASK_STOPPED` 及零派发；不发送伪造屏幕广播、不直接调用停止来制造通过。
3. 唤醒并解除专用模拟器键盘锁，尝试旧审批与恢复入口，确认旧任务不恢复、没有新模型请求、原派发仍为零、实际计数仍为零。不得重新启动或重置 Fixture 来读取结果，否则可能抹去副作用。前后保存真实合成界面截图，熄屏状态由断言记录，不把黑屏作为可用截图。
4. 失败先保留有界状态与计数，再在 `finally` 唤醒、清理任务并恢复原配置和无障碍设置。等待超时保持失败，不重发熄屏或放宽保护。

`android.yml` 的 `screen-off-diagnostic` 只选择这个用例，API30–37八档共8项；工作流标题、原JUnit选择和截图清单核对同一范围。新增熄屏用例时完整套件为每档245项、共1960项；此后加入字体用例为每档246项。本次1968项完整通过来自40636551126的独立核验，不由专项或编译结果推算。

这只验证待确认阶段的实际熄屏与唤醒后不恢复。无凭据模拟器不保证安全键盘锁处于锁定状态，因此不能据此报告密码锁屏验收；模型等待或已提交动作中的熄屏也属于其他场景。

## 任务诊断焦点：保留原失败与测试条件变化

源码 `ba5fbe29561bfca4471d18b6bffd3d7c04a796f7` 的[完整回归40622992507](../../screenshots/40622992507-1/manifest.json)为**1959/1960通过，1失败，零错误、跳过和缺失**。新增熄屏用例八档全部通过；唯一失败是API30既有任务诊断用例清空后等待主窗口获得焦点。原失败及[现场摘录](diagnostics/task-report-focus-40622992507/)保持原样，不与后续通过结果拼接。

末段测试原先在同一主线程回调内显示诊断 Dialog 并立即清空。当前源码分开这两个步骤，等待当次真实 Dialog 获得焦点，再核对同一 Dialog／快照和显示状态，执行原清空与旧复制断言。清空后原15秒主窗焦点期限、剪贴板不变及旧任务操作失效检查均保留；底层按钮的 `performClick()` 是调用实际监听器的测试注入，不代表用户可以触摸穿透模态窗。

修正后的[专项40625129440](../../screenshots/40625129440-1/manifest.json)为8/8，随后同一 `5c71d4a485de8806a4436db02bfc72e43fc68670` 源码完整1960项通过。这验证的是“已显示并获得焦点的预览被清空后失效”；**没有证明原API30失败根因，也未覆盖同帧快速显示／关闭**。生产代码和安全窗口保护未因本次测试准备修正改变。

## 真实系统字体变化：服务控件与原配置恢复用例八档通过

源码 `3cff90d62fe181fdee9c83b6348ea4becd6ac121` 的[字体专项40632979448](https://github.com/ecojust/Agent-OS/actions/runs/40632979448)经独立核验为**API30–37八档8/8通过，零失败、错误、跳过和缺失**。[原始清单](../../screenshots/40632979448-1/manifest.json)保留64个载荷、1,082,679字节和32张真实PNG；这是开发分支的固定专项，未声明主线或完整套件验收。截图保持原采集时序，基线图可能仍显示较早文案，不作为断言瞬间文字同步的证明。

[FontScaleControlWindowInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/FontScaleControlWindowInstrumentedTest.java) 的 `realSystemFontScaleRebuildsControlsAndRevokesOldWindow` 在专用模拟器将系统字体比例从1.0变为1.3：保存原始设置及实际配置，通过系统设置触发变化并等待服务／应用资源中的实际字体比例生效，不手动调用配置回调、不修改显示密度或旋转。核对旧View已移除、旧身份和许可失效，新窗口保持原状态、文字实际变大、平台登记与真实View几何一致；当前暂停／停止按钮仍至少48dp、位于可见安全区域，短标签没有省略或裁切。API34及以上支持非线性缩放，不要求像素大小简单乘以1.3。

清理同时验证原raw设置和实际配置恢复：在同一个原10秒期限内，先显式写回原实际字号并等待生效，再原样恢复原设置字符串；原值缺失则删除该设置行。最终仍要求设置行及服务／应用实际字体比例全部匹配。两阶段任一步失败仍计为失败，并尝试恢复原行及隐藏窗口；原始失败不被清理异常覆盖。这一恢复顺序不证明先前失败的具体raw差异，也不证明隐藏窗口会阻止配置更新。

该用例还保留旧按钮引用，检查其真实监听器拒绝旧窗口调用，再通过新窗口按钮的实际监听器验证继续、暂停和停止。`performClick()` 属于测试注入，不证明物理触摸命中；回调使用合成 `RunControl`，不冒充真实 `AgentController` 任务链。API30在指定阶段之前原样重设 UiAutomation 配置以清理测试节点缓存，不改变生产逻辑或观察期限。

先前源码5c71d4a48的完整1960项回归保持其准确范围，不包含新增字体用例。`font-scale-diagnostic` 固定选择本用例，八档共8项；3cff和本次81f的完整回归中，该用例也均8/8通过，是各自完整套件的子集，不另加执行分母。3cff整套的API36视觉代次失败仍保留。该首个放大样本不代表最大字体、Activity重建、横竖屏或读屏服务已验收。主界面真实字体变化后的重建、草稿恢复规则和安全窗口保护现已由下节b7bb独立专项验证，不能由主动 `recreate()` 替代系统配置变化。

两轮原始专项分别保留于 [40628762988](../../screenshots/40628762988-1/manifest.json) 和 [40630197321](../../screenshots/40630197321-1/manifest.json)，均1/8通过、7失败，零错误／跳过／缺失。[原始冻结数值](diagnostics/font-control-safe-area-40630197321/)确认七档字体1.0时面板顶部12、安全顶部24，且实际布局已完成；这些基线失败不能归因为1.3字体重建。生产修正按全局安全区统一初始、重建与拖动坐标，安全验证先于身份／许可发布，保留48dp及精确登记要求。独立推导的831×1662大窗口样本已在本次API34–37实际通过，继续要求View／登记／平台矩形精确相等；归档中的四张831×1662图片是现场截图，转换输出另由原用例核对640×1280尺寸及真实PNG超限拒绝。

安全区修正源码b6fd的[原拖动／审批收缩专项](../../screenshots/40631512822-1/manifest.json)8/8通过，但[字体专项](../../screenshots/40631510591-1/manifest.json)仅4/8通过：API30–33均在最后恢复原字体配置超时，仍计为失败。源码3cff仅修正测试清理，生产安全区修正不变；其后8/8通过不抹去这四项失败。四项失败的具体raw差异原先未记录，恢复失败的具体根因仍未证明。


## 主Activity真实字体变化：八档8/8通过

源码b7bb的[专项40642275055](https://github.com/ecojust/Agent-OS/actions/runs/40642275055)八档8/8通过，零失败、错误、跳过、额外用例、不可读和缺失。 [MainActivityFontScaleInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/MainActivityFontScaleInstrumentedTest.java) 在每档真实设置1.0→1.3，验证实际Activity替换、创建时同profile非配置对象、草稿原始文本与settings对象身份、最终saved-state Bundle无合成敏感marker，以及主动finish再打开恢复已保存配置和空任务。关键按钮在真实View滚动后核对48dp、安全区和完整标签；原始偏好／配置lease不变，真实Controller与模型请求保持基线，原字体及所有所见Activity／worker完成清理。

[归档](../../screenshots/40642275055-1/manifest.json)32载荷、34,396字节，完整集合、大小/SHA-256、逐案JSON、JUnit摘要与原日志均独立核验。每档基线、实际替换、fresh-open、结束各记录一次保护性未尝试捕获，共32条blocked，零PNG；不解除FLAG_SECURE、不把无PNG当作测试跳过，也不称为操作系统截图拒绝。性能8invalid，专项fullSuiteAcceptance=false。该1.3样本不代表进程死亡、所有字号、横竖屏、实际读屏服务或真机验收。

两轮测试契约失败保持原样：[8b97首轮](../../screenshots/40640583322-1/manifest.json)0/8，baseline时误断言RadioButton.performClick返回true；[原日志](diagnostics/activity-font-radio-40640583322/)未记录当时checked值。修正为单次真实点击后核对按钮、分组与草稿状态。[2691第二轮](../../screenshots/40641436998-1/manifest.json)0/8，实际替换后UI草稿检查已通过，但恢复后系统交接对象非空断言失败；[原日志](diagnostics/activity-font-retention-40641436998/)保留。Android在performResume中清空交接引用，测试现于真实postCreated采样，再检查原对象身份；fresh-open的空值也在创建时检查。生产行为、原安全及期限断言均未改，不把后来的通过写回旧记录。

同b7bb[普通录像](../../screenshots/demos/40642313167-1/demo.mp4)39.064秒、457,501字节、22张流程PNG已核验；其13阶段和8确认通过，安全标志保留，使用本地合成模型，不执行字体专项。相同精确源码的[完整1976项40643561197](https://github.com/ecojust/Agent-OS/actions/runs/40643561197)实际1974/1976通过，API32／33的该新用例在最后原raw恢复失败：原记录应不存在，最后raw为1.0但service/app比例已为1.0；新增用例在整套中为6/8，不用专项8/8替代。[全量归档](../../screenshots/40643561197-1/manifest.json)4239载荷、46,323,541字节、2231PNG已独立核验，零错误、跳过、缺失。1077条blocked及24次既有空界面安全标志例外保留；8份性能通过仍非预算验收。[原始连续日志](diagnostics/activity-font-restore-40643561197/)保留，持久化写回时点待修正。完整通过基线仍为81f的1968/1968。

## 原始字体设置恢复：14c两个八档专项通过

共享字体辅助器对每次真实数值变化先写一次等值的非规范数字，再等框架规范化字符串与实际服务／应用资源同时匹配；相同数值分支不等待不会发生的回写。最后原raw或缺失行仍单次恢复，整个恢复阶段共用原十秒，不放宽断言、不重试。这个观测条件适用于本测试的受控字体序列，不是任意系统队列空闲的证明；早期失败仍保留并尽力恢复。

精确14c源码的[Activity专项](../../screenshots/40646422586-1/manifest.json)与[控制窗专项](../../screenshots/40646425323-1/manifest.json)各8/8通过，零失败、错误、跳过、缺失；所有32+64载荷及逐案／JUnit摘要已独立核验。Activity保留32条保护性未尝试捕获、零PNG；控制窗32张真实PNG全解码，安全标志未清除。原b7bb全量1974/1976及两项恢复失败保持原样，不与新专项拼成通过。

同14c[普通录像](../../screenshots/demos/40646472832-1/demo.mp4)36.885秒、440,347字节已核验；普通流程不执行两个字体用例。相同精确源码的[完整1976项40647046321](https://github.com/ecojust/Agent-OS/actions/runs/40647046321)正在运行，旧完整通过基线仍是81f，不把专项16项代替1976项。

## 分开的后续验证

- **后续独立验证：** 主Activity实际1.0→1.3、草稿及安全窗口专项已8/8通过；更多字体样本、横竖屏后关键操作可达与实际读屏服务共存仍按 [R8](../DESIGN_RULES.md#r8-统一交互与可访问性) 分别安排。完整回归结果另行核对，不合并专项分母。
- **独立应用回归：** [固定第三方应用探针](third-party-smoke.md)保留 API31 Notes/off 的键盘准备失败与原32组合。其定位和复测属于应用检查点，不计入执行器合成应用矩阵。
- **外部条件：** 真实模型任务需要可用的实际服务配置；厂商与低资源验收需要对应设备；AOSP、OTA及恢复需要明确参考机型、地区变体、启动链与恢复资料；正式交付还需签名和维护条件。APK试用不要求解锁 Bootloader。
- **未公开能力：** 普通 APK 的截图及坐标点击／滑动入口仍未开放。内部视觉链路和低API“不支持”断言分别记录，不算公开能力的正向通过，也不因新增本表而扩大 `api_matrix` 的范围。

依据：[兼容性矩阵与发布条件](../COMPATIBILITY.md)、[设计规则的验收安排](../DESIGN_RULES.md#验收安排)、[产品里程碑](../PRODUCT_PLAN.md#5-里程碑与验收)。
