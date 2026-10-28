# 数值偏好文件导入导出

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

Android 测试已编写并冻结，等待实际执行。原 264 项全部保留，新增以下 6 项，即每档 270 项、八档 2160 项，原 8 项独立范围排除不变。未执行前不将界面或生命周期路径标为通过。

| 新增用例 | 验证边界 |
| --- | --- |
| `realSafImportPreviewsAndAppliesOnlyFourUnsavedNumericFields` | 实际 Main、代理、系统 SAF、测试 DocumentsProvider、预览及回填；显式保存前配置不变 |
| `realSafExportWritesTheCapturedFourFieldSnapshotWithoutConfigurationAuthority` | 实际 SAF 导出；只含冻结四值，不含连接、授权或任务 |
| `malformedOversizedAndCancelledImportsLeaveTheOriginalDraftAndSavedStateUntouched` | 畸形、超限和取消不改草稿或保存配置 |
| `oldPickerResultsCannotReachARecreatedMainOrClearANewerOwner` | Main 实际重建与旧结果回调；旧操作不能回填或消费新操作 |
| `recreatedBrokerDoesNotReopenThePickerOrAdoptAnOrphanedTransfer` | 代理实际重建后不自动重开选择器或采用旧结果 |
| `cancelledProviderIoKeepsItsProcessSlotUntilActualCloseWithoutBlockingMainWork` | 真实提供方阻塞、取消后槽位保持；Main 实际配置保存仍完成，未在此用例启动任务 |

前两项通过真实 Main、代理、系统文件选择器及测试 DocumentsProvider；异常、取消与阻塞准备也使用真实 SAF。旧结果用例额外直接调用旧 Activity 的回调，明确属于回调边界检查，不声称系统真实投递了这些伪造迟到结果。代理重建请求可能被正常取消后的 `finish` 抢先终止；只在实际产生替代实例时检查其恢复 Bundle，不强求平台必须新建页面。`Activity.recreate()` 不等于进程死亡。保护页面不清安全标志，截图记录采用 `secure_numeric_preferences_capture_not_attempted`，不生成受保护页面 PNG。以上是冻结测试路径，尚非设备通过结果。

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

本轮精确源码的整体演示尚待录制。普通 Fixture 演示不会打开受保护的配置或预览页面，不能替代上述模块验收；继续保留 README 中旧视频的原始源码标识。工程检查点保持 9/20（45%），模拟器成功不代表真机、真实模型、任意文件提供方或完整 ROM 验收。
