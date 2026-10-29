# 修正后数值导入专项的 API32 原始失败

源码 `42db0baf3528ff33dd1beaa4da1cb32dc54c16f4`，原运行 [40902327883](https://github.com/ecojust/Agent-OS/actions/runs/40902327883)，attempt 1、`numeric-import-diagnostic`。API32 原作业 `123547680747`；只读提取运行 [40902802508](https://github.com/ecojust/Agent-OS/actions/runs/40902802508)，检查作业 `123549384793`。保留 [Checks 原始注释](annotations.json)及按原顺序连接的[有界诊断消息](failure.txt)。

唯一选择用例为 `NumericPreferencesInstrumentedTest#realSafImportPreviewsAndAppliesOnlyFourUnsavedNumericFields`。原 JUnit 报告记录一项 failure：`The real system DocumentsUI never became foreground`，发生于 `Session.begin:425`，原等待调用为第423行、`Session.await:938`。

新增原始分类为 `lastSafRoot={lastResult=NULL_ROOT,lastRole=UNKNOWN,observations=473,nullRoots=473,nonSystemApps=0,permissionDenied=0,packageNotFound=0,verifiedRoots=0}`。这表示原10秒等待中473次 `getRootInActiveWindow()` 都返回 null；未得到可进入系统应用身份和 `MANAGE_DOCUMENTS` 权限核验的根。因此本轮能区分空根与身份/权限拒绝，但不能据此证明画面没有 DocumentsUI，或确定为何该接口没有返回根。

失败仍在 Provider 根选择及祖先布局检查之前，原报告没有 `rootsLayout*`、`rootsRefreshFailures` 或 `lastRootsRefresh*` 字段。缺少这些字段不等于刷新失败计数为0；本案不能证明已进入祖先刷新重观测分支，也不是一次 `REFRESH_FALSE` 分支恢复失败。

八档专项为7通过、API32一项失败。此前 API32 只有泛化等待超时，本轮新增分类没有使其通过；专项也不替代同源码完整回归。恢复分支是否实际执行另据原逐案 `protected-saf-root-refresh-reobserved-*` 阶段统计，并保留所属整案结果。未重跑、伪造截图或放宽安全窗口保护。
