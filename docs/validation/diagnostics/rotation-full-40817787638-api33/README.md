# 旋转v1完整回归中的API33原始失败

源码 `8e04afa9092d00270c53485d3035ea0bcc1fb8fb`，原完整运行 [40817787638](https://github.com/ecojust/Agent-OS/actions/runs/40817787638)。只读提取运行 [40818849491](https://github.com/ecojust/Agent-OS/actions/runs/40818849491)，检查作业 `123266287423`。保留Checks原始分片及按序连接的原消息。

失败方法为 `NumericPreferencesInstrumentedTest#malformedOversizedAndCancelledImportsLeaveTheOriginalDraftAndSavedStateUntouched`。系统文件选择器中发现一个目标根目录节点，但其祖先不能被识别为既定抽屉或侧栏上下文；记录 `UNKNOWN_ROOTS_LAYOUT`，未尝试点击。Provider查询返回且安装诊断可见，不能据此认定为Provider安装失败，也不能仅凭发生于旋转模块整套回归就归因于旋转。

当前仅保留该轮实际失败与诊断，具体根因待确认；不增加未知文件选择器布局的点击许可，不以其他源码的通过覆盖本轮失败。
