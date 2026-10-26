# 应用兼容性验收记录工具

`scripts/app_compatibility.py` 将[应用兼容性方案](../APP_COMPATIBILITY.md)中的固定样本、三态结果和统计口径落实为可审查记录。只使用 Python 3.10 及以上的标准库，不下载、安装或操作任何应用。

工具验证记录结构、文件引用和统计，**不证明应用真的运行过，也不授予真机、ROM、性能、授权或发布认证**。设备、安装包官方来源、操作者、方法和结果均由记录人声明，须人工核对原始证据。目前没有已验收第三方应用清单。

## 操作流程

```bash
# 1. 先填写并审查样本，再冻结；输出文件必须尚不存在。
python3 scripts/app_compatibility.py freeze \
  --sample docs/validation/examples/app-compatibility-sample.json \
  --output /tmp/app-compatibility-frozen.json

# 2. 即使未导入任何结果，也会按完整固定样本统计全部未验证项。
python3 scripts/app_compatibility.py report \
  --frozen /tmp/app-compatibility-frozen.json

# 3. 在结果 JSON 中填入冻结文件的 sample_sha256、原样环境和实际记录。
python3 scripts/app_compatibility.py import \
  --frozen /tmp/app-compatibility-frozen.json \
  --batch /tmp/app-compatibility-results.json \
  --output /tmp/app-compatibility-ledger-1.json

# 4. 后续结果导入新文件；必须携带原账本，保留全部尝试。
python3 scripts/app_compatibility.py import \
  --frozen /tmp/app-compatibility-frozen.json \
  --batch /tmp/app-compatibility-retest.json \
  --ledger /tmp/app-compatibility-ledger-1.json \
  --output /tmp/app-compatibility-ledger-2.json

python3 scripts/app_compatibility.py report \
  --frozen /tmp/app-compatibility-frozen.json \
  --ledger /tmp/app-compatibility-ledger-2.json \
  --output /tmp/app-compatibility-summary.json
```

`freeze`、`import` 和带 `--output` 的 `report` 不覆盖现有文件。缺字段、错误类型、超过 64 层嵌套及其他输入校验失败返回退出码 `2`；成功生成记录返回 `0`，**退出码 `0` 不代表验收通过**。不带 `--output` 的报告输出到终端。`--repo` 可在子命令之前指定证据所在仓库根目录。

例子中的包名、版本、签名摘要、设备和流程均为合成占位内容，未安装、未测试，也没有配套假截图。[样本示例](examples/app-compatibility-sample.json)可直接冻结；[结果模板](examples/app-compatibility-results.json)中的 `sample_sha256` 和时间需按对应冻结文件填写，模板状态保持 `unverified`。

## 冻结内容

| 字段 | 要求 |
| --- | --- |
| `schema_version`、`sample_id` | 当前版本为整数 `1`；记录可追溯的样本批次名称 |
| `evidence_kind`、`frozen_by` | `actual` 或 `synthetic`；冻结操作者。合成样例不能获得待发布复核资格 |
| `environment` | 精确机型、`physical_device`/`emulator`、API、系统构建、执行器版本、模型标识 `model_id`、地区、服务配置；仅 Wi-Fi、无实体 SIM、无启用 eSIM。不写密钥 |
| `apps[].package` | 每个包在固定样本中只出现一次，不能用同包多个版本扩大分母 |
| 版本与来源 | `version_name`、字符串 `version_code`（规范非负十进制、无前导零，范围 `0..9223372036854775807`）、64 位十六进制签名证书 SHA-256、来源 HTTPS URL、渠道和合法使用依据。URL 须有有效主机名，不得嵌入用户名或密码 |
| 分类与分组 | `category`、`ordinary`、`critical` 必须在冻结前声明；工具不自动把失败或有依赖的应用移组 |
| `dependencies` | 具体服务、账号、认证或本机 SIM 依赖；无依赖时使用空列表 |
| `flows` | 唯一流程 ID、完整描述、维度、状态和 `capture_policy: ui\|nonvisual`。每个应用必须有安装/更新流程和日常核心流程 |

`installation` 与 `daily` 流程固定包含 `off`、`idle`、`executing` 三态，顺序不变；`agent` 自动操作流程可选，仅用 `executing`。三态指应用安装更新/日常行为在 Agent 关闭、启用空闲及执行任务时的可用性，**不把应用正常运行混同于 Agent 能否自动完成同一流程**。`executing` 对照需要在方法中记录实际 Agent 活动，不允许仅把开关开启当作执行态。

