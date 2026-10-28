# Android CI 的代码检出范围

Android完整工作流的构建任务及八档设备任务现在只检出 `.github`、`app`、`core`、`fixture`、`compat-smoke`、`docs`、`gradle`、`scripts` 八个目录。checkout v4 的cone模式自动保留根目录文件，包括Gradle入口、README与配置。测试结果仍写入本次新目录并上传；历史截图继续留在仓库与原提交中，证据发布工作流没有改动。

在固定源码 `3eb74b5d5119738ba8e3c560ed8b078df223bba1` 中，历史 `screenshots/` 包含186,583个Git树文件、2,144,992,421字节；其余407文件共5,943,146字节。这是Git树未压缩内容大小，不是网络下载体积或节时测量。checkout v4 当前实现会在指定sparse范围、未显式设置filter时使用 `blob:none`，并在检出前设置稀疏规则；树元数据仍会取得，Git可按需获取其他对象。本次不改源码ref、fetch-depth、权限或原凭据设置。

保留的目录具有实际依赖：进度检查需要 `docs/progress.json` 与SVG；兼容性脚本使用文档中的例子及锁定清单；脚本测试会读取 `.github/workflows/third-party-smoke.yml`。Gradle包含app、core、fixture与compat-smoke四个模块，不能只保留app/core。脚本中的历史截图名称用于临时合成fixture或新输出，没有依赖仓库内旧截图文件。

从上述精确源码创建独立稀疏工作树后，确认八目录及根文件完整、历史截图目录不存在，407个输入前后Git内容一致。实际一次脚本检查 **336/336通过，零失败、错误或跳过，49.024秒**，包括原314项及离线APK预检22项，其中11项真实SDK集成用例确实执行；进度检查也通过。[原始日志、逐项结果与清单](diagnostics/android-sparse-checkout-local/manifest.json)已保留。Windows批处理文件按既有 `.gitattributes` 的CRLF规则核对Git内容，没有将正常行尾转换误判为源码变化。

本地验证证明文件集合满足这些脚本依赖，不代表已经测得远端检出耗时改善。没有为此重复核心、Gradle或设备测试；远端稀疏检出将随下一次有实际源码修复需求的完整回归验证，已完成的3eb运行40736683857使用其原工作流，不能算作远端稀疏检出验证。

后续精确源码eee60的 [40745270296](https://github.com/ecojust/Agent-OS/actions/runs/40745270296) 已实际使用新配置：九个任务均记录 `blob:none`、八目录的 `sparse-checkout set` 及eee60检出，[连续原始记录](diagnostics/numeric-preferences-40745270296/README.md)已保留。构建任务成功，脚本新分母342项在58.431秒内通过且零跳过；八档设备各完成270项。设备总结果仍为2154通过、6失败，具体UI断言另见该轮报告，不把检出成功称为全套验收，也没有进行可归因于检出方式的耗时对照实验。

随后24b9的 [40750181658](https://github.com/ecojust/Agent-OS/actions/runs/40750181658) 沿用相同工作流和执行脚本，九个任务全部成功：脚本342项通过、零跳过，设备2160/2160通过。[本轮原始记录](diagnostics/numeric-preferences-40750181658/README.md)补齐结果，前轮失败保留；仍不作网络体积或节时的因果比较。
