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

完整dump在提取后丢弃，因此归档审核能独立重算载荷和诊断JSON的摘要、核验固定解析源码及字段一致性，但不能从归档重放完整dump提取或重新计算其`outputSha256`。该摘要是采集器报告的读取指纹，不能描述成已独立核验的原始dump附件。

v4仍使用原5秒准备期限、最多4次visible及4次hidden WMS采样、最多一次BACK和一次确认。官方APK、锁文件、四个测试方法、窗口身份/完整包含/遮挡校验、HOME策略和安全窗口策略均保持现有契约。后置读取会消耗时间并影响调度，不宣称零扰动；它们不会授权额外动作或截图。

## 关联的含义与局限

[固定源码核对](diagnostics/notes-ime-interfaces-2026-10-31/ime-dump-interface-plan.md)证明WMS的`mClient`、Populator的`windowToken`与服务端`WindowInfo.token`有共同的IWindow来源；原诊断的`selectedToken`及InputDispatcher的输入通道token不是这个键。接口在实际镜像上的权限与格式仍须用本轮证据核验。

即使文本匹配唯一，也只表示有字段来源依据的候选关联。没有持有Binder强引用，没有原子快照，也未核验各次读取间system_server生命周期；对象打印值可能碰撞、重建，不能跨运行复用。重复、格式缺失、截断、未观察到或不可用都不能当成确定的对象缺失。Populator的可见清单与按display清单会重复打印同一对象，必须分别计数。

若WMS和Populator均有唯一候选、最终服务清单无该候选，只能缩小到后续报告/注册及读取间变化；若Populator缺项，还不能区分上游未提供、预筛选、分组、缓存和通知时序。第一阶段不追加InputDispatcher或SurfaceFlinger命令，不临时循环到出现期望状态。诊断不能独自证明输入法已隐藏、原失败已修复或平台存在bug。

## 预声明验证

先完成有界采集、字段提取、异常保留、严格用例绑定与归档边界检查，以及APK构建、lint和宿主回归。冻结源码后执行**一轮完整32项**：API30–37八档、两款官方APK及关闭/空闲两态；保留原四方法、全部失败和未验证，不依据结果临时追加轮次。没有触发的新标记不补成实际采集。

另各执行一次API35真实APK四项录像和整体操作录像。前者不并入32项矩阵，后者使用合成Fixture与本地测试模型。失败录像保留可取得的证据，不反复重录；README每类型只更新为审核后的最新实际视频，旧视频保留原来源。上述模拟器诊断不代表真机、真实模型、正式50应用样本或系统镜像验收。

本地已覆盖891项既有宿主检查：首次发现运行880项，另一个SDK测试类因未配置环境跳过；设置实际SDK/JDK后，该类11项单独全部执行通过，未把跳过计为通过。相关归档34项属于这891项。新增[主机替身检查](diagnostics/notes-ime-association-2026-10-31/java-host-tests/host-validation.json)25项及实际helper解析自测通过，模拟阻塞调用在3004毫秒返回，余下两条跳过，占槽、迟到描述符关闭、溢出检测及原异常保留均核验。它不模拟Android Binder取消或实际服务权限。

新增[分析器26项边界检查](diagnostics/notes-ime-association-2026-10-31/analysis-tools/analysis-validation.json)及独立审查通过；重放历史第二轮仍为24/8/0，原8份确认诊断的用例归属和原JSON摘要一致，新标记为零。格式或时序矛盾保留原文并禁止解释，不借用上一用例。最终源码的APK构建与lint已通过（42秒），[本地完整记录](diagnostics/notes-ime-association-2026-10-31/local-validation.json)保留源文件和日志摘要；源码已冻结为`72f77af85481eb90c0511a52e61c7cbab3ac6595`，首轮实际结果见下文。先前实测来源仍见[几何诊断报告](notes-target-geometry-diagnostics.md)。

## 固定源码的本轮运行

以下均固定于`72f77af85481eb90c0511a52e61c7cbab3ac6595`，[派发来源记录](diagnostics/notes-ime-association-2026-10-31/source-runs.json)保留实际运行时间。各运行分别统计，原始失败及每段录像的实际源码保留。