冻结输出包含时间与内容 SHA-256；改应用、版本、关键流程、采集策略、分组、设备或服务配置会导致原摘要校验失败。`ui` 流程不得通过临时将结果写为 `nonvisual` 绕过截图，真正无界面的流程必须在冻结前声明为 `nonvisual`。需要变更时建立新样本并保留旧记录、变更原因和旧分母；摘要不是数字签名，人工重算摘要或另建残缺账本不能视为合法延续。

## 导入结果和证据

结果批次必须携带对应冻结摘要、完全一致的 `environment` 和 `evidence_kind`。每条结果包含：

- 唯一 `id`、冻结包名/版本、`flow_id` 和 `state`。
- `status`：`passed`、`limited`、`failed` 或 `unverified`。
- 带时区的 `tested_at`、人工操作者 `operator`、具体执行方法 `method` 和结果说明 `notes`。时间不得早于冻结时间；未执行项的时间仅表示记录时间，必须在方法中注明未执行。
- `dependency_status`：覆盖每个冻结依赖，值为 `met`、`missing` 或 `unknown`；缺失或未知依赖的结果不能标为通过。
- `evidence`：采集状态、原因、截图引用和报告引用。每个引用包含相对路径和实际文件的 SHA-256。

| `capture_status` | 允许的证据 |
| --- | --- |
| `captured` | 至少一个存在且摘要相符的 PNG/JPEG；通过项还需要独立报告证明流程与结果 |
| `protected` | 不截受保护页面；填写原因和不泄露敏感信息的执行报告 |
| `nonvisual` | 仅限冻结的 `nonvisual` 流程，填写原因和执行报告 |
| `unavailable`、`not_collected` | 如实记录失败或未采集原因，不能标为通过；无需伪造截图补齐 |

所有截图和报告引用限制在仓库 `screenshots/` 下，拒绝绝对路径、目录穿越、软链接、缺失文件、摘要不符和超过 16 MiB 的单文件。截图校验仅检查格式标记与摘要，不解释画面或证明目标完成。报告可以使用相应测试运行目录中的 JSON、文本或其他原始记录；人工回归也应归档脱敏报告。不得将真实账号、密钥或个人页面提交到仓库，不为取得截图关闭产品保护。

通过项必须有报告；受保护页面和预先声明的无界面流程无需截图。`ui` 流程实际受保护时可凭具体原因和报告保留实际结果，交由人工复核，不能将普通未采集伪称受保护。失败或未验证项可在无文件时记录原因。没有结果的冻结流程自动保持未验证。合法人工登录、外部设备验证码等依赖按冻结方案记录，不能把外部验证码当成本机短信功能通过。

同一包/流程/状态重测时使用新的 `id`，显式填写 `supersedes` 为当前最新尝试的 ID；时间不得倒退。重复 ID、跳过最新尝试或混入其他样本/版本/设备均拒绝。历史失败保留，报告按最新尝试汇总，并同时显示总尝试数及当前结果数。

## 统计与门槛

统计遍历冻结应用和流程，结果缺失不影响分母：

1. 任一必测流程失败，应用为 `failed`。
2. 安装更新与日常核心流程在全部三态均通过，应用才为 `passed`。
3. 部分通过或明确受限、其余未验证，应用为 `limited`；完全没有可用验证结果时为 `unverified`。
4. Agent 自动操作流程单独统计；其失败不能替代正常应用兼容结论。

JSON 同时给出完整、普通、关键、有依赖应用和 Agent 操作分组，以及各状态、各维度结果；零分母比例为 `null`，不算达标。完整样本至少 50 个、完整通过率至少 90%、普通至少 95%、关键 100% 且两类非空，才满足数量门槛。样本还必须声明为真实证据、真实手机，才可能得到 `ready_for_human_gate_review: true`。

**`release_accepted` 永远为 `false`。** 数量门槛只决定是否具备人工复核条件；正式发布仍需审查样本代表性、真实设备和证据、平台兼容要求、三态性能及服务授权。模拟器、少量开发样本、合成结果和截图文件存在均不能作为“大部分应用已可用”的结论。

## 验证工具本身

```bash
python3 -m unittest discover -s scripts -p 'test_app_compatibility.py' -v
```

单元测试仅使用临时目录和合成记录，覆盖固定分母、三态缺失、依赖缺失、版本/环境错配、重测历史、冻结采集策略、受保护/无界面证据、路径及软链接逃逸、摘要篡改、版本号和来源 URL 校验、错误结构/深度及发布门槛。临时 PNG 仅为合成解析测试数据，测试不会下载或执行第三方应用。
