# cdea 整体录像 ready 失败：设置来源只读核对

本文件仅分析已有原件和固定源码；未改代码、未补录或复测。原运行 41178250336/source cdea60c126af3cb50805f6e989eccbf7a7a1efb1，API35；失败 `Accessibility service did not connect`，ready 未完成，0/13阶段，0确认。原件见 [cdea失败清单](../../../../screenshots/demos/41178250336-1/manifest.json)。

## 可以直接确认的顺序

1. `DemoWalkthroughInstrumentedTest.java:499–510` 的预检只读 qemu，并以 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` 取得 UiAutomation。
2. `DeviceHarness.java:49,58–66` 同样请求非抑制模式，先读原 settings，再通过 Settings provider 写入 Agent 组件列表和全局 flag。`writeSecure:583–598` 对 putString 返回值断言成功；组件列表还有立即 readback 相等断言。原堆栈已进入 await 后的连接断言，意味着这次同步写入和立即读回检查当时没有失败。
3. `await:776–783` 最多8秒，每50ms仅查询本进程服务实例和 isConnected；没有反复重写/自动重新启用。
4. `connectionDiagnostics:766–773` 先 shell dumpsys accessibility，再读两项 Settings。原件服务列表 null、全局 flag 1，以及 server Bound/Enabled/Binding/Crashed 全空，属于先后读取，不是原子状态。dump 只给 installedServiceCount=3，没有足够组件名单证明 Agent 当时在 installed 集合。
5. 该字符串在构造器内层catch创建原 AssertionError 时就已求值；随后才进外层catch:90–92的 close。close:785–798 确实恢复原列表和flag，但顺序在诊断冻结之后，所以不能将原 null 直接解释成当前 cleanup 的结果。
6. ScreenshotEvidence 在 base.evaluate 退出后才执行失败capture。demo_video.py:376–439没有写 accessibility settings；只选择模拟器、只读前检、启动 screenrecord、执行指定唯一方法。仅 instrumentation outcome 非 completed 才强停目标；本次 outcome completed/exit0/仪器case失败，不走那条强停支路。
7. 原流程尚未构造控制器/进入writing chapter；本次 compat-smoke 的 Notes后置诊断不在该 app test APK/所选方法中运行。生产 app 没有 disableSelf 调用，未找到此阶段自身另一个清空服务列表的执行点。

## 固定 Android15r1 确实存在的系统写入路径

固定 frameworks/base commit `4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3`，文件大小/摘要/URL见 [固定源码索引](source-index.json)。实际模拟器系统镜像与此tag源码逐字一致尚未证明。

- AccessibilityManagerService `updateServicesLocked:2736–2826` 从当前 mInstalledServices 建立组件集合，再于2810–2819删除不在集合内的 enabled组件并持久化。`persistColonDelimitedSetToSettingLocked:2673–2722` 默认空集合写 null。
- `SettingsStringObserver.onChange:5652–5658` 读 enabled设置后调用 onUserStateChanged/updateServices；此分支没有重新扫描 installed服务。
- `ManagerPackageMonitor.onSomePackagesChanged:6223–6254` 在进入锁前调用 parseAccessibilityServiceInfos；`onSomePackagesChangedLocked:759–769` 更新 mInstalledServices并读配置。新安装与这些异步观察之间存在需要实测区分的顺序。源码提供一种**可发生删除的路径**；本次没有记录其触发、当时installed名单或写入者，不能宣布它就是根因。
- `onPackagesForceStoppedLocked:818–846` 也会删除匹配的 enabled组件，PackageMonitor ACTION_PACKAGE_RESTARTED 会进入此处理；onPackageRemoved:772–800、policy拒绝:2764–2770、AccessibilityServiceConnection.disableSelf 也有删除路径。旧原件没有这些事件的发生证据。
- startInstrumentation:16589 调用了内部 forceStopPackageLocked(..., 'start instr')，但核对的函数体并未直接发送 PACKAGE_RESTARTED；不能仅凭函数名字把上述监听器删除路径拼接为已发生的链。
- `updateAccessibilityEnabledSettingLocked:3251–3262` 使用 UiAutomation可内省或用户服务处理事件决定全局 flag，所以 flag=1 可与 Agent组件列表为空并存，不证明 Agent服务已连接。
- `UiAutomationManager:173–177` 显式按 DONT_SUPPRESS flag 决定是否抑制，项目两处使用该flag；抑制模式本身也不等同于把持久组件列表写 null。

## 最小的下一步诊断建议（尚未实施）

独立声明一个有界诊断运行，不更改原8秒连接断言、不重复启用、不用固定sleep或重试掩盖失败。保存三个阶段的单调时刻：启用前、现有成功write/readback之后、原失败冻结后。每阶段只提取目标固定组件的 installed/enabled/bound成员布尔、集合大小、用户ID/解锁状态以及两项设置是否缺失；不保存完整服务名字或任意dump。把现有同步write返回/readback结果冻结，可避免为同一事实新增读取。

若需要捕捉清空时刻，另立诊断预算注册一次只读ContentObserver，仅计数和保存少量设置变更时刻/目标组件成员状态（如最多8条）；实际服务列表查询与后置dump须独立记录成本，超限/不可用保持未知。无结果不得重新写enable列表直到成功。若能证实installed目标先缺失后出现、enabled在其间被清除，再考虑“新装后先证明目标服务已被系统识别”是否为有依据的测试初始化修复；当前还不能实施此修复或宣称平台bug。

公共 `AccessibilityManager.getInstalledAccessibilityServiceList()` 是否恰好对应需要观察的同用户服务端 installed 集合、调用权限和生命周期，需要在实施前单独核对；本页没有以 PM 查询结果代替该集合，也没有声称某个公共读取已经验证可用。
