# Notes 失败后 IME 关联诊断：41174426442 / attempt1

源码 `9b86b2a842f9e837e20e2df183893da697faaf40`；原四方法×八档仍保留固定32项：**24通过 / 8失败 / 0未验证**。后置诊断不改变原v4结果。

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

## 修正后的实际收获与剩余缺口

新版只修正独立诊断的`mAttrs`嵌套括号解析，保留原v4判定。**本轮6项成功选中唯一配置IME的WMS/mClient候选，API37两态首次得到完整后置清单中的0/0/0匹配。** 这些都是新源码9b86b2a8的实际记录，首轮72f的8个null保持原样。

| 独立来源与分组 | WMS/mClient候选 | 后续匹配观察 |
| --- | --- | --- |
| 首轮72f，8个失败用例 | 0/8，未取得共同键 | 全部null；原件不重标 |
| 修正9b，API31/32四项 | 4/4 | a11y命令不可用、final旧格式；null |
| 修正9b，API36两项 | 0/2；attrs成功10、未解析1 | 不选候选，保持null |
| 修正9b，API37两项 | 2/2 | 完整后置可见/分组/final清单匹配0/0/0 |

本轮是6项WMS选中、2项WMS提取未完成；仅API37两态具备三份后置清单的完整候选查找结果，不是八项均已完成关联。

- **API37两态**：WMS按配置owner/display/package选中唯一INPUT_METHOD，10个attrs完整、未解析0。Populator notificationsEnabled=true，可见清单3项、1个display分组，最终accessibility完整配对2项；同轮mClient文本在这三份后置清单均匹配0。WMS的onScreen/visible/ready/hasSurface/shown仍为true。调查边界因此可缩到Populator清单形成之前或其中，以及读取间变化；不能仅凭这些dump区分上游未提供、预筛选、缓存、通知时序或对象重建，也不是原BACK决策时刻的证明。
- **API31/32两态**：WMS候选已成功读取，attrs分别9/12项完整。后置WMS仍是onScreen/Surface.shown/hasSurface为true、visible/ready为false；`window a11y`命令不可用，最终accessibility为旧的未配对格式，因此候选匹配计数仍为null。
- **API36两态**：已识别一个INPUT_METHOD记录，但10个attrs成功、1个未解析；严格返回`WMS_ATTRIBUTES_INCOMPLETE`，不选mClient，后两清单的匹配计数仍为null。不能称该记录属于哪个窗口，也不能把这两项当成清单缺项或忽略未知记录。

采集开始在原失败冻结后0–2毫秒，三条命令合计20–47毫秒，无字节截断或超时。四个COMMAND_UNAVAILABLE仅属于API31/32的a11y命令，退出码仍未暴露。八份原确认诊断各四个WMS样本，几何marker为0；旧API30几何失败在本轮没有再触发，不据此声称行为修复。

与72f首轮逐案比较：32项原结果、32项原主错误、16项Notes的确认契约字段及八项失败的原隐藏样本字段/计数都一致。比较排除本来就应随独立运行变化的时间戳、窗口ID和token；详见[独立来源比较](attrs-correction-comparison.json)。两轮各自保留32项，后置解析改善不替换原错误。

下一步先为API36未解析attrs补充固定字段的失败原因，再评估API37过滤前元数据的可用来源及共同身份键；本轮不追加采集或测试。完整dump未留存，不能据目前的单个未解析计数猜测具体格式；InputDispatcher/SF的其他token也不能直接替代IWindow键。任何进一步采集另行固定范围和来源，不放宽动作或截图准入。

## 每条命令的状态

| 命令 | 读取状态 | 数量 |
| --- | --- | --- |
| ACCESSIBILITY | COMPLETE | 8 |
| WMS_A11Y | COMMAND_UNAVAILABLE | 4 |
| WMS_A11Y | COMPLETE | 4 |
| WMS_WINDOWS | COMPLETE | 8 |

EOF只记为COMPLETE，退出码NOT_EXPOSED；SERVICE_TIMEOUT/调用方TIMEOUT之后跳过余项。完整dump不归档，outputSha256是采集器报告的原字节/前缀指纹，不能从已丢弃原dump独立重算；独立可复核的是原instrumentation和marker rawSHA、源码解析契约及字段一致性。所有时刻、有限字节摘要覆盖范围、字段状态、截断与拒绝原因在`association-analysis.json`及原始instrumentation中保留。

## 原件与历史保留

归档运行 `41174726087`，证据提交 `a5a6732b9a639fc990df798d7856a2a727675553`，父提交为精确测试源码。只恢复`screenshots/third-party-41174426442-1`，未恢复归档README。

完整归档审计：56个payload、2,527,152字节；原状态、实际四方法、官方APK锁/签名与安装身份、服务状态、恢复、全部SHA和PNG均按实际证据核验。详见`archive-audit.json`；32项设置恢复成功。

对首次后置诊断72f一轮逐项比较：`{"passed -> passed": 24, "failed -> failed": 8}`。每轮独立32项，旧失败仍保留；状态变化不独自证明稳定修复。旧API30几何失败不从本轮成功截图或后置dump重建。

本轮后置清单可以缩小候选来源边界，但不能指定具体筛选分支、证明隐藏像素、授权BACK/确认/截图或宣称原八项失败修复。后续定位应保留原v4结果，先依据实际缺口选择独立诊断契约。
