# 数值偏好文件导入导出

## 当前 SAF 失败定位：增加分类诊断

此前完整矩阵 [40818512531](../../screenshots/40818512531-1/manifest.json)为 2167/2168 通过，唯一失败是 API30 的普通导入用例。其[原始失败记录](diagnostics/rotation-full-40818512531-api30/README.md)发生在点击测试 Provider 根目录之前，`actionAttempted=false`；尚未进入文件读取或预览，不能据此判定产品导入逻辑有缺陷。

旧日志将祖先节点刷新失败、窗口或包变化、祖先不足以及布局标记缺失均归为 `UNKNOWN_ROOTS_LAYOUT`。其中 `refresh=1` 属于更早的点击准备阶段，不代表随后祖先遍历成功。历史完整运行中，导出和取消准备也曾在这一位置失败；同一组 SAF 测试源码亦曾完整通过。尚无证据证明旋转用例、前序测试清理、固定侧栏或无障碍缓存是根因。

本轮只给原祖先遍历增加固定诊断字段，不增加平台读取，不改变刷新、回收、点击、等待、重试或成功条件；生产代码和安全窗口保持原样。新增字段如下：

| 字段 | 含义 |
| --- | --- |
| `rootsLayoutDepth` / `rootsLayoutVisited` | 最后尝试的零起始层级，以及刷新和上下文检查均通过的节点数 |
| `rootsLayoutMask` | 原判定已观察到的标记：drawer=1、drawer_layout=2、rail=4 |
| `rootsLayoutExit` | `REFRESH_FALSE`、`CONTEXT_CHANGED`、`PARENT_NULL` 或 `DEPTH_LIMIT`；未执行时为 `NOT_SAMPLED` |
| `rootsLayoutResult` | 原判定结果 `UNKNOWN`、`DRAWER` 或 `RAIL` |

`PARENT_NULL` 只表示此次遍历未取得父节点，不证明获得了完整层级。下一步在同一源码上执行完整矩阵（271×8）及固定导入单案 `numeric-import-diagnostic`（1×8），对比前序测试的影响。API30 专项沿用完整矩阵已有的固定测试 APK `--force-queryable` 安装准备，不修改生产权限。专项归档单列分母，`fullSuiteAcceptance=false`，保护页面仍记录 blocked。单案通过或一次完整矩阵不复现均不能证明旧失败根因已修复。

本地测试 Java 编译已通过；专项选择、归档及 watchdog 的相关宿主检查 97/97 通过，另 98 项相关归档、视频和性能脚本检查通过。实际运行记录如下，不将源码审查或编译当作设备验证。

### 第一轮分类诊断：独立导入对照

精确源码 `e923dd2697bfde3d4d57e46f216d5933a7f0b878` 的专项 [40898458626](../../screenshots/40898458626-1/manifest.json)（attempt 1）实际执行固定导入用例八次，7 通过、1 失败，API30/31/33/34/35/36/37.0 通过，API32 失败。没有错误、跳过、缺失、额外或重复用例。32 个载荷共 26,480 字节，文件集合、长度和 SHA-256 均核验；15 条捕获记录均因安全窗口而不尝试截图，0 PNG，无新增安全标志例外。专项不执行性能用例，八档性能采集均为 invalid，仍不构成性能验收。

API32 的[原始失败](diagnostics/numeric-import-40898458626-api32/README.md)是 `begin()` 等待 `safRootAvailable()` 超时：在原 10 秒等待期间没有得到满足系统应用标志及 `MANAGE_DOCUMENTS` 检查的非空可访问根。此前代理和选择器启动请求数量、Intent 契约及代理安全窗口检查已通过。失败发生在根目录点击准备之前，没有执行 `rootsLayout`，因此没有新增祖先分类字段；现有报告不能区分根为空、包不可查询或身份条件不满足，也不能证明 DocumentsUI 在屏幕上从未显示。

这与旧 API30 的 `UNKNOWN_ROOTS_LAYOUT` 是不同失败点。独立测试无需前序旋转或其他数值偏好用例就能出现此次 API32 等待失败，但不能据此反推旧失败原因。API30 单案通过仅是本轮未复现，不代表旧失败修复。两项问题分开保留，继续与同源完整矩阵对照；不拼接不同运行的通过结果。

### 完整运行复现与有界重新观察修正

同源完整运行 [40898449432](../../screenshots/40898449432-1/manifest.json) 为 2163/2168 通过，API30 为 268/271、API31 为 269/271，其余六档各 271/271，构建通过。身份、JUnit 摘要及逐案结果一致，无错误、跳过、缺失、额外、重复或不可读报告；4472 个载荷共 47,247,751 字节、2272 张 PNG 的摘要与完整解码通过，1441 条 blocked 和 24 次既有安全截图例外保留。八份性能报告仍仅是诊断，预算验收为 false。[API30 原始失败](diagnostics/numeric-full-40898449432-api30/README.md)包含旋转、导出、导入三项，[API31 原始失败](diagnostics/numeric-full-40898449432-api31/README.md)包含取消准备与导出两项。四项 SAF 失败均为 `idleObserved=true`、`rootsLayoutDepth=0`、`rootsLayoutVisited=0`、`rootsLayoutMask=0`、`rootsLayoutExit=REFRESH_FALSE`、`actionAttempted=false`。

这四次的直接失败点已明确：事件空闲等待后，保留的目标节点自身第一次刷新失败，祖先遍历尚未开始；测试将该结果和未知布局合并，并立即失败。`Mask=0` 在这里不证明布局标记缺失。事件空闲不能保证先前节点仍可刷新。底层为什么返回 false 尚未确定，可能涉及节点、可见性、连接或查询条件；不能仅凭这些字段断言 Adapter 重建，也不能替旧运行补写未记录的分类。

本次修正仅针对 `REFRESH_FALSE`：关闭整份旧目标/动作快照，清空稳定观察记录，在原 5 秒剩余期限和原循环间隔内重新从当前系统根查找。必须再次满足唯一目标、两次稳定观察、空闲、识别布局、同窗及最终刷新等原点击条件。其他未知布局仍立即失败，单次点击返回 false 仍失败，不重放点击，不增加等待期限。失败日志保留累计次数及最后一次失效层级/标记；成功重新准备并完成一次根目录点击后，追加固定的 `protected-saf-root-refresh-reobserved-<次数>-depth-<层级>` blocked 元数据，不读取或保存保护页面。该记录只证明准备过程恢复，整案结果仍以其业务断言为准。

