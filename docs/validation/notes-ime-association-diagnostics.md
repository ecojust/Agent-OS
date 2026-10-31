# Notes 输入法窗口的失败后关联诊断

本项继续调查 v4 的八项 `confirmation_input_method_hidden_not_verified`，不是放宽隐藏守卫或更改确认动作。此前同源 `33ac5825bc7537b9a67c9daeeee6916da12256d6` 两轮各为 **24通过 / 8失败 / 0未验证**：API31/32/36/37的Notes关闭/空闲两态失败；Calculator16项及Notes另外8项通过。旧API30标题几何失败在这两轮未复现，仍保留历史失败，未称作稳定修复。

原始证据已证明：API31/32发送过一次被系统接受的BACK，之后四次WMS读取中visible/ready为false，但onScreen/Surface.shown仍为true；API36/37的原始无障碍清单完整且IME计数为零，因此没有发送BACK，四次WMS读取仍报告显示。清单缺项不能解释为较低层输入法被过滤，也不能据此判断输入法像素或具体平台规则。

## 采集契约

采集仅属于专用合成模拟器的测试APK。只对已核验官方Notes身份、原确认准备以输入法隐藏未验证失败的情况执行；原Throwable、失败结果、步骤及原确认诊断先固定，再在既有失败截图/清理之前读取。新记录使用独立 `NOTES_IME_ASSOCIATION_DIAGNOSTIC_V1` 标记，不加入v4通过判定，不回填此前BACK决策或四次原WMS样本。

最多依次执行三条只读命令，每条仅一次：

| 顺序 | 命令 | 观察范围 |
| --- | --- | --- |
| 1 | `dumpsys -t 2 window -a windows` | 唯一配置输入法的WMS记录与`mClient`文本 |
| 2 | `dumpsys -t 2 window a11y` | Populator已经筛选的可见/按显示分组窗口清单及`windowToken` |
| 3 | `dumpsys -t 2 accessibility` | 最终报告窗口与配对`WindowInfo.token` |

预声明每条最多等待3000毫秒、最多保留256KiB，并至多多读1字节检测溢出，调用方采集期限9000毫秒。调用shell本身与读取都位于同一个受限工作槽；超时停止启动余下命令，该槽直到原工作实际退出才释放，后续用例不能叠加堵塞线程。`dumpsys -t 2`的服务超时不替代调用方期限。实际时间、保留字节（outputBytes）、摘要覆盖范围、截断/超时/不可用和跳过原因分别记录；拿不到退出码时不把EOF称为命令成功。期限约束等待，不保证远端dump进程被终止；序列化/调度另有开销，远端服务锁也可能影响之后的既有截图和清理，整个仪器运行仍受原宿主超时保护。

归档只保留固定字段、匹配数量、状态、读取顺序和单调时刻，不保存完整dump、任意窗口标题或输入内容。独立标记最多16KiB；采集、解析、序列化异常和超限使用明确的有限状态，不替换原异常。此上限与既有确认/几何标记分别计算，异常栈另计。

v4仍使用原5秒准备期限、最多4次visible及4次hidden WMS采样、最多一次BACK和一次确认。官方APK、锁文件、四个测试方法、窗口身份/完整包含/遮挡校验、HOME策略和安全窗口策略均保持现有契约。后置读取会消耗时间并影响调度，不宣称零扰动；它们不会授权额外动作或截图。

## 关联的含义与局限

[固定源码核对](diagnostics/notes-ime-interfaces-2026-10-31/ime-dump-interface-plan.md)证明WMS的`mClient`、Populator的`windowToken`与服务端`WindowInfo.token`有共同的IWindow来源；原诊断的`selectedToken`及InputDispatcher的输入通道token不是这个键。接口在实际镜像上的权限与格式仍须用本轮证据核验。

即使文本匹配唯一，也只表示有字段来源依据的候选关联。没有持有Binder强引用，没有原子快照，也未核验各次读取间system_server生命周期；对象打印值可能碰撞、重建，不能跨运行复用。重复、格式缺失、截断、未观察到或不可用都不能当成确定的对象缺失。Populator的可见清单与按display清单会重复打印同一对象，必须分别计数。

若WMS和Populator均有唯一候选、最终服务清单无该候选，只能缩小到后续报告/注册及读取间变化；若Populator缺项，还不能区分上游未提供、预筛选、分组、缓存和通知时序。第一阶段不追加InputDispatcher或SurfaceFlinger命令，不临时循环到出现期望状态。诊断不能独自证明输入法已隐藏、原失败已修复或平台存在bug。

## 预声明验证

先完成有界采集、字段提取、异常保留、严格用例绑定与归档边界检查，以及APK构建、lint和宿主回归。冻结源码后执行**一轮完整32项**：API30–37八档、两款官方APK及关闭/空闲两态；保留原四方法、全部失败和未验证，不依据结果临时追加轮次。没有触发的新标记不补成实际采集。

另各执行一次API35真实APK四项录像和整体操作录像。前者不并入32项矩阵，后者使用合成Fixture与本地测试模型。失败录像保留可取得的证据，不反复重录；README每类型只更新为审核后的最新实际视频，旧视频保留原来源。上述模拟器诊断不代表真机、真实模型、正式50应用样本或系统镜像验收。

本地已覆盖891项既有宿主检查：首次发现运行880项，另一个SDK测试类因未配置环境跳过；设置实际SDK/JDK后，该类11项单独全部执行通过，未把跳过计为通过。相关归档34项属于这891项。新增[主机替身检查](diagnostics/notes-ime-association-2026-10-31/java-host-tests/host-validation.json)25项及实际helper解析自测通过，模拟阻塞调用在3004毫秒返回，余下两条跳过，占槽、迟到描述符关闭、溢出检测及原异常保留均核验。它不模拟Android Binder取消或实际服务权限。

新增[分析器26项边界检查](diagnostics/notes-ime-association-2026-10-31/analysis-tools/analysis-validation.json)及独立审查通过；重放历史第二轮仍为24/8/0，原8份确认诊断的用例归属和原JSON摘要一致，新标记为零。格式或时序矛盾保留原文并禁止解释，不借用上一用例。最终源码的APK构建与lint已通过（42秒），[本地完整记录](diagnostics/notes-ime-association-2026-10-31/local-validation.json)保留源文件和日志摘要；源码已冻结为`72f77af85481eb90c0511a52e61c7cbab3ac6595`，本轮设备结果待记录。先前实测来源仍见[几何诊断报告](notes-target-geometry-diagnostics.md)。

## 固定源码的本轮运行

以下均固定于`72f77af85481eb90c0511a52e61c7cbab3ac6595`，[派发来源记录](diagnostics/notes-ime-association-2026-10-31/source-runs.json)保留实际运行时间。结果与录像尚待审核，不预先计为通过。

| 运行 | 预声明范围 | 当前状态 |
| --- | --- | --- |
| [41173725544](https://github.com/ecojust/Agent-OS/actions/runs/41173725544) | 八档×两APK×关闭/空闲，32项 | 运行中 |
| [41173740075](https://github.com/ecojust/Agent-OS/actions/runs/41173740075) | API35真实APK四项录像 | 运行中，录像待归档 |
| [41173741948](https://github.com/ecojust/Agent-OS/actions/runs/41173741948) | 合成Fixture/本地模型整体录像 | 运行中，录像待归档 |
