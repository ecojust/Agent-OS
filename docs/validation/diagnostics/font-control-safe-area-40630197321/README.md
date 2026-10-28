# 控制窗安全区域：冻结几何诊断

源码 `f2ff7ce5f911c66509bfe72819ff2fa089038cfd` 的[字体专项40630197321](https://github.com/ecojust/Agent-OS/actions/runs/40630197321)只增加断言点数值诊断，原安全区域条件、字体与48dp检查、观察期限均保留。八档各1项，API30通过，其余七档失败；不能计为完整1968项回归。

本目录保留各API原 `Print Android failure details` 日志成员的连续摘录。七档失败从原Report行保留到原解析计数行；API30只保留原零failure/error摘要行。时间戳、内容、换行及原有行尾空格保持原字节（Git属性仅对此目录的原日志关闭文本归一化与行尾空格格式提示）；[清单](manifest.json)记录准确源运行／源码／job URL、原日志ZIP和各成员的字节数与SHA-256、原成员内1起始行号、摘录校验值及对应数值解析。API30的实际通过结论来自原始归档逐案记录与采集JUnit摘要交叉核验，不能仅从零failure摘要推算。

这不是独立下载或重解析的XML，也不是完整原JUnit报告。下列数值来自失败断言前同次主线程元数据快照；API30没有失败几何日志，不补造其数值。

| API | metrics bounds | metrics stable／visible insets (left,top,right,bottom) | safe rect | panel rect | metrics status／navigation visible |
| --- | --- | --- | --- | --- | --- |
| 31 | 0,0,320,640 | 0,24,0,0 | 0,24,320,640 | 12,12,308,124 | true／false |
| 32 | 0,0,320,640 | 0,24,0,0 | 0,24,320,640 | 12,12,308,124 | true／false |
| 33 | 0,0,320,640 | 0,24,0,0 | 0,24,320,640 | 12,12,308,124 | true／false |
| 34 | 0,0,320,640 | 0,24,0,0 | 0,24,320,640 | 12,12,308,124 | true／false |
| 35 | 0,0,320,640 | 0,24,0,48 | 0,24,320,592 | 12,12,308,124 | true／true |
| 36 | 0,0,320,640 | 0,24,0,48 | 0,24,320,592 | 12,12,308,124 | true／true |
| 37.0 | 0,0,320,640 | 0,24,0,0 | 0,24,320,640 | 12,12,308,124 | true／false |

七档均在 `baseline_configuration`、`actualScale=1.0` 时失败，尚未进入1.3变化阶段。`rootInsetsPresent=true`，但root stable／visible四边均为0，root status／navigation visible均为false；panel、title、pause、stop四个View均attached=true、laidOut=true、layoutRequested=false、shown=true。面板顶部12小于metrics安全区域顶部24；记录显示已布局面板与该安全区域条件不符。

这些记录保留global metrics与根View insets的差异，不能据root零insets取消原安全区域约束，也不单独证明平台差异的全部根因。后续生产修复与设备复验另行记录，不覆盖本次失败。
