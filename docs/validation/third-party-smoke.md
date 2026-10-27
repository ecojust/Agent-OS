# 第三方应用开发探针

此探针使用未修改、未重新签名的官方 Calculator 1.4.0 APK，在专用模拟器上检查安装、桌面入口启动和 `2 + 3 = 5`。它补充合成应用测试，不属于常用应用总体兼容率、升级、真机或正式发布验收。

## 固定范围

- 安装包来源、版本、字节数、SHA-256、签名证书、API 和 ABI 固定在 [样本清单](third-party-calculator-lock.json)。下载、安装前和设备内的身份分别校验；不接受同名但不同签名的应用。
- API 30、35、37 三个环境；API 37 固定 Emulator 37.2.12，并在运行时复核版本。
- 每个环境保留两态结果：Agent 服务关闭，以及服务实际绑定但没有任务。测试读取实际绑定状态，不能只凭设置项判断开启成功。
- 独立测试 APK 通过公开测试接口操作算术界面，不压制无障碍服务，不增加 Agent 生产权限。没有账号、私人数据或支付操作。
- 分别记录每一步、真实结果、截图尝试、失败和未运行状态。截图只来自已核对身份的合成算术场景；保护窗口、未知前台或采集失败如实登记，不移除安全标记。
- Agent 执行态、正常更新及数据保留、真实手机和其他应用仍是未验证项。

## 构建与运行

```bash
./gradlew :compat-smoke:assembleDebug :compat-smoke:assembleDebugAndroidTest :compat-smoke:lintDebug
python3 -m unittest discover -s scripts -p 'test_third_party_smoke*.py'
```

在 master 上手动运行 `third-party-smoke.yml`，选择 `mode=test`。驱动仅允许该工作流的全新专用模拟器，拒绝连接个人手机或操作已经安装目标应用的设备。

测试结束后，以同一工作流的 `mode=archive` 和 `run_id` 归档。归档器核对源码、运行轮次、固定矩阵和文件校验值，保留失败、缺失及受保护截图，输出 `screenshots/third-party-<run>-<attempt>/` 和独立证据分支；审核后再合入主分支。

本模块首次本地验证：测试 APK 构建及 lint 通过，51 项探针/归档 Python 测试通过。设备结果需以实际运行及归档为准。
