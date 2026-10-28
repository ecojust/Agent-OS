# 公开 APK 的 API 行为覆盖盘点

本表基于源码 `7af1ac97903148e3ff912e1fdb10de3a2f7c312d`，将当前公开 APK 能力与已有测试对应，便于安排下一步验证。它是代码与覆盖范围盘点，不是新增的冻结验收合同，也不证明列出的用例已在每个 API 通过。具体结果须核对同一源码的原始 JUnit、截图清单及运行范围；专项通过不能替代完整回归。

`api_matrix` 记录 Android 11—17 的 API 行为验证。真实模型、第三方应用日常使用、真机、性能及 ROM 分别验收；它们不因本表或模拟器全绿而完成，也不全部并入此检查点。

## 当前能力与已有用例

| 公开 APK 契约 | 已有测试源与具体核对点 | 结果边界 |
| --- | --- | --- |
| 打开界面不自动开始任务 | [MainActivityInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/MainActivityInstrumentedTest.java)：`nativeLauncherOpensWithoutStartingAnAgentTask` | 不等于应用更新、进程回收与重启全流程验收 |
| 加密配置保留、损坏／未知版本拒绝、授权绑定安装身份 | [SettingsStoreInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/SettingsStoreInstrumentedTest.java)：`encryptedConfigurationSurvivesAStoreReopen`、`corruptedConfigurationIsNotSilentlyOverwritten`、`newerConfigurationVersionIsPreserved`、`applicationGrantMatchesTheInstalledIdentity` | 实际 Keystore／存储检查不替代正式签名连续性与升级验收 |
| 本地 HTTP 计划经确认后操作真实合成应用，并重新观察结果 | [AgentControllerInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/AgentControllerInstrumentedTest.java)：`approvedHttpPlansTypeSaveAndClickWithFreshDeviceObservations`；[DeviceToolsInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/DeviceToolsInstrumentedTest.java)：`entersTextAndVerifiesTheActualSavedFixtureState` | 验证执行链，不证明真实模型理解或任意第三方应用可操作 |
| 暂停、停止、撤权与配置失效后拒绝旧操作 | [AgentControllerInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/AgentControllerInstrumentedTest.java)：`pausingAfterConfirmationWhileLayoutIsPendingNeverDispatchesTheOldInput`、`stoppingAfterConfirmationWhileLayoutIsPendingRejectsLateApprovalAndNeverWrites`；[DeviceToolsInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/DeviceToolsInstrumentedTest.java)：`revokingAccessibilityCannotLeaveAUsableExecutor`；[ConfigurationLeaseControllerInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/ConfigurationLeaseControllerInstrumentedTest.java) | 直接调用暂停／停止不等于真实平台熄屏／锁屏；旧任务与替代任务仍需分别核对 |
| 按实际输入焦点观察，拒绝未知遮挡和密码内容 | [DeviceToolsInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/DeviceToolsInstrumentedTest.java)：`observationsAndWritesFollowTheInputFocusedWindowAcrossNavigation`、`showingTheKeyboardDoesNotSwitchObservationToAnOldApplicationWindow`、`anUnrecognizedOverlayStillBlocksTheTargetAndPausesBeforeDispatch`、`passwordContentIsRedactedAndCannotBeModified` | 当前窗口、键盘及遮挡场景不扩展为旋转、多窗口或 PiP 全覆盖 |
| 控制条身份、精确边界、48dp目标与当前节点语义一致 | [OwnedControlWindowInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/OwnedControlWindowInstrumentedTest.java)：`ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow` 及原窗口失效用例；[VisualEventGenerationInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/VisualEventGenerationInstrumentedTest.java)：`actualControlGrowthAndRootVisibilityAndUnknownWindowsRevokeWithoutAnyDeviceAction` | API30状态用例有明确的 UiAutomation 缓存准备；24sp布局测试不等于系统字体缩放；节点标签不等于实际读屏服务共存 |
| 连续导航模式只允许已声明的普通导航省去逐次确认 | [ContinuousNavigationInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/ContinuousNavigationInstrumentedTest.java)：`launchScrollAndHomeChangeTheRealUiWithoutAnApproval`、`textClickAndBackStillRequireDistinctApprovalsInNavigationMode`、`rejectingInputCannotBeBypassedWithAnyNavigationTool` | 点击、输入与返回仍需确认，不代表任意连续自动操作 |
| 任务诊断由用户预览／复制，旧预览不能操作替代任务 | [TaskDiagnosticsInstrumentedTest](../../app/src/androidTest/java/dev/agentos/app/TaskDiagnosticsInstrumentedTest.java)：`activePreviewIsFrozenRedactedExplicitlyCopiedAndCanStopItsOriginalTask`、`completedCodeSurvivesButClearReplacementAndLeavingInvalidateOldDialogActions` | 诊断与预算数字不代表性能预算或完成动作数量 |