另为 API32 的选择器等待补充固定根分类和计数：空根、非系统应用、缺少文档权限、包不可查询、核验通过；仅复用原读取和短路，不增加查询或改变 10 秒等待。未知包名、页面文字和 URI 均不写入日志。两处修改已编译并独立审查，通过情况待新的设备运行确认。API30 旋转的任务已停止错误单独排查，不能把 SAF 修正当作旋转修复。

旋转失败另补终止状态诊断：在原窗口诊断之前读取既有任务状态、取消/代次、线程中断标记及与原 owner 匹配的末条动作回执，输出固定分类而不输出任务标识或内容。这些读取并非原子快照，也不是失败瞬间的值；不增加等待或平台读取，不更改原动作、断言或恢复。当前日志只能确认尚未派发时任务已停止，事后控制窗脱离不能证明是停止触发原因。新增诊断与 SAF 修正已一同通过 Java 编译及独立审查，待同源回归。

### 修正后独立导入：API32 的根始终为空

源码 `42db0baf3528ff33dd1beaa4da1cb32dc54c16f4` 的专项 [40902327883](../../screenshots/40902327883-1/manifest.json) 仍为 7/8 通过，API32 失败。32 个载荷共 26,480 字节，来源、文件集合、摘要及逐案/JUnit 结果均核验；0 PNG、15 条保护性 blocked，无额外或缺失用例。此次专项没有任何 `protected-saf-root-refresh-reobserved-*` 阶段，所以七个通过的单案尚不能证明新恢复分支执行过。

[API32 原始分类](diagnostics/numeric-import-40902327883-api32/README.md)为 473 次观测全部 `NULL_ROOT`，非系统应用、权限拒绝、包查询失败及已核验根计数均为 0。问题发生在布局及 Provider 根目录选择之前，与 `REFRESH_FALSE` 分开追踪。同源完整运行 `40902279945` 的 API32 作业通过；完整归档正在核验，不能将其与专项拼接成一次通过。

