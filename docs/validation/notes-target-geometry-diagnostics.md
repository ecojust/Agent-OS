# Notes 标题完整包含检查的原读取诊断

本项继续推进[真实APK日常使用检查点](third-party-smoke.md)。上一批源码435d的[运行41163144860](../../screenshots/third-party-41163144860-1/manifest.json)为23通过、9失败；其中API30 Notes关闭态在标题`locked_note_title`的完整包含检查失败，发生在该次`ACTION_SET_TEXT`之前，确认准备尚未开始。原件没有保存当时应用/目标矩形，因此不能判断空矩形、越界、窗口变化或动画。八项隐藏准备失败另有独立[原读取证据](notes-confirmation-diagnostics.md)，不与本项混同。

## 两轮实际结果

固定源码33ac的两轮均完整执行32项，分别为 **24通过、8失败、0未验证**。每轮Calculator16/16、Notes8/16；不合并通过率。

| 运行与原件 | 通过 / 失败 / 未验证 | 载荷与截图 | 逐案结论 |
| --- | --- | --- | --- |
| [41165280718](../../screenshots/third-party-41165280718-1/manifest.json) | 24 / 8 / 0 | 56载荷、2,511,195B、32计算器PNG、32 Notes blocked | [第一轮报告](diagnostics/notes-target-geometry-41165280718/findings.md) |
| [41165282816](../../screenshots/third-party-41165282816-1/manifest.json) | 24 / 8 / 0 | 56载荷、2,506,414B、32计算器PNG、32 Notes blocked | [第二轮报告](diagnostics/notes-target-geometry-41165282816/findings.md) |

每轮16次官方APK安装、32次身份/设置恢复和8项Notes新进程全文读回均核验。两轮API30的关闭/空闲两态均通过，所有八档均无新几何marker，**没有取得实际失败矩形**。对最新旧批435d逐32组合比较，23项继续通过、8项继续失败，API30关闭态由旧失败变为本轮通过；这只是两轮未复现，诊断改动未提供稳定修复或删除历史失败的依据。

每轮原八项隐藏失败仍保留8条完整确认诊断、32次原WMS：31/32接受一次BACK后仍不满足隐藏，36/37原决策及后续保留清单IME=0而另次WMS三可见谓词为true，无BELOW_FOCUSED证据。四次样本包装最晚分别在START+2289/+2234毫秒结束，不能称连续WMS观测满五秒，也不能换算为BACK后时长。

全部来源、安装、载荷、PNG及原JSON已审核；[独立原件复核](diagnostics/notes-target-geometry-2026-10-31/independent-original-review.json)确认每轮32个原JUnit终态、两类未绑定记录均为0及全部字段/时序一致。归档[离线分析器与14项自检](diagnostics/notes-target-geometry-2026-10-31/analysis-tools/analysis-validation.json)按完整状态块绑定本案，重复日志只在同用例内去重；原数据不改，不用失败后截图补矩形。

本轮已完成有界原矩形诊断的实现与预声明两轮复测，应用检查点仍进行中。下一步优先单独执行下述输入法窗口关联采集，验证原八项失败的清单来源/父层证据；若后续再次遇到几何失败，再按已经加入的同次诊断核对。普通写入执行态、升级、扩样、真实模型和真机仍未验收。

## 固定范围与记录边界

仅修改compat-smoke测试辅助器，复用失败那次已经取得的`NotesRead`快照，保存应用、目标、显示屏和IME矩形，应用/目标/IME/显示屏编号，固定阶段/资源/读取阶段及已有的内存动作计数。动作计数沿用当前harness的既有饱和计数，达到64时明确标记，不能解释为整个运行的精确动作总数。缺失值仍明确缺失；未知动作只保留固定替代标记，不保存任意节点内容、标题、正文、包名、原dump或异常私有文本。

在原`fullyInside`断言失败时，给同一个原AssertionError附加独立`NOTES_TARGET_GEOMETRY_DIAGNOSTIC_V1` suppressed消息后原样抛出。主错误、原异常对象、v4主JSON/schema与四个原方法保持，序列化失败或超限明确记录且不能替换原错误。现场诊断采集路径只使用已有内存数据，不新增getWindows、getRoot、节点读取、shell、时钟读取或输入事件；不把原失败改成等待，不改变完整包含/遮挡守卫、五秒期限、采样和动作额度，也不重发标题输入。失败后的序列化仍有开销，不宣称时序完全没有影响。

单条新消息最多4KiB；若完整包含失败发生在创建确认阶段，原确认诊断仍可能附加一次最多16KiB消息，两种消息分别计数，总上限20KiB（原异常栈另计）。API30历史SET_TITLE发生在确认准备之前，不能凭它虚构确认阶段数据。这些元数据来自原来的有序读取，冻结并不使其成为同一时刻的原子平台快照，也不增加原动作权限。守卫还用于输入后读回和确认阶段；新marker的具体动作边界必须结合原堆栈、stage和lastAction判断，不能一概称为输入前失败。

