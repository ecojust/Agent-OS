# API37 输入法状态后置观测

本轮承接[首轮原始窗口采集失败](window-infos-raw.md)：2eeb源码注册成功，但18条IME候选的触摸区域均为空。新增一次普通输入法状态dump，检查输入视图启动/显示决策与最后计算的Insets；不改变Android测试页、原始监听器、一次显示动作、12秒观察区间或四项过滤。原Notes32项本轮不运行，24通过/8失败保持。

## 冻结合同

新增命令仅在原始进程正常退出、有效结果已冻结且合成页面已启动后执行，位于后置截图之前。固定argv为：

```text
adb -s <dedicated-emulator> shell /system/bin/timeout -s KILL 8s /system/bin/dumpsys -t 5 input_method --user 0
```

最多一次，宿主12秒、262144字节；不重试、不请求root、不改输入法/hidden API策略。内部dumpsys5秒、设备进程8秒与宿主12秒分别记录，不能据本地或设备命令结束推断远端Binder工作已取消。固定源码的普通dump按顺序转发到当前客户端和输入法，慢转发可以耗尽预算；超时、非零、取消、输出限额、无效UTF-8或不完整服务块均保留未知。

该命令在原始回调冻结后执行，因此不会扩展原12秒采样。它可能短暂占用IME主线程，后置截图的实际时刻也随之改变。原回调、dump开始/结束区间与截图是三个有序但非原子的观测，不能拼成同一帧，也不能把后置新状态回填原窗口数据。

[固定源码审阅](diagnostics/window-infos-ime-state-2026-11-01/design-review.json)与[CLI语义复核](diagnostics/window-infos-ime-state-2026-11-01/dumpsys-cli-review.json)已保存。普通单服务dump没有通用成功footer；服务不存在或内部超时也可能返回exit0。因此需要核对完整原命令输出、唯一IMMS/用户/当前输入法服务边界、固定末尾字段及错误文本，不能只认退出码。

## 身份、字段与结果

输入法期望组件和包/UID继续来自原来的配置与包管理查询；这些原件、来源SHA/run/attempt与dump命令参数、时间、大小和摘要重新绑定。dump中的当前用户、绑定组件、display及服务类用于核对本次上下文，不是原始窗口Binder的同窗证明。外部包管理UID不会被改称dump证明的当前输入法进程UID。

只派生配置中的键盘枚举、基础视图创建状态（不证明输入视图对象非空）、输入/输入视图启动、显示请求、输入视图显示决策、窗口可见、候选区可见、最后计算的contentTop/visibleTop/touchableInsets/touchableRegion及硬键盘显示设置缓存。明确的false/null是负状态，缺失或非法值是未知，二者不混为一谈。设置缓存0表示UNKNOWN，不能当false或实时系统设置。

这些字段不直接给出`mInputView`是否非空、实际视图高度、最终WMS/SF触摸区域或实际键盘像素。尤其VISIBLE区域模式下，IMS保存的touchableRegion字段为空本身不等于最终窗口区域为空；还需看模式、边界和后续裁剪。

新报告使用schema2；raw marker、fixture、capture和命令原件保持原schema。旧schema1继续按旧来源与29条命令合同核验，禁止为旧报告补写新观测。schema2正常路径为30条命令，`ime-state.json`从唯一原命令重新解析；即使重算派生文件哈希，也不能伪造与原始输出不一致的状态。

原`raw_passed`和正对照计数不变。新实验整体通过还要求后置观察完整记录；若原正对照已失败，保持其主失败原因，dump超时或缺字段不会覆盖它。如果原正对照通过而新观测未知，则单列`IME_STATE_NOT_RECORDED`，保留原正对照计数。只在dump结束后才收到的页面IME可见事件不能补成原12秒内通过。

## 实施与验证进度

