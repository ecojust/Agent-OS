# API37 ForTest 能力探针：独立合同建议

结论：可以在独立测试进程做固定公开 TestApi 成员查找，并至多调用一次 `WindowInfosListenerForTest()`。固定源码中该构造器只创建两个 `ArrayMap`，没有具体的监听注册或窗口读取副作用阻塞。构造调用成功只证明该构造器在本次测试进程返回，不能证明注册权限、回调、字段取值或 IME 映射可用。

本次源码审核没有运行 Android 设备、Gradle 或 CI，没有改产品或测试代码；产出本审核文件。并行独立复核了现有启动参数。机器可读精确清单见同目录 `source-capability-review.json`，22 份固定源码的摘要全部重新验证。

## 最小执行合同

1. 独立 API37 专用合成模拟器测试类、方法、报告和源 SHA，不并入原32项矩阵的判定。先验证显式专用设备标记、实际 SDK、源/运行身份。不要实例化会连接 UiAutomation 的 `SmokeHarness`；本任务无需连接无障碍、授予 shell 权限或运行窗口 dump。
2. 对下列3个固定二进制类名使用 `Class.forName(name, false, loader)`。这里只要求不主动初始化；后续唯一构造调用会触发通常的类初始化，不应把整个探针标成“完全没有类初始化”。
3. 用公开 `getConstructor()`、精确原始参数类型的 `getMethod()`、固定名称的 `getField()`。核对 declaringClass、修饰符、参数和返回/字段类型；不枚举私有成员，不使用 `getDeclared*`、`setAccessible`、`trySetAccessible` 或隐藏策略豁免。
4. 仅当外类及无参构造的公开元数据匹配时，允许 `Constructor.newInstance()` 一次。分开记录 lookup 状态、`invocationAttempts` 0/1、完成状态、异常类和有限原因类。调用前标记尝试；失败不重试。
5. 两个 add 和两个 remove 只查找，全部 `invocationAttempts=0`。14个字段不调用 `Field.get/set`；不构造 WindowInfo/DisplayInfo，不调用实例的 `toString()`。
6. 固定27个公开元数据检查槽（3类+1构造+4方法+14字段+5缺口名称查询），加独立构造调用结果。父类不可用时保留依赖项 `NOT_ATTEMPTED_DEPENDENCY`，不丢行、不缩小分母。具体设备信息只需 API/build/ABI 等，不读取窗口标题或内容。可用公开Context.checkSelfPermission记录当前应用的ACCESS_SURFACE_FLINGER检查值；它不是实际注册调用、权限委托或SF原生路径成功的证明。
7. 设备结果有界JSON；主机保留原始仪器输出、命令argv、run/source/attempt和文件hash。无最终结果、超时、进程崩溃或非终态仪器结果应是未验证运行，不能把先前部分成功行拼成完整通过。

## 精确固定 AOSP17 TestApi 表面

基线 frameworks/base `94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`。

类：

- `android.window.WindowInfosListenerForTest`：public，非abstract。
- `android.window.WindowInfosListenerForTest$WindowInfo`：public static。
- `android.window.WindowInfosListenerForTest$DisplayInfo`：public static。

外类公开无参构造：`WindowInfosListenerForTest()`。

外类公开实例方法，原始返回类型均为void，参数为单个Consumer或BiConsumer：

- `addWindowInfosListener(java.util.function.BiConsumer)`
- `addWindowInfosListener(java.util.function.Consumer)`（固定源码标为deprecated，仍存在）
- `removeWindowInfosListener(java.util.function.BiConsumer)`
- `removeWindowInfosListener(java.util.function.Consumer)`（deprecated）

`WindowInfo` 的12个 public final 实例字段：

| 名称 | 原始类型 |
| --- | --- |
| windowToken | android.os.IBinder |
| name | java.lang.String |
| displayId | int |
| bounds | android.graphics.Rect |
| touchableRegion | android.graphics.Region |
| transform | android.graphics.Matrix |
| isTrustedOverlay | boolean |
| isVisible | boolean |
| isTouchable | boolean |
| isFocusable | boolean |
| isDuplicateTouchToWallpaper | boolean |
| isWatchOutsideTouch | boolean |

