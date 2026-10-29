# 旋转v1完整回归中的API36原始失败

源码 `8e04afa9092d00270c53485d3035ea0bcc1fb8fb`，原完整运行 [40817787638](https://github.com/ecojust/Agent-OS/actions/runs/40817787638)。只读提取运行 [40819305088](https://github.com/ecojust/Agent-OS/actions/runs/40819305088)，检查作业 `123267761778`。保留Checks原始分片及按序连接的原消息。

失败来自既有VisualEventGenerationInstrumentedTest同高状态事件用例第三轮：预期视觉代次45，实际46。原诊断记录windowId=6的content=4非自有事件引起失效；被匹配的自身文字事件为windowId=869、content=2。不能将非自有事件改称自身事件，也不能指定窗口6的具体系统组件来源。本轮不证明自身同高事件被误分类，不放宽产品对外部变化的拒绝。

旧失败保留，后续不同源码未复现也不等于已修复其触发来源。
