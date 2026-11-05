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

本项无 UI 操作，不能用截图证明 profiling 可用。整体操作演示另行记录并按实际源码列在 README；普通 Fixture 录像不作为输入法方法追踪的证据。

短时方法追踪能力子项已完成：2026-11-05的63a/API37原件已验证格式、停止与清理。此前048f首次校验未取得PID，失败保持；缺PID原因、较长窗口容量与重建调用方仍未验证。

本地原生格式模块[35项测试](diagnostics/ime-profile-capability-2026-11-03/parser-test-review.json)通过，测试字节是按固定ART源码构造的格式样本，不是设备证据；支持现代完整summary及非streaming V1磁盘格式2/3，其他格式保留未验证。符号表方法ID0、线程内部索引、时钟维度、overflow、截断和初始未配对exit分别校验。初始exit允许存在，不据此宣称调用栈已完整；summary的is_precise_trace也不单独证明非采样，请求模式以原命令为准。

[归档模块](diagnostics/ime-profile-capability-2026-11-03/archive-validation.json)24项测试及真实prepare/临时Git来源集成通过。其5份来源文件和4份Python执行字节均先核验后加载；缺失、部分及失败结果保持独立。最终[94项整合测试](diagnostics/ime-profile-capability-2026-11-03/host-validation.json)通过（35采集/35格式/24归档），工作流语法检查通过。

失败保存是有界的：有可读大小记录时，超限或不稳定文件可保留有限前缀，始终未验证；如果大小查询本身失败，可能仅保留命令回执而无二进制。不存在把这种缺失升级为完整原件的回退路径。

[最终独立合同审阅](diagnostics/ime-profile-capability-2026-11-03/contract-review.json)已核对真实平台CLI、正常FD=null状态、UID及native PID绑定、失败保存、受限停止和可信源码加载，结论限定为实现合同通过，不替代设备结果。

## 首次设备结果与下一步