## 预声明验证与复测

新增初始化边界检查仍会通过现有NotesWait构造器取时钟，这属于初始化检查，不是实际失败数据采集。初始化边界检查覆盖原异常保留、空/越界/极值矩形、快照冻结、未知动作脱敏以及显式错误/超限；宿主归档检查覆盖新marker完整保留及与原确认marker共存。完成构建、lint、相关回归及独立审查后冻结源码。

在同一源码执行**两轮独立完整32项**：官方未修改Calculator1.4.0/Notes1.7.0，API30–37八档及Agent关闭/绑定空闲两态，仍为原四方法。每轮保留固定分母、所有失败和未验证项，不拼接通过率，不根据结果删样或临时追加轮次。若本次完整包含失败未复现，只记录该轮未取得实际失败诊断，历史API30失败仍未解决；初始化合成检查不冒充真实界面失败。

另各执行一次独立API35真实APK四项录像和普通整体操作录像，失败同样归档，不重录以替换失败。API35四项不并入任何32项矩阵；普通录像使用合成Fixture与本地测试模型，不代表真实模型、Agent执行态写入、升级或真机验收。既有录像保留原来源，实际新录像审核后才更新README。

本地验证已完成：compat-smoke及测试APK构建、lint通过（49秒），891项完整宿主回归全部通过、无跳过（112.304秒），其中相关归档检查34项；两次独立审查无阻断。新增Android初始化边界检查随同源两轮八档执行，原四方法均实际运行，未出现初始化失败；两轮原件及独立录像结果分别列于本页。输入法窗口来源接口的并行调查仅用于选择后续独立诊断，不向这两轮原准备流程添加平台采集。

## 并行完成的输入法接口可行性核对

固定源码的[只读接口方案](diagnostics/notes-ime-interfaces-2026-10-31/ime-dump-interface-plan.md)及15份[源码摘要](diagnostics/notes-ime-interfaces-2026-10-31/ime-interface-source-index.json)已核验。现有shell接口有条件提供WMS mClient、Populator windowToken与最终WindowInfo.token的文本候选关联，不必先改系统镜像；原selectedToken并非这个关联键。方案尚未在设备执行，不能保证候选唯一、镜像格式一致、取到原回调全部字段或证明IME隐藏。本轮几何诊断不增加这些dump；后续采集须独立计数并区分原失败与后置观察。

## 固定源码与本次运行

已冻结源码 `33ac5825bc7537b9a67c9daeeee6916da12256d6`；[本地验证与源边界](diagnostics/notes-target-geometry-2026-10-31/local-validation.json)保留实际文件摘要、日志摘要与原生产代码/四方法/锁文件不变证明。

| 独立运行 | 固定范围 | 当前记录 |
| --- | --- | --- |
| [第一轮41165280718](https://github.com/ecojust/Agent-OS/actions/runs/41165280718) | 八档×两APK×关闭/空闲，32项 | 24通过/8失败/0未验证，完整原件已审核 |
| [第二轮41165282816](https://github.com/ecojust/Agent-OS/actions/runs/41165282816) | 同源独立32项 | 24通过/8失败/0未验证，完整原件已审核 |
| [真实APK录像41165285095](https://github.com/ecojust/Agent-OS/actions/runs/41165285095) | 仅API35原四项 | 4/4通过；48.850秒、882帧，实际视频已审核归档 |
| [整体录像41165286817](https://github.com/ecojust/Agent-OS/actions/runs/41165286817) | API35合成Fixture/本地模型 | 13阶段/8确认通过；36.256秒、515帧，实际视频已审核归档 |

上述运行于2026-10-31 03:39 UTC分别启动，均绑定同一源码。两轮矩阵、API35四项录像与普通整体演示各自计数；旧ad0f录像保留原来源。

整体录像[原清单](../../screenshots/demos/41165286817-1/manifest.json)与[审核](diagnostics/notes-target-geometry-2026-10-31/overall-video-audit.json)已保存，README普通操作一类已更新到实际33ac视频；它不证明发生过几何失败，也不替代两轮原APK结果。

真实APK[原清单](../../screenshots/demos/probes-41165285095-1/manifest.json)及[两段合并审核](diagnostics/notes-target-geometry-2026-10-31/video-audit.json)已归档。README每种类型仍只列最新实际视频；本轮普通/真实APK分别36.256秒和48.850秒，四条Notes blocked原样保留，不能把视频当作原几何失败或输入法隐藏通过的证据。
