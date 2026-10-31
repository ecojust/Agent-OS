# Android 12/12L 的 IME 隐藏字段：源码调查

2026-10-31 只读研究；未修改产品、测试或 v4 合同，未新增设备采样。固定 AOSP 提交为 Android12 `cebf5c06997b64f4e47a1611edb5f97044509d76`、12L `cf4b54771d1781281377ef64bb6f609842f376ba`；官方文件链接、字节数与 SHA-256 见[源码索引](ime-hidden-source-index.json)。这些版本证明机制存在，不证明测试镜像逐字采用它们，也不以历史后置 WMS 样本替代原四次读取。

## 字段为何可以同时出现

| 字段 | 实际含义及限制 |
| --- | --- |
| `isOnScreen` | 对非 Activity／非 wallpaper 的 IME，主要检查有 surface、未 destroying、policy，以及父 WindowState／动画条件；**不检查 Insets clientVisible 或 mViewVisibility**。true 不等于有可见像素。 |
| `isVisible` | 在 surface、父 WindowState、exit/destroying、policy 条件外，还检查可控 Insets provider 的 clientVisible。false 可以表示客户端隐藏请求，不能证明合成事务已完成。 |
| `isReadyForDisplay()` | 另检查 transition、mViewVisibility 与 token 可见性，或窗口／父转换动画；这是 WMS 资格判断，不是像素回读。 |
| `mLastHidden` | WindowStateAnimator 自己 hide/show 路径维护的历史账本，不查询 Insets leash。 |
| `Surface: shown` | WindowSurfaceController 的 **buffer 子层**本地账本。show/hide 修改它并排入事务；不查询父层、alpha/crop 或物理呈现。 |

三个谓词见 [12 WindowState:1846](https://android.googlesource.com/platform/frameworks/base/+/cebf5c06997b64f4e47a1611edb5f97044509d76/services/core/java/com/android/server/wm/WindowState.java#1846)、[1952](https://android.googlesource.com/platform/frameworks/base/+/cebf5c06997b64f4e47a1611edb5f97044509d76/services/core/java/com/android/server/wm/WindowState.java#1952)、[2006](https://android.googlesource.com/platform/frameworks/base/+/cebf5c06997b64f4e47a1611edb5f97044509d76/services/core/java/com/android/server/wm/WindowState.java#2006)；12L 对应1897、2017、2071行。`isParentWindowHidden` 只检查父 WindowState.mHidden，不检查 SurfaceControl 的父 leash。

Insets 控制另有父 animation leash，隐藏操作作用于这个父层，不经过子层 WindowSurfaceController.setShown(false)，见 [InsetsSourceConsumer:391](https://android.googlesource.com/platform/frameworks/base/+/cebf5c06997b64f4e47a1611edb5f97044509d76/core/java/android/view/InsetsSourceConsumer.java#391)。子层继承父层可见性，因此 **父 leash 隐藏时，child shown=true、mLastHidden=false 仍可以成立**。但当前字段没有读取该父层，不能据此改记当次隐藏成功。enter/exit 标志也没有覆盖全部客户端 Insets 动画状态。

## 五秒保留表面是具体候选，尚非现场根因证明

两版本均定义 `TIMEOUT_SURFACE_REMOVAL_MILLIS=5000`。正常 InputMethodService.hideWindow 先清自身 window/decor 可见状态，**此处不调用 mWindow.hide()**。Insets hide 完成后，来自窗口的 removal 请求须匹配当前焦点 window token 并存在有效 IME session；进入调度时如仍请求显示、window visible、没有 window 或已 scheduled，会直接返回。最近为 fullscreen 时立即 remove，其他情况延迟5000ms；重新显示可取消该回调。见 [12 InputMethodService:916](https://android.googlesource.com/platform/frameworks/base/+/cebf5c06997b64f4e47a1611edb5f97044509d76/core/java/android/inputmethodservice/InputMethodService.java#916)、[12L:909](https://android.googlesource.com/platform/frameworks/base/+/cf4b54771d1781281377ef64bb6f609842f376ba/core/java/android/inputmethodservice/InputMethodService.java#909)。最后的 Dialog.hide 只将 decor 设为 GONE，surface 处理还依赖后续框架事务。

若实际走到这条缓存分支，从 BACK **之前**开始的 v4 五秒准备窗口可能一直看到保留的子表面。当前诊断只有 START／窗口清单／WMS 时间及 BACK 计数，**没有 BACK 精确时间戳**，只能检查该机制与顺序的一致性，不能计算精确 BACK 后耗时、认定 removal 计时已开始，或声称五秒必然到时删除。系统接受注入也不证明已进入 IME 默认 BACK 处理。

AOSP 另可在转换中创建挂到目标 Activity 的独立 `IME-snapshot-surface`，有自己的 show/remove 流程，见 [DisplayContent:3866](https://android.googlesource.com/platform/frameworks/base/+/cebf5c06997b64f4e47a1611edb5f97044509d76/services/core/java/com/android/server/wm/DisplayContent.java#3866)。不能把原 IME child shown=true 命名为 snapshot，也不能由 child 消失证明没有 snapshot；此机制存在不代表 Notes 当次创建过它。

## 保留原判定与下一步

这些单窗口字段不能单独证明实际可见像素或屏幕已没有所有 IME 影像。原 v4 正向隐藏组合仍按原合同判定；当前不满足的组合继续失败，不用 isVisible=false、窗口列表缺少 IME 或源码推断替代隐藏证明，也不延长原五秒／四次采样、不追加 BACK 或确认。

下一步先审核当前原四次记录。若需验证保留机制，应另立固定样本的诊断，绑定实际 IME window/token、Insets control target/leash，并记录相应请求与生命周期时序；必要时核对祖先层及独立 snapshot。额外读取和五秒之后的观察必须独立计数，不能回填原32项。普通 IMS dump 未输出 surface-removal scheduled 标志，不能从其他字段冒称已直接取得它。上述新增诊断尚未实施。