| 运行 | 预声明范围 | 当前状态 |
| --- | --- | --- |
| [41173725544](https://github.com/ecojust/Agent-OS/actions/runs/41173725544) | 八档×两APK×关闭/空闲，32项 | 24通过/8失败/0未验证；原件已归档 |
| [41173740075](https://github.com/ecojust/Agent-OS/actions/runs/41173740075) | API35真实APK四项录像 | 4/4通过，46.685秒/843帧已审核 |
| [41173741948](https://github.com/ecojust/Agent-OS/actions/runs/41173741948) | 合成Fixture/本地模型整体录像 | 13阶段/8确认通过，39.148秒/506帧已审核 |

## 首轮实际结果与解析缺陷

[72f首轮原件](../../screenshots/third-party-41173725544-1/manifest.json)完整32项，**24通过/8失败/0未验证**；Calculator16/16、Notes8/16。56载荷共2,530,658字节、32 PNG及32条Notes blocked保留，32份安装身份与设置恢复、8份Notes新进程全文读回核验。API31/32/36/37的Notes关闭/空闲仍是原隐藏准备失败，原32次WMS样本模式未变；31/32各一次BACK被接受，36/37未发送BACK，八项确认/HOME均零。API30两态通过、几何marker零，旧标题失败仍未得到真实失败矩形。

八份新marker均在原确认诊断冻结后0–2毫秒启动，各用23–52毫秒完成，无超时。但八份后置WMS虽完整返回且各有一个输入法引用，解析器均给出`inputMethodRecordCount=0`，未取得`mClient`。这是**未建立候选关联**，不能解释成窗口不存在。API31/32的`window a11y`实际不可用，最终服务dump为旧式无配对格式；API36/37的Populator与最终配对格式可识别（分别4/3及3/2个可见/最终窗口），由于没有候选键，各`matchCount`均为null，不是零。原始诊断与[独立原件复核](diagnostics/notes-ime-association-2026-10-31/independent-original-review.json)保留这些限制。

源码核对与首轮精确helper的合成复现发现明确解析缺陷：固定AOSP12L/17的`LayoutParams.toString`会先输出嵌套`sim={...}`，再输出顶层`ty=`。首轮`mAttrs=\{[^}]*...ty=`模式会在内层右括号提前停止，因此不支持这种合法格式。现有完整dump未归档，不能声称重建了这八次实际字节；已证明的解析缺陷与本轮未识别形状一致，需修正后独立验证。

## 修正后的独立验证契约

修正仅针对测试诊断中的WMS属性解析，使用有界平衡括号，且只接受顶层唯一类型字段；嵌套类型不能充当原窗口类型，未完成属性解析单列未知。原采集命令、调用额度、触发条件、原v4结果/动作/时间预算保持。首轮72f结果不重算，不把它替换成后续通过数据。

完成新增解析边界检查、主机替身检查、构建与lint并冻结另一份源码后，另执行**一轮完整32项**，与72f首轮分别报告，不合并分母。再录一次该修正源码的整体操作演示；已经录制成功的72f真实APK四项视频保留其实际源码，因为本次只修正失败后的属性提取，不改API35四项核心操作。新录像不存在或失败时保留已有成功视频的原来源，不改标版本或反复补录。未修复的应用失败继续列为失败。

首轮[真实APK视频](../../screenshots/demos/probes-41173740075-1/video/demo.mp4)为46.685秒、758,718字节、843帧，四项4/4；9载荷1,088,574字节、4 PNG和4条Notes blocked保留。[整体视频](../../screenshots/demos/41173741948-1/demo.mp4)为39.148秒、442,972字节、506帧，13阶段/8确认；30载荷1,183,865字节、22 PNG。两段的来源、全部载荷、固定源码验证器、全帧解码及实际画面已[审核](diagnostics/notes-ime-association-2026-10-31/video-audit.json)，首轮归档时README已链接这两段实际72f录像；不代表在API35复现了八项隐藏失败。

属性解析修正已完成：每条属性块最多8192字符、嵌套深度最多8，只认唯一顶层`ty`；任一窗口属性未解析完成就记录`WMS_ATTRIBUTES_INCOMPLETE`，不据此选择唯一IME。新增两个固定解析计数，旧标记仍兼容。修正后25项采集替身检查、29项分析检查、独立24项解析复核及构建/lint（43秒）通过；旧72f报告重放逐案结果与marker摘要不变。[修正验证记录](diagnostics/notes-ime-association-2026-10-31/attrs-correction-local-validation.json)证明采集器WMS解析前代码、原准备/截图/清理接线、APK/锁/四方法和生产代码未改。891项既有宿主检查仍归72f，未为这次解析修正重复执行或改标来源。修正源码已固定为`9b86b2a842f9e837e20e2df183893da697faaf40`。独立[32项运行41174426442](https://github.com/ecojust/Agent-OS/actions/runs/41174426442)与[整体录像41174428202](https://github.com/ecojust/Agent-OS/actions/runs/41174428202)已完成并审核，结果见下文；[来源记录](diagnostics/notes-ime-association-2026-10-31/attrs-correction-source-runs.json)与首轮分别保存。

## 修正轮实际结果与下一步

`9b86b2a842f9e837e20e2df183893da697faaf40`的[41174426442原件](../../screenshots/third-party-41174426442-1/manifest.json)完整执行32项，仍为 **24通过/8失败/0未验证**。56载荷共2,527,152字节、32 PNG与32条Notes blocked全部核验；32份安装身份及设置恢复、8份Notes新进程全文读回通过。与72f首轮逐项比较，原32结果/主错误、16项Notes确认字段及8×4次原隐藏WMS的字段值/计数不变；时间、窗口ID和token不跨运行比较。几何marker为零，旧API30标题失败未据此称为修复。

修正后6项取得唯一配置IME的WMS候选，2项仍拒绝选择；只有API37两态取得完整的后续候选查找结果：

| 原失败组合 | 后置WMS | 后续来源与实际候选匹配 |
| --- | --- | --- |
| API31/32 × off/idle，4项 | 候选已选中，属性分别9/12项完整 | a11y命令不可用，最终服务为旧式未配对格式；匹配未知 |
| API36 × off/idle，2项 | 10项属性成功、1项未解析；虽识别1条INPUT_METHOD，仍拒绝选择mClient | Populator及最终清单格式可识别，但没有候选键；匹配为null，不能称缺项 |
| API37 × off/idle，2项 | 10项属性完整，唯一配置IME候选已选中 | Populator通知开启，可见清单3项、单display分组、最终配对2项；同轮候选文本分别匹配0/0/0 |

API37的结果说明：在**失败后的三次有序读取**中，WMS候选文本已不在Populator的已筛选清单内。它缩小了调查边界，但不能区分上游未提供、预筛选、缓存/通知时序或对象重建，也不能回填原BACK决策或证明输入法像素隐藏。API36未知记录的具体格式尚无证据，不猜测原因，也不忽略它来建立唯一关联。

八份采集均在原失败冻结后0–2毫秒开始，耗时20–47毫秒，无截断或超时；原31/32各一次BACK被接受，36/37仍为零，八项确认/HOME均为零。[逐案结果与来源比较](diagnostics/notes-ime-association-41174426442/findings.md)及[独立原件复核](diagnostics/notes-ime-association-2026-10-31/attrs-correction-independent-original-review.json)分别保存。72f首轮八个未知候选保持原样，没有用修正版结果改写历史。

修正源码的[最新整体视频](../../screenshots/demos/41174428202-1/demo.mp4)为38.827秒、399,328字节、455帧，13阶段/8确认通过；30载荷1,133,280字节、22 PNG、固定源码验证器及全部帧解码/实际画面均[审核通过](diagnostics/notes-ime-association-2026-10-31/attrs-correction-overall-video-audit.json)。README普通任务一类已更新为9b86，真实APK一类保留最新实际72f视频；两轮矩阵与录像各自统计，所有旧片保留真实来源。

本步骤完成了有界诊断、格式修正与两份源码的独立验证，**原八项应用失败仍未修复，应用验收检查点继续进行中**。下一步先为API36未解析属性补充有界结构失败原因，再评估API37过滤前输入窗口元数据的只读来源和共同对象键；继续保留原动作与截图准入。整体工程检查点仍为10/20，不因诊断新增而增加通过数。

后续已启动[属性拒绝原因与过滤前来源](notes-ime-attrs-failure-reasons.md)步骤，在保持本页原判定的基础上补充有界结构信息；新结果单独记录，不回写以上历史运行。
