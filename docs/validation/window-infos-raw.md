# API37 有界原始窗口采集

本实验承接[原始字段与输入法身份来源核查](window-infos-raw-source.md)，在独立 shell `app_process` 中实际调用一次原始窗口监听器，使用合成编辑页的一次键盘请求作正对照。它不运行原 Notes v4 流程；原 32 项的 24 通过、8 失败继续保留。

首次真实API37采集已完成，**正对照失败，0通过/1失败**。shell入口实际注册成功并读到57次完整回调，但所有18条IME候选记录的触摸区域均为空；不能标记该诊断正对照或原Notes失败已经修复。具体来源与时序见下方实际结果。

## 执行范围

专用 GitHub 托管模拟器限定 Android17/API37、x86_64、用户0、shell UID2000，测试APK的构建与目标 SDK 保持35。只安装 compat 测试 APK，不安装或启动 Agent、Notes；不更换输入法、不修改 hidden API 策略、不增加豁免、不请求 root。宿主要求 `dalvik.vm.extra-opts` 为空，并保存真实命令、构建指纹、源码、APK/jar 摘要及当前输入法前后配置。

固定18项类/构造/方法/字段/常量检查通过后，才加载原始监听器子类、构造并注册。编译使用最小隐藏类型声明；这些声明不进入最终 dex，构建器解析 dex 的类定义表再次核验，不能用字符串存在与否替代定义检查。主程序的入口不直接链接隐藏父类，避免在准入记录之前因子类验证失败退出。

没有额外权限查询桥。注册的实际结果检验当前 shell 是否能访问 SF；固定源码的权限声明不当作设备授权证据。native 注册失败可能直接终止进程，缺失结果或崩溃按失败保存。

注册返回并保存初始缓存后，程序输出绑定来源的 READY。宿主只在收到唯一合法 READY 后，启动一次 `RawWindowFixtureActivity`。该页面只含合成文本，先请求编辑焦点，在首次窗口焦点回调后投递一次 `showSoftInput`，让系统先完成输入法焦点登记；失焦或投递失败不重试。最多三次小型私有回执记录创建、请求和首次观察到 IME insets 可见。接口接受与实际可见分别记录。

| 边界 | 固定上限及含义 |
| --- | --- |
| 元数据准入 | 5000ms；同步检查返回后再次核期，超期不开始构造/注册，不能中断已进入的同步调用 |
| 构造、注册、注销 | 探针显式 Java 调用各至多一次；平台终结器可能另行清理。注销返回不是 SF 移除确认 |
| 观察区间 | READY 起12000ms；初始缓存单列，自然回调单列，不等待至获得某个结果 |
| 回调存储 | 最多128份回调，加一份初始缓存；超过上限计数并标不完整 |
| 单份数据 | 最多256个 handle、16条候选行；最多保有16个不同窗口 Binder 引用 |
| 最终标记 | 128KiB；若超限，明确标不完整和省略条数，不升级为通过 |
| 设备侧进程 | 固定 `timeout -s KILL 40s` 包裹当前诊断进程；异常结束不算正常注销 |
| 宿主进程 | 单次原始命令60s、256KiB输出；本地清理不声称已观察到远端进程退出 |

回调只复制固定字段到有界内存，不做 shell 调用、JSON输出或等待另一监听器。handles 上限约束 Java 处理及归档，不能限制回调前 JNI 已发生的数组转换。冻结之后的在途回调不扩展已声明观察区间；实际单调时间和丢弃计数单独保存。

## 窗口候选与通过条件

配置组件来自当前用户设置，包/UID由真实系统查询核验。每份回调依次统计类型、显示器、UID、包名匹配数；包名不可读或空、空 handle、字段读取失败与限额超出均保持不完整。仅收集匹配配置包、UID、TYPE_INPUT_METHOD=2011、display0的行，不保存其他窗口标题、文本、token字符串或触摸区域内容。

