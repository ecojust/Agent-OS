# 文件选择动作修复的本地验证

隔离工作树基于eee60，只修改冻结的 `NumericPreferencesInstrumentedTest.java`。实际仅执行 `:app:assembleDebugAndroidTest :app:lintDebug`，59秒成功；[原始日志](build.original.txt)和[源文件/产物清单](manifest.json)保留执行身份。

225个Android/Gradle输入前后相同，并在集成时逐一匹配主分支工作树。lint为0错误、54警告，对上一轮无新增或移除。实际测试APK为6,054,985字节；[编译清单原输出](test-apk-manifest.original.txt)与上一轮逐字节一致。APK大小变化不构成运行性能结论。

没有重跑未修改的342项脚本或核心333项，也没有在本地运行设备测试。六个业务测试体、270项/API、生产代码和安装脚本均保持；新的点击准备方式能否消除失败，待精确源码设备回归。
