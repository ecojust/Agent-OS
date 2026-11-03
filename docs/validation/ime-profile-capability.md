# API37 输入法方法追踪能力校验

本项接续[IME 重建 Trace 诊断](window-infos-reset-trace.md)，验证原系统输入法能否生成有界、可解析的方法记录。它是后续直接调用链观测的前置校验，不运行原 A/B 对照或 Notes 用例，不用短时能力结果判断重建入口。

## 已有记录能回答什么

919bee 的原 Trace 中，IME 重建没有可识别的调用祖先。进一步[独立查询](diagnostics/ime-profile-capability-2026-11-03/existing-context-review.json)检查了原事件、日志、flow、参数及固定派发源码：没有 Binder 事务或专属 ContentObserver 入口记录；1954 条通用 `call: settings` 没有可识别设置键或观察者的参数。窗口日志中的 `configChanged=false` 也不是 IMS 配置回调证据。原件未改，不能再通过时间相邻关联得出调用方。

原 Notes 仍为24通过/8失败；其5秒从确认准备开始，与独立 fixture 的 show 时间轴不同。本项不修改该预算、显示请求、区域判断或失败分母。

## 本项范围

在独立 API37 x86_64 模拟器上，只对已经运行的默认 LatinIME 进程做一次短时、非采样、非 streaming 的 `am profile` 校验。不安装应用，不发起新的显示请求，不修改键盘设置、调试属性、包标志、权限或安全窗口行为，不请求 root。

[固定源码与格式审阅](diagnostics/ime-profile-capability-2026-11-03/design-and-parser-review.json)确认：固定 framework 源码允许 `ro.debuggable=1` 的系统通过相应 shell 权限请求 profiling；`userdebug` 名称本身不能替代实际属性与权限检查。`profile start` 会先清理 AMS 的全局 profiling 槽，因此必须先读取完整、未按包过滤的状态。已有或不明的采集会阻止启动，不通过试运行覆盖它。

目标绑定默认输入法包名、UID、唯一 PID、进程开始时间及命令行。启动后和停止前还要核对 AMS 记录的目标进程与本轮唯一输出路径。只请求一次启动和一次有条件停止；进程变化、其他 owner 或不明状态保留未验证结果。停止前还在同一远端 shell 中再次核对身份。AMS 在目标 PID 消失时可能按全局停止处理，因此这些核对仍不是原子 owner 句柄，校验仅限本轮独占的专用测试模拟器。设备尚存活时执行清理，工作流结束后的模拟器销毁不是唯一停止路径。

确认自有槽后固定持有1秒；方法文件的实际时长还包含启动、核验与停止开销，不能预称恰1秒。只接受默认空值或8MiB的二进制缓冲属性，不写该属性；符号表另占空间。宿主总Trace输入限16MiB、命令最多32条且总输出限8MiB、产物限32MiB。

命令退出0仅表示请求返回，不能替代 ART 方法文件已生成的证据。实际文件需经过格式、长度、符号表、记录及 overflow 检查；不支持的格式、截断和溢出保持未验证。ART 编码的线程索引不直接当作 Linux TID，也不把名为 main 的线程直接映射到先前 Trace 的主线程。

非采样方法追踪会扰动线程和 JIT 状态。即使停止并清理文件，也不声称原编译缓存已恢复；专用模拟器在本轮结束后销毁。短时能力通过不证明长窗口不溢出、原延迟能复现、方法栈足以识别重建调用方，或原 Notes 已修复。

## 验证与原件

[独立工作流](../../.github/workflows/ime-profile-probe.yml)提供 test 与 archive 两种模式。原命令输出、状态与可取得的方法文件按原字节保存；归档核对固定源码、原运行、当前 attempt、唯一 job 和 artifact。通过、失败、未执行与捕获结果分别保留。

本项无 UI 操作，不能用截图证明 profiling 可用。整体操作演示另行记录并按实际源码列在 README；现有919bee的34.445秒视频保持原来源，新的录制尚未执行。

当前实现与宿主验证已完成，设备可用性、实际格式、停止及清理结果待冻结源码后的单次实测确认。

本地原生格式模块[35项测试](diagnostics/ime-profile-capability-2026-11-03/parser-test-review.json)通过，测试字节是按固定ART源码构造的格式样本，不是设备证据；支持现代完整summary及非streaming V1磁盘格式2/3，其他格式保留未验证。符号表方法ID0、线程内部索引、时钟维度、overflow、截断和初始未配对exit分别校验。初始exit允许存在，不据此宣称调用栈已完整；summary的is_precise_trace也不单独证明非采样，请求模式以原命令为准。

[归档模块](diagnostics/ime-profile-capability-2026-11-03/archive-validation.json)24项测试及真实prepare/临时Git来源集成通过。其5份来源文件和4份Python执行字节均先核验后加载；缺失、部分及失败结果保持独立。最终[94项整合测试](diagnostics/ime-profile-capability-2026-11-03/host-validation.json)通过（35采集/35格式/24归档），工作流语法检查通过。

失败保存是有界的：有可读大小记录时，超限或不稳定文件可保留有限前缀，始终未验证；如果大小查询本身失败，可能仅保留命令回执而无二进制。不存在把这种缺失升级为完整原件的回退路径。

[最终独立合同审阅](diagnostics/ime-profile-capability-2026-11-03/contract-review.json)已核对真实平台CLI、正常FD=null状态、UID及native PID绑定、失败保存、受限停止和可信源码加载，结论限定为实现合同通过，实际设备能力仍待单次验证。
