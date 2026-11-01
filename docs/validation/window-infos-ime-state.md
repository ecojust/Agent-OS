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

设备实测及同源整体录像尚未执行，合成检查不代替实际采集。接下来冻结源码，只运行一次专用API37实验和一次既有API35整体演示，失败原件照常保存。
