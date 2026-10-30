# API 矩阵的重复验证与未关闭原因

截至 2026-10-30 UTC，导航拒绝顺序已有生产修正及同案八档前后对照：**0/8 → 8/8**；修正源的完整回归为 **2184/2184**。此前固定的两轮完整回归均为 **2166/2168**，API30 旋转均失败，API34 分别失败于视觉事件和导航来源中断，这些原失败全部保留。导航的具体缺陷已验证修正；旋转和视觉问题本轮未复现，尚不能据此认定根因已修复，API 矩阵检查点继续进行中。

## 修正源的完整回归

源码 `b3e2cacfcc48c5819b0c25f9d8900b6d723c9f44`，完整运行 [40978861976](https://github.com/ecojust/Agent-OS/actions/runs/40978861976)，attempt 1，API30、31、32、33、34、35、36、37.0 **各273/273，共2184/2184**。缺失、额外、重复、failed、skipped、JUnit errors、不可读报告均为 0。清单按精确源码冻结：原270项加旋转及两个新增导航用例，没有删除原有身份。

| 独立核验子集 | 本次完整运行结果 |
| --- | --- |
| 原270项/API | 2160/2160 |
| 原Settings来源暂停、控制窗重建后暂停、新授权观察替换旧来源 | 各8/8，合计24/24 |
| 真实旋转 | 8/8，本轮未复现历史失败 |
| 六个SAF用例/API | 48/48 |

[唯一归档40980594033](https://github.com/ecojust/Agent-OS/actions/runs/40980594033)发布证据提交 `4f1a8794f9812285e3e0f3f26a9e754c8315d9b2`。[原始清单](../../screenshots/40978861976-1/manifest.json)包含 **4504 payload、48,098,452 B、2288张PNG、1510条blocked**；PNG为2260张320×640、24张640×320、4张831×1662。全部实际文件集合、字节数、SHA-256、PNG解码/引用、逐案JSON、采集JUnit摘要及精确源码身份经交叉核验，16份截图/性能artifact的来源与上传时段也已核对。未独立下载全部原JUnit XML，不将摘要核验称为重解析原XML。原24次测试窗口截图例外保持不变；八份性能报告仅作诊断，`budgetAcceptance=false`。

SAF48案均有一次初始化标记，全部prior1、无prior0；只有 **API30 的 `realSafExportWritesTheCapturedFourFieldSnapshotWithoutConfigurationAuthority`** 实际记录 `protected-saf-root-refresh-reobserved-1-depth-0`，随后整案与断言通过。SAF保护记录为 **145＝96个原阶段/结束记录＋48个初始化＋1个恢复**。此恢复仍不证明底层节点失效的单一平台原因。

原CI build日志确认503项脚本检查实际执行通过（91.574秒）；Gradle主体53秒成功，`:core:test`实际执行，应用JVM任务为NO-SOURCE。日志未单列核心逐案总数，本页的339/339来自下文同源本地核心验证，未挪作CI逐案统计。同源[唯一普通录像](../../screenshots/demos/40978865253-1/manifest.json)也通过13阶段与8次确认，但不执行Settings重建或旋转专项。

三个导航用例在此完整运行全部通过，重建负例另有独立专项8/8。旋转虽为8/8，本轮没有失败诊断，新增失配细类尚未取得设备失败样本；视觉用例也未复现，旧全局代次断言的归因限制仍在。主界面旋转草稿、多窗口/PiP、进程终止、执行中外部升级仍缺具体验收，不能将本次全绿当作这些边界已覆盖。

## 预先固定的两轮完整样本

两轮测试源码均为 `8e8e00518b11f55cca4c7c24dd74c49a0e35cd21`，attempt 1，执行 API30、31、32、33、34、35、36、37.0。相对上一完整通过源 `3f2bf1da0556ee532c8d793073a0eeac313468ae`，产品、测试、工作流、脚本和构建配置均未变化，只有文档、进度与证据更新。已按源提交的 git 对象核对原 **271 项/API、2168 项总分母**，后续工作树增加测试不会改写这两轮身份集。

| 轮次 | 原运行与完整证据 | 实际结果 | 唯一归档运行 | payload 文件与字节数 |
| --- | --- | --- | --- | --- |
| 1 | [40975741234](https://github.com/ecojust/Agent-OS/actions/runs/40975741234) · [manifest](../../screenshots/40975741234-1/manifest.json) | 2166 通过、2 失败 | [40977533783](https://github.com/ecojust/Agent-OS/actions/runs/40977533783) | 4471 份，47,351,670 B |
| 2 | [40975743980](https://github.com/ecojust/Agent-OS/actions/runs/40975743980) · [manifest](../../screenshots/40975743980-1/manifest.json) | 2166 通过、2 失败 | [40977457980](https://github.com/ecojust/Agent-OS/actions/runs/40977457980) | 4471 份，47,357,329 B |

| API | 第一轮 | 第二轮 |
| --- | --- | --- |
| 30 | 270/271，旋转失败 | 270/271，旋转失败 |
| 31 | 271/271 | 271/271 |
| 32 | 271/271 | 271/271 |
| 33 | 271/271 | 271/271 |
| 34 | 270/271，界面事件失败 | 270/271，导航来源中断失败 |
| 35 | 271/271 | 271/271 |
| 36 | 271/271 | 271/271 |
| 37.0 | 271/271 | 271/271 |

两轮均为缺失、额外、重复、skipped、JUnit errors、不可读报告各 0；逐案 JSON 与设备汇总一致，原采集 JUnit 摘要与归档一致。所有 payload 的大小、SHA-256、实际文件集合及 PNG 引用/解码通过，另核对每轮 16 份截图/性能 artifact 的来源、上传时段及采集绑定。完整性有效，两轮完整矩阵仍失败。JUnit 核验范围为原采集摘要与逐案 JSON，未独立下载全部原 XML。

每轮均有 2271 张 PNG：2244 张 320×640、23 张 640×320、4 张 831×1662；第一轮有 1495 条 blocked，第二轮有 1492 条 blocked。每轮 24 次既有测试窗口截图例外均与基线一致，无新增例外。每轮八份性能报告只通过诊断一致性检查，仍为 `diagnosticOnly=true`、`budgetAcceptance=false`。两轮分别保留全部失败，不用通过项互相补齐，也不追加替代样本。

## 本轮失败已确认到什么程度

两轮 API30 都失败于 `AgentControllerInstrumentedTest#realRotationRejectsOldApprovalAndKeepsControlsReachable`。阶段均为 `old_observation_rejected`，派发 0、模型请求 1；旧 `TYPE_TEXT` 返回 `STALE_OBSERVATION`，submitted/accepted 均为 NO，任务随后已结束。两份 trace 都记录六个已结束的 `SNAPSHOT_OBSERVE` 事件（不代表成功观察）、六次 `OWNED_WINDOW_MISMATCH`、一次 `LOCAL_CONTROL_STALE`、零次 `OBSERVATION_REVISION_MISMATCH`，被覆盖事件为 0。首个 local stale 可以来自预期的旧写拒绝；后续六次窗口失配仍需定位。原文见[第一轮 API30](diagnostics/stability-full-40975741234-api30/README.md)和[第二轮 API30](diagnostics/stability-full-40975743980-api30/README.md)。

第一轮 API34 失败于 `VisualEventGenerationInstrumentedTest#actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications`。`round=2` 时匹配的自有事件 windowId 为 866，但另外两条 windowId 3 的事件使 generation 从 33→34→35；content 分别为 2、4，均 `eventMatchesVisual=false`。诊断无丢条、采样未失败。这说明原断言被其他窗口事件打断，尚不能确定这些事件的具体平台来源，也不能据此允许忽略外部事件。见[第一轮 API34 原文](diagnostics/stability-full-40975741234-api34/README.md)。

按该源代码复核，测试的事件过滤器只等待指定自有事件，不会阻止服务收到其他窗口事件；随后等待 UI 空闲，再要求整个三轮期间的全局 generation 保持不变。这个断言同时依赖“期间没有外部失效事件”，强于仅验证自有文字事件不失效。上述两次实际增量均来自非自有窗口，符合当前保守撤销规则；没有证据表明匹配的自有事件造成了增量。原案第2轮先在 generation 断言失败，该轮后面的原 guard 存活断言未执行，不能补记其结果。下一步需在单次真实事件处理内记录固定元数据及前后 generation，分别验证自有事件不增代、外部交错撤销原 guard，并取得非空的存活正例；不能忽略外部事件、重建 guard 或以重跑替换失败。该测试归因整改尚未实现，不算视觉问题已修复。

第二轮 API34 失败于 `NavigationObservationGuardInstrumentedTest#anUnscopedSettingsWindowCannotBeReplacedByAnAutomaticLaunch`。本次已取得主消息诊断：`outcome=RETURNED`、`return.code=STALE_OBSERVATION`；ok/submitted/dispatchSubmitted/dispatchAccepted 均为 FALSE，accepted 为 MISSING。epoch 为 1→1，paused/cancelled/inFlight 均为 false，派发、审批和 HTTP 请求均为 0。调用前与清理前采样均为 Settings，窗口及根 ID 均为 506。trace 为 finalEvents 1、finalCompleted 0，其他四类计数为 0、无覆盖；这不是成功终检，也尚未唯一确定 stale 的具体条件。原合同要求 `TASK_PAUSED`，普通未提交返回仍计失败。见[第二轮 API34 原文](diagnostics/stability-full-40975743980-api34/README.md)；该新证据不补造旧 API37 未记录的返回值。

| 原有子集 | 第一轮 | 第二轮 |
| --- | --- | --- |
| 原 270 项/API 子集 | 2159/2160 | 2159/2160 |
| 真实旋转 | 7/8 | 7/8 |
| 原导航来源中断用例 | 8/8 | 7/8 |
| 六个 SAF 用例/API | 48/48 | 48/48 |

## SAF 初始化与恢复的实际分支

两轮各 48 个 SAF 用例均通过，各有 48 条 `protected-saf-interactive-windows-requested-prior-1`，prior0 均为 0。该 prior 是当前 Session 请求前读取的窗口跟踪位，不能证明冷启动、默认配置或平台缓存正确。

第一轮恰有两条 `protected-saf-root-refresh-reobserved-1-depth-0`：分别来自 **API32、API33** 的 `malformedOversizedAndCancelledImportsLeaveTheOriginalDraftAndSavedStateUntouched`。各有一次 depth0 刷新失败后丢弃旧准备、重新观察，再由真实 root 点击返回接受，整案和断言均通过。第二轮该恢复 marker 为 **0**，不能宣称它也触发了恢复分支。第一轮有 **146** 条 SAF 保护 blocked（96 条原阶段/结束记录＋48 条初始化＋2 条恢复），第二轮有 **144** 条（96＋48，无恢复 marker）。

已有 SAF 修正针对测试初始化和明确的 `REFRESH_FALSE` 准备失效，恢复仍在原 5 秒窗口内重新完成全部准备，不重放点击。底层节点失效原因未确定；本次 SAF48/48 也不关闭旋转、导航或其他矩阵边界。

## 导航拒绝顺序的独立失败前对照

测试源 `74090a97192e9b6dcf90c94f8457c5cc66fc86e6` 的固定专项 [40976664678](https://github.com/ecojust/Agent-OS/actions/runs/40976664678) 已完成。该源 `app/src/main` 与 `core/src/main` 均与 `8e8e00518` 相同；增加的独立用例为 `NavigationObservationGuardInstrumentedTest#aKnownUnscopedSettingsOriginStillPausesAfterControlWindowRecreation`，scope 为 `navigation-origin-rebuild-diagnostic`，每 API 一案。

结果为 **合同通过 0/8、原始失败 8/8、未验证 0；合格目标反例 8/8**。八案均先成功观察真实未授权 Settings，再真实移除并重建控制窗，核验旧身份撤销、新身份/lease 有效、同一 owner/profile/configuration/epoch 及 Settings 焦点；`recreationVerified=true` 后只调用一次 `launch_app`，没有重新观察。八案均普通返回 `STALE_OBSERVATION`，未暂停，epoch1→1，零审批、零派发、零 HTTP 请求；调用区间各有一次 localStale，无覆盖。这些是完成前置后的目标失败，不能以“复现成功”将测试改记为通过。

唯一归档 [40977233884](https://github.com/ecojust/Agent-OS/actions/runs/40977233884) 已成功；[设备证据](../../screenshots/40976664678-1/manifest.json)为 **32 payload、25,212 B、0 PNG、8 blocked、0 secure-window 临时例外**。八份性能附件为未执行该性能用例对应的 invalid，不构成性能验收。八档原文、派生字段、原作业和提取来源见[完整前对照诊断](diagnostics/navigation-rebuild-40976664678/README.md)；该诊断目录自己的 32 份材料、182,114 B 不计入设备 payload。

前对照确认了“已知未授权来源遇到控制窗重建时未按合同暂停”的具体缺陷。它不证明所有历史导航失败或 API30 旋转失败都来自这一路径。该源完整清单为 **272 项/API**，本次只执行八案专项，没有产生 272×8 的完整通过证据。

## 生产修正与同案设备验证

导航生产修正已实现：成功 `observe` 后保存不含节点内容、仅用于拒绝的来源记录，绑定原 owner/task、epoch、profile 和配置许可；下一次成功观察会替换该记录，包括新的 scoped 观察。`launch_app` 仍先校验目标应用授权，再对同一有效绑定中的已知禁止来源执行条件暂停。任何启动派发仍须经过原有控制窗、最终快照、窗口版本、配置及提交前校验；来源记录不能许可旧动作或免除最终校验。

核心 `pauseIfExecutionEpoch` 只暂停对应的当前执行代次，取消 hook 在锁外运行；旧代次的拒绝不能暂停后来恢复的任务。本地 core 的 **339 项测试通过，包含新增 6 项条件暂停测试**，无失败/错误/skipped。这证明核心状态边界的本地回归；通知仍经异步 UI 路径，不代表状态和控制窗界面已变成原子更新。

本次另增加 `aFreshScopedObservationReplacesThePriorUnscopedLaunchOrigin` 正例：同一 owner、epoch、profile、配置及适配器，先观察未授权 Settings，再由测试侧返回 Fixture 并成功观察新的 scoped 界面；随后单次 launch 仍须通过原检查并明确被接受。该用例未通过暂停/恢复让旧记录自然过期。原普通导航负例和控制窗重建负例的方法字节均未改；新增正例后，修正源完整清单为 **273 项/API、2184 项**。它不会改写前对照的 272 项/API 或两轮旧源的 271 项/API。

修正源 `b3e2cacfcc48c5819b0c25f9d8900b6d723c9f44` 的同案专项 [40978858645](https://github.com/ecojust/Agent-OS/actions/runs/40978858645) 已 **8/8 通过**，失败、未验证、JUnit errors/skipped/unreadable/unexpected 均为 0。选中方法相对前对照源码逐字节不变，八案均通过原 `TASK_PAUSED`、owner 暂停、零审批/派发/HTTP 与 Settings 焦点断言。通过案不输出 failure-only `navigationDiagnostic`，不能据此编造成功时的 trace 数值。

唯一归档 [40979236639](https://github.com/ecojust/Agent-OS/actions/runs/40979236639) 的[完整证据](../../screenshots/40978858645-1/manifest.json)为 **32 payload、29,324 B、0 PNG、16 blocked、0 secure-window 例外**，全部文件集合、大小、摘要、逐案记录、采集 JUnit 摘要和八档来源均已核验。性能附件八项 invalid，与未执行性能用例的专项范围一致，不是性能验收。旧源八个完成前置的失败与新源八个合同通过分别成立，未重跑同案来挑选通过结果。

当前旋转相关改动仅为原拒绝点增加分类和数字坐标诊断。同源完整运行 `40978861976` 已核验三个导航用例各8/8及整套2184/2184；旋转本轮也为8/8，但没有取得新的失配细类样本，尚无根因修复结论。以上结果与旧失败分别保留，API 矩阵检查点继续进行中。

详情见 [API 盘点](apk-api-acceptance.md)、[SAF 排查](numeric-preferences.md)、[旋转记录](rotation-controls.md)和[导航记录](navigation-origin-verification.md)。
