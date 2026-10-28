# Notes v3 准备失败诊断

本轮只补齐第三方测试探针的失败记录，不修改 Agent 产品行为或 Notes APK，也不宣称已修复确认按钮问题。[原 v3 运行](https://github.com/ecojust/Agent-OS/actions/runs/40555177328)保留 **22/32 通过、10 失败**；其中 API31–34 两态在确认按钮准备阶段报告键盘遮挡，API35 两态未取得精确资源。旧记录没有同次目标矩形和资源匹配数量，不能据此确定布局原因。

## 先冻结验证范围

- 使用原[应用清单](third-party-app-set-lock.json)：Calculator 1.4.0、Notes 1.7.0，API30–37，服务关闭/空闲，共32项；保留四个原有测试方法。
- APK 来源、字节数、摘要、签名、默认应用和键盘设置、动作顺序及结果验收均不变。
- 准备与输入回读共用原5秒期限；稳定600毫秒、表面采样间隔至少150毫秒且最多4次。禁止自动BACK，HOME最多一次，不重复写操作。
- 目标仍必须唯一、可见、可用、非密码、属于当次已验证窗口，全部位于应用窗口内且完全不与已验证输入法相交。未知窗口、错误身份和歧义仍立即拒绝。
- 截图仍须正向确认键盘隐藏，继续保留 blocked；不为获得诊断或截图增加输入动作、隐藏键盘或清除安全标记。

固定 Notes 源码的[创建回调](https://github.com/FossifyOrg/Notes/blob/786fc41f68d1aed8d82144a6de0127ed4dbe8b61/app/src/main/kotlin/org/fossify/notes/dialogs/NewNoteDialog.kt#L29)绑定原生确认按钮；[标题布局](https://github.com/FossifyOrg/Notes/blob/786fc41f68d1aed8d82144a6de0127ed4dbe8b61/app/src/main/res/layout/dialog_new_note.xml#L19)没有显式 IME 提交监听。固定 Commons 6.1.0 的 [showKeyboard](https://github.com/FossifyOrg/Commons/blob/61092edcc8adcdfddbd74b9ca94871b27563a9b1/commons/src/main/kotlin/org/fossify/commons/extensions/AlertDialog.kt#L8)负责显示键盘与请求焦点。这些源码不能证明某个键盘动作会创建笔记，因此本轮不增加该动作。

## 有界记录契约

仅准备超时携带一个不可变的 `notesPreparationDiagnostic`。它标明 `schemaVersion: 1`、`snapshotKind: LAST_OBSERVATION`，记录最后一轮原有读取的固定阶段/资源类别、读取进度、已验证应用窗口和显示区域、已验证输入法区域，以及本次资源查询的匹配计数和目标检查状态。没有读到的值明确为空或未读取；每轮清除上一轮的值，不能把不同轮次的窗口和节点拼接。

记录只复制既有读取的数字、布尔值和矩形，不额外查询节点、窗口或表面，不保留可回收对象，也不保存标题、正文、原始窗口转储或未知包名。原错误字符串与400字符消息限制不变；结构化诊断在原失败处理处以内存操作附加，诊断序列化异常不得覆盖原失败。此记录是拒绝时平台报告的几何，不能单独证明实际像素覆盖或系统内部原因。

初始化边界检查覆盖同次读取、未读取/reset、计数区别、不可变副本、固定类别和大小限制；仍属于原四个测试的初始化，不增加32项分母。归档回归验证错误和诊断保真、既有 instrumentation 文件字节保留，以及报告中独立修改/删除/截短诊断被拒绝。归档器不保证所有异常都存在完整 instrumentation 日志。

## 当前验证状态

本地 `:compat-smoke:assembleDebug`、`:compat-smoke:assembleDebugAndroidTest`、`:compat-smoke:lintDebug` 已通过；第三方脚本79/79、全脚本307/307通过，新增三项诊断保真回归包含在内。独立代码审查确认原getter的短路顺序、查询次数、回收路径和动作预算保持不变。生产 `app/core`、工作流、冻结清单及四个真实测试方法未变。

源码 `5c57c04d7a5ee56275742c3488f4822a03505658` 的原32项[运行40669447296](https://github.com/ecojust/Agent-OS/actions/runs/40669447296)正在执行，新增Android初始化边界检查也须在这些实际API上执行；本地构建不替代设备验证。新运行必须独立归档成功、失败和缺失，不能覆盖旧轮次或把记录增加当作兼容性通过。

同源码的[整体操作演示40669473228](../../screenshots/demos/40669473228-1/manifest.json)一次录制成功：34.263秒、422,372字节，30载荷、22张实际PNG；13阶段与8次确认通过，安全标志保留。视频完整解码、文件大小/摘要与实际帧已两次独立核验。演示使用API35合成应用及本地测试模型，不包含Notes诊断或受保护操作回执页，也不是第三方应用或真实模型验收。