源码检查发现单案没有构造会启用生产无障碍服务的 `DeviceHarness`，自己的 UiAutomation 只请求节点 ID 和非重要节点；完整运行前面的普通截图则会设置且保留 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`。Android 12L 的[取根接口](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.1.0_r1/core/java/android/view/accessibility/AccessibilityInteractionClient.java#279)不直接检查这个标志，但[服务管理器](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.1.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java#2198)据此启动全局窗口跟踪。这是待实测的初始化差异候选，不是权限拒绝的证据。

下一步增加仅 API32 全部读根为空之后的失败诊断：依次读原状态、同 flags 重设、开启窗口检索、恢复原 flags、再次开启，每步最多一次根读取和固定 100 毫秒等待，2 秒限制新观察调用的启动，最终恢复原 flags。原测试仍失败，不继续导入或点击。仅输出 flags、空根标记、固定包角色候选和窗口编号，不读页面正文或截图。该对照已编译并独立审查，设备结果待采集。

这是有顺序、可能影响后续状态的观察：[每次重设还会清理客户端缓存](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.1.0_r1/core/java/android/app/UiAutomation.java#636)，关闭窗口跟踪时服务端[仍保留活动窗口编号](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.1.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityWindowManager.java#511)。因此同 flags 重设不是纯缓存对照，恢复原 flags 也不等于回到冷启动状态；2 秒并非同步平台调用或必要恢复操作的硬超时。普通流程录像保持 `42db0baf3` 的真实来源，不能将它改称此失败后诊断的录像。

本模块实现[设计规则 R5/R6](../DESIGN_RULES.md)中的普通数值偏好迁移。任务预算区域新增导入、导出入口，通过系统文件选择器访问用户选定的文档。导入先展示四项数值，确认后只填入当前草稿；只有原有的保存设置或开始任务操作才持久化并应用。配置读取失败、任务进行中或模型探测期间不开放新操作。

## 文件契约

```json
{"format":"agent-os-numeric-preferences","schemaVersion":1,"maxOutputTokens":2048,"maxModelRounds":30,"maxToolCalls":100,"maxActiveSeconds":600}
```

固定六个必需字段，最大 4096 字节。四项数值范围分别为 128–8192、1–300、1–1000、1–3600；活动时间只接受整秒，不改变内部 `TaskBudget` 的毫秒精度。严格 UTF-8 解码并拒绝 BOM；未知字段、重复字段（包括转义后的同名键）、非整数类型、负数、小数、指数、溢出和尾随文档均拒绝。错误不包含原文、文件 URI 或解析异常原因。

导出在点击时冻结四项有效数值，不构造含密钥的完整配置副本。文件没有模型地址、名称、密钥、资料、授权、执行模式、任务或历史，不是配置备份。不能通过导入恢复执行授权、自动保存、发起模型探测或启动任务。

## 所属关系与取消

每次操作使用一个非导出的安全窗口 Activity 代理系统选择器，且一个进程内随机标识只能认领一次。代理自行构造结果 Intent，仅返回标识；实际数值和完成状态取自本机内存记录，不转发文件提供方的额外参数。固定请求码只用于路由，连取消也需要核对所属关系，旧结果不能清除新操作。

结果可先于 `onResume` 到达，恢复前台后才读取文件或应用草稿。等待系统选择器期间允许正常暂停；读写或预览期间离页则取消结果使用权。Main 销毁、配置代次变化、模型探测或任务输入生命周期变化均撤销旧操作。重建和进程重启不恢复 URI、未确认数值或操作记录；已经明确填入的草稿沿原有同资料内存保留规则处理。

文件读写使用独立的单槽、无队列工作线程，不占配置存储线程。回调不强持 Activity；取消仅改变内存状态，不在主线程关闭或取消远程提供方。实际 I/O 的 `finally` 才释放槽位，即使用户取消也不允许另开线程堆积。读取最多 4097 字节以判定超限；写入完成包含流关闭。提供方阻塞时其他文件操作被拒绝，配置保存与任务操作仍可运行。取消或失败可能留下空文件或部分导出内容，不保证回滚或固定完成时限。

Main 和代理页面保持 `FLAG_SECURE`。不增加生产权限、不扩大应用查询范围、不持久化 URI 授权、不改系统文件选择器或第三方应用行为。

## 验证范围

核心格式已通过独立审查及实际 JVM 测试：原 314 项完整保留，新增 19 项，共 333 项，39 份 JUnit XML，零失败、错误或跳过。本次核心输入 81 个文件运行前后相同，测试总时间 16.400 秒；验证记录 SHA-256 为 `93b104a58e5abb7ae553028c9dc1930ea230dcefbc6131f998fa580a61733aa6`。纯 JVM 测试没有 UI 截图。

Android 测试已按冻结清单完成[第五轮当前合同验收](#第五轮完整通过与验收范围)：八档 2160/2160 通过，新增六项为 48/48。原 264 项全部保留，新增以下 6 项，即每档 270 项，原 8 项独立范围排除不变。前四轮失败及首轮缺失记录继续保留于下文；当前通过范围仅限所列用例，不扩展为全部界面或生命周期路径通过。

| 新增用例 | 验证边界 |
| --- | --- |
| `realSafImportPreviewsAndAppliesOnlyFourUnsavedNumericFields` | 实际 Main、代理、系统 SAF、测试 DocumentsProvider、预览及回填；显式保存前配置不变 |
| `realSafExportWritesTheCapturedFourFieldSnapshotWithoutConfigurationAuthority` | 实际 SAF 导出；只含冻结四值，不含连接、授权或任务 |
| `malformedOversizedAndCancelledImportsLeaveTheOriginalDraftAndSavedStateUntouched` | 畸形、超限和取消不改草稿或保存配置 |
| `oldPickerResultsCannotReachARecreatedMainOrClearANewerOwner` | Main 实际重建与旧结果回调；旧操作不能回填或消费新操作 |
| `recreatedBrokerDoesNotReopenThePickerOrAdoptAnOrphanedTransfer` | 代理实际重建后不自动重开选择器或采用旧结果 |
| `cancelledProviderIoKeepsItsProcessSlotUntilActualCloseWithoutBlockingMainWork` | 真实提供方阻塞、取消后槽位保持；Main 实际配置保存仍完成，未在此用例启动任务 |

前两项通过真实 Main、代理、系统文件选择器及测试 DocumentsProvider；异常、取消与阻塞准备也使用真实 SAF。旧结果用例额外直接调用旧 Activity 的回调，明确属于回调边界检查，不声称系统真实投递了这些伪造迟到结果。代理重建请求可能被正常取消后的 `finish` 抢先终止；只在实际产生替代实例时检查其恢复 Bundle，不强求平台必须新建页面。`Activity.recreate()` 不等于进程死亡。保护页面不清安全标志，截图记录采用 `secure_numeric_preferences_capture_not_attempted`，不生成受保护页面 PNG。以上是冻结测试路径，各平台实际结果分别统计。

测试 APK 使用独立进程的文档提供方及控制提供方。文档提供方保留系统 `MANAGE_DOCUMENTS` 权限和真实 URI grant 校验；控制入口只允许仪器目标 UID 操作固定合成文件模式和读取有界遥测。控制入口不会打开文档或授予 URI 权限，其获取本进程文档提供方时短暂采用测试 APK 身份，再恢复调用身份直接执行受限控制协议。这避免了“尚无 URI grant 时无法取得文档提供方”的初始化问题，不绕过实际 SAF 文件授权。生产 APK 没有这些测试组件。

生产实现独立审查记录 SHA-256 为 `aedd79df832ce7c29451cf5c070ffa66dbcd2bdd6861af8d5ec7a73d111460b9`；测试四文件最终冻结记录为 `3e1efe0f637661fed5539e42a9a5b79353a698e848fb4b10e4625cc1874305db`。仅源码检查不构成真实设备、文件提供方或界面流程验收。

这六项用例未专门注入部分导出、流关闭失败或编辑后迟到的预览确认；相关拒绝逻辑目前仅作源码审查。也没有用页面重建冒充实际进程死亡验证。

## 本地构建与成品核验

在隔离工作树实际执行 `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :fixture:assembleDebug`，1分12秒成功。266个源码与构建输入前后相同，清单 SHA-256 `e72ef04389b10c2a91c45c447661afe21766a5533592ff58dfe25970f08578f0`。核心333项沿前述独立实际执行记录，不重复运行；脚本未改，本地没有重跑旧314项脚本测试，也没有将纯构建冒充设备执行。

lint 为0错误、54警告，较前轮增加8项：真实 DocumentsUI 本地化资源查询产生1项 `DiscouragedApi`；测试控制提供方的客户端已用 `try-with-resources` 关闭，别名写法仍被报1项 `Recycle`；4条固定合成测试输入及预览数值拼接产生6项 `SetTextI18n`。未屏蔽警告。

| 本地构建产物 | 字节 | SHA-256 |
| --- | ---: | --- |
| 主调试 APK | 951,220 | `c1db40cd2497f6fe6cb9030e05159e66dcf98a701d6bf728f6624cc679073439` |
| 测试 APK | 6,014,205 | `60d0b20f413b52dfa4dc7ddd5ce4ce2048a460d4c7e6ee5709f826a6c89b334a` |
| Fixture APK | 41,237 | `75e61e5bf0add767a5353bbd09341e6c56214eab0be1a605281424a632e2db39` |

实际 APK 清单已核验：代理唯一且 `exported=false`、无 Intent filter；主 APK 无 Provider，权限仍只有原有 `INTERNET`/`ACCESS_NETWORK_STATE`，查询范围不变。两个新增 Provider 只在测试 APK 且共享独立测试进程，权限和文档授权分别按上文契约保留，原截图测试桥未变。成品清单审计 SHA-256 `73d220c0ca1f47dd36aeb82367924d17d918c199ebeef321c413200b43ad1202`；本地构建完整报告 `170e6b4122420ca9d6f010ac74cf7b374b064727c5021000d09efc5a21e3b6c9`。

新 APK 小于上轮本地包，原因是旧包含中央目录不引用的 ZIP 填充区，新包没有这些间隙；有效载荷实际增加，DEX 压缩方式未变。旧生产703个类与测试4164个类全部保留，新包分别为726/4229个类；22个app及33个core生产Java主类均核对实际编译输出与DEX。大小核验报告 SHA-256 为 `b7610c799a491ee67bce204bdce8751f3a1559d8456a35793921429d05d0424a`，不将文件变小解释成运行时性能优化。

本轮精确源码 `41e44b00069b0b58725f8e8de89afa5cb9bcf4f9` 的[唯一整体录像](../../screenshots/demos/40719665055-1/demo.mp4)已成功归档并核验：35.347秒、427,699字节，13阶段与8次确认通过。普通 Fixture 演示不打开受保护的配置或预览页面，不能替代上述模块验收，详情见[录像范围](operation-demo.md#数值偏好源码的整体演示)。八档首轮 [40719564182](https://github.com/ecojust/Agent-OS/actions/runs/40719564182) 已失败，原始证据与缺失清单按下文保留。工程检查点保持9/20（45%），模拟器成功不代表真机、真实模型、任意文件提供方或完整ROM验收。


## 首轮八档结果：保留失败与缺失

精确源码 `41e44b00069b0b58725f8e8de89afa5cb9bcf4f9` 的运行 `40719564182`（attempt 1）结论为失败。冻结分母为 270×8=2160；逐案账本记录2094项，其中2057通过、37失败，另66项缺失且全部来自API32。新增六项共26通过、22失败，没有额外或重复身份。

| API | 通过 | 失败 | 缺失 | 新增六项通过/失败 |
| --- | ---: | ---: | ---: | --- |
| 30 | 264 | 6 | 0 | 0/6 |
| 31 | 267 | 3 | 0 | 3/3 |
| 32 | 184 | 20 | 66 | 0/6 |
| 33 | 270 | 0 | 0 | 6/0 |
| 34 | 268 | 2 | 0 | 4/2 |
| 35 | 268 | 2 | 0 | 4/2 |
| 36 | 268 | 2 | 0 | 4/2 |
| 37.0 | 268 | 2 | 0 | 5/1 |

七档完成的JUnit汇总为1890项、17失败、零错误或跳过，其中16项为新增测试，另1项为API37旧 `ResolveNodeAtInstrumentedTest`。API32没有JUnit：仪器测试达到1200秒期限并退出124；最后进度为204/270、20失败，不能把其零份报告或归档字段 `unrecordedTests=0` 当作270项完成。66项缺少记录，最后正在执行的用例未知，不将这些身份擅自标为全部未执行。

[逐项证据](../../screenshots/40719564182-1/manifest.json)由归档运行 [40723904690](https://github.com/ecojust/Agent-OS/actions/runs/40723904690)发布，不可变提交为 `4da3e0c5cd25ce11181b8b00fc09585ff6ee102f`；归档父提交 `3c846ec8986137af28f4d57eaf2e58bad8de4c4d`仅比测试源码多文档和录像。4261个载荷共44,799,257字节，2135张真实PNG、1390条blocked；新增模块74条blocked、零PNG，生产安全窗口没有关闭。原24条空页面截图例外未扩大。八档性能诊断已收集，但仍为 `diagnosticOnly=true`、`budgetAcceptance=false`。

归档逐文件大小、摘要、JSON、PNG解码、用例身份和9个任务/34个产物来源已交叉核对。根清单SHA-256为 `736b567ad963ada78d0c20d9074ad8428ddedd8834471cd8c0628beb9a8eb550`；独立审计记录为 `cd4dfca658e9b8a9cc0fa83442384d3fe932ec7c3927093642a9837bc1570576`。完整运行日志ZIP的37个失败身份与账本一致；没有独立下载原JUnit XML，JUnit数字来自归档汇总及工作流原始打印。

[原始失败摘录与66项缺失身份](diagnostics/numeric-preferences-40719564182/README.md)保留可复核来源。API30六项均止于根目录选择准备，原断言只能证明没有观察到可见匹配节点，不能证明提供方没有返回根目录。API31等档出现节点刷新失效、没有可点击祖先或点击返回false；跨应用 `sendKeyDownUpSync(BACK)`还出现 `INJECT_EVENTS` 权限异常。API32首个已命名失败同为权限异常，随后19项在Main启动阶段超时，现有日志不足以确认因果。API37旧用例首次滚动遇 `STALE_OBSERVATION`，未提交动作，产生版本变化的原因仍未知。

独立的实际SAF导入、导出用例在API31/33/34/35/36/37共12/12通过；这不覆盖API30、API32或其他生命周期路径。目前仅准备修正测试节点观察和系统返回方式，保留六项业务断言、生产代码与权限，待新精确源码完整回归验证。


## 系统文件选择器测试驱动修复

首轮失败证据已先独立提交保留。本次只修改测试APK内的选择器驱动和文档提供方诊断，生产实现、核心、权限、清单和CI工作流均与41e44相同。原六个测试体的11,725字节完全一致，SHA-256 `a9a7c7463a4199e2817ba0dd30d26bfa6c3bb2ed94fd7a47756e07a32b72936c`；全套仍为270项/API，原8个独立范围排除不变。

驱动每次从当前真实系统SAF窗口重新读取有界节点树，要求完整观察和唯一目标；刷新并核对目标、可点击祖先、窗口、包身份与矩形。只有连续两次观察稳定且最终复核仍成立才尝试一次点击，返回false也不重放。重新观察只发生在点击提交前，沿用原5秒期限。根目录列表允许最多4次原生向前滚动；下一次滚动必须观察到同一窗口和列表内首末子节点、数量或矩形的实际变化，并重新稳定。缺失节点、空端点矩形、越出列表/保存容器的祖先不能满足准备条件。不注入坐标、不伪造URI授权或系统选择结果。

取消改为一次 `UiAutomation.performGlobalAction(GLOBAL_ACTION_BACK)`，前置仍核实实际系统选择器，后续仍核实原草稿、持久状态与所属关系。文档提供方新增8个固定遥测字段，区分根目录查询进入、返回及调用方检查结果；使用进程生命周期计数，保留DocumentsUI预先缓存查询。遥测不增加平台权限查询、不改原调用方判定、不输出包名、URI、任意文本或异常详情。计数饱和明确标记；查询返回仅表示cursor已返回，不证明目录已显示。失败时遥测输出有界，不承诺Binder调用具备硬超时。

最终驱动SHA-256 `0037404a581510b26f8344fced28fcbe51dddd3640af6c9d2fb84076a43603f3`；提供方SHA-256 `6dc9f741ad0a432b81ad883c08ca8e2bbbfe2b65e320d63382a3e1779c3d46b8`。独立审查冻结记录为 `c020a9586f45bae7b9ad6e61cf045959eba441345e465af95ed2c5c2ba48bbe5`，预声明270项清单记录为 `1fc8a6c3394a548730ab0919d550bd3a68e0ba6860fad8c4d3ae51509eef4144`。API30原失败的具体原因仍未确定；本次不修改API37旧滚动测试或放宽生产观察版本检查，实际效果待精确源码完整回归。

生产功能未变，整体录像继续引用原41e44的唯一实际录制，不改标为本次测试驱动源码。

修复后的本地实际执行仅为 `:app:assembleDebugAndroidTest :app:lintDebug`，58秒成功，54个任务中29个执行、25个来自缓存。266个构建输入前后相同且逐一匹配主分支集成文件，清单SHA-256 `9dd5f09f95c4ebfb00496094f60a145ee40f05b9d32181d2e150f85f29efce89`。lint为0错误、54警告，与首次构建类别及数量一致。实际测试APK为6,028,813字节，SHA-256 `e9293188c14c690086201880ce9c05041d0bb4ee6dbc4ddc74616f91c1621642`。没有重跑未改的核心333项或脚本测试，也没有将本地构建当作设备用例通过。


## 测试驱动修复后的第二轮结果

精确源码 `300c4c7a1e0bd634f5c6b474ed2c50b057734172` 的完整运行 [40728782554](https://github.com/ecojust/Agent-OS/actions/runs/40728782554)（attempt 1）仍失败。八档各270项均完成，共2143通过、17失败，零错误、跳过、缺失、额外或重复身份；八份JUnit摘要与逐案账本一致。原有264×8=2112项全部通过；新增六项为31通过、17失败。API32此次完整完成，没有再次出现首轮的20分钟超时，但不能据此断言首轮超时原因已查明。

| API | 通过/失败 | 新增六项通过/失败 |
| --- | --- | --- |
| 30 | 264/6 | 0/6 |
| 31 | 268/2 | 4/2 |
| 32 | 268/2 | 4/2 |
| 33 | 269/1 | 5/1 |
| 34 | 268/2 | 4/2 |
| 35 | 269/1 | 5/1 |
| 36 | 268/2 | 4/2 |
| 37.0 | 269/1 | 5/1 |

[原始证据](../../screenshots/40728782554-1/manifest.json)由归档 [40731740448](https://github.com/ecojust/Agent-OS/actions/runs/40731740448)发布；固定提交 `3de84a085223171bcab0a8dfa9035a851cd0950c` 的父提交就是被测源码300c。4424载荷、46,379,391字节、2232张真实PNG与1428条blocked已逐文件核对；新增模块79条blocked、零PNG，原24条空页面截图例外不变。根清单SHA-256 `85f4ec34e7be8401c7a650bad6907fce0544101de383b3eccd242743c3674cec`；独立载荷审计 `fad21bc4d0d332422e77a171255ddbb80f934cc881bcb5ddd4dedbffcd1e0f2d`，来源审计 `5330cdf4255011150653b34c45cea2da306d1c4f57a8409f97eaaae7d1884ee0`。9个任务、34个产物来源吻合；八档性能采集通过仍只属于诊断，未验收性能预算。没有独立下载原JUnit XML。

[本轮原始失败摘录](diagnostics/numeric-preferences-40728782554/README.md)分别保留八档：API30六项都在提供方根目录选择准备失败，失败时取到的进程级遥测为 `rootsQueryEntered=0`、`rootsQueryReturned=0`、`NOT_QUERIED`。这不能证明整个系统或跨进程缓存历史从未查询，也不能将失败归因于本次 `queryRoots` 内尚未执行的调用方检查。其余11项在一次系统Back被接受后等待Main的复合就绪条件超时；API31/32旧结果用例失败在首次Main重建之后，API33/34/36同用例失败在第二个选择器取消之后，不能统称为重建失败。日志尚不能区分取消是否送达、前台焦点或其他就绪字段。

AOSP源码显示，DocumentsUI会在打开根目录后异步关闭抽屉，Back可由仍开启的抽屉优先消费；输入文件名可见及全局Back被接受不证明选择器已取消。这构成需要加强和记录的测试准备条件，尚非本轮已实测证明的因果。后续修订保留单次Back、六项业务断言与生产代码，同时继续定位API30根目录发现路径。原两轮失败均保留，模块尚未完成跨平台验收。


## 第三轮前的取消准备与失败诊断

此次仅继续修改测试驱动并新增测试诊断辅助器。六个测试方法体仍与41e44/300c逐字节相同，设备清单仍为270项/API；生产实现、测试Provider权限、原排除用例与安全窗口均不变。API30目前增加诊断，不将其根目录问题标为已修复。

选择根目录后，沿用原文件列表的5秒准备期限：从同一个实际系统SAF窗口完整读取有界树，要求唯一、可见且启用的固定输入文档，以及连续两次一致的节点身份和几何。实际祖先同时含 `drawer_roots` 与 `drawer_layout` 才认作抽屉，`nav_rail_container_roots`认作常驻导航栏；单独 `container_roots`不足以判断，未知布局拒绝。准备完成要求可识别抽屉已不可见，不把常驻导航栏误当作抽屉。没有第二次Back、点击重放或延长Main的10秒等待。这是加强后的可观察测试条件，不等于读取平台私有动画完成位，也不证明此前失败就是抽屉动画造成。

Main的原就绪条件未变；在原等待期间的同一次主线程读取中，附带保存固定生命周期、焦点、ready/busy、存储与所属关系布尔值及代理阶段。失败保留原AssertionError的cause，追加最后准备状态；该状态不冒称失败瞬间的SAF窗口或焦点，不在失败后额外获取UI树。

失败诊断只读取固定测试Provider的已安装元数据与最后核验过的系统SAF身份，明确是仪器目标UID的PackageManager视角，不能替代DocumentsUI的实际发现结果。另用一次固定命令 `logcat -d -v brief -s ProvidersCache:V`，读取至多64 KiB的既有、已按tag过滤的日志；只对本固定authority的已知失败头及同PID连续块输出固定类别，不输出或保存原日志、URI、任意包名与异常原文。它是有限历史窗口，不是同次操作的原子证据；未命中不能排除发现或缓存问题，`COMPLETE`只表示读到EOF，不等于取得shell成功退出码。PackageManager、shell与FD读取不承诺本地硬超时；没有轮询、清日志、额外线程、`queryRoots`调用或权限变化。日志分类器另有12个本地合成向量核对，未增加设备测试分母。

最终测试文件SHA-256为 `a71e718ca1a4893054308cf81feda022414b243aef70ac1345c206d0c8609d99`，诊断辅助器为 `0bcce0ca6e788389646df1932bf49f336f514be7f859ea609ecd524b64ad674b`。本地仅实际执行 `:app:assembleDebugAndroidTest :app:lintDebug`，1分6秒成功（exit 0）；267个源码/构建清单项前后不变，清单SHA-256 `a4d5de513282deea1b181172a8f8c1b4c61cdc785b8958d16638bf41b514409a`。主分支集成逐一匹配其中266项，唯一差异是此前单独提交的CI步骤排序，Android源码与Gradle输入均匹配。CI现在先安装SDK再运行包括新增APK预检在内的脚本；不把旧分支工作流冒称本地实际验证对象。

测试APK为6,049,193字节，SHA-256 `2e233a24b02bd9d936085abaadd53923d2e107be083c6a8397113fa894a8df9b`；lint为0错误、54警告，无新增或移除。实际APK的runner、target与三个测试Provider权限/进程/grant声明保持原样。构建报告摘要 `a55f18d244645abc95d70489d93bbd87c11e8520014e075f87a3749910f419c6`。未重跑未改的核心333项、原314项脚本或已验证的APK预检22项；新精确源码的八档结果待实际运行。整体录像继续保留准确的518621d4来源，不改标为测试驱动源码。

最终独立源码审查记录SHA-256为 `1a89c11839aaac1fdb476cbf7533b228555e1a4c58b0f96ef8e2b5b7cd1cd56f`，270项预声明记录为 `0b9ef03a67f5fb718ba2d542a070b66a5b59b0265d63de373c5a9a5fde592bec`。这些冻结记录针对隔离测试驱动增量；主分支另含已分别提交的离线APK脚本及CI前置顺序，不将它们描述成相对300c未变。


## 第三轮结果与剩余失败

精确源码 `3eb74b5d5119738ba8e3c560ed8b078df223bba1` 的完整运行 [40736683857](https://github.com/ecojust/Agent-OS/actions/runs/40736683857)（attempt 1）完成2160项：2146通过、14失败，零错误、跳过、缺失、额外或重复身份。原有2112项全部通过；新增六项跨八档为34通过、14失败。API30为264/6，API31–36各269/1，API37为268/2（通过/失败）。八份JUnit摘要、逐案账本与原日志的失败身份吻合；没有独立下载原JUnit XML。

API30六项仍止于提供方根目录选择准备。新增诊断确认仪器目标UID可见的已安装Provider组件、authority、enabled/exported、MANAGE_DOCUMENTS读写权限、URI授权和独立进程声明符合预期，应用未stopped/suspended；但这不是DocumentsUI UID的实际发现结果。进程查询计数仍为零，有限历史ProvidersCache日志没有命中本authority或失败头，不能据此排除包可见性、缓存或平台发现问题。API31–37七个导出用例均在 `DIRECTORY_READY/DIRECTORY_NOT_READY` 失败，未进入文件保存点击；API37代理重建用例另在 `No recreated transfer window may remain owned` 断言失败。上一轮11个取消后Main复合就绪超时未再出现，但不将“本轮未出现”当作已证明旧失败因果。

[完整证据](../../screenshots/40736683857-1/manifest.json)由归档 [40740470120](https://github.com/ecojust/Agent-OS/actions/runs/40740470120)发布，固定提交 `2293745e500e4dd01870447d8530abda580b8001`，父提交 `41a53966c12848a2c140c06edf556d1170102fd2`。父提交另外含Android CI稀疏检出调整，不是纯文档变更，也不替换被测源码3eb。4424载荷、46,366,650字节、2232张PNG与1431条blocked已逐文件核对；新增模块82条blocked、零PNG，原24条安全窗口测试例外保持。八档性能采集仍只属于诊断，未验收性能预算。根清单SHA-256为 `cf88fb8fae546ac44804913b66ceaa4c7d5ecbc48bd742c980434099362e25e0`。独立载荷审计为 `d9d5b41be2a952761ebfc4878d1fe4827b6b26a187f9723cbf44291ed0ae09a3`，9个任务/34个产物来源审计为 `b244ffc56dfb8646ffdf1847d3b623c74282ac51a270fe9b9b9afff2ef9d36d8`。

[原始失败摘录](diagnostics/numeric-preferences-40736683857/README.md)保留各档连续原字节。完整日志ZIP为581,175字节，SHA-256 `b224c2a4e47fffa38a861e420197e1c53bf74b82d0640e7cf71c27371e07b1bd`。构建任务的原始脚本结果为 `Ran 336 tests in 62.467s / OK`，无跳过；SDK准备先于脚本执行，新增APK预检的真实SDK测试随整套实际执行。此运行尚未使用后提交的稀疏检出工作流，不能用它宣称远端稀疏检出已经验证。

三轮失败均保留。工程检查点仍为9/20（45%），数值偏好跨平台验收继续进行；普通整体录像仍准确引用518621d4，不用该视频替代受保护页面的专项测试。


## 导出准备与代理销毁等待修复

第三轮七个导出失败可以结合固定3eb源码进一步定位：`preparation()`已经用不同返回值排除了系统窗口、完整观察、未知布局和抽屉可见问题；原记录同时为一个输入节点和非空矩形，而该计数增加时必取得节点。因此当次 `DIRECTORY_NOT_READY` 剩余不满足的条件是 `inputEnabled=false`。这是源码和原诊断的逻辑推导，原日志没有直接打印该布尔位。[AOSP 12.1的创建文件选择逻辑](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/tags/android-12.1.0_r1/src/com/android/documentsui/picker/Config.java#66)明确禁用没有写权限标志的已有文件；本测试输入文件的flags为0。此源码解释与观察一致，不冒称已提取各档系统APK证明每个版本的具体调用链。

修复从本次已核验的真实SAF Intent区分OPEN和CREATE。导入继续要求输入文件可用；导出把该只读文件用于确认目录，保留同一系统窗口、完整唯一节点、非空几何、抽屉不可见和两次稳定观察，并额外要求保存容器内唯一的真实文件名编辑框可见、启用、可编辑且值为 `numeric-preferences.json`。保存点击仍执行原最终准备核对，之后仍读取实际导出字节、精确校验六个键和四项数值，没有伪造结果或点击重试。

代理调用 `finish()` 后，Main恢复及消费所属关系与代理 `onDestroy` 回调不是同一个观察条件。原等待Main就绪不能保证已满足后面的“全部代理销毁”断言。本次在同一原10秒等待内，额外观察本Session已登记的全部代理均收到销毁回调；不主动调用finish、追加等待或移除原断言，也不承诺未来不会再有尚未观察到的生命周期事件。失败记录增加活动代理数量，以及同次目录观察的固定字段，直接区分文件可用性、抽屉、完整性和保存编辑框状态。

六个业务测试方法体仍为11,725字节，SHA-256 `a9a7c7463a4199e2817ba0dd30d26bfa6c3bb2ed94fd7a47756e07a32b72936c`；270项/API、原排除范围、生产实现和安全窗口均未改变。测试文件冻结SHA-256为 `69230e57fb97fbeac6b7e99a6386981a69f8268a5fcb0e9e848335b65586f461`。本地构建已通过，设备效果仍需新精确源码实际验证。

API30另外完成了[精确系统镜像静态核验](diagnostics/numeric-preferences-api30-image/README.md)：Google官方镜像的实际指纹与失败设备10067904一致，实际DocumentsUI.apk没有声明 `QUERY_ALL_PACKAGES`，对应特权配置也无该项，框架定义其为normal权限。该APK SHA-256为 `c07ca46778a773973c2af56204bf211778cf9b42b2476bd144c403f1f1131aed`。这补齐了泛AOSP不同标签之间的证据差异；尚不是对原设备实时AppsFilter决定的观测。

固定失败诊断追加实际SAF包的查询全部包权限请求与授权结果，并单次执行固定 `dumpsys package dev.agentos.app.test`，最多读取64 KiB。仅当读取完整、唯一准确包区段及唯一严格匹配的forceQueryable字段均存在时，才报告声明值与安装override；缺失、重复、畸形或截断均为UNKNOWN。这两个字段不等于有效发现结果。原ProvidersCache分类不变，两个固定命令分别关闭FD；没有轮询、重试、额外线程或权限变化。EOF仍不代表shell退出成功，Binder及FD读取仍无本地硬时限。helper冻结SHA-256为 `68c69b285358f9ee9f5fa9cd3a234d41d95faf5f1af8a7074e5cb5082571944c`。


API30完整回归现在先构建测试APK，再仅对固定 `dev.agentos.app.test` APK执行一次 `adb install -r -t --force-queryable`，成功后透传原Gradle仪器测试参数。没有把该设置放进全局adbOptions，没有增加 `-g`，主APK安装方式、Provider权限和调用方校验不变。准备构建和安装均位于原1200秒执行边界内，安装另有60秒上限；任一步失败立即保留退出结果，不继续connected测试。其他API和所有独立诊断scope仍使用原命令。普通更新安装保留override由AOSP11的PackageSetting复制和仅true写入路径支持；AGP/UTP前置安装不卸载、后置保留APK参数未变。这是依据实际镜像和工具链修正测试环境，是否消除原失败仍以新运行验证，不称为已观测过的实时授权证明。

[本地实际验证](diagnostics/numeric-preferences-fix3-local/README.md)：两项指定Android任务58秒成功，225个Android/Gradle输入前后一致，清单SHA-256 `05340b9cb6fd6b64a39b0a6a6a79fa84c7210a7de9b4c4d1b190931462a044e9`；此清单明确排除非Gradle脚本/工作流，不能与先前267项直接比较。lint仍0错误、54警告且无新增/移除；实际测试APK为7,228,055字节，SHA-256 `a7286322813005f5e4ccab388ba2d30ff8e18892bace95fe78d38633089fff85`，实际编译清单与上轮相同。相关两个脚本模块83/83通过，新增六项覆盖固定安装范围、构建/安装失败及两阶段超时；另24个纯分类向量通过。整套脚本新分母为342，本地仅核清单，未冒称已重跑全部342项或核心333项。

五个修改代码文件的最终独立审查冻结为 `d2071eddb176699a1f6778ed9d2c667833855e961c8767e03da12231a6931282`，270项/API预声明清单为 `1b76ae50acf0716510b04cf6a8abc31fb0ffe4597452fe158a44a3f60098b24d`；实际远端结果尚待新运行。


## 第四轮结果与已观察的修复效果

精确源码 `eee60b1b273e458db92f3ed2391ee5a84d63e2fb` 的完整运行 [40745270296](https://github.com/ecojust/Agent-OS/actions/runs/40745270296)（attempt 1）完成2160项，2154通过、6失败，零错误、跳过、缺失、额外或重复身份。新增六项为43通过、5失败；原有2112项为2111通过、1失败，不能继续沿用上一轮“旧用例全部通过”的结论。

| API | 通过/失败 | 本轮失败 |
| --- | --- | --- |
| 30 | 267/3 | 三个用例在真实导入文件点击准备时找不到合适动作祖先 |
| 31 | 269/1 | 无效/取消导入用例进入取消选择器准备时，根目录单次点击返回false |
| 32、33、34、37.0 | 各270/0 | 无 |
| 35 | 269/1 | 原有VisualEventGeneration同框状态测试 |
| 36 | 269/1 | 无效/取消导入用例的无效文件循环中，根目录单次点击返回false |

导出和代理重建分别8/8通过。API30原始执行段记录测试APK安装成功，后续三个失败的运行时诊断均为 `testForceOverride=true`；SAF查询全部包的请求和授权均为false，但本测试Provider的根目录查询已实际进入并返回6、10、17次，调用方检查为SYSTEM_ALLOWED。三个用例均已进入 `IMPORT_DOCUMENT`：文件节点唯一、启用，目录观察完整且抽屉关闭，尚未尝试文件点击。它们不再属于之前的根目录未发现问题；未完成的导入路径仍计失败。

API31/36的失败发生在一次根目录动作返回false后，没有重放；两者测试体位置不同，不能合称同一次循环或同一取消问题。API35旧用例记录控制条文本事件窗口859与使generation从34增至35的窗口4内容描述事件不同，失效时 `eventMatchesVisual=false`。这支持非自有窗口事件按现有规则触发保守失效，不能据此认定控制条事件被误分类，也不知道窗口4的归属。后续guard断言未执行；[独立调查与未实施的测试合同方案](diagnostics/visual-generation-40745270296/README.md)保留这些限制，本轮没有修改生产失效规则或旧测试身份。

[完整证据](../../screenshots/40745270296-1/manifest.json)由归档 [40748128968](https://github.com/ecojust/Agent-OS/actions/runs/40748128968)发布，固定提交 `6bd8fcd130f830472fa56095500fd04411f58c0e` 的父提交恰为被测源码eee60。4421载荷、46,241,604字节、2229张真实PNG与1440条blocked已逐文件核对；新增模块91条blocked、零PNG，原24条安全窗口截图例外保持。八档性能采集仍为诊断，未验收预算。根清单SHA-256为 `7e9afaae5d551c04803dd50ab571dfa5ffc974d9bb999efbfb07c902f1f73a4a`，独立载荷审计为 `148a963a72d4f5486ec5c549ebafd65f72c1fa01146e21645c6a90dd9fe3cc8b`。没有独立下载原JUnit XML；八份归档摘要、逐案账本及原日志交叉一致。

[本轮原始材料](diagnostics/numeric-preferences-40745270296/README.md)同时保留八档报告、安装原段及九个任务的稀疏检出记录。原始脚本步骤实际报告 `Ran 342 tests in 58.431s / OK`，零跳过；本轮确实执行新稀疏检出工作流，九个任务均执行八目录的 `sparse-checkout set` 后检出eee60。整体设备运行仍失败，不把这些构建与脚本结果替代2160项验收。四轮失败保留，工程检查点保持9/20（45%）。

第四轮9个任务与34个产物来源的独立核验记录为 `02328e078523c4b2794a458600f247b029fa5d1287b355a08992eba1941233e0`，完整审计汇总为 `7c328adc80d6ab5fa7f04b5c412db1aabb897ef8688cc7c9c37343f42522ef60`。


## 文件点击能力与根目录动作准备

第四轮三个API30导入失败尚未尝试动作。AOSP11的[文档列表无障碍代理](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/tags/android-11.0.0_r1/src/com/android/documentsui/dirlist/AccessibilityEventRouter.java)直接发布并处理 `ACTION_CLICK`，不依赖View的 `isClickable`；这与原测试驱动只接受该布尔值的条件不一致。源码机制不等于已经观测到失败节点的actionList，实际效果仍需设备验证。

本次仅修改测试驱动：点击目标改为要求已刷新节点明确声明 `ACTION_CLICK`，导入目标的祖先范围限制在真实 `dir_list` 内，不能把整列表或外层窗口当作文件动作。两次稳定观察增加固定动作位、clickable及selected状态比较，最终重新核对同窗、可见、启用、节点、矩形及祖先。动作节点的refresh放在最后，随后只有本地快照和原期限检查，再执行一次真实点击。返回false仍失败，不用重放、补选中或补OPEN来掩盖结果；预览和真实I/O的原业务断言保留。

API31/36的两次根目录false尚未确定唯一平台原因。只对根目录点击，在原5秒准备期限的剩余时间内等待100毫秒无障碍事件安静，超时则在动作前失败；之后重新核实根布局和实际当前SAF窗口，再做最终节点复核。这是额外可观察的准备条件，不是读取ListView私有 `mDataChanged` 状态或证明布局已完成。当前窗口检查在等待之后，不沿用等待前的窗口判断。固定失败诊断追加能力位、动作祖先深度、矩形和是否观察到事件安静；原最多四次根列表滚动及实际进展要求保持。

测试文件冻结SHA-256为 `661eb3a19ef31206268b8306157eb327fa01ec59e94012dd5d24d7c73b6f9818`。六个11,725字节业务块仍为原 `a9a7c7463a4199e2817ba0dd30d26bfa6c3bb2ed94fd7a47756e07a32b72936c`，270项/API、生产实现、安装脚本及旧视觉测试均未改变。独立审查记录为 `ba6aca435d11417bc3abe1c6cf888f3f9faaa9b0f6d93cc90aa39e5d0ec7e34f`，预声明清单为 `b5d00856607d4bb4a9f5179edf6914ca2616b02de94014d58e8156d67ccbfd18`。

[本地最小验证](diagnostics/numeric-preferences-fix4-local/README.md)仅执行测试APK构建和lint，59秒成功。225个Android/Gradle输入前后相同，并逐一匹配主分支集成文件，清单SHA-256 `c8257955f5e113a4cee1e0b1b4293ee0a1d920f15ba95b445c53a28642d50d05`；lint仍0错误、54警告，无新增或移除。测试APK为6,054,985字节，SHA-256 `f1c8cc793b3740c292365eaceb1e79058b58b5ecfe3993929aa337ed39c4a140`，实际编译清单与上一轮相同。没有重跑未改的脚本342项、核心333项或本地设备测试，远端效果待新精确源码回归。


## 第五轮完整通过与验收范围

精确源码 `24b9b44c70eff0e5afd66e58e87031160a6609ec` 的完整运行 [40750181658](https://github.com/ecojust/Agent-OS/actions/runs/40750181658)（attempt 1）通过：API30、31、32、33、34、35、36、37.0各270项，共2160/2160，零失败、错误、跳过、不可读报告、缺失、额外或重复身份。新增六项实际SAF/生命周期用例为48/48，原有2112项也全部通过。原始脚本步骤实际执行342项，68.718秒、`OK`且零跳过；九个任务全部成功。

本模块的当前合同完成这八档合成模拟器验收：真实系统选择器导入和导出、预览后只填四项未保存草稿、独立显式保存、格式/大小拒绝与取消、Main与代理重建、旧回调所属关系隔离，以及阻塞Provider取消后保留实际I/O槽直到关闭。测试没有跳过失败动作、伪造URI结果或关闭生产安全窗口。固定六个业务块和270项/API分母仍与原预声明一致。真机、任意第三方Provider、进程死亡、部分导出写入失败及上文未覆盖场景仍不在这六项验收内。

[真实证据](../../screenshots/40750181658-1/manifest.json)由归档 [40752795110](https://github.com/ecojust/Agent-OS/actions/runs/40752795110)发布，固定提交 `d0d1170765498c79a28e9cc2572faec3145ba396` 的父提交恰为被测源码24b9。4423个载荷共46,071,796字节、2231张真实PNG和1445条blocked已逐文件核验；本模块96条均为固定保护原因、零PNG，原24条安全窗口截图例外未扩大。八档性能采集仍是诊断，未验收性能预算。

根清单SHA-256为 `4aec4f370d704f7f49036f2f290e6bc6fa44dedf8208f18691660657779c75aa`，独立载荷审计为 `ca5df0073fda22a03f2e7e02cd18fe86549be475274299690ad95733bfb370c8`，9个任务/34个产物来源审计为 `91c46b74b898b480e0492e63f14a5e44404236fb91b9599a703ad8d193ad04ba`，最终审计汇总为 `d8a090c6d87645cf57ce532ec258c7424c0a5e4307268ee58552e33ade913bcf`。两路原日志、逐案账本和归档JUnit摘要交叉一致；没有独立下载原JUnit XML。[原始成功记录](diagnostics/numeric-preferences-40750181658/README.md)保留各档零失败打印、开始/完成行身份、完整脚本步骤及API30安装原段。完整ZIP为562,601字节，SHA-256 `0e6b87edf75bd543d774dd9dc0f3ad15a79ae0b1b8d22ddd41e4b60506f266ca`。

前四轮失败和首轮66项缺失均继续保留，不改标为通过。API35旧视觉用例本轮通过，但生产规则和测试合同未改，不能用本轮未再出现断言此前外部事件的根因已经修复。工程检查点继续按原20项条件计数，保持9/20（45%）；这次模块验收不等于真实模型、实体手机、广泛应用、性能预算或ROM验收。整体录像仍准确保留518621d4来源；后续变动均为测试驱动及执行环境，不用普通录像冒充受保护页面的验证。
