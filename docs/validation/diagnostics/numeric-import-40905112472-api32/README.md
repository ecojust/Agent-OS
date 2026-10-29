# API32 空根失败后的有序 flags 诊断

源码 `dc1b0e06f19156aec463ff672ca174327df4f448`，原运行 [40905112472](https://github.com/ecojust/Agent-OS/actions/runs/40905112472)，attempt 1、`numeric-import-diagnostic`。API32 原作业 `123557642307`；只读提取运行 [40905708510](https://github.com/ecojust/Agent-OS/actions/runs/40905708510)，检查作业 `123559770778`。保留 [Checks 原始注释](annotations.json)及按分片编号连接的[有界原始诊断](failure.txt)，字段与原作业日志一致。

固定用例 `NumericPreferencesInstrumentedTest#realSafImportPreviewsAndAppliesOnlyFourUnsavedNumericFields` 仍失败于 `begin()`：原10秒等待中466次 `getRootInActiveWindow()` 全部为 null。`nonSystemApps/permissionDenied/packageNotFound/verifiedRoots` 均为0；未得到可进入身份和权限核验的根。这不证明实际屏幕从未显示 DocumentsUI。

仅在原失败后执行以下五步，每步只读一次根；`rootNull` 沿用原字段含义，时间从探针开始累计。原 flags 为65554，开启窗口检索位后为65618。

| 阶段 | requested / actual flags | rootNull | 包名候选 / windowId | elapsedMs |
| --- | --- | --- | --- | --- |
| BASELINE | 65554 / 65554 | TRUE | UNKNOWN / -1 | 101 |
| SAME_FLAGS_RESET | 65554 / 65554 | TRUE | UNKNOWN / -1 | 203 |
| RETRIEVE_ON | 65618 / 65618 | FALSE | AOSP_DOCUMENTS_UI_CANDIDATE / 12 | 305 |
| ORIGINAL_FLAGS（OFF） | 65554 / 65554 | FALSE | AOSP_DOCUMENTS_UI_CANDIDATE / 12 | 409 |
| RETRIEVE_ON_AGAIN | 65618 / 65618 | FALSE | AOSP_DOCUMENTS_UI_CANDIDATE / 12 | 511 |

五步 `error=NONE`；`stop=COMPLETED`、`initializationError=NONE`、`restore=RESTORED`，共511ms。恢复标记只证明最终服务 flags 读回原值；恢复后没有再次读取根。`role` 只按包名分类候选，不重新核验系统应用标志、`MANAGE_DOCUMENTS` 或页面内容。

这是同一 UiAutomation 会话的有状态顺序探针，**不是独立的 ABA 因果对照**。同 flags 重设也更新服务端状态，100ms间隔不证明平台稳定；首次ON后，OFF阶段仍返回同一候选窗口12，但未证明活动窗口、缓存及事件历史回到探针开始前的状态。结果记录了本次顺序中的非空根出现，不能证明唯一根因、排除经过时间等影响，或把OFF后仍有根解释为检索位无影响。

探针不点击、不继续失败的文件选择流程，不调用 `getWindows()`，也不清除 `FLAG_SECURE`。2秒预算只限制开始新的诊断调用，不是平台在途调用及强制恢复的硬时限。原断言仍抛出，八档专项为7通过、API32一项失败；不能把诊断取得候选根改记为导入/预览/保存成功。原报告没有 `rootsRefreshFailures` 或祖先布局字段；祖先刷新恢复分支另按原逐案阶段统计，不能从本探针推定。