`DisplayInfo` 两字段均 public final 实例字段：`displayId:int`、`transform:android.graphics.Matrix`。

两嵌套类型的构造器是包可见，不是本轮测试入口。外类方法泛型经过擦除；查找应使用 `Consumer.class`/`BiConsumer.class`，不以未经调用的泛型注解推断实际回调值。

来源：固定 `core/api/test-current.txt:4968–4993`；ForTest类/字段在54–175，构造197–200，add215及234，remove270及287。ForTest构造没有底层 nativeCreate；只有add内部237行新建 `WindowInfosListener`，后者自身构造40–45行才进入nativeCreate。因此不需要“为了清理”调用remove；本轮从未注册。

## 结果语义必须分开

- `LOOKUP_OK`：本进程可解析到与固定公开签名匹配的元数据。不是“已调用成功”“具有SF权限”或普通应用公开SDK承诺。
- 类/方法/字段 `NOT_FOUND_OR_FILTERED`：分别来自ClassNotFoundException、NoSuchMethodException、NoSuchFieldException。Android隐藏访问可能表现为不存在；本轮不足以区分实际镜像缺成员和访问过滤。
- `SIGNATURE_MISMATCH`：找到名称但declaringClass、类型、修饰符或参数不匹配；不悄悄接受相似重载。
- `ACCESS_REJECTED`：显式SecurityException或构造调用IllegalAccessException；不要仅由NoSuchMember猜测此因。
- 构造 `COMPLETED`：至多一次调用返回了期望类实例；不推及任何其他方法。
- 构造 `INVOCATION_FAILED`：InvocationTargetException，保留有限原因类；初始化/LinkageError独立记录。无需输出异常消息、任意对象或堆栈里的潜在内容。宿主超时和native崩溃单独保留运行失败，不保证都能转为Java结果。
- 能力负结果与测试基础设施失败分开。一次完整执行可以如实报告部分TestApi不可用，不能因此把缺口列为全部通过；本轮也不改变历史24/8矩阵。

## 现有启动flags复核

当前第三方API37任务经 `.github/workflows/third-party-smoke.yml:140–153` 调 `scripts/third_party_smoke_set.py`。后者314–321明确构造 `adb -s SERIAL shell am instrument -w -r` 加固定 `-e` 参数及runner，241行传原argv给subprocess，未追加hidden/TestApi选项。

固定 `Instrument.java:94–95` 默认 `disableHiddenApiChecks=false`、`disableTestApiChecks=true`；523–527分别设置两个flag。AMS15391–15398分别处理；`usesNonSdkApi`另可影响一般hidden访问。仓库manifest未声明它，所查执行脚本/源码没有设置hidden_api_policy、VMRuntime豁免或追加no-hidden/no-test选项。compileSdk与targetSdk均35。

因此应写“沿用现有am instrument默认TestApi检查豁免，未显式禁用一般hidden检查”。不要写“默认全部隐藏API开放”“普通APK公开API可用”或“已直接测量实际ART策略”。实际API37镜像与固定AOSP源码完全相同仍未证明。

## 本轮不能关闭的缺口

固定ForTest表面不提供完整inputConfig、CLONE/isClone、原始frame、layoutParamsType、ownerUid；bounds经过display transform和取整。12/14字段lookup全成功也不能用派生boolean重建这些字段。本轮额外固定对`WindowInfo`做5次公开`getField`查询：`inputConfig`、`isClone`、`frame`、`layoutParamsType`、`ownerUid`。它们在固定源码中未定义，不为缺失字段发明期望类型；如实际镜像查到，记录观察到的declaringClass/type/modifiers及源码未列状态，不自动升级为固定受支持TestApi。NoSuchField仍只说明未找到或被过滤。不查找原始私有`WindowInfosListener`/`InputWindowHandle`。

即使未来取得回调，IWindow token会成为测试进程的Binder引用，不能与system_server的`mClient.toString()`字符串作跨进程身份比较。ForTest又没有可靠的配置IME owner/type映射。名字、区域相似或列表顺序不能替代强关联；本轮完全不采集窗口对象，更无IME身份结论。

注册权限、初始缓存与自然回调、注销行为、完整筛选前数据、同进程强Binder身份映射都属于后续独立实验或明确待解条件，不由本轮构造成功自动通过。
