# API37 WindowInfos 公开 TestApi 能力探查：41180160267 / attempt1

源码 `214e90afba8481655ae33d5ad99a7d49b0a4557d`，独立测试运行 [41180160267](https://github.com/ecojust/Agent-OS/actions/runs/41180160267) 完成，单用例 **recorded 1/1**。这里的 recorded 表示完整取得预声明的能力回执，不是监听器、应用兼容或 Notes 隐藏准备验收。

## 真实结果

| 固定查找项目 | 数量 | 原始结果 |
| --- | ---: | --- |
| ForTest 及 WindowInfo/DisplayInfo 三个类 | 3 | FOUND_MATCHED |
| ForTest 公开无参构造查找 | 1 | FOUND_MATCHED |
| add/remove 的 Consumer、BiConsumer 公开方法 | 4 | FOUND_MATCHED；未调用 |
| WindowInfo/DisplayInfo 公开实例字段 | 14 | FOUND_MATCHED；未读取字段值 |
| inputConfig、isClone、frame、layoutParamsType、ownerUid | 5 | NOT_FOUND_OR_FILTERED |

27 个固定查找均尝试，前22项所需符号表面匹配。允许的 ForTest 无参构造随后只尝试1次，成功1次；该结果不证明注册、回调、取消注册或资源清理可用。`ACCESS_SURFACE_FLINGER` 检查结果为 **DENIED**。未注册监听器，未取得或读取任何窗口数据；没有额外隐藏 API 豁免请求、UiAutomation、权限收养、系统 dump 或截图。

五个缺口保留为 `NOT_FOUND_OR_FILTERED`，不区分符号不存在与访问过滤，不推断返回类型，不把这五项计入已匹配的必要表面。本轮没有发现完整输入标志、克隆标志、原始 frame、窗口类型或 owner UID 的可读取来源，也没有建立探针进程与 system_server 文本候选的强身份连接。

## 设备、时序与访问语境

实际设备为专用 `emulator-5554`、API37 / Android17、x86_64，Emulator **37.2.12**（build16428233），系统 build `CE2A.260420.051.A3`。只安装 `dev.agentos.compat` 与 `dev.agentos.compat.test` 两个本次构建 APK；安装前检查目标包、Agent 与 Notes 均不存在。APK 原字节SHA、aapt 包名/minSdk/targetSdk、安装成功输出、`pm path`、instrumentation 目标注册均保留并交叉核验。

测试进程 UID10156、targetSdk35，目标包和测试包与预声明一致。构建 fingerprint 的 SHA 与 getprop 原始值重算一致。27项查找加一次构造的进程内记录耗时 **6ms**，未达到5秒操作间检查期限；单次 `adb am instrument` 的主机收据为 **1,255ms / 7,442 bytes**，在60秒 / 256KiB限制内。

执行语境是默认 instrumentation 测试进程。没有传额外 hidden API/TestApi 调整 flags，但标准 instrumentation 本身具有源码定义的默认 TestApi 访问安排，因此这次结果不能推广到普通安装 APK 的访问能力，也不是对有效 ART 策略的直接测量。固定源码依据见[访问边界复核](../window-infos-capability-2026-10-31/source-capability-review.md)。

## 原件与验证

归档运行 [41180389001](https://github.com/ecojust/Agent-OS/actions/runs/41180389001) 成功；证据提交 `bdea5876cfd23d221de6e8d647f07df048bc75a6` 的父提交就是测试源码214e。只恢复[原始非视觉子树](../window-infos-capability-41180160267-1/manifest.json)，未覆盖 README，也未修改原manifest。此目录只存派生分析。

归档索引包含6份JSON载荷，合计 **71,561 bytes**：其中4份是原始测试载荷，另外2份是GitHub来源元数据；加manifest共7个git blob均逐字核验。17条固定命令全部完成，源/job/artifact/时间窗口、8个关键源码文件在214e的原字节SHA、两APK身份、所有载荷hash及原marker绑定均通过审计。

原instrumentation严格保留唯一目标方法 `WindowInfosCapabilityInstrumentedTest#inspectPublicTestApiSurface`，状态块顺序为 **1 → 2 → 0**，唯一marker位于显式绑定的2号块，最后终结码为 **-1**。没有借用其他case或“OK”文本。详见[原marker分析](marker-analysis.json)、[完整审计](archive-audit.json)和[结构化结果](findings.json)。GitHub artifact ZIP摘要仅为服务元数据，未把它声称为本地重下载ZIP验证；本地独立重算的是已归档原载荷和git blob。

本轮没有重新执行原32项矩阵，没有重写历史后置 IME 候选，也没有修复或改判原八项 Notes 失败。下一步仍需先解决可信身份连接、所需原始元数据与权限/注册边界，再决定是否值得设计独立的新探查；本次没有扩大为监听实验。
