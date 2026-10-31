# Notes v4 原读取诊断：41161477724 / attempt 1

源 `ad0f0a723e2efbb146a06e173a0ebdecc6706ee6`，固定四方法 × 八档的原始结果是 **22 通过、6 失败、4 未验证**。API 36 在 userdata 创建前报告磁盘不足，随后 emulator boot 超时，未执行兼容探针；该档四项保留未验证，不从其他运行补齐。API 31、32、37 的 Notes off/idle 六项仍在 `HIDDEN_PREPARATION` 失败，确认提交均为 0。本次没有稳定修复或完成验收的证据。

关键纠正：**API 37 当次 BACK=0 对应的原 `backDecisionInventory` 均为完整扫描、`imeCount=0`；不能归因为已观察到低层 IME 被过滤。** 本轮 API 37 四次隐藏采样前和最终保留的清单同样没有 IME 条目，实际没有任何 `BELOW_FOCUSED` 记录。窗口清单没有条目与另一次 WMS 正向可见观测可以同时成立；此证据尚未说明清单为何缺项。

## 原件和诊断保真

归档：`screenshots/third-party-41161477724-1/manifest.json`；manifest SHA-256 `dfd790d37ac308243e74d5b8cdb61ab447990f9b14408a165cca82e45fff964c`。逐一核对分析使用的 report、ui-results、instrumentation 原件大小/SHA、source/run/attempt、v4 schema、每档四组合与 manifest 计数，以及 report 与 UI 原状态的一致性。GitHub 产物来源与全归档审计另由归档审查负责。

六项失败各在原 `instrumentation.json.output` 保留 **一次** `NOTES_CONFIRMATION_DIAGNOSTIC_V1` suppressed JSON；各 4,731–4,782 字符，均未截断，分别含四个原隐藏 WMS 样本，合计 24 个。每次原 WMS 均得到唯一 INPUT_METHOD 记录、配置服务的 owner/display/package 三项匹配，进入 visibility 阶段，结果为 `REFUSED / input_method_is_visible_animating_or_unverified`。没有 dump 解析异常、身份匹配失败、重复字段或矛盾字段；`mAnimatingExit` 缺失是原解析器允许的形式，不是此轮拒绝原因。这里的“原样本”是原调用的有界解析诊断，未保存或重建完整 WMS dump。

机器输出 `notes-same-read-analysis.json` 保存完整原 suppressed JSON、各条 SHA、原 instrumentation 行号/偏移、全部原字段与原 v4 回执；`notes-historical-comparison.json` 保存 32 组合逐项历史比较和 START 相对时间。六项已执行诊断的结构/顺序/计数检查没有异常；分析器另明确标记 API 36 两个 Notes 组合缺少原 instrumentation/UI 载荷，不能分析该档准备阶段。

## 原 WMS 八字段

表内除 `mAnimatingExit` 外每字段的原 count 均为 1；`mAnimatingExit` 为 count 0 / MISSING。各列描述对应组合的全部四次读取，而非失败后的截图检查。

| 原字段 | API 31/32，4 组合 × 4 样本 | API 37，2 组合 × 4 样本 |
| --- | --- | --- |
| isOnScreen | TRUE | TRUE |
| isVisible | FALSE | TRUE |
| isReadyForDisplay() | FALSE | TRUE |
| mLastHidden | FALSE | FALSE |
| mEnterAnimationPending | FALSE | FALSE |
| mAnimatingExit | MISSING | MISSING |
| mHasSurface | TRUE | TRUE |
| Surface: shown | TRUE | TRUE |

API 31/32 的 BACK 按下/抬起都被接受，正向可见样本 2，正向隐藏样本 4；第一项 `isOnScreen` 已不满足隐藏谓词，保留的 surface 字段也不满足现有隐藏 surface 分支。API 37 没有提交 BACK，正向可见样本 0、隐藏样本 4，三项可见谓词均为 TRUE。没有依据通过忽略 onScreen、认可子表面仍 shown、或只凭清单没有 IME 来放宽隐藏准入。

| API | 配置服务 UID | WMS selectedToken | 唯一记录位置 / 结束边界 |
| --- | --- | --- | --- |
| 31 | 10083 | c48cc5c | 5 / next_window |
| 32 | 10078 | 9d56ee2 | 8 / next_window |
| 37 | 10112 | b7641b6 | 6 / next_window |

每档两个状态各四样本的该组字段一致。该 token 是 WMS 窗口引用，与 accessibility window ID 没有记录共同标识；不能把两种读取得到的对象直接等同。

## 读取时序与原清单