冻结源码 `048f1501f9c7607ff711eacbef6e1d0070d011b2` 的唯一运行 [41641068620](https://github.com/ecojust/Agent-OS/actions/runs/41641068620)（2026-11-03、attempt 1）失败；[原始清单](../../screenshots/ime-profile-probe-41641068620-1/manifest.json)保留 `unverified`，计数为0 recorded / 1 unverified。归档运行 [41641542579](https://github.com/ecojust/Agent-OS/actions/runs/41641542579)成功，固定证据提交为 `ff7b12f5133f80e374ea59a0dd647a86fdc8dd5c`。[独立审计](diagnostics/ime-profile-capability-2026-11-03/failure-audit-41641068620.json)核对6个Git原件共210,799字节、5个索引载荷共208,341字节、5份来源文件及13条命令的原字节和运行身份，均一致。

13条原始命令中，前12条通过：实际镜像为 Android17/API37、x86_64，`ro.debuggable=1`、shell UID2000、user0；默认输入法是 `com.android.inputmethod.latin/.LatinIME`，包UID为10112。第13条 `pidof com.android.inputmethod.latin` 正常结束但退出1、输出0字节，错误为 `COMMAND_FAILED_ime_pid`。这只证明该次查询未枚举到目标进程，不能由此确定冷启动、服务绑定或进程名称的具体原因。

`target=null`、`startAttempted=false`；未进入进程身份及全局槽校验，没有发出 profile start/stop、拉取或清理命令，归档也没有方法文件。实际方法格式、缓冲容量和 ART 请求是否可用均未测到，不能把此结果写成“平台不支持方法追踪”，更不能据此判断重建调用方。

[下一步的前提审阅](diagnostics/ime-profile-capability-2026-11-03/precondition-review.json)建议先补有界的只读 PID、进程列表与完整 `dumpsys input_method` 观测，确认默认配置、活动客户端和绑定进程之间的状态。如果需要启动输入法，另行引入明确标注的合成编辑器初始化并记录安装、焦点及显示请求；之后重新执行原有 UID/PID/开始时间/命令行与全局槽准入。该路径会改变本轮“无APK、无UI”的实验前提，必须作为新来源验证，不能静默预热或将本次失败回填为通过。原 A/B、Notes 用例和5秒预算保持不变。

同源[整体演示](../../screenshots/demos/41641074840-1/demo.mp4)已完成并独立核验：34.750秒、453帧、402,782字节，13阶段/8确认及仪器1/1通过。它仅覆盖API35 Fixture与本地模型，安全标志保留；与本项未执行的API37方法追踪分开统计，详见[录制说明](operation-demo.md)。

## 2026-11-05：补齐 PID 缺失时的状态记录

本轮只补首次 PID 缺失时的只读诊断。触发条件严格为原 `pidof` 命令正常结束、退出1且输出为空；其他错误、超时或多PID不沿用这条分支。随后各执行一次完整进程表、未过滤的 `dumpsys input_method` 和第二次同名 PID 查询。第二次即使读到 PID，仍保留首次失败，不在本轮继续 profiling。正常取得 PID 时，原进程身份、全局槽、追踪及清理检查保持。

追加命令各限5秒，进程表限1MiB、输入法服务输出限2MiB、第二次PID输出限64KiB；追加阶段用单调时钟设置20秒命令准入预算，并为单条执行和子进程终止留出时间。此预算不声称宿主调度、磁盘写回有硬实时保证。整个运行仍最多32条命令、总输出最多8MiB；典型缺PID路径为13条原检查加3条诊断。单项失败、超时或截断保留已取得的字节；取消和预算不足停止后续发起，不补写成功结果。

进程表和输入法服务原文分别留存，不把它们当成同一时刻的原子快照。服务dump退出0也不单独证明所有子服务输出完整、IME已绑定或键盘可见；两次空PID不证明两次查询之间一直没有进程。未知布局保持未知，实际绑定状态由本轮原件审阅后再记录。

本轮不安装APK、不发出显示请求、不修改设置或权限，不执行原A/B和Notes用例。[106项宿主整合测试](diagnostics/ime-readiness-2026-11-05/host-validation.json)已通过（44采集/27归档/35格式），工作流语法检查通过；[接口审阅](diagnostics/ime-readiness-2026-11-05/interface-review.json)和[独立实现审阅](diagnostics/ime-readiness-2026-11-05/implementation-review.json)均完成。新增测试覆盖晚到PID、超时/截断、取消、准入预算、回放隔离及原PID结果写入后被中断的保存边界。上述扩展已冻结为63a307dfc，API37专项和同源整体录像各执行一次，原件审查状态见下文。先前048f失败与34.750秒录像保持各自来源。

### 方法文件的 Git 二进制属性

冻结来源63a307dfc的API37运行42026098407已在CI通过，但首次归档42026446120在发布前的 `git diff --cached --check` 失败：[原始错误回执](diagnostics/ime-readiness-2026-11-05/archive-publish-failure-42026446120.json)显示，Git将长文本符号表开头的混合方法文件按文本检查，将后续原生记录字节报为行尾空白。产物校验此前已通过，当次证据分支没有发布；该失败保留，后续成功归档与独立核验见下文。

修正只为 `/screenshots/ime-profile-probe-*/method.trace` 声明 `binary`，同时在归档的旧源码检出中于 `git add` 前设置同一条本地 Git 属性，兼容尚无此规则的历史源码。[独立Git验证](diagnostics/ime-readiness-2026-11-05/binary-transport-audit.json)已用超过8KiB的文本头及含NUL/CRLF的原生样本验证：索引blob与重新检出逐字节相同，普通文本和其他目录仍执行空白检查。不改原文件，不清除其空白，不跳过其他文件的检查；重新归档同一次运行，设备不重跑。整体录像42026103268已归档并完成独立复核，README入口保持实际63a录制来源。


## 第二次设备结果：短时能力子项已完成

[唯一设备运行42026098407](https://github.com/ecojust/Agent-OS/actions/runs/42026098407)对应源码 `63a307dfc05f399dea997105c59589c6d7677a6b`（2026-11-05、attempt 1）。本次第一次 `pidof` 就返回1512，实际UID/Tgid为10112/1512，进程开始ticks为2081，前后命令行一致。镜像指纹与048f样本相同，但没有证明其进程启动时序相同或前次缺PID的原因。新增3条 `absence_*` 命令均未执行：它们已有宿主测试覆盖，本次没有取得缺失态的设备原文，也没有执行初始化或补发显示请求。

Git归档修正来源 `a123a748d16e69f3551bfd558efc1f78210b1e39` 只重新归档同一设备运行。[第二次归档42027092540](https://github.com/ecojust/Agent-OS/actions/runs/42027092540)成功，证据提交 `9ce0c04b23c95a8821c240aa92a6b2ad5a71b912` 的父提交仍是63a；[原始清单](../../screenshots/ime-profile-probe-42026098407-1/manifest.json)保持真实测试来源，未重跑设备或改写失败记录。

[独立原件审核](diagnostics/ime-readiness-2026-11-05/original-profile-audit-42026098407.json)确认7个Git原件共1,696,077字节、6个索引载荷共1,691,201字节、5份来源文件共97,796字节；31条命令、1,027,694字节输出均可追溯。完整AMS槽为空→本轮所有→仍为本轮所有→为空；一次启动、一次带身份保护的停止、连续两次稳定文件大小、远端与拉取摘要及自有文件清理均通过。方法文件[method.trace](../../screenshots/ime-profile-probe-42026098407-1/method.trace)为271,550字节，SHA-256 `7e88955b88f7ff2be94cb6db64c628525a6c516b288cd5968387c4efc2c0a309`，Git中的原字节一致。

[独立原生格式复算](diagnostics/ime-readiness-2026-11-05/native-audit-42026098407.json)没有调用生产解析器：73,782字节文本summary、32字节头部、197,736字节记录体；ART V1非streaming磁盘格式3、双时钟、每条14字节，共14,124条记录，PID1512且无overflow。797个方法、5个编码线程字典，其中4个线程有记录；7066条enter、7058条exit、0条unwind，符号引用和各线程时钟核验通过。summary的elapsed为1,893,651µs；这不是先前Perfetto的BOOTTIME时间轴，编码main线程也不当作Linux TID，记录数不当作调用次数或完整调用栈。

本子项完成范围仅为这套API37专用模拟器中已存在的默认IME进程能生成有界、完整、可解析的短时方法记录。没有重跑原A/B或Notes；原24通过/8失败、5秒预算及失败分母不变。缺PID的具体原因和稳定性、较长窗口是否溢出、重建入口仍未闭合。下一步设计有界较长窗口与调用栈完整性校验，再接原对照；不出现重建、溢出或调用链不完整时继续记录未知，不能靠延长原Notes预算关闭失败。应用验收检查点仍进行中，工程检查点保持10/20。

同源[整体录像](../../screenshots/demos/42026103268-1/demo.mp4)为36.813秒、487帧、439,182字节，13阶段/8确认及仪器1/1通过。30载荷、22PNG、31Git原件与全段解码均经[独立核验](diagnostics/ime-readiness-2026-11-05/overall-video-audit-42026103268.json)，已查看暂停及最终浮条移除；stopped PNG仍等待确认，不充当末帧。仅API35 Fixture/本地模型，安全标志保留，不替代方法文件、原Notes或真实模型验收。
