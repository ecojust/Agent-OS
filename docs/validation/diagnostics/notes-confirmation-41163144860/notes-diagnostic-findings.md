# Notes v4 原读取诊断：41163144860 / attempt 1

源 `435d91b794ddc4efca52de56b4be4f31997639a3`，本轮固定四方法 × 八档独立结果为 **23 通过、9 失败、0 未验证**；计算器 16/16，Notes 7/16。API 36 本次确实启动并执行四方法，其结果属于本轮，不能填回第一批 `41161477724` 的四项未验证。历史 API 31/32/36/37 的 Notes 两态八项隐藏准备失败再次出现，另新增 API 30 Notes off 的标题矩形守卫失败；没有修复验收通过的证据。

归档 `screenshots/third-party-41163144860-1/manifest.json`，manifest SHA-256 `8f39b8c6bef5dffe1075668960ca596ffc521610ad25edc184603cbe259aa626`。分析核对实际使用的 report/UI/instrumentation 大小、SHA、source/run/attempt、schema、每档四组合以及原报告一致性；全归档来源审计由归档审查另行完成。`notes-same-read-analysis.json` 保留全部原 suppressed JSON、每条 SHA/行号/偏移及原八字段；`notes-historical-comparison.json` 保存历史 32 组合对照和各次读取相对时刻；`notes-extra-failure-analysis.json` 保存 API 30 原错误、三处原堆栈、回执、原文件/冻结源码哈希。

## 新增 API 30 Notes off 失败

原错误为 `AssertionError: Notes resource is not fully inside its original application window`，业务阶段 `create_note`。原堆栈固定在 `exercise:212 → notesSetText:832 → awaitNotesTarget → requireUsableNotesNode:853`；对应资源为 `locked_note_title`。冻结源码显示该次 `awaitNotesTarget` 失败发生在这次标题 `ACTION_SET_TEXT` 提交之前，也在文字类型选择和 `notesConfirmCreate` 之前。

原确认回执为 `NOT_STARTED`；BACK/visible/hidden/elapsed/confirm 全为 0，HOME 0，未提供确认准备 suppressed 诊断。它不能归入下面八个 `HIDDEN_PREPARATION` 失败。守卫要求应用与目标矩形非空且应用矩形完整包含目标；原件没有保留这次失败的具体两矩形或 NotesRead 记录，因此不能确定是空矩形、越界、快照变化或其他原因，也不能据另一轮通过将它判为可忽略波动。下一步需保留该原读取的有界矩形/窗口身份及调用顺序诊断，继续维持现有完整包含和遮挡门槛。

## 八项原确认失败

八项各保留一次 `NOTES_CONFIRMATION_DIAGNOSTIC_V1` suppressed JSON（4,731–4,782 字符），均未截断；每项含原四次隐藏样本，共 32 次。结构、计数和读取次序检查均无异常。每次 WMS 仅有一个 INPUT_METHOD 记录，配置服务 owner/display/package 三项匹配，进入 visibility 阶段，原结果均为 `REFUSED / input_method_is_visible_animating_or_unverified`；没有解析异常、身份失败或字段重复/冲突。`mAnimatingExit` 缺失是原解析器允许的形式。此处保留的是原调用的有界解析结果，未保存完整 WMS dump。

**API 36 和 37 的原 `backDecisionInventory` 全部 `scanComplete=true`、`imeCount=0`、`imeEntries=[]`，因此本轮 BACK=0 没有低层 IME 被过滤的证据。** 这些组合之后四次隐藏前清单和最后清单仍全部为空。本轮所有保留清单都没有 `BELOW_FOCUSED`。WMS 独立读取到可见的配置 IME，不能倒填成前一清单曾有对应对象。

| 原 WMS 字段 | API 31/32（16 样本） | API 36/37（16 样本） |
| --- | --- | --- |
| isOnScreen | TRUE / count 1 | TRUE / count 1 |
| isVisible | FALSE / count 1 | TRUE / count 1 |
| isReadyForDisplay() | FALSE / count 1 | TRUE / count 1 |
| mLastHidden | FALSE / count 1 | FALSE / count 1 |
| mEnterAnimationPending | FALSE / count 1 | FALSE / count 1 |
| mAnimatingExit | MISSING / count 0 | MISSING / count 0 |
| mHasSurface | TRUE / count 1 | TRUE / count 1 |
| Surface: shown | TRUE / count 1 | TRUE / count 1 |

API 31/32 的四项均 BACK 一次且按下/抬起被接受、visible=2、hidden=4；API 36/37 四项均 BACK=0、visible=0、hidden=4。八项均未证实隐藏，确认/HOME 均为 0。三可见谓词和子 surface 字段的关系不能据后续截图、AOSP 机制或清单缺项改判为隐藏。

| API | 配置 UID | WMS token | 记录位置 / 边界 |
| --- | --- | --- | --- |
| 31 | 10086 | 7223960 | 5 / next_window |
| 32 | 10079 | dbf145d | 8 / next_window |
| 36 | 10113 | 8b3da46 | 7 / next_window |
| 37.0 | 10112 | d0c95ca | 6 / next_window |