以下时间均为设备 `SystemClock.elapsedRealtime` 相对该组合准备 START 的毫秒；不是 UTC，也不是 BACK 后经过时间。诊断没有 BACK 按下/抬起时刻，无法给出准确的 BACK+N ms。WMS 读取和 accessibility 清单是先后不同读取，不能宣称原子同刻。

| 组合 | 原 BACK / visible / hidden | 原回执耗时 ms | WMS 样本起止 ms（按 1–4 顺序） | 各样本总耗时 ms |
| --- | --- | --- | --- | --- |
| 31 off | 1 / 2 / 4 | 5018 | 2005–2016；2177–2188；2348–2359；2519–2530 | 11，11，11，11 |
| 31 idle | 1 / 2 / 4 | 5019 | 1631–1644；1806–1817；1976–1987；2146–2157 | 13，11，11，11 |
| 32 off | 1 / 2 / 4 | 5015 | 2032–2047；2205–2219；2380–2396；2556–2573 | 15，14，16，17 |
| 32 idle | 1 / 2 / 4 | 5012 | 1772–1788；1947–1968；2128–2142；2300–2316 | 16，21，14，16 |
| 37 off | 0 / 0 / 4 | 5000 | 1284–1303；1464–1487；1648–1668；1830–1852 | 19，23，20，22 |
| 37 idle | 0 / 0 / 4 | 5030 | 1264–1282；1444–1458；1617–1634；1792–1808 | 18，14，17，16 |

表中起止对应整个原样本包装，内部 shell dump 的单独起止完整保留于 JSON。所有四次隐藏读取在 START+2573 ms 以内结束；固定四次额度用完后，原循环仍检查到原五秒期限，没有追加 WMS 样本。API 31/32 的机制线索是平台可能延迟删除非 fullscreen IME 子表面、Insets 隐藏可能作用父 leash；本次没有记录相应平台分支、hideWindow 时刻或实际表面层级，不能据此认定实际因果、屏幕像素状态或输入连接归属。

| 组合 | 原决策清单：序号 / START ms / IME 条目 | 四次隐藏前清单：序号 / START ms | 最后清单：序号 / START ms | 所有保留清单的 focused ID / layer |
| --- | --- | --- | --- | --- |
| 31 off | 17 / 882 / ID 2，display 0，layer 1，CANDIDATE_REQUIRES_IDENTITY | 30/2003；33/2172；36/2345；39/2516 | 85 / 4965 | 8 / 0 |
| 31 idle | 17 / 874 / ID 2，display 0，layer 1，CANDIDATE_REQUIRES_IDENTITY | 31/1627；34/1802；37/1973；40/2143 | 93 / 4966 | 11 / 0 |
| 32 off | 17 / 908 / ID 1，display 0，layer 1，CANDIDATE_REQUIRES_IDENTITY | 30/2029；33/2202；36/2376；39/2553 | 84 / 4962 | 11 / 0 |
| 32 idle | 17 / 885 / ID 1，display 0，layer 1，CANDIDATE_REQUIRES_IDENTITY | 32/1770；35/1944；38/2124；41/2297 | 91 / 4960 | 14 / 0 |
| 37 off | 13 / 633 / 无 IME | 26/1280；29/1460；32/1645；35/1826 | 93 / 4947 | 9 / 0 |
| 37 idle | 13 / 628 / 无 IME | 26/1261；29/1441；32/1614；35/1789 | 95 / 4978 | 12 / 0 |

所有表中清单均 `scanComplete=true`、`notRetained=0`；所有隐藏前及最后清单均 `imeCount=0`、条目为空。focused ID/layer 在每组合上述保留清单中没有变化。清单时刻为读取开始，32 off 的第二隐藏前清单读取完成在 START+2203 ms，其余所列清单开始/完成毫秒值相同。`CANDIDATE_REQUIRES_IDENTITY` 仅是原 metadata 过滤留下的候选分类；独立的原 BACK 回执才记录完整前置检查通过，不能将该枚举当作根身份已验证。

## 与历史独立比较及下一步

历史 `40834304846` / source `db1943a8afa46ad21d72ba6198614a383a042d3f` 独立结果为 24 通过、8 失败、0 未验证。本轮逐组合对应为：22 项仍通过，6 项仍失败，历史 API 36 的计算器两通过和 Notes 两失败均变为本轮未验证；没有失败转通过。历史没有原四次诊断，不能声称两轮原 WMS 八字段逐次相同，也不能将其失败后截图观测填入原准备阶段。

下一步应保持本批原件和失败结论独立，等待另一精确源码的完整固定八档批次补充诊断；不将新 API 36 结果补入本批 32 项。API 37 应调查已配置 IME 在 WMS 可见而 accessibility 清单不提供条目的原因，再决定是否需要新契约。当前证据不支持仅调整 IME/layer 过滤顺序就能解决该项。
