# API37 硬键盘显示设置对照

本轮承接[后置输入法观测](window-infos-ime-state.md)：AVD文件报告 `hw.keyboard=no`，实际输入法收到 `qwerty/v/v`，硬键盘下显示软键盘缓存为FALSE、输入视图未显示。冻结一次同模拟器的A→B对照，核查该设置改变后原始窗口正对照是否出现，同时归档运行时输入设备与AVD原文件。

## 冻结执行合同

仅使用专用API37合成模拟器，用户0、shell UID2000。预先只读保存AVD配置文件，要求唯一 `hw.keyboard=no`；运行一次有界 `dumpsys input`（服务5秒、设备8秒、宿主12秒、262144字节），再用 `settings --user 0 list secure` 保存原设置的存在性和值。

只支持 `show_ime_with_hard_keyboard` 原本缺失或为字符串 `0`。原值为1或其他内容时保留未完成原因，不改写。list必须完整、非空、键唯一，并含有效的 `default_input_method` 上下文；与A选择的组件及后续读回核对。空输出即使exit0也不构成缺失证据，不能将 `settings get` 的字符串 `null` 直接视为键不存在。配置文件与输入设备、IME Configuration来自不同层；未打印的字段保留未知。

| 组别 | 设置与执行 | 必须保留的结果 |
| --- | --- | --- |
| A baseline | 原设置；完整执行一次既有raw流程 | 原始正对照通过或失败、完整回调、一次show回执、后置IME状态、PNG及清理结果 |
| B enabled | 临时设置为1并读回核对；相同APK/jar执行一次既有raw流程 | 同样保留全部原件；正对照、IME缓存TRUE、输入视图显示分别核对 |
| 恢复 | 原缺失则delete，原0则put0；各执行一次，再读回 | 同时核对原值和存在性；失败、取消或未知不能写成恢复成功 |

A只在完整取得原始结果、页面回执、IME状态与截图、清理成功，且IME缓存FALSE时才进入B。A允许的原始失败仅为 `RAW_POSITIVE_CALLBACK_NOT_OBSERVED`；失败继续进入原始组别分母。两组复用未修改的 `window_infos_raw.py`，各一次构造/注册/注销、一次显示请求、12秒回调区间与四项过滤均保持。

A确认安装了本次合成包后，仅卸载该 `dev.agentos.compat` 并核对不存在，再运行B的原全新安装检查。两组APK及jar字节相同，每组独立核对安装身份，UID可能变化。固定顺序、输入法暖状态和重装仍是混杂因素，因此这不是随机交替实验，也不单凭一对结果证明原Notes失败的因果。

写设置前先持久化原值与写入意图，避免命令超时但已生效时漏恢复。正常路径在finally恢复；工作流finalize只在尚未尝试恢复时作一次兜底。已尝试过的恢复不重复写。取消状态保留，恢复自身使用独立的有限命令期限；若模拟器已销毁，保留恢复未观测状态。恢复承诺只包括逻辑值及存在性，不包括SettingsProvider标签、默认值或内部元数据。

## 证据与独立校验

顶层保留设置读写原命令、持久化journal、输入设备dump与派生结果、AVD原文件及原APK；每组保留既有raw完整目录，包括jar、原始回调、fixture、IME状态、命令、截图和报告。顶层最多16命令/2MiB输出，每组仍使用原40命令/2MiB上限，整树有效命令目录上限80、输出6MiB。

输入设备解析器只解释固定源码的EventHub与InputReader段，按实际ID关联设备，保留名称、来源、KeyboardType、外部/虚拟标志和启用状态。Reader/原生manager没有打印的keyboard、hardKeyboardHidden明确为 `not_printed`；不会从某个键盘能力反推出IME最终配置。虚拟设备ID与 `IsVirtualDevice` 字段也分别保留。格式、数量、关联或边界不符合时状态未知，仍可完成计划的两组采集，但顶层不标通过。

新归档单独使用 `window-infos-keyboard`，校验源workflow/job模式、原始来源、嵌套文件白名单、大小/摘要和每组原合同。原schema1/2归档保持原来源，禁止补写新观测。顶层对照结果与两组raw结果分别统计；即使B成功且恢复确认，A失败仍计为一组失败。

## 实施进度

宿主协调、输入设备解析、嵌套归档与专用CI接线已完成，[195项本地验证](diagnostics/window-infos-keyboard-2026-11-01/local-validation.json)通过：既有raw宿主/归档/构建74、IME33、新input解析26、新协调23及新归档39。归档其中10项使用真实协调校验器联合验证，覆盖A失败/B成功、两种原值恢复、恢复失败、输入未知及原件篡改；[具体回执](diagnostics/window-infos-keyboard-2026-11-01/archive-validation.json)保持合成数据的范围。actionlint与进度一致性检查通过，Android及产品代码未改。

本次设备对照和同源API35整体操作录像待源码冻结后各执行一次；API35影片只展示Fixture与本地模型普通流程，不冒充API37专项录像。原Notes32项不在本次执行范围，原24通过/8失败、API36后续适配与修复复测均未关闭。
