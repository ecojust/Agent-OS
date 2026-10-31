# Notes 后置WMS属性拒绝原因：41177392095 / attempt1

源码 `35519e9fb77dc751bf8529768292cdcd1eb3b7ed`；原四方法×八档仍保留固定32项：**24通过 / 8失败 / 0未验证**。后置诊断不改变原v4结果。

新关联诊断8个用例、8次原始打印；未绑定0、格式错误0、一致性问题0。原确认诊断8项、32次原隐藏样本；几何标记0次。

| API / 状态 | 原结果与 BACK/确认 | 后置 WMS | Populator 可见/分组 | 最终服务 |
| --- | --- | --- | --- | --- |
| 31 / off | failed; BACK1/确认0 | 唯一文本候选 | unavailable / unavailable | unavailable |
| 31 / idle | failed; BACK1/确认0 | 唯一文本候选 | unavailable / unavailable | unavailable |
| 32 / off | failed; BACK1/确认0 | 唯一文本候选 | unavailable / unavailable | unavailable |
| 32 / idle | failed; BACK1/确认0 | 唯一文本候选 | unavailable / unavailable | unavailable |
| 36 / off | failed; BACK0/确认0 | 不可用 | unavailable / unavailable | unavailable |
| 36 / idle | failed; BACK0/确认0 | 不可用 | unavailable / unavailable | unavailable |
| 37.0 / off | failed; BACK0/确认0 | 唯一文本候选 | 0 / 0 | 0 |
| 37.0 / idle | failed; BACK0/确认0 | 唯一文本候选 | 0 / 0 | 0 |

表内匹配数量仅属于后置读取：0是完整、识别成功清单中的未匹配；不可用不是0。Populator可见和显示分组会重复打印同一对象，分别计数。相同文本不构成Binder强身份，未核验system_server生命周期，也不证明原准备时刻或像素状态。

## API36的本轮具体拒绝原因

**两个API36状态均触发`ATTRS_SIZE_LIMIT`，不是本轮观察到的尾部重复属性块。** 这是失败后补充WMS诊断无法选择候选的原因，不是此前原v4隐藏准备失败的根因或修复。

| 固定结构字段 | API36关闭/空闲两态的实际值 |
| --- | --- |
| 正式Window分段序号 / 总数 | 3 / 11 |
| 分段结束类型 | NEXT_WINDOW_HEADER，后面仍有正式记录 |
| 与原IME引用的关系 | OTHER_RECOGNIZED_WINDOW，仅文本关系 |
| 属性anchor数 / 首个偏移 / 第二偏移 | 1 / 229 / null |
| 整个分段长度recordChars | 11698个Java UTF-16字符单元 |
| 外层扫描前缀scanChars | 8192 |
| 已扫描前缀的maxDepth / 顶层ty遇到次数 | 5 / 1 |
| 首个已知尾部标题 / 两anchor间尾部标志 | null / null |
| type词法类别 / 前缀长度 / 完整性 | null / null / null，非type-value拒绝 |
| 未解析数 / 保留详情 / 省略数 | 1 / 1 / 0 |

诊断明确停在属性扫描的8192单元上限；`recordChars=11698`是整个Window分段长度，不能等同于属性块长度，也没有记录后续闭合位置或完整类型值。这里的最大深度5只覆盖已经扫描的前缀。只有一个anchor、无第二anchor、没有匹配的固定尾部标题，而且不是最后一条正式分段，因此[固定源码允许的尾部混入解释](../notes-ime-attrs-reasons-2026-10-31/aosp16-wms-record-boundaries.md)没有得到本轮证据支持。旧9b只保存10/1计数，不能把新增字段倒填到旧运行。

本轮继续保留`WMS_ATTRIBUTES_INCOMPLETE`：API36每态10条属性成功、1条未知，不选择mClient，后续匹配计数仍为null。不能因为header文本不是原引用就忽略这条记录，也不能把扫描上限当作该窗口不存在或其类型已完整核验。

## 其他来源与原结果保持

API31/32/37六项成功选中WMS文本候选，新增attrs详情均为空列表、遗漏0。API31/32的a11y命令仍不可用，final为旧格式；API37两态完整后置Populator可见/显示分组/final清单仍匹配0/0/0。API36的null与API37的完整清单观察零分开保留，没有把八项全称作已关联。

与9b上一轮逐案比较：32项原状态、32项主错误、16项Notes确认契约字段、八项原失败的32次隐藏样本字段和计数均一致。比较不要求独立运行的时刻、窗口ID和token相同；原token不跨运行比较。几何marker仍为0，旧API30失败保持历史原件。

八份后置记录在原失败冻结后0–3毫秒开始，三条命令合计19–46毫秒，没有截断或调用方超时。新字段只解释已读取文本的拒绝位置，未增加平台读取、动作或原采样次数；固定失败原因没有重新判定原八项失败。

如果随后调整独立诊断的资源上限，须单独冻结来源，继续要求完整闭合、类型与身份完整、所有记录可验证，并在新运行验证实际格式；不跳过未知记录、不改原v4判定。当前原件不含完整dump，不能预先声称增加上限就一定能选中或证明属性合法。

## 每条命令的状态

| 命令 | 读取状态 | 数量 |
| --- | --- | --- |
| ACCESSIBILITY | COMPLETE | 8 |
| WMS_A11Y | COMMAND_UNAVAILABLE | 4 |
| WMS_A11Y | COMPLETE | 4 |
| WMS_WINDOWS | COMPLETE | 8 |

EOF只记为COMPLETE，退出码NOT_EXPOSED；SERVICE_TIMEOUT/调用方TIMEOUT之后跳过余项。完整dump不归档，outputSha256是采集器报告的原字节/前缀指纹，不能从已丢弃原dump独立重算；独立可复核的是原instrumentation和marker rawSHA、源码解析契约及字段一致性。所有时刻、有限字节摘要覆盖范围、字段状态、截断与拒绝原因在`association-analysis.json`及原始instrumentation中保留。

## 原件与历史保留

归档运行 `41177719816`，证据提交 `c8da364ad9492ac36274f08d2703de2a93a70665`，父提交为精确测试源码。只恢复`screenshots/third-party-41177392095-1`，未恢复归档README。

完整归档审计：56个payload、2,534,874字节；原状态、实际四方法、官方APK锁/签名与安装身份、服务状态、恢复、全部SHA和PNG均按实际证据核验。详见`archive-audit.json`；32项设置恢复成功。

对9b属性解析修正轮逐项比较：`{"passed -> passed": 24, "failed -> failed": 8}`。每轮独立32项，旧失败仍保留；状态变化不独自证明稳定修复。旧API30几何失败不从本轮成功截图或后置dump重建。

本轮后置清单可以缩小候选来源边界，但不能指定具体筛选分支、证明隐藏像素、授权BACK/确认/截图或宣称原八项失败修复。后续定位应保留原v4结果，先依据实际缺口选择独立诊断契约。
