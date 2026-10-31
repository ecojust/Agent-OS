# Notes 失败后 IME 关联诊断：41173725544 / attempt1

源码 `72f77af85481eb90c0511a52e61c7cbab3ac6595`；原四方法×八档仍保留固定32项：**24通过 / 8失败 / 0未验证**。后置诊断不改变原v4结果。

新关联诊断8个用例、8次原始打印；未绑定0、格式错误0、一致性问题0。原确认诊断8项、32次原隐藏样本；几何标记0次。

| API / 状态 | 原结果与 BACK/确认 | 后置 WMS | Populator 可见/分组 | 最终服务 |
| --- | --- | --- | --- | --- |
| 31 / off | failed; BACK1/确认0 | 不可用 | unavailable / unavailable | unavailable |
| 31 / idle | failed; BACK1/确认0 | 不可用 | unavailable / unavailable | unavailable |
| 32 / off | failed; BACK1/确认0 | 不可用 | unavailable / unavailable | unavailable |
| 32 / idle | failed; BACK1/确认0 | 不可用 | unavailable / unavailable | unavailable |
| 36 / off | failed; BACK0/确认0 | 不可用 | unavailable / unavailable | unavailable |
| 36 / idle | failed; BACK0/确认0 | 不可用 | unavailable / unavailable | unavailable |
| 37.0 / off | failed; BACK0/确认0 | 不可用 | unavailable / unavailable | unavailable |
| 37.0 / idle | failed; BACK0/确认0 | 不可用 | unavailable / unavailable | unavailable |

表内匹配数量仅属于后置读取：0是完整、识别成功清单中的未匹配；不可用不是0。Populator可见和显示分组会重复打印同一对象，分别计数。相同文本不构成Binder强身份，未核验system_server生命周期，也不证明原准备时刻或像素状态。

## 本轮尚未完成窗口关联

八份WMS后置记录均为`referenceCount=1`、`inputMethodRecordCount=0`、`WMS_RECORD_MISSING_OR_AMBIGUOUS`，未提取到`mClient`。**这个0是提取器计数，不是系统输入法窗口不存在的证据。** 新诊断全部保持INCONCLUSIVE，后两清单的候选匹配计数均为null。

API31/32的`window a11y`返回COMMAND_UNAVAILABLE，最终accessibility为未支持的旧格式。API36的Populator可见清单完整解析4项、最终配对3项；API37对应3项/2项，两个版本均有1个display分组、notificationsEnabled=true。这些是清单总量，不能在缺少mClient候选时推断哪一项是IME。

采集开始在原失败冻结后0–2毫秒，三条读取总耗时23–52毫秒；没有超时、截断或额外动作。新结果未回填原BACK决策和四次WMS，原八项隐藏准备失败仍在。

独立宿主复现发现当前诊断解析器的确定缺陷：它用`mAttrs=\{[^}]*\bty=`，会在合法的`sim={...}`嵌套闭括号处提前结束，从而漏掉后面的`ty=INPUT_METHOD`。固定AOSP17源码明确先输出sim嵌套块再输出ty；使用`git show 72f77af8:...`提取的原helper做合成对照，普通attrs正确选中1条、只增加合法嵌套sim后却返回0/UNAVAILABLE。见[固定源码索引](wms-parser-source-index.json)及[复现证据](wms-parser-reproduction.json)。

此实验证明原解析器不支持一种合法格式；由于本轮完整dump按契约不归档，仍不能断言八个实际dump逐字包含这个格式。后续应修正独立诊断的attrs解析并以新源码、独立冻结的一轮32项验证；本轮原数据不重标、不重算成关联成功。

## 每条命令的状态

| 命令 | 读取状态 | 数量 |
| --- | --- | --- |
| ACCESSIBILITY | COMPLETE | 8 |
| WMS_A11Y | COMMAND_UNAVAILABLE | 4 |
| WMS_A11Y | COMPLETE | 4 |
| WMS_WINDOWS | COMPLETE | 8 |

EOF只记为COMPLETE，退出码NOT_EXPOSED；SERVICE_TIMEOUT/调用方TIMEOUT之后跳过余项。完整dump不归档，outputSha256是采集器报告的原字节/前缀指纹，不能从已丢弃原dump独立重算；独立可复核的是原instrumentation和marker rawSHA、源码解析契约及字段一致性。所有时刻、有限字节摘要覆盖范围、字段状态、截断与拒绝原因在`association-analysis.json`及原始instrumentation中保留。

## 原件与历史保留

归档运行 `41174016345`，证据提交 `7c9a6933801d8b6891e9576da6ca38360b2c199e`，父提交为精确测试源码。只恢复`screenshots/third-party-41173725544-1`，未恢复归档README。

完整归档审计：56个payload、2,530,658字节；原状态、实际四方法、官方APK锁/签名与安装身份、服务状态、恢复、全部SHA和PNG均按实际证据核验。详见`archive-audit.json`；32项设置恢复成功。

对最近33ac第二轮逐项比较：`{"passed -> passed": 24, "failed -> failed": 8}`。每轮独立32项，旧失败仍保留；状态变化不独自证明稳定修复。旧API30几何失败不从本轮成功截图或后置dump重建。

本轮后置清单可以缩小候选来源边界，但不能指定具体筛选分支、证明隐藏像素、授权BACK/确认/截图或宣称原八项失败修复。后续定位应保留原v4结果，先依据实际缺口选择独立诊断契约。
