# 控制条拖动专项：原始失败现场

源码 `e99ef4a403246ab6a64ee9e2fad1ab2680e1bf02` 的[专项运行](https://github.com/ecojust/Agent-OS/actions/runs/40539311569)仅选择一个既有用例，在八个 API 环境执行，共5项通过、3项失败。它不替代完整1936项回归。

三档失败均在原5秒登记断言结束后、清理窗口前记录。下表是原始有界 JUnit 摘要中的实际读数；各列顺序采样，不是原子快照。

| API | 本地 View／根节点刷新前后 | 无障碍窗口表 | WMS 唯一候选 frame |
| --- | --- | --- | --- |
| 30 | `[12,48][308,138]` | `[12,36][308,126]` | `[12,48][308,138]` |
| 31 | `[12,24][308,114]` | `[12,12][308,102]` | `[12,24][308,114]` |
| 32 | `[12,24][308,114]` | `[12,12][308,102]` | `[12,24][308,114]` |

本地控件均已附着、可见且没有待执行布局，平移为0、缩放为1；本地登记仍指向相同实际窗口 ID，根节点的 ID 和包名匹配。WMS 数据在完整读取后按固定合成标题、应用 UID 和窗口类型关联唯一候选；此关联仅作诊断，不能证明跨进程 token 相同，也不参与授权。

这次直接观察到窗口表几何落后于实际 View 和根节点。早前报告只记录 `rootRefresh=true`，没有根坐标，不能用本次结果补推早前每次根节点的位置。当前失败仍保留，未通过延长期限或忽略几何检查转为成功。

`api-*-annotations.json` 是原 GitHub Checks 响应；`api-*-summary.txt` 按诊断分块序号还原原始摘要。[清单](manifest.json)记录测试源码、源任务、原报告 artifact、检查运行及各文件校验值。[截图与原测试载荷](../../../../screenshots/40539311569-1/)保留在独立原始归档中。

官方固定版本源码提供了与实测一致的解释：旧版[窗口移动通知](https://android.googlesource.com/platform/frameworks/base/+/android-12.1.0_r1/services/core/java/com/android/server/wm/WindowState.java#2297)受内容变化条件控制；[服务端窗口查询](https://android.googlesource.com/platform/frameworks/base/+/android-12.1.0_r1/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java#1694)不保证重新计算已有窗口表；新版增加了[窗口信息监听路径](https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/services/core/java/com/android/server/wm/AccessibilityWindowsPopulator.java#259)。本轮没有追踪系统内部条件位，不能声称已经证明具体执行过哪个源码分支。
