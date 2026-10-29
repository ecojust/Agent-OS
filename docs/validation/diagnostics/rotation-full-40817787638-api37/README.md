# 旋转v1完整回归中的API37原始失败

源码 `8e04afa9092d00270c53485d3035ea0bcc1fb8fb`，原完整运行 [40817787638](https://github.com/ecojust/Agent-OS/actions/runs/40817787638)。只读提取运行 [40819206977](https://github.com/ecojust/Agent-OS/actions/runs/40819206977)，检查作业 `123267438602`。保留Checks原始分片及按序连接的原消息。

API37失败来自既有VisualEventGenerationInstrumentedTest同高状态事件用例：预期视觉代次43，实际44。原诊断记录windowId=1的content=4外部事件引起失效，不能把外部事件改称被测控制窗事件，也不能据此放宽产品对外部变化的拒绝。该历史失败保留，具体触发来源仍待确认。

不将后续不同源码或调整顺序后的通过写回本轮，也不将未复现称为根因修复。
