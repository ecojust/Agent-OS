# 本地 APK 升级预检（开发者 CLI）

这是 [R9](../DESIGN_RULES.md#r9-更新迁移与恢复) 中签名连续性与版本方向的一个离线检查入口。它检查操作者提供的两份本地 APK；`baseline` 不表示已从设备读取或已证明安装的版本。通过仅表示本页列出的文件、签名和清单条件成立，不代表正式发布、可安装、配置迁移、真机或 ROM 升级已验收。

脚本不安装 APK，不启动应用，不联网，不读取应用配置，不修改输入 APK。没有新权限、应用 UI、密钥管理或安装流程。正式签名私钥仍须独立管理；测试使用临时证书不能替代正式发布证据。

## 使用

需要 POSIX 开发环境、Python 3.9+、可信本地 Android SDK Build Tools（已测试 35.0.0，含 `apksigner`、`aapt2`）及 SDK 所需 Java。工具路径、SDK 与运行环境由操作者信任，不从 APK 选择可执行程序。

```sh
python3 scripts/check_apk_upgrade.py \
  --baseline /local/previous.apk \
  --candidate /local/candidate.apk \
  --trusted-cert-sha256 "$AGENTOS_TRUSTED_CERT_SHA256" \
  --apksigner "$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner" \
  --aapt2 "$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt2"
```

`--trusted-cert-sha256` 必须是从独立可信发布记录取得的 64 位十六进制证书 SHA-256；不能因为两个包签名相同，就把其中一个包的证书自动设为可信。该参数不是私钥。不要把未知下载包自己的签名输出当作独立信任来源。

成功返回码为 `0`，标准输出为 JSON。检查拒绝返回 `1`，只输出固定 `reason`，不转发 SDK 的任意错误文本。命令参数错误由 argparse 返回 `2`。输入路径、应用清单原文和 SDK 原始输出不进入检查报告。

## 通过条件

两份输入分别复制到权限 `0700` 的私有临时目录，快照文件仅所有者可读（`0400`）。后续 ZIP、`apksigner verify --verbose --print-certs -Werr` 和 `aapt2 dump xmltree` 全部读取同一个快照，检查前后再次核对该快照的字节数与 SHA-256。复制时观察到源文件大小、mtime 或 ctime 改变会拒绝；这不承诺提供任意并发写入情况下的原子文件系统快照。报告的身份属于实际核验的副本，输入之后再被替换不改变该报告，也不授权使用替换后的文件。

必须同时满足：

- 两包真实 SDK 验签成功且各只有当前 `Signer #1`；两者证书均精确匹配显式 pin。证书变化不通过，也不会根据 lineage 自动放行。报告 `lineageEvaluated=false`：当前证书相同的包可能含历史 lineage，本工具没有验证或批准签名轮换。
- 两包实际根清单的 package 均为 `dev.agentos.app`，`minSdkVersion=30`，明确的 target SDK 不低于 30。SDK/ABI 变化的真实兼容性未由此证明。
- 两包 `versionCode` 为正的 32 位有符号整数，候选严格大于基线；不按 versionName 比较。非零 `versionCodeMajor` 不支持。
- 只支持独立 APK，拒绝根 split/feature/config split/requiredSplitTypes/splitTypes 声明、`uses-split`，以及 application 的 `isSplitRequired` 声明。即使某声明是空值或 false，此最小版本也保守拒绝。AAB、APK 集合、拆分安装均不在范围内。

报告记录两包的字节数、SHA-256、固定包名、版本、SDK 门槛、当前证书摘要和实际 SDK 工具版本。它不检查 APK 是否来自某个源码提交，也不证明应用功能、安全权限或配置迁移正确；这些继续由已有构建、产物审计、回归和实际升级验收承担。不要在检查后重新构建另一份 APK 并沿用旧报告。

## 有界处理

| 项目 | 固定上限或规则 |
| --- | --- |
| 每个输入 | 非空普通文件，最多 64 MiB；最终路径分量不得是符号链接 |
| ZIP 目录 | EOCD 前检；最多 4096 项，中央目录最多 1 MiB；拒分卷、ZIP64 目录/中央目录 ZIP64 元数据、尾随数据和重复名称 |
| ZIP 内容 | 不解包到文件系统；仅 stored/deflate；拒加密；声明展开总量最多 256 MiB |
| 根清单 | 唯一 `AndroidManifest.xml`，展开最多 1 MiB；实际读取验证压缩/CRC，拒不完整或歧义的文本转储属性 |
| 每次 SDK 调用 | 固定 30 秒；stdout + stderr 实时累计最多 1 MiB；不使用 shell 字符串执行 |
| 退出清理 | 终止该调用进程组，额外最多等待 2 秒；清理失败不掩盖原检查失败 |

临时快照在正常退出及受处理异常时删除。操作系统强制终止进程可能留下临时文件，操作系统临时目录清理策略仍适用。输出与等待边界不等于 SDK 内部内存、CPU 或解析漏洞的沙箱；只使用可信、维护中的本地 SDK。上限不对每种第三方 APK 格式作通用兼容承诺。

## 本地验证

```sh
python3 -m unittest discover -s scripts -p test_check_apk_upgrade.py -v
```

测试分为纯边界检查和真实 SDK 集成。真实集成临时生成两套测试证书，用 `aapt2` 编译最小二进制 Android 清单，再由实际 `apksigner` 签名与验签。覆盖正常版本增长、错误 pin、不同/多个签名者、已签名内容被篡改、错误包名、相同/降低版本、split、非零 major、提高最低 SDK、清单多行字符串、源文件替换和快照变更。边界测试覆盖输出洪水、超时、子进程保留管道、固定错误、损坏压缩流及 ZIP 声明上限。测试不安装这些合成 APK。

默认查找 `$ANDROID_SDK_ROOT`（或 `$ANDROID_HOME`）下 Build Tools 35.0.0 与 android-35；也可用 `APK_PREFLIGHT_BUILD_TOOLS`、`APK_PREFLIGHT_ANDROID_JAR` 显式指定本地工具。该仓库开发容器另提供 `/workspace/android-toolchain/sdk` 默认路径。缺少 SDK 或 keytool 时，真实集成组标为 skipped，不能把这种结果说成完成验签验收。完整CI的脚本检查已移到SDK准备步骤之后，确保Build Tools 35.0.0与android-35先安装，再运行包括本模块的脚本用例；精确源码3eb的完整CI [40736683857](https://github.com/ecojust/Agent-OS/actions/runs/40736683857)已实际执行整套336项（原314+本模块22），62.467秒、零失败/错误/跳过；[原始结果](diagnostics/numeric-preferences-40736683857/README.md)与设备侧失败分开保留。

后续正式发布仍需可信正式证书/产物来源、同一产物的设备安装与升级、配置保留/拒绝与恢复、任务终止及不自动续跑等证据；ROM 还需要明确机型与恢复流程。本模块不会自动推进这些验收结论。

本次本地实际执行为 **22/22通过，22.090秒，零失败、错误或跳过**；真实SDK集成组确实执行。两份脚本与独立审查冻结逐字节相同，[原始测试日志、实际正例报告及输入摘要](diagnostics/apk-upgrade-preflight-local/README.md)已归档。没有重跑未改的核心333项或原314项脚本，也没有运行设备安装测试；纯CLI验证没有UI截图。源码518621d4的唯一整体应用录像已录制并归档，35.867秒、444,016字节；[录像及范围](operation-demo.md#离线apk预检源码的整体演示)只覆盖普通Fixture应用流程，不替代此CLI的实际验签报告。

后续测试驱动源码eee60的完整CI [40745270296](https://github.com/ecojust/Agent-OS/actions/runs/40745270296)已执行新的342项脚本，58.431秒、零失败/错误/跳过；增加的六项属于仪器测试执行边界，本CLI仍为原22项。[原始步骤](diagnostics/numeric-preferences-40745270296/build-script-tests.original.txt)与设备侧六项失败分别记录。

最新24b9的 [40750181658](https://github.com/ecojust/Agent-OS/actions/runs/40750181658)再次实际完成相同342项脚本，68.718秒、零跳过，本CLI代码及22项范围不变；[原始步骤](diagnostics/numeric-preferences-40750181658/build-script-tests.original.txt)已归档。该轮八档设备2160项也全部通过，仍不替代本工具范围之外的真机安装、升级或正式签名验收。