每条候选保留完整 inputConfig、四个 frame 边、区域是否为空，以及 NOT_VISIBLE、CLONE、空 frame 和四条件结果。不能预先剔除不可见、克隆或空区域；同一实际 Binder 对应多行时全部保留。窗口身份在本进程以实际 Binder 引用分组，导出的编号只对本次运行有意义。

正对照要求完整正常运行、前后配置及UID一致、编辑页确实只请求一次且观察到 IME 可见，并至少有一份**请求之后**的完整自然回调：非空 Binder 恰好一个、无空 token，且有候选行通过四项条件。宿主独立重算位标志、矩形空性、计数和时间条件，不只接受生产器的 passed 标签。

这里的匹配仍是“配置 IME 包与 UID 的输入法类型窗口候选”，不独立证明具体服务组件或原失败时刻的 IWindow。两个进程的 `elapsedRealtime` 可以比较时间先后，但回调与页面 insets 不是原子快照。原始监听只返回 SF 子集，初始缓存空、没有回调或无候选不证明 WMS 中没有输入法窗口。

本轮不注册 ForTest 第二监听器。ForTest不执行Populator四项过滤，其同窗关联不能自行解释旧失败；原始字段的实际读取已经是本实验需要验证的新信息。

## 原件与截图

每条命令保存固定参数、时限、实际时间、退出结果和有界原始输出。READY、最终标记、fixture私有回执、设备属性、远端jar摘要和前后配置均重新核对；重复标记、来源不符、截断或超时不能记为通过。原命令输出保留，即使派生JSON缺失或无法验证，也不能补造结果。

原始进程结束后、清理编辑页前，只截一次模拟器屏幕，保存于 `screenshots/window-infos-raw-<run>-<attempt>/`，与诊断原件共同形成不可变归档。截图仅说明这个采样时刻的合成场景；它不证明较早某次回调的 Binder 身份，也不能代替字段和时序验证。缺失/受保护/失败的截图按实际状态记录，不清除安全窗口标志。

同一源码另尝试一次既有 API35 整体操作演示，使用 Fixture 与本地合成模型；录像失败非阻塞，不重复录制以获得成功。它与本页 API37 窗口实验分别报告。

首个完整正对照只验收指定 API37 构建及配置输入法的诊断入口。原失败冻结后的采集、API36适配、失败根因、实际修复及复测仍需后续证据；普通生产 APK 没有因此获得隐藏平台能力。


## 本地验证与源码冻结

[本地验证](diagnostics/window-infos-raw-2026-11-01/local-validation.json)完成：58项Python检查（22宿主、23归档、13构建）通过，包含真实子进程超时/输出边界及完整合成原件归档；Java收集器另有37条语义断言。实际Java生产器的一次正向及四个失败路径通过宿主解析，覆盖缺字段、常量错误、最后一次查找跨期及注册异常后的单次清理；另有[实际Java输出限额检查](diagnostics/window-infos-raw-2026-11-01/synthetic-output-limit.json)：128份回调只序列化32份时明确报告INCOMPLETE和96份省略，没有误判通过。这些均为合成宿主测试，不是Android设备证据。

实际javac/D8构建生成11,317字节jar、10个自有类定义，未打入Android桩类；APK构建/lint与actionlint通过。首次本地在线构建等待依赖元数据连接，取消后以已有固定依赖缓存离线完成（32秒）；CI仍正常构建。源码、字节摘要、[独立设计审查](diagnostics/window-infos-raw-2026-11-01/implementation-review.json)和[固定焦点顺序依据](diagnostics/window-infos-raw-2026-11-01/design-review.md)已保存。该实现已冻结为 `2eeb64be2be468f1aac4d521a5cf89a85a9fcaa0`，随后执行一次API37独立采集；结果如下，整体录像另行验收。


## 首次实际结果：监听可用，正对照失败

