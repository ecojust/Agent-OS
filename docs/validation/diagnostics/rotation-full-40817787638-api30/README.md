# 旋转v1完整回归中的API30原始失败

源码 `8e04afa9092d00270c53485d3035ea0bcc1fb8fb`，原完整运行 [40817787638](https://github.com/ecojust/Agent-OS/actions/runs/40817787638)。只读提取运行 [40819204609](https://github.com/ecojust/Agent-OS/actions/runs/40819204609)，检查作业 `123267432469`。保留Checks原始分片及按序连接的原消息。

API30旋转用例在旧观察拒绝阶段任务结束，派发0、模型请求1；与前两次专项的批准前窗口等待失败阶段不同。晚于失败的系统采样显示Fixture实际帧为640×320、无障碍边界为642×322；旋转动画对象仍存在但mAnimRunning=false，进入/退出动画为空。这些顺序读取不能证明原断言瞬间的服务端或缓存状态。另有数值配置导出用例因UNKNOWN_ROOTS_LAYOUT拒绝文件选择器根目录点击。

AOSP11的`mAnimRunning`字段不能单独代表实际动画生命周期：[真正的isAnimating路径](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r48/services/core/java/com/android/server/wm/ScreenRotationAnimation.java#502)委托多个SurfaceAnimator。这里只逐字段保留后采样，不宣称动画已完成。Controller在正常结束、受控停止或异常退出的finally都会取消原owner；本轮未记录最终resultKind/resultCode，因此取消本身也不能确定终止原因。

不将后续不同源码或调整顺序后的通过写回本轮，也不将未复现称为根因修复。
