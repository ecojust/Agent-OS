# API37 过滤前测试接口能力探查

本步骤承接[API36后置属性扫描修正](notes-ime-attrs-failure-reasons.md)。cdea60c1的独立32项仍24通过/8失败，API36/37各两态在完整后置Populator与最终窗口清单中均未匹配配置IME候选。已消除的属性扫描限制不解释原Notes隐藏失败。

本轮目标是实际验证固定API37模拟器上的公开测试接口能否被发现，以及一个无参构造能否调用。它不是窗口监听实验、原32项应用回归或原八项失败的修复验收。

## 冻结范围与结果含义

沿用现有`am instrument -w -r`启动方式和目标SDK35，独立运行一个显式选择的方法。固定AOSP17实现默认放开Instrumentation的TestApi检查、保持一般hidden API检查；两者不同。本轮保留这个测试默认值，不追加`--no-hidden-api-checks`或其他策略修改，不能将结果推广为普通生产APK的访问能力。安装独立compat测试APK，不安装或运行Notes日常流程，不启动Agent服务，不请求窗口截图或窗口内容。仅在专用API37模拟器执行一次来源固定的探查；APK、运行ID/attempt、原instrumentation和结构结果共同归档。

只对固定`WindowInfosListenerForTest`、嵌套`WindowInfo`和`DisplayInfo`查找公开构造、方法及字段，核对预期签名；使用不主动初始化类的加载方式，不枚举任意成员、不访问私有成员、不使用`setAccessible`。固定源码证明ForTest公开无参构造只初始化两份ArrayMap，因此允许至多一次实际构造，并单列尝试与完成状态；不由构造成功推出注册可用。

注册/注销方法只查找，不调用。字段只查找名称与类型，不读取实例值，不读取窗口名字、token、矩形或输入配置。缺少或被运行时隐藏策略过滤的成员统一如实记录为未找到/被过滤；不能仅靠查找失败宣称底层实现不存在。

不注册listener、不委托shell权限、不修改隐藏API策略、不额外申请豁免、不增加原Notes采样、动作或准备时间。记录固定SurfaceFlinger权限的当前检查结果，仅说明检查时的应用权限，不能代替注册调用、权限委托或原生回调验收。

探查执行是否完整与接口能力是否满足分别判定。一个完整且正确记录成员缺失的测试，可以说明探查成功，但不能标记完整过滤前诊断可用。原八项失败继续保留。

## 实现边界

固定目录为27槽：3类、1构造、4方法、14个源码列出的字段及5个缺口名称。`requiredSurface=MATCHED`仅表示前22项声明的签名和形状匹配，不表示5项缺口得到满足，更不表示完整过滤前诊断可用。类必须是预期的公开普通类，字段和方法分别核对声明类型、修饰符与声明归属。缺口字段没有编造预期类型，找到也只记录有限类型枚举，不推断其语义。

反射前后使用单调时钟，5000毫秒期限只限制后续操作的开始并报告实际是否超期，不能强制中断已经进入的同步类加载/构造。完整marker最多16KiB，包含固定枚举、数量、来源和受限环境信息；无异常原文或窗口字段值。主机单次instrumentation命令期限60秒、输出最多256KiB；设置/安装命令另有10秒/60秒期限和64KiB输出限额，正常路径17条固定命令，不自动重试。主机清理只终止其自行启动的进程组，不据此承诺远端测试进程已同步停止。

原命令输出与退出状态、唯一测试class/method的开始/标记/结束、最终instrumentation结束码、27槽顺序、实际APK包和SDK、设备属性及来源摘要均重新核对。失败/截断/超时保留原件，未执行记为未验证，不能凭一行“OK”或借用其他用例的marker认定完成。只有完整固定单项可记为`recorded`；这仍是诊断执行结果。

## 源码依据与身份限制

[固定AOSP17研究](diagnostics/notes-ime-attrs-reasons-2026-10-31/upstream-source-review.md)及其[源码索引](diagnostics/notes-ime-attrs-reasons-2026-10-31/upstream-source-index.json)说明：ForTest公开14个字段（WindowInfo12、DisplayInfo2），其中bounds经过显示变换，部分boolean来自inputConfig。现有公开签名没有完整inputConfig、isClone、原始frame、窗口类型或ownerUid。本轮对这些固定缺口名称做公开字段查询，不据名字推测缺失字段的值。

即使类和成员可访问，仍未验证SF权限、实际注册、初始缓存/自然回调、注销，以及配置IME的可信身份来源。同一个IWindow对象跨进程产生的BinderProxy文本不可直接比较；仅凭名字、形状或输入通道token也不能补成强身份。实际模拟器镜像与固定AOSP源码逐字一致尚未建立，源码依据与设备观察分别保留。

## 验证与归档安排

先检查固定白名单、访问拒绝/字段缺失、签名变化、重复或错误来源marker、执行超时/输出截断和未执行结果，再构建/lint并独立审查。冻结源码后执行一轮API37单项探查；结果完整前不预先写成通过。没有视觉过程，归档明确为非视觉记录，不制造截图或用桌面画面证明接口能力。

同一源码另尝试一次整体操作录像，使用既有API35 Fixture与本地测试模型。录像失败非阻塞，保留全部失败原件，不为取得成功片重复录制。README按实际来源保留最近完整演示及最新失败记录，72f真实APK录像不重标为新源码。本轮未改原32项流程、旧诊断或正式应用样本，不重跑其未变更的完整矩阵，也不把旧运行日期改为本轮。


## 整体录像准备失败的并行源码核对

上轮cdea录像在ready阶段服务未连接。本次[只读源码核对](diagnostics/window-infos-capability-2026-10-31/ready-service-source-review.md)确认原启用写入与立即读回检查已经通过，失败诊断先于清理，不能用后续恢复解释当时的null。固定AOSP15存在根据已安装服务清单移除启用项的路径；旧原件缺少相应时序，尚未证明实际触发。该调查不改变本轮演示脚本，也不把这个假设称为稳定根因；后续须先核对公共服务清单读取语义，再设计独立、有界的就绪性诊断。


## 本地验证完成

[Java宿主检查](diagnostics/window-infos-capability-2026-10-31/java-host-tests/host-validation.json)81项通过，验证固定访问、非初始化查找、至多一次构造、缺失/拒绝/签名变化/期限及输出限制；这些使用受控替身，不能当作设备结果。[主机与协议验证](diagnostics/window-infos-capability-2026-10-31/host-validation.json)和[归档/工作流验证](diagnostics/window-infos-capability-2026-10-31/archive-workflow-validation.json)共54项通过，包括实际子进程超时/输出上限/读取异常/EOF后取消清理、完整17命令原件重放和归档边界。

真实Java合成输出与Python严格协议交叉验证通过；本地两份实际APK的aapt输出已回放，测试APK正常的空versionCode被独立内部解析器处理，不放宽官方APK验证器。最终构建/lint通过（27秒），工作流actionlint及[独立实现审查](diagnostics/window-infos-capability-2026-10-31/independent-implementation-review.json)通过，来源见[本地回执](diagnostics/window-infos-capability-2026-10-31/local-validation.json)。原产品、原32项入口/诊断、旧工作流和APK锁均保持；新设备探查尚未执行。
