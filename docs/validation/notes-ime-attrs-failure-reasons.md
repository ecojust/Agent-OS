# Notes 输入法属性失败原因与过滤前来源

本步骤承接[窗口关联诊断](notes-ime-association-diagnostics.md)。原9b86源码运行41174426442为24通过/8失败/0未验证：API31/32/37六项已取得配置IME的WMS候选，API37两态在后置完整Populator与最终清单中均匹配0/0/0；API36每态10项属性解析成功、1项未知，仍拒绝选择，匹配为null。原八项隐藏失败及旧API30几何失败保留。


本步骤已完成API36后置诊断的扫描容量修正及独立实测：355轮定位8192字符上限，cdea修正轮两态均11条属性完整解析、0未知、成功取得配置IME文本候选。原应用用例仍24通过/8失败；API36与37在完整后续清单中候选均匹配0/0/0，原Notes隐藏失败未修复。整体录像修正轮在ready失败，部分视频与原件保留，README保留355成功演示。详见下方最终结果。

## 固定采集范围

本次先解释已有WMS读取中的属性拒绝原因，不增加平台读取。仍只有失败结果冻结后、既有截图和清理前的三条只读dump；每条调用方等待最多3000毫秒，总期限9000毫秒，最多保留256KiB并允许1字节溢出探测。原v4四方法、官方APK/锁、5秒准备期限、四次隐藏采样、一次BACK/确认上限及所有身份/遮挡/截图准入不变。

在独立`NOTES_IME_ASSOCIATION_DIAGNOSTIC_V1`中增加可选属性结构诊断。最多保留8条未解析记录，其余只计遗漏数量；只输出固定原因、计数、偏移、括号深度、词法类别及与原IME引用的关系。固定源码支持的WMS尾部标题可以编码为有限枚举，不能输出任意标题、原属性字符串、未知类型文本或异常内容。整个标记继续限制16KiB，超限或采集错误保留明确回退，原Throwable和旧诊断不被替换。

35519原因诊断版的属性分析与9b86的接受/拒绝及唯一候选选择保持等价。缺失、重复、未闭合、大小/深度超限及类型字段异常分别记录；未知记录仍阻止唯一选择。结构计数说明解析器拒绝的原因，不自动证明窗口不存在或哪个平台行为出错。完整dump不归档，不能从旧摘要恢复其字节；旧两个运行不依据新增逻辑重算。

偏移和长度按Java UTF-16字符单元计，属性位置指向左括号；主扫描位置不等于包括类型前探在内的全部访问长度。类型值可能只读到有限前缀，未读完时词法类别不能代表完整类型。固定尾部标题只记录出现及相对位置，不说明其全部内容已解析，也不授权忽略尾部窗口。

固定AOSP16源码存在主窗口清单后再次打印隐藏/移除/销毁/调整大小窗口的路径，可能造成一个切分段内多个`mAttrs`。目前这是源码允许的路径，尚未证实为原API36记录；本轮须以实际固定结构字段判断，不先修改边界来获得候选。

## 验证与运行安排

先执行旧解析器对照、固定失败原因、数据不泄露及有界采集检查，核对宿主解析器对旧记录的解释不变，再完成构建/lint和独立审查。冻结源码后运行**一轮完整32项**：API30–37八档、Calculator1.4.0/Notes1.7.0及关闭/空闲两态。保留全部原失败和未验证，不根据结果增加同源轮次。

若实际结构明确证明可修复的诊断实现缺陷，先保存这一轮，再针对该缺陷另行冻结修正源码及一轮独立32项；不回填旧结果，不扩大动作或隐藏判定。不明确的格式保持未知，不能跳过未识别记录来建立唯一候选。

每份变更源码尝试一次整体操作录像，失败非阻塞、保留原件且不为得到成功片反复重录。已归档的72f真实APK四项录像保留实际来源；本步骤只改变失败后诊断，不重复录制未改变的API35日常操作。README每种类型只列最新审核后的实际视频。

API37过滤前信息来源并行进行固定源码核对，研究字段、权限、隐藏API边界和对象关联。它不加入本轮设备采集，不自动采用跨进程对象字符串、窗口名字或InputDispatcher输入通道token作为IWindow身份，也不以shell权限替代实际可调用性验证。任何后续listener或额外命令须单独定义范围和预算。

本地采集检查25项、属性元数据检查129项及3235组旧类型解析器差分通过；另有独立445组完整WMS差分、16项结构检查，原判断字段完全一致。243360字节合成WMS的完整标记6202字符，低于16KiB且未保留标题原文；这些均为主机替身检查，不代表设备运行。APK构建与lint通过（45秒）。宿主分析器39项检查及独立复审通过；重放72f/9b旧两轮，原32项及诊断解释均不变。[本地验证](diagnostics/notes-ime-attrs-reasons-2026-10-31/local-validation.json)保留来源与日志摘要。源码已冻结为`35519e9fb77dc751bf8529768292cdcd1eb3b7ed`；本轮设备结果已审核如下。

