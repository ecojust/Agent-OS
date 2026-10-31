# Notes 原矩形诊断实跑：41165280718 / attempt 1

固定源码 `33ac5825bc7537b9a67c9daeeee6916da12256d6`，原四方法×八档结果为 **24通过、8失败、0未验证**。这是本轮独立32项；不与另一轮合并通过率。Calculator16/16，Notes8/16。

## API30：未复现，尚无真实失败矩形

本轮API30的Notes关闭/空闲两态均完成原创建、保存与新进程完整标题/正文读回，`NOTES_TARGET_GEOMETRY_DIAGNOSTIC_V1`在所有八档原instrumentation中均为0。新代码只在原完整包含断言失败时附加诊断；本轮没有取得真实失败矩形，不能声称修复了历史边界问题或用成功截图重建旧矩形。

对最新旧批41163144860/source435d逐32组合比较：23项继续通过、8项继续失败，API30 Notes关闭态由旧失败变成本轮通过。旧批的标题输入前完整包含失败仍保留，见`historical-comparison.json`；这一状态变化只表示本轮未复现，不是行为修复证据。

## 原八项隐藏失败仍存在

八条原确认诊断各出现一次、每条四个原隐藏WMS样本，共32次；geometry与confirmation未绑定记录均为0，结构/时序/身份一致性检查无异常。API31/32两态BACK一次且按下/抬起被接受、visible2/hidden4；API36/37两态BACK0、visible0/hidden4。各项确认提交与HOME均0。

| 原字段 | API31/32的16次样本 | API36/37的16次样本 |
| --- | --- | --- |
| isOnScreen | TRUE / count 1 | TRUE / count 1 |
| isVisible | FALSE / count 1 | TRUE / count 1 |
| isReadyForDisplay() | FALSE / count 1 | TRUE / count 1 |
| mLastHidden | FALSE / count 1 | FALSE / count 1 |
| mEnterAnimationPending | FALSE / count 1 | FALSE / count 1 |
| mAnimatingExit | MISSING / count 0 | MISSING / count 0 |
| mHasSurface | TRUE / count 1 | TRUE / count 1 |
| Surface: shown | TRUE / count 1 | TRUE / count 1 |

API36/37的原BACK决策清单、四次隐藏前清单和最后清单均完整扫描且IME=0，没有BELOW_FOCUSED证据。API31/32决策有一个较高层IME候选，后续隐藏前/最后清单均IME=0。WMS与窗口清单属于不同先后读取，未记录可直接关联的共同对象标识；不能据这些字段认定像素、父层、缓存分支或平台Bug。

原四次样本包装最晚在准备START+2289ms结束；固定额度耗尽后没有追加WMS。没有BACK精确时间戳，不能换算成BACK后耗时或称连续WMS观测满五秒。完整原JSON、raw SHA及逐案时间均在`confirmation-analysis.json`；几何marker扫描和未绑定计数在`geometry-analysis.json`。

## 来源与载荷复核

归档运行 `41165622248`，证据提交 `a035367405d4753f59c77d3f4b0f8259fdc24d83` 的父提交为精确测试源码。仅恢复`screenshots/third-party-41165280718-1`，没有恢复归档README。

原件56载荷、2,511,195字节、32张320×640计算器PNG及32条Notes blocked。全部文件大小/SHA、PNG解码、原四方法、官方APK来源/锁/安装身份、原service状态与恢复，以及GitHub source/job/artifact身份均核验通过。16次官方APK安装、32次安装身份、32次设置恢复、8项Notes完整新进程读回分别有原证据。安全标志保持。

完整来源/逐案审核为`archive-audit.json`，归档源绑定为`archive-source-binding.json`；它们证明记录与范围一致，不把失败判成通过。当前没有执行态、升级、扩样、真实模型或真机验收。

另实际查看本轮API30计算器关闭态结果PNG，可见原界面的2+3及5；此图仅为画面核对，安装和Notes持久化仍依据原回执，见`visual-check.json`。
