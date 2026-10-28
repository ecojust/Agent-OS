# NumericPreferences 第五轮成功运行原始记录（40750181658）

对应[原运行](https://github.com/ecojust/Agent-OS/actions/runs/40750181658)，源码 `24b9b44c70eff0e5afd66e58e87031160a6609ec`，attempt 1。build及八档emulator job均完成并报告success；八档各有270例开始/完成，共2160，原分母及8个排除身份不变。八份Print均报告一个解析报告、零failure/error。**不可变归档逐案核验确认2160/2160通过，0 failure/error/skip/缺失；本轮固定全套通过。** NumericPreferences六例×八档48/48通过，其余原2112例亦全部通过。

计数来自[不可变归档总清单](https://github.com/ecojust/Agent-OS/blob/d0d1170765498c79a28e9cc2572faec3145ba396/screenshots/40750181658-1/manifest.json)与八档逐案账本；每档完整270个身份同时匹配测试运行绑定和独立冻结的源码集合。归档工作流为40752795110，提交 `d0d1170765498c79a28e9cc2572faec3145ba396`，parent恰为测试源码 `24b9b44c70eff0e5afd66e58e87031160a6609ec`。JUnit数字来自归档的采集端解析摘要，本目录没有独立下载原JUnit XML，也不以日志和账本核对替代另项完整载荷/PNG审计。

[manifest.json](manifest.json) 记录原ZIP、每档Exercise成员的字节数与SHA256、Starting/Finished原行位置及各行字节hash。八份 `api-*-report.original.txt` 只保留各自原 `Parsed 1 Android reports; found 0 failure/error entries.` 行；零失败报告没有可摘录的失败栈。所有保存的日志片段均连续、按原字节复制，未拼接、过滤或改变行尾。

| API | 原零失败打印 |
| --- | --- |
| 30 | [原文](api-30-report.original.txt) |
| 31 | [原文](api-31-report.original.txt) |
| 32 | [原文](api-32-report.original.txt) |
| 33 | [原文](api-33-report.original.txt) |
| 34 | [原文](api-34-report.original.txt) |
| 35 | [原文](api-35-report.original.txt) |
| 36 | [原文](api-36-report.original.txt) |
| 37.0 | [原文](api-37.0-report.original.txt) |

[脚本测试完整原步骤](build-script-tests.original.txt) 第19–21行实际报告 `Ran 342 tests in 68.718s`、`OK`，原进度为342个点、无skip计数。它是本次CI执行记录，未打印逐案身份；不与前几轮或本地运行耗时混用。

[API30安装至测试开始原段](api-30-install-through-start.original.txt) 连续保留Exercise第295–426行：固定“仅安装测试APK、force-queryable”说明、`Performing Streamed Install`、`Success`以及后续 `Starting 270 tests`。该段没有回显完整adb argv；固定命令另见[同一测试源码第129–140行](https://github.com/ecojust/Agent-OS/blob/24b9b44c70eff0e5afd66e58e87031160a6609ec/scripts/run_android_tests.sh#L129)，作用对象仅为 `app-debug-androidTest.apk`。原段未补写参数，不将测试安装覆盖状态当作生产APK权限变化或通用可见性保证。

本轮 `.github/workflows/android.yml` 与 `scripts/run_android_tests.sh` 对第四轮源码 `eee60b1b273e458db92f3ed2391ee5a84d63e2fb` 均逐字节相同，源码字节数和hash列于manifest；不重复保存九份checkout原段。[第四轮诊断](../numeric-preferences-40745270296/README.md)已单独保留当轮实际稀疏检出命令。本目录不据此宣称性能benchmark或固定节时。

[第四轮](../numeric-preferences-40745270296/README.md)、[第三轮](../numeric-preferences-40736683857/README.md)、[第二轮](../numeric-preferences-40728782554/README.md)及[第一轮](../numeric-preferences-40719564182/README.md)失败继续保留。本次未复现不等于此前每项失败的历史因果已被证明；模拟器结果也不替代真机、真实模型或性能预算验收。
