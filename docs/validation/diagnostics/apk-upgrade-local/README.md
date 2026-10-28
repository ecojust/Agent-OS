# APK 原地升级模块的本地构建记录

这是本地构建与实际 SDK 预检，**没有安装、设备执行或升级通过结果**。远端固定八档的实际结果另行归档。

固定旧源码 `b1fd337262775a505327088975a3320fe323f034` 在独立稀疏工作树构建真实旧APK，13秒成功。候选与独立探针的前两次本地构建分别因测试插件没有 `lintDebug` 任务、目标app的core实现依赖未进入独立测试编译类路径而失败；原日志保留。修正为对app执行lint，并为探针显式声明core为 `compileOnly`，运行时仍调用已安装产品中的core实现。

最终一次构建34秒通过：app APK、Fixture APK、独立升级探针APK及app lint。222个Android/Gradle输入前后摘要一致。app lint仍为0错误、54警告；AGP的独立 `com.android.test` 模块没有生成lint任务，不宣称本次对该模块执行过lint。实际编译清单确认探针包为 `dev.agentos.upgradesmoke.test`、目标为 `dev.agentos.app`，使用固定AndroidJUnitRunner且为testOnly。

[实际预检报告](actual-product-preflight.json)来自上述两份完整产品APK，实际版本1→2，包名与SDK门槛匹配，真实apksigner校验各一个当前签名者且匹配独立导出公开证书的摘要。开发签名仅在本地临时使用，不能称正式签名发布验收。公开证书与工具原记录保留，私钥和APK二进制没有提交；产物大小与摘要见[清单](manifest.json)。

两份本地产物的[ZIP条目比较](product-apk-entry-comparison.json)显示四个实际DEX均逐字节相同，仅编译清单条目不同；签名块由前述实际SDK预检分别验证。这与本轮只递增产品版本、不修改生产Java或配置格式的范围一致，不能推广为其他构建产物也相同。

本目录所有标为original的文件均按原字节复制，没有把构建失败改写成通过。纯构建、lint和离线文件检查没有UI截图；受保护设备页面的采集政策不由本地构建验证替代。

新宿主脚本首轮34项中19失败，原文见[首次记录](host-tests-first.original.txt)；修复成功行换行解析和合成元数据预算并增加边界回归后，[最终35项](host-tests-final.original.txt)在3.581秒全部通过、零跳过。归档器先在当时宿主上完成31项（原日志保留），随后在最终宿主上[实际复核31项](archive-tests-final.original.log)，0.381秒全部通过、零跳过。源文件SHA见清单，不将不同宿主版本的测试记录混用。

[真实辅助APK SDK检查](actual-helper-sdk-check.json)使用最终宿主代码读取本地实际Fixture/探针产物，验证独立证书pin、包名，以及探针真实runner与targetPackage；没有伪造CI环境或连接设备。