宿主接线、独立解析器及归档兼容已完成。[107项本地检查](diagnostics/window-infos-ime-state-2026-11-01/local-validation.json)通过：29宿主、32归档、13构建、33解析器；[独立审查](diagnostics/window-infos-ime-state-2026-11-01/final-implementation-review.json)另核12个反例。覆盖超时/截断/取消/非零后的采集与清理、原失败优先、迟到IME事件、原件与派生不一致、尾部重复字段和写侧车后尚未同步报告的中断恢复。恢复只重算派生状态，不升级实验结果；[真实旧schema1回放](diagnostics/window-infos-ime-state-2026-11-01/legacy-replay.json)保持原失败和原字节。actionlint通过，Android与产品源码未改，本地未重复Gradle；CI将正常构建/lint，重新调试签名可能改变APK字节，不能称二进制必然相同。

实现冻结为 `de150f4c975eec6c9d0b2f2b44e825c60fd0130a`，随后完成一次专用API37实验与一次既有API35整体演示。实际结果如下；合成检查不代替设备证据。


## 实际结果：新增观测完整，原始正对照仍失败

2026-11-01，源码 `de150f4c975eec6c9d0b2f2b44e825c60fd0130a` 的[唯一API37运行41293997835](https://github.com/ecojust/Agent-OS/actions/runs/41293997835)结束为failure，原因仍为 `RAW_POSITIVE_CALLBACK_NOT_OBSERVED`。新增IME观察为**recorded，15项字段完整**，不是解析失败、超时或缺少原件；整体仍为**0通过/1失败**。上一轮2eeb的0通过/1失败单独保留，原Notes32项没有重跑。

[归档41294222386](https://github.com/ecojust/Agent-OS/actions/runs/41294222386)成功，提交 `758db509a825b5e10953918897817839cb15b4e6` 的[不可变清单](../../screenshots/window-infos-raw-41293997835-1/manifest.json)仍标failed。实际设备为Android17/API37、x86_64、shell UID2000，指纹 `Android/sdk_phone64_x86_64/emu64x:17/CE2A.260420.051.A3/16571990:userdebug/test-keys`；输入法前后都是 `com.android.inputmethod.latin/.LatinIME`、UID10112、display0，合成页面UID10156。未修改输入法、显示设置、原始监听器或测试页源码。

[原始回调](../../screenshots/window-infos-raw-41293997835-1/raw-result.json)保存69次完整回调，0本地限额丢弃、0区间外回调；25条候选全部属于本进程同一非空Binder编号1，全部区域为空。其中4条NOT_VISIBLE、1条frame为空、0条CLONE；后21条可见且frame非空，仍未满足四项过滤。25条是同一窗口候选的重复记录，不是25个不同窗口。

[页面回执](../../screenshots/window-infos-raw-41293997835-1/fixture.json)只请求一次，显示请求被接受，并收到IME insets可见事件。请求时刻33567ms，候选出现在33890–34607ms，即请求后323–1040ms；最后候选至43954ms冻结没有新回调，不能称为连续轮询。显式注册/注销各一次，原进程正常退出，两项清理返回成功；注销Java调用返回仍不是SF移除确认。

### 新增状态揭示运行时硬键盘条件

[IME原件及派生结果](../../screenshots/window-infos-raw-41293997835-1/ime-state.json)来自唯一普通dump，49,154字节、39ms、退出0；原命令和完整输出另保存在[commands.json](../../screenshots/window-infos-raw-41293997835-1/commands.json)。当前用户、选中/当前IME组件、服务类、display与合成页面EditorInfo均匹配，所有15项字段由独立分析逐项复核。

| 后置实测字段 | 实际值与含义 |
| --- | --- |
| Configuration | `qwerty/v/v`：keyboard=2、keyboardHidden=1、hardKeyboardHidden=1；系统向IME报告硬键盘可见 |
| SettingsObserver缓存 | 1/FALSE：缓存认为有硬键盘时不显示软键盘；不是新读取的实时secure设置 |
| mViewsCreated / mInputStarted / mInputViewStarted | 全为true；不证明实际输入视图非空或可见 |
| mShowInputRequested | true，显示请求已进入输入法状态 |
| mIsInputViewShown | **false**，输入区域的显示决策为不显示 |
| mDecorViewVisible / mWindowVisible | 全为true；窗口可见不等于键盘内容可见 |
| mCandidatesVisibility | 4/INVISIBLE |
| 最后Insets | contentTop=616、visibleTop=616、touchableInsets=2/VISIBLE、`SkRegion()`；这是IMS缓存，不直接等于最终WMS/SF区域 |
| EditorInfo | 非null，inputType=2097153（0x200001），package=dev.agentos.compat；原dump里的客户端InputBinding UID也为10156 |

[后置截图](../../screenshots/window-infos-raw-41293997835-1/screenshot.png)为320×640，只见合成编辑器，没有软键盘。宿主时间顺序为：raw结束09:41:15.681044，dump开始15.685024、结束15.724765，截图开始15.733022、结束15.855712（均UTC）。raw→dump间隔3.980ms、dump→截图间隔8.257ms；相邻并不代表原子快照，也不把39ms内的新状态回填9秒多以前的最后候选回调。

### 原因范围已缩小，但尚未做因果对照

固定InputMethodService的`onEvaluateInputViewShown()`先检查硬键盘显示设置缓存，再判断`keyboard == KEYBOARD_NOKEYS`或`hardKeyboardHidden == HARDKEYBOARDHIDDEN_YES`。本轮实际2/1加缓存FALSE恰满足该基类返回false的条件。固定LatinIME确实覆盖此方法，但只在私有强制显示标志期间返回true，其他情况调用基类；该私有标志本次未采集。输入视图启动回调的置位也不要求`mIsInputViewShown=true`，因此启动true、显示false并不矛盾。详见[独立源码/原件分析](diagnostics/window-infos-ime-state-2026-11-01/independent-ime-analysis.json)和[固定LatinIME三文件核查](diagnostics/window-infos-ime-state-2026-11-01/latinime-source-review.json)。

这将“硬键盘显示策略”从猜测推进为实际记录到的条件，该源码分支与后置输入视图不显示及无键盘画面一致；还没有证明某次旧回调必然执行了该源码分支，或证明原Notes八项失败的根因。源码标签未与镜像字节核同，未观测私有强制显示标志、实际子视图尺寸及最终区域生成链，不能据缓存空region单独下结论。

同时，[CI日志两次报告AVD文件检查通过](diagnostics/window-infos-ime-state-2026-11-01/avd-runtime-disagreement.json)：`hw.keyboard=no`。这只验证配置文件，无法推翻IME收到的运行时`qwerty/v/v`。本轮没有归档原AVD配置文件或运行时输入设备清单，尚未确定差异来自哪一层。下一步先核对运行时输入设备与AVD配置，再冻结可恢复原值的单变量软键盘显示设置对照；该对照必须单列环境变化，保持一次显示请求和四项过滤，不用于回填本轮或关闭原32项。

### 原件审核

[独立审核](diagnostics/window-infos-ime-state-2026-11-01/independent-original-review.json)核对18份执行源码/273,757字节、30条命令/99,897输出字节、12份索引文件/262,666字节，另有[不依赖生产校验器的Git及来源审计](diagnostics/window-infos-ime-state-2026-11-01/independent-provenance.json)。原raw四谓词、时序、15个IME派生字段与2,133字节服务体摘要全部一致；测试载荷为9份/237,298字节，其余3份是原始来源元数据。jar/dex字节已归档并核验；APK与原artifact ZIP未在本归档保留，未声称独立重算其摘要。构建与lint在CI成功。


## 同源整体演示

同一de150源码的[独立API35整体视频](../../screenshots/demos/41294000343-1/demo.mp4)完成13阶段/8确认，41.493秒、640×1280、451,929字节、524帧；[30载荷/1,185,751字节及22PNG](../../screenshots/demos/41294000343-1/manifest.json)、Git原字节、完整解码和实际关键帧[核验通过](diagnostics/window-infos-ime-state-2026-11-01/overall-video-audit.json)。唯一录制41294000343，归档41294302477、提交50fae3099a00a94e39d6c6633af13deed8eb0b9c。

这是Fixture及本地合成HTTP模型的既有普通操作流程，保留安全窗口标志；本页API37实验只保存后置PNG及非视觉原件，没有把这段API35影片称为IME专项录像。README每类仅列最新已验证视频，真实APK与繁忙停止仍保持原来源，旧失败录像继续保留。