固定AOSP16的[记录边界研究](diagnostics/notes-ime-attrs-reasons-2026-10-31/aosp16-wms-record-boundaries.md)和六份[源码索引](diagnostics/notes-ime-attrs-reasons-2026-10-31/aosp16-wms-source-index.json)已保存。API37的[过滤前接口研究](diagnostics/notes-ime-attrs-reasons-2026-10-31/upstream-source-review.md)及22份[源码索引](diagnostics/notes-ime-attrs-reasons-2026-10-31/upstream-source-index.json)区分TestApi访问、权限委托、字段缺失与同进程Binder身份要求；尚未执行listener能力探查。它不证明存在可直接替换当前诊断的完整接口。

本步骤不预先将原八项应用失败或整体应用验收检查点标为完成。

## 本轮固定来源

[来源记录](diagnostics/notes-ime-attrs-reasons-2026-10-31/source-runs.json)绑定上述35519源码。独立完整32项[41177392095](https://github.com/ecojust/Agent-OS/actions/runs/41177392095)及整体录像[41177393808](https://github.com/ecojust/Agent-OS/actions/runs/41177393808)已完成审核。完整32项仍为24通过/8失败/0未验证，56载荷/2,534,874字节及32PNG核验，原状态、主错误和八项原失败的32次隐藏采样字段与9b一致。详见[逐案结果](diagnostics/notes-ime-attrs-reasons-41177392095/findings.md)及[独立原件审核](diagnostics/notes-ime-attrs-reasons-2026-10-31/independent-original-review.json)。整体录像36.571秒、511帧，13阶段/8确认通过；[视频](../../screenshots/demos/41177393808-1/demo.mp4)及[审核](diagnostics/notes-ime-attrs-reasons-2026-10-31/overall-video-audit.json)已归档。既有72f真实APK录像保留原来源。


## 实测原因与独立修正安排

API36关闭/空闲两态均报告`ATTRS_SIZE_LIMIT`：第3/11条正式Window分段，后面仍有下一条正式窗口，单个属性anchor位于229，整个分段11698个字符，扫描停在8192；已扫描前缀最大深度5、顶层类型出现1次，未出现已知尾部标题。因此本轮不采用[条件性尾部方案](diagnostics/notes-ime-attrs-reasons-2026-10-31/conditional-tail-inventory-plan.md)。这些字段定位的是后置诊断解析上限，不是原Notes隐藏失败原因；11698不是属性块长度，也不能证明后续闭合或类型唯一。旧9b缺少这些字段，不倒填旧记录。

下一份源码将属性扫描范围绑定现有256KiB捕获上限，检查完整属性闭合、深度8、唯一顶层类型、完整记录清单及候选身份；直接超长输入与截断结果继续拒绝。嵌套结构诊断版本将显式声明该上限，并保留旧schema1的8192解释。三条命令、每条3秒/总9秒等待及原v4动作/采样额度不变。这是独立诊断容量修正，尚未验证实际记录后半段是否满足全部条件。

修正源码冻结后单独执行一轮完整32项和一次整体录像，保存全部通过、失败及未验证。原355轮不改写；原八项隐藏失败和app_acceptance检查点继续进行中。API37下一步仍需独立的类/成员可访问能力探查，源码研究不等于实际listener可用。


## 完整捕获范围修正

针对355轮实际`ATTRS_SIZE_LIMIT`，修正已将扫描范围绑定原`MAX_BYTES=262144`，并在任何正则/分段前拒绝超过262144个UTF-16单元的直接输入。正常采集仍受原262144字节加1字节溢出探测约束；文本长度保护和原始字节额度分别记录。attrsDiagnostics schema2明确`scanLimitChars`与`inputLimitChars`，schema1历史字段继续按8192解释。

此变更允许完整捕获内的大属性块被检查，不允许看到第一个ty就提前返回；必须通过外层闭合、深度8、唯一顶层ty、所有记录属性完整和配置IME身份检查。原采集器截断输出不进入解析，原动作和隐藏检查完全保持。此源码不再声称所有解析结果与旧8192版本相同；差分验证将允许的变化明确限定在旧`ATTRS_SIZE_LIMIT`域，直接超长输入新增拒绝单列。

[可复现宿主验证](diagnostics/notes-ime-attrs-scan-2026-10-31/java-host-tests/host-validation.json)通过25项采集检查、2607组扫描差分（1789组旧非SIZE域保持，818组变化属于旧SIZE域）及83项边界检查。实际API36记录后半段是否可接受，仍须新冻结源码的一轮32项结果；本地合成样本不能代替设备证据。

独立复核250组完整WMS比较、770断言通过，20处变化均属于旧SIZE域；8条v2详情的完整标记5923字符。[独立采集审查](diagnostics/notes-ime-attrs-scan-2026-10-31/independent-collector-review.json)记录实际worker截断拒绝等边界。分析器44项检查通过，72f/9b/355三轮各32项及原marker逐值回放一致，旧355仍按8192限制解释。构建/lint通过（44秒），见[本地回执](diagnostics/notes-ime-attrs-scan-2026-10-31/local-validation.json)。

修正源码固定为`cdea60c126af3cb50805f6e989eccbf7a7a1efb1`，独立完整32项[41178248694](https://github.com/ecojust/Agent-OS/actions/runs/41178248694)及整体录像[41178250336](https://github.com/ecojust/Agent-OS/actions/runs/41178250336)已完成，结果如下。见[修正来源绑定](diagnostics/notes-ime-attrs-scan-2026-10-31/source-runs.json)；README保留355实际成功影片，另列修正轮失败短片，不把旧片标为新版本。

冻结后补齐的[宿主交叉验证回执](diagnostics/notes-ime-attrs-scan-2026-10-31/host-analysis-validation.json)绑定同一helper与分析器摘要：真实Java解析器输出的长闭合、长未闭合、后段重复type和直接超输入四样本均被宿主正确解释；旧三轮96项/24原始诊断通道逐值回放一致。这里只增加验证原件，运行中的测试源码保持cdea60c1。


## 修正轮最终结果

固定cdea60c1的[完整32项原件](../../screenshots/third-party-41178248694-1/manifest.json)仍为**24通过/8失败/0未验证**。[逐案分析](diagnostics/notes-ime-attrs-scan-41178248694/findings.md)与[独立原件复核](diagnostics/notes-ime-attrs-scan-2026-10-31/independent-original-review.json)一致：56载荷2,538,233字节、32安装身份/设置恢复、32PNG及32Notes受保护截图记录均通过校验；8项Notes成功用例的新进程全文读回通过。所有32项状态/主错误和原八项失败的32份隐藏样本字段与355轮相同，动态时刻和对象文本不跨运行比较。

| 子项 | 状态 | 实测结果与剩余边界 |
| --- | --- | --- |
| API36属性拒绝原因定位 | 已完成 | 355两态均为独立扫描8192上限；非本轮尾部混入，也非原Notes失败根因 |
| 完整捕获范围解析修正 | 已完成（本轮验证） | cdea两态11 parsed/0 unparsed，schema2显式262144；唯一IME及owner/display/package检查通过 |
| API36/37后续候选关联 | 进行中 | 两API各两态完整可见/显示分组/最终清单匹配均0/0/0；具体筛选或读取间变化未定位 |
| 原八项隐藏准备失败 | 进行中 | 原结果、5秒期限、4次隐藏采样、BACK/确认/HOME次数保持；不能根据后置结果放行 |
| 整体录像修正轮 | 失败，已归档 | ready阶段辅助服务未连接，0/13阶段；未重录，保留355完整演示 |

新API36两态WMS全部11条属性可解析，详情entries为空，之后Populator可见4项/1个显示组、最终3对；API37为10条属性、可见3项/1显示组、最终2对，两者通知均开启。八个原失败现均可选中WMS文本候选；API31/32后续接口仍不支持，null保持不可用，不能变成零。全扫描成功说明本轮格式通过源码条件；它不恢复旧355丢弃的完整dump、旧闭合位置或类型文本。

八次后置读取均在原失败冻结后0–3毫秒开始，总耗时28–50毫秒，三条命令分别15–30/5–9/6–11毫秒，无截断或调用方超时。完整dump不保留，不能独立复算已丢弃的原字节指纹；原instrumentation/marker摘要、固定源码和字段一致性可核验。后置有序文本仍不是同一原子快照、强Binder身份或像素隐藏证明。

下一步先按[过滤前接口研究](diagnostics/notes-ime-attrs-reasons-2026-10-31/upstream-source-review.md)完成独立API37类/成员访问能力探查，核对可用字段和同进程身份关联来源；不直接注册listener、不新增原矩阵读取或修改隐藏API策略。API36同类候选缺口、31/32旧接口限制、原八项失败、执行态/升级/扩样/真实模型和真机验收均未关闭。

## 修正轮录像失败原件

cdea整体录制41178250336在`ready`阶段报`Accessibility service did not connect`，完成0/13阶段、0次确认；原设置读到enabledServices=null，已启用/绑定/绑定中/崩溃服务均为空，为什么为空尚未定位。仅有[11.308秒桌面短片](../../screenshots/demos/41178250336-1/demo.mp4)，193帧、69,693字节，全帧解码通过；8载荷88,482字节、0PNG，唯一截图因桌面不是批准测试页面而blocked。它不证明任务过程通过。

[原清单](../../screenshots/demos/41178250336-1/manifest.json)、[视频审核](diagnostics/notes-ime-attrs-scan-2026-10-31/overall-video-audit.json)和[失败分析](diagnostics/notes-ime-attrs-scan-2026-10-31/overall-video-failure-analysis.json)已归档。app/Fixture/演示脚本/工作流与355成功源相同，新增compat-smoke后置诊断不在这个演示方法中执行；不能把本次服务未连接归因为扫描修正。按既定约定不为得到成功影片重录、不阻塞已验证诊断模块，README保留355的36.571秒完整演示与72f真实APK录像实际来源。