## 最小下一步：实际熄屏停止

当前生产服务已有屏幕广播及交互／锁屏状态检查；测试中的 `DeviceHarness` 只在初始化和清理时唤醒、解除专用模拟器键盘锁，没有触发真实熄屏并验证停止的仪器用例。先完成当前源码的完整回归，再新增一个 `actualScreenOffStopsPendingApprovalAndWakeDoesNotResumeIt` 用例，复用现有控制器测试设施：

1. 使用 `DeviceHarness`、本地 `REJECT_CLICK` 场景与真实保存配置，等待实际控制窗和待确认请求；记录原审批、原 Controller 的 `RunControl`、请求次数及 `Count: 0`，确认派发次数为零。
2. 确认实际亮屏后只发送一次 `input keyevent 223`，在固定期限内分别确认 `PowerManager.isInteractive()==false` 与任务停止。核对审批清除、原任务取消、`STOPPED/TASK_STOPPED` 及零派发；不发送伪造屏幕广播、不直接调用停止来制造通过。
3. 唤醒并解除专用模拟器键盘锁，尝试旧审批与恢复入口，确认旧任务不恢复、没有新模型请求、原派发仍为零、实际计数仍为零。不得重新启动或重置 Fixture 来读取结果，否则可能抹去副作用。前后保存真实合成界面截图，熄屏状态由断言记录，不把黑屏作为可用截图。
4. 失败先保留有界状态与计数，再在 `finally` 唤醒、清理任务并恢复原配置和无障碍设置。等待超时保持失败，不重发熄屏或放宽保护。

这只验证待确认阶段的实际熄屏与唤醒后不恢复。无凭据模拟器不保证安全键盘锁处于锁定状态，因此不能据此报告密码锁屏验收；模型等待或已提交动作中的熄屏也属于其他场景。

## 分开的后续验证

- **已有公开能力的验证缺口：** 系统字体缩放、横竖屏后关键操作可达、实际读屏服务共存。依据 [R8](../DESIGN_RULES.md#r8-统一交互与可访问性) 逐项安排；其中字体／旋转可先用专用模拟器验证，不能由单次24sp文字测量推断通过。
- **独立应用回归：** [固定第三方应用探针](third-party-smoke.md)保留 API31 Notes/off 的键盘准备失败与原32组合。其定位和复测属于应用检查点，不计入执行器合成应用矩阵。
- **外部条件：** 真实模型任务需要可用的实际服务配置；厂商与低资源验收需要对应设备；AOSP、OTA及恢复需要明确参考机型、地区变体、启动链与恢复资料；正式交付还需签名和维护条件。APK试用不要求解锁 Bootloader。
- **未公开能力：** 普通 APK 的截图及坐标点击／滑动入口仍未开放。内部视觉链路和低API“不支持”断言分别记录，不算公开能力的正向通过，也不因新增本表而扩大 `api_matrix` 的范围。

依据：[兼容性矩阵与发布条件](../COMPATIBILITY.md)、[设计规则的验收安排](../DESIGN_RULES.md#验收安排)、[产品里程碑](../PRODUCT_PLAN.md#5-里程碑与验收)。
