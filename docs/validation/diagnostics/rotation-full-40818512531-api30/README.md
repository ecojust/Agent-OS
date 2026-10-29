# 旋转v2完整回归中的API30原始失败

源码 `43381e9e9c67f1c6eb7c5fbf4ced0ed5e03fa9da`，原完整运行 [40818512531](https://github.com/ecojust/Agent-OS/actions/runs/40818512531)。只读提取运行 [40820289923](https://github.com/ecojust/Agent-OS/actions/runs/40820289923)，检查作业 `123270924948`。保留Checks原始分片及按序连接的原消息。

唯一失败为 `NumericPreferencesInstrumentedTest#realSafImportPreviewsAndAppliesOnlyFourUnsavedNumericFields`。系统文件选择器发现一个合成根目录节点，但其祖先不满足原抽屉/侧栏上下文要求，记录 `UNKNOWN_ROOTS_LAYOUT`，未尝试点击。Provider历史查询返回20次不证明本次祖先树完整；安装诊断未报告缺失，不据此推断旋转因果或Provider故障。

这与旧完整运行中API30导出、API33取消流程属于相同可观察失败类别，但具体方法和运行不同；不能拼接通过记录或声称SAF根因已修复。旋转v2在本轮八档均通过，完整矩阵仍因本案不满足全通过条件。后续应记录原祖先遍历的固定失败类别，再据证据处理，不扩大未知布局的点击许可。
