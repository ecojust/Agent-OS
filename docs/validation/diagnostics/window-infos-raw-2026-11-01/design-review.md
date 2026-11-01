# API37 raw 窗口独立实验设计复核

2026-11-01，只读审查；未改仓库、未执行设备、Gradle、监听器或权限操作。

## 可执行范围

采用独立 shell app_process、一个 raw listener、一个专用 EditText fixture，先准入再注册一次，以 READY 通知主机启动一次 fixture。允许自然回调，初始缓存单列；12 秒观察期限内复制固定字段，freeze 后至多注销一次并退出。此合同能验证该镜像的 raw 注册与配置包/UID/类型/display 的候选数据，不证明具体 IME 服务组件，也不改判旧 Notes 24/8。

ForTest 不执行 Populator 四谓词，因此本轮不需要第二 listener 才有信息增益。原始记录直接计算 NOT_VISIBLE=2、CLONE=65536、regionEmpty、frameEmpty 即可；不能预过滤这些行求唯一。相同 Binder 的多个 raw 行/clone 必须保留，唯一性按不同非空 Binder 而不是行数判定。

## 固定源新增核对

固定 frameworks/base `94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`，新下载索引见 source-index.json。

- `PackageManagerShellCommand.java` 的命令分发及 help 中没有 check-permission；已缓存同提交 ActivityManagerShellCommand 也没有该命令。不应新增伪准入命令。实际 shell UID/环境/固定签名过关后，让 register 的真实结果说明访问是否成功；预注册权限标 `NOT_INDEPENDENTLY_QUERIED`，不额外构造 ActivityThread Context 或反射私有权限接口。
- `SurfaceFlinger.cpp:10717–10744` 的 add/remove listener 对非 SYSTEM/GRAPHICS UID 检查 ACCESS_SURFACE_FLINGER。WindowInfosListenerReporter.cpp:36–53 注册失败可能 FATAL；Java catch 无法保证截获。独立进程、设备端 timeout 与主机时限须保存无 marker/崩溃/超时。
- app_main.cpp:79–88 在调用工具 main 前启动 Binder 线程池，raw listener 回调无需为此创建 ActivityThread 或主 Looper。
- `TextView.java:5685–5701` 的 setShowSoftInputOnFocus(false) 只控制自动显示标志；`InputMethodManager.java:2572–2615` 的显式 showSoftInput 不读取该标志，检查 served view 后请求 Insets 显示。
- `ViewRootImpl.java:5256–5260` 在 focus callbacks 之后才调用 ImeFocusController.onPostWindowFocus；后者 `ImeFocusController.java:98–111` 进入 IMM.onPostWindowGainedFocus，IMM:906 起更新 mNextServedView/startInput。因此在 Activity.onWindowFocusChanged 同步 show 可能过早，并可能被初次 STATE_ALWAYS_HIDDEN 处理覆盖。已建议并看到 root 修正为一次 editor.post；Runnable 只要失焦/finish/destroy 即记录失败，不再次安排或重复 show。
- JNI nativeUnregister:155–160 忽略 removeWindowInfosListener 返回的 status_t；调用正常返回不能称 SF 已确认移除。报告 RETURNED 合理，独立进程退出及在途 callback 边界仍需保留。

## 实现复核要点

1. Main 仅链接公开 SDK 类型，通过反射在 metadata 准入后加载 RawListenerSession 子类；stubs 只能在编译 classpath，D8 只输入 owned class files。必须检查实际 DEX class_defs 不含 android 定义，不能 grep 全 DEX 字符串（合法引用也有 android 名称）。
2. RawCollector 的 256 handles/16 candidate rows/16 Binder IDs/128 callbacks 都是 Java 处理或保存上限；JNI 在进入 callback 前已转换原数组。限额超出不在前缀上宣布唯一。
3. callback 可以先于 register 返回和 initial 捕获；真实 Binder IDs 也可能先由 callback 分配。host 不应假定导出 initial→samples 就是捕获总顺序。callbacksSeen 包含 outsideWindow，校验 seq 应允许相应间隙。
4. snapshot complete 与 unique candidate 是两种判断。positive callback 仍需完整、无空 token、distinct Binder=1、候选行>0，并绑定 fixture 请求之后且 observationDeadline 之前。空 package、空 handle、字段读取失败均不能解释成 IME 不存在。
5. 收集线程只复制有界数据、不写 stdout、不执行 shell或等待，freeze 后在主线程序列化。Main 超出完整 marker 128 KiB时删尾并输出 outputTruncated/omitted、INCOMPLETE，不能保留成功状态。
6. 主机60秒与设备timeout40秒提供两层边界。取消或缺少远端退出证据时仍保持 remoteProcessExit 未知；kill本地adb并不证明远端进程同步退出。native同步调用不能靠Java期限中断。
7. Fixture三个有界receipt写，show返回true与实际insets可见分列；focus_lost/post_rejected 是合法失败phase，需要归档保留，而非丢作未知格式。截图只证明受控场景，不证明Binder、原raw帧或旧失败原因。

## 已发现并已发 owner 的修正

- Fixture同步show顺序：root已改为一次post，复核通过。
- 不存在cmd package check-permission：host已采纳实际register访问结果，不加盲目前置。
- Main admit仅操作开始前检查5秒；最后一个check跨期后仍可能启动constructor。已要求check结束后再判deadline、标明超期，停止后续阶段。已复核 owner 修正：每次成功查找后再次检查时限，跨期立即标记DEADLINE_EXCEEDED，不开始构造；class/字段槽改为仅加载其实际owner和必要依赖。
- Host合法失败phase与参数边界：已复核host纳入focus_lost/post_rejected，并对齐Main的包名/UID范围；保留初始与callback的独立顺序。

其余当时已落盘Main/Collector/Session未发现新的阻塞问题。最终schema、测试及设备结果由owner/root完成后另作验收，本文不把源码可行性当作实际注册成功。


最终短复核：Main/Collector/Session/Fixture关键执行路径与host候选、时序、计数核验未见新的阻塞问题。此结论为当前文件只读实现审查，未运行单元测试、Gradle或设备；owner/root的构建和实际镜像结果另列。
