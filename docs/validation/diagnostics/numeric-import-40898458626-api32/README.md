# 数值导入专项的 API32 原始失败

源码 `e923dd2697bfde3d4d57e46f216d5933a7f0b878`，原运行 [40898458626](https://github.com/ecojust/Agent-OS/actions/runs/40898458626)，attempt 1、`numeric-import-diagnostic`。API32 原作业 `123533925034`；只读提取运行 [40899007157](https://github.com/ecojust/Agent-OS/actions/runs/40899007157)，检查作业 `123535858399`。保留 [Checks 原始注释](annotations.json)及按原顺序连接的[诊断消息](failure.txt)。

唯一选择用例为 `NumericPreferencesInstrumentedTest#realSafImportPreviewsAndAppliesOnlyFourUnsavedNumericFields`。原 JUnit 报告记录一项 failure：`The real system DocumentsUI never became foreground`，调用链为 `Session.await:907` → `Session.begin:421` → 用例第74行。

准确源码的 `begin` 在原10秒期限内等待 `safRootAvailable()`。该条件要求当前活动窗口根非空，且根包通过系统/更新系统应用标志与 `MANAGE_DOCUMENTS` 权限校验。原失败没有记录哪个条件不满足，因此只能确认未取得已核验的系统 SAF 根，不能据错误文案证明画面从未显示文件选择器，也不能归因为缓存、包可见性、权限或 Provider 故障。

失败发生在 Provider 根选择及祖先布局检查之前，原报告**没有 `rootsLayoutDepth/Visited/Mask/Exit/Result` 字段**。它不是一次已采样的 `UNKNOWN_ROOTS_LAYOUT`，不能用于解释或关闭此前完整回归中的该类失败。专项固定八项为7通过、API32一项失败；它不替代同源码完整回归，也不把缺少保护页面截图视作跳过测试。未重跑或放宽原期限、布局许可及安全窗口保护。
