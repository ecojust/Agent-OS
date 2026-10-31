# Notes 后置属性完整扫描修正：41178248694 / attempt1

源码 `cdea60c126af3cb50805f6e989eccbf7a7a1efb1`；原四方法×八档仍保留固定32项：**24通过 / 8失败 / 0未验证**。后置诊断不改变原v4结果。

新关联诊断8个用例、8次原始打印；未绑定0、格式错误0、一致性问题0。原确认诊断8项、32次原隐藏样本；几何标记0次。

| API / 状态 | 原结果与 BACK/确认 | 后置 WMS | Populator 可见/分组 | 最终服务 |
| --- | --- | --- | --- | --- |
| 31 / off | failed; BACK1/确认0 | 唯一文本候选 | unavailable / unavailable | unavailable |
| 31 / idle | failed; BACK1/确认0 | 唯一文本候选 | unavailable / unavailable | unavailable |
| 32 / off | failed; BACK1/确认0 | 唯一文本候选 | unavailable / unavailable | unavailable |
| 32 / idle | failed; BACK1/确认0 | 唯一文本候选 | unavailable / unavailable | unavailable |
| 36 / off | failed; BACK0/确认0 | 唯一文本候选 | 0 / 0 | 0 |
| 36 / idle | failed; BACK0/确认0 | 唯一文本候选 | 0 / 0 | 0 |
| 37.0 / off | failed; BACK0/确认0 | 唯一文本候选 | 0 / 0 | 0 |
| 37.0 / idle | failed; BACK0/确认0 | 唯一文本候选 | 0 / 0 | 0 |

表内匹配数量仅属于后置读取：0是完整、识别成功清单中的未匹配；不可用不是0。Populator可见和显示分组会重复打印同一对象，分别计数。相同文本不构成Binder强身份，未核验system_server生命周期，也不证明原准备时刻或像素状态。

## 预声明条件修正的实际结果

API36 的 off/idle 两项本轮均完成 11 个属性记录的解析，未解析数为0，`attrsDiagnostics` 为 schema2、`scanLimitChars=inputLimitChars=262144`、`entries=[]`；WMS 取得唯一配置 IME 文本候选，owner/display/package 检查均通过。此限制是已捕获文本的 Java UTF-16 单位；原 256KiB 字节读取、三条命令、每条3秒/合计9秒、depth8、完整闭合与唯一类型要求均保留。

两项 API36 的 Populator 可见清单4项、显示分组1项、最终服务3对，通知开启；候选匹配均为 **0/0/0**。API37 两项仍为清单3项/显示1组/最终2对，匹配 **0/0/0**。当前总计8项 WMS 选中；API31/32 后续命令/legacy格式仍不可用，不能写成零或已完整关联。

旧 source355 / run41177392095 的 API36 两项仍保留 `10 parsed/1 unparsed` 与 `ATTRS_SIZE_LIMIT`：第3/11条、recordChars11698、首锚点229、scanChars8192、maxDepth5、单锚点、未见固定tail heading。该旧轮只保存前缀元数据，没有外括号闭合位置或完整类型；本轮的解析成功不回填旧记录，也不证明尾部清单假设。新旧两轮分别保留32项分母。

本轮证据确认后置解析的8KiB上限缺口已在新 API36 采样中消除；原 Notes 八项失败依旧，尚未得到稳定功能修复。32项状态与主错误、16项 Notes 确认契约元数据，以及8项原诊断中的32份隐藏样本字段/计数均与355轮相同，详见`attrs-scan-comparison.json`。

后置采集开始于原结果冻结后 0–3ms，耗时 28–50ms；三条命令的调用方耗时分别为 WMS_WINDOWS 15–30ms、WMS_A11Y 5–9ms、ACCESSIBILITY 6–11ms。原四份隐藏样本最后结束于确认开始后 1755–2280ms；这不是连续5秒 WMS 采样，也不是同一次决策读取。

后续若继续定位36/37筛选前差异，应先确定能与当前候选建立可信同进程身份关系的元数据来源；不能把不同进程的 Binder 文本直接连接，也不能由0匹配反推具体过滤原因。本轮停止在预声明的一次修正32项范围内。

## 每条命令的状态

| 命令 | 读取状态 | 数量 |
| --- | --- | --- |
| ACCESSIBILITY | COMPLETE | 8 |
| WMS_A11Y | COMMAND_UNAVAILABLE | 4 |
| WMS_A11Y | COMPLETE | 4 |
| WMS_WINDOWS | COMPLETE | 8 |

EOF只记为COMPLETE，退出码NOT_EXPOSED；SERVICE_TIMEOUT/调用方TIMEOUT之后跳过余项。完整dump不归档，outputSha256是采集器报告的原字节/前缀指纹，不能从已丢弃原dump独立重算；独立可复核的是原instrumentation和marker rawSHA、源码解析契约及字段一致性。所有时刻、有限字节摘要覆盖范围、字段状态、截断与拒绝原因在`association-analysis.json`及原始instrumentation中保留。

## 原件与历史保留

归档运行 `41178532305`，证据提交 `bf2c1c77a8575eac2812fbae8945b7688613d6b6`，父提交为精确测试源码。只恢复`screenshots/third-party-41178248694-1`，未恢复归档README。

完整归档审计：56个payload、2,538,233字节；原状态、实际四方法、官方APK锁/签名与安装身份、服务状态、恢复、全部SHA和PNG均按实际证据核验。详见`archive-audit.json`；32项设置恢复成功。

对355固定拒绝原因诊断轮逐项比较：`{"passed -> passed": 24, "failed -> failed": 8}`。每轮独立32项，旧失败仍保留；状态变化不独自证明稳定修复。旧API30几何失败不从本轮成功截图或后置dump重建。

本轮后置清单可以缩小候选来源边界，但不能指定具体筛选分支、证明隐藏像素、授权BACK/确认/截图或宣称原八项失败修复。后续定位应保留原v4结果，先依据实际缺口选择独立诊断契约。