每档两态各四样本的上述身份字段相同；不能跨运行比较 token/UID 来认定同一窗口，更不能把 WMS token 与 accessibility window ID 等同。

## 时序与清单

以下均为设备 `SystemClock.elapsedRealtime` 相对每个组合 START 的毫秒，不是 UTC 或 BACK 后时间。未记录精确 BACK 按下/抬起时间，因此不能写成 BACK+N ms。样本包装内原 shell dump 的独立起止保留在 JSON。

| 组合 | 原回执耗时 ms | 四样本包装起止 ms | 各次耗时 ms |
| --- | --- | --- | --- |
| 31 off | 5020 | 2022–2033；2194–2205；2365–2375；2545–2556 | 11，11，10，11 |
| 31 idle | 5021 | 1686–1697；1856–1866；2025–2035；2195–2206 | 11，10，10，11 |
| 32 off | 5001 | 1732–1746；1906–1919；2079–2092；2253–2269 | 14，13，13，16 |
| 32 idle | 5014 | 1653–1667；1828–1840；2000–2013；2173–2185 | 14，12，13，12 |
| 36 off | 5045 | 1255–1279；1444–1474；1639–1663；1827–1852 | 24，30，24，25 |
| 36 idle | 5012 | 1263–1288；1455–1481；1644–1670；1834–1859 | 25，26，26，25 |
| 37.0 off | 5016 | 1236–1246；1413–1422；1576–1585；1740–1749 | 10，9，9，9 |
| 37.0 idle | 5014 | 1274–1284；1451–1461；1616–1629；1785–1796 | 10，10，13，11 |

所有四次包装最晚在 START+2556 ms 结束；额度耗尽后原循环继续检查到五秒期限，没有追加 WMS 隐藏样本，不能称作连续观察到五秒。

| 组合 | 原决策清单：序号 / START ms / IME 条目 | 四 hidden 前清单：序号 / START ms | last：序号 / START ms | 保留清单 focused ID / layer |
| --- | --- | --- | --- | --- |
| 31 off | 17 / 895 / ID 4，display 0，layer 1，CANDIDATE_REQUIRES_IDENTITY | 30/2018；33/2190；36/2362；39/2541 | 84 / 4967 | 8 / 0 |
| 31 idle | 17 / 883 / ID 4，display 0，layer 1，CANDIDATE_REQUIRES_IDENTITY | 32/1683；35/1853；38/2022；41/2191 | 93 / 4968 | 11 / 0 |
| 32 off | 17 / 896 / ID 2，display 0，layer 1，CANDIDATE_REQUIRES_IDENTITY | 31/1728；34/1902；37/2075；40/2248 | 90 / 4948 | 11 / 0 |
| 32 idle | 16 / 854 / ID 2，display 0，layer 1，CANDIDATE_REQUIRES_IDENTITY | 31/1650；34/1825；37/1996；40/2170 | 92 / 4962 | 14 / 0 |
| 36 off | 13 / 644 / 无 IME | 25/1250；28/1439；31/1634；34/1823 | 91 / 4990 | 10 / 0 |
| 36 idle | 13 / 649 / 无 IME | 25/1258；28/1449；31/1640；34/1829 | 90 / 4957 | 13 / 0 |
| 37.0 off | 13 / 615 / 无 IME | 26/1234；29/1412；32/1575；35/1738 | 97 / 4964 | 9 / 0 |
| 37.0 idle | 13 / 651 / 无 IME | 26/1273；29/1449；32/1615；35/1783 | 96 / 4963 | 12 / 0 |

所有所列清单 scanComplete=true、notRetained=0；所有 hidden 前和 last 清单均 imeCount=0。每组合所保留清单的 focused ID/layer 不变。表中时间为读取开始，完成时间完整保留在 JSON；这是多次先后读取，不能宣称原子同刻。候选枚举仅说明 metadata 过滤结果，配置 IME 根身份仍依赖另外的原检查。

## 历史比较和剩余问题

历史 `40834304846` / source `db1943a8afa46ad21d72ba6198614a383a042d3f` 为独立的 24 通过/8 失败/0 未验证。逐组合比较本轮：23 项仍通过，8 项仍失败，API 30 Notes off 一项由历史通过变为本轮失败，没有失败转通过。历史未保存准备阶段四样本原字段，不能宣称八字段逐次相同；第一批与本批也保留各自固定 32 分母。

Android 12/12L 的条件性五秒 IME surface 缓存与父 Insets leash 隐藏仍仅是可解释部分字段关系的机制线索，本次未记录实际 hideWindow 分支/时刻或父层状态，不能认定现场因果或实际像素。下一步最小证据缺口分别为：API 30 标题失败检查的同次原应用/目标两矩形、窗口身份和读取次序；API 36/37 平台窗口回调与 accessibility 清单的原过滤字段，以及能关联 WMS/平台对象的共同 token（若平台确实提供），而不是把 ID/layer 当身份；API 31/32 父层、Insets 与 IME 隐藏/缓存生命周期的实际分支与时刻。现证据不支持仅调整 layer 过滤顺序。本轮完成原八项隐藏失败的原读取诊断，先准确归档，不继续修改代码或重跑。保持隐藏谓词、完整矩形门槛、一次 BACK、原五秒期限与四次隐藏额度。