2026-11-01，冻结源码 `2eeb64be2be468f1aac4d521a5cf89a85a9fcaa0` 的[唯一运行41278377093](https://github.com/ecojust/Agent-OS/actions/runs/41278377093)结束为 failure；失败原因是 `RAW_POSITIVE_CALLBACK_NOT_OBSERVED`。[归档41278593569](https://github.com/ecojust/Agent-OS/actions/runs/41278593569)成功仅表示原件已保存，其[不可变清单](../../screenshots/window-infos-raw-41278377093-1/manifest.json)仍为 failed，归档提交为 `10e53ee9712e384077a20b6ed0b6ca1a1b762ac9`。直接下载产物曾返回HTTP403，随后通过仓库归档工作流取得同一运行的原件，没有重跑设备。

实际设备是专用Android17/API37、x86_64模拟器，指纹 `Android/sdk_phone64_x86_64/emu64x:17/CE2A.260420.051.A3/16571990:userdebug/test-keys`。诊断进程UID2000，测试APK UID10156/target35，配置输入法前后均为 `com.android.inputmethod.latin/.LatinIME`、UID10112、display0；没有安装Agent或Notes，没有修改输入法或隐藏接口策略。

| 实际观测 | 结果及边界 |
| --- | --- |
| 元数据与注册 | 固定18项全部匹配；显式构造、注册、注销各一次，注册成功，注销Java调用返回；不是SF已移除监听器的独立确认 |
| 原始数据 | 初始缓存0个handle；57次自然回调全部完整保存，0丢弃，0区间外回调；初始缓存空不证明WMS无IME |
| 候选身份 | 共18条候选记录，全部是本进程同一非空Binder编号1；不是18个不同窗口，也未独立证明具体IME服务组件 |
| 四项过滤 | 18条全部 `regionEmpty=true`；其中7条NOT_VISIBLE、3条frame为空、0条CLONE；其余11条可见且frame非空，仍因空区域不满足四条件 |
| 页面请求 | 一次 `showSoftInput` 返回已接受；页面后来观察到一次IME insets可见。接口接受、insets事件、实际键盘绘制分别判断 |
| 最终结果 | 正对照回调0；原始标记 `RECORDED` 表示采集完成，报告与清单仍failed。进程正常退出，两个清理命令成功；未直接观测模拟器后续销毁 |

[原始命令](../../screenshots/window-infos-raw-41278377093-1/commands.json)、[raw结果](../../screenshots/window-infos-raw-41278377093-1/raw-result.json)及[fixture回执](../../screenshots/window-infos-raw-41278377093-1/fixture.json)保留原始来源。两份独立审核核对11份索引载荷、177,340字节、29条命令与45,928字节命令输出；17份冻结执行源码、245,693字节逐Git/摘要验证。全部候选的位标志、frame空性、四谓词、时间条件和正对照计数重新计算一致，见[原件审核](diagnostics/window-infos-raw-2026-11-01/independent-original-review.json)与[独立重算](diagnostics/window-infos-raw-2026-11-01/independent-original-analysis.json)。jar字节已归档；APK只有构建与安装记录/摘要，未在此原件包中保留APK，不能声称已独立重算APK摘要。

### 失败时序与仍缺少的证据

以下均为同一设备的 `elapsedRealtime` 毫秒，宿主墙钟另在commands中保存：

| 事件 | 时间（ms） |
| --- | ---: |
| 注册返回并输出READY | 25829 |
| 页面唯一显示请求 | 27201 |
| 第一条IME候选（callback40） | 27428 |
| 页面首次IME insets可见事件 | 27874 |
| 首条NOT_VISIBLE清除且frame非空候选（callback47） | 27929 |
| 最后一条候选（callback57） | 28260 |
| 固定12秒观察截止/冻结 | 37829 |

18条候选只出现在请求后227–1059ms。最后一次回调至截止没有新回调，不是连续12秒的窗口轮询，也不证明整个期间状态未变。后11条 `inputConfig=260`，在固定AOSP定义中是NOT_FOCUSABLE(4)与TRUSTED_OVERLAY(256)，不含NOT_TOUCHABLE(8)，因此不能把空区域直接解释成该标志被置位。

[采集后的唯一截图](../../screenshots/window-infos-raw-41278377093-1/screenshot.png)为320×640，实际画面只有合成编辑页，没有可见软键盘。截图在采集结束后单独获取；页面早先的insets事件不能据此升级为键盘像素或触摸区域非空的证明，后置截图也不能倒推早先全部时刻均无键盘。

本轮已证明该指定shell环境能取得过滤前候选及实际Binder分组，排除了本轮注册被拒、采集超时或探针本地超限丢弃回调这几种失败路径。仍未证明空region来自输入法视图未创建/未显示、输入区域生成/裁剪、跨进程时序或其他平台行为；固定AOSP标签不等于这份模拟器镜像的逐字节来源。四项过滤和正对照门槛保持，不重跑未变源码以覆盖失败。下一步先核对输入法inputView与insets/触摸区域生成路径，再确定一次有界、能区分这些状态的诊断；原Notes失败冻结后的采集、API36适配及实际修复仍未验证，原32项继续24通过/8失败，本轮没有重跑它们。


### 固定源码复核与下一次最小观测

[失败后源码复核](diagnostics/window-infos-raw-2026-11-01/first-run-source-review.json)重新核对13份固定源码的摘要及行号。`WindowInsets.isVisible(ime)`读取类型可见性映射，不能单独证明输入视图已创建、已绘制或具有有效高度。InputMethodService中的窗口可见、`mInputViewStarted`、`mIsInputViewShown`和输入区域计算也是不同状态；默认VISIBLE区域模式下，输入/候选frame未显示可以形成空区域，但`mTmpInsets.touchableRegion`字段为空本身不能直接推出最终WMS区域为空，仍需结合区域模式、override、导航区域和SF裁剪。

固定JNI会把native触摸区域逐矩形并集后赋给Java handle的Region，生产采集器直接读取该对象的`isEmpty()`；未找到只因窗口类型是IME就替换成默认空区域的分支。[补充Region/Dex核查](diagnostics/window-infos-raw-2026-11-01/region-source-dex-review.json)核对两份底层源码及本次实际归档jar的反汇编：Region保留传入native指针，`isEmpty()`调用SkRegion，实际Reader确实读取该Region，没有常量true替代。固定新版IMM的显式显示路径不读取`SHOW_IMPLICIT`标志，TextView关闭自动焦点显示也没有直接关闭EditorInfo/InputConnection的证据。因此暂不修改这两个参数来尝试让本轮变绿；这些源码路径仍不是当前镜像运行时根因证明。

下一次实验先保持同一显示动作和四项门槛，增加一次有界普通`dumpsys input_method --user 0`，保存实际来源与采样时间，提取当前IME子dump中的Configuration、输入视图启动/显示状态、窗口可见状态及最后计算的Insets模式/边界/区域。该接口不直接报告`mInputView`是否为空或视图实际高度，也不与原回调原子同步；超时、截断或缺失IME子dump均保持未知，不补成通过。本轮尚未执行这个新增观测。


## 同源整体录像

同一2eeb源码的[独立API35整体录像](../../screenshots/demos/41278369605-1/demo.mp4)完成13阶段/8确认，32.688秒、640×1280、402,418字节、447帧；[30份载荷/1,142,785字节及22PNG](../../screenshots/demos/41278369605-1/manifest.json)、来源摘要与视频完整解码核验通过，[审核记录](diagnostics/window-infos-raw-2026-11-01/overall-video-audit.json)另存。录制运行41278369605、归档运行41278821423、归档提交199f71b176ac39fdad3693811aea32e58470b895。

这是既有Fixture和本地合成模型流程，保留安全窗口标志，没有拍摄本页API37监听实验，也没有重跑官方APK或原32项；本页原件只有一张后置合成页面PNG。README只替换普通操作这一类的最新视频，真实APK及繁忙停止视频保持原来源，旧cdea录制失败继续保留。
