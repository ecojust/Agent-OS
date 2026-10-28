# API35 同框状态测试中的非自有窗口事件

[完整运行40745270296](https://github.com/ecojust/Agent-OS/actions/runs/40745270296)使用主分支源码`eee60b1b273e458db92f3ed2391ee5a84d63e2fb`，首次手动运行、attempt 1。[API35任务](https://github.com/ecojust/Agent-OS/actions/runs/40745270296/job/123017267350)的旧用例`VisualEventGenerationInstrumentedTest#actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications`在第三次状态发布后失败：预期generation 34，实际35。本目录独立保留这项失败，不将它计为通过，也不把完整套件或旧用例集合称为全部通过。

[原始摘录](api35-failure.txt)是运行日志ZIP中`emulator-tests (35)/10_Print Android failure details.txt`连续第19–35行，2485字节，保留原字节、换行和第22行尾空格；SHA-256为`ff9bb13d7a809caecc5a50edb3db3417307b4241004dd7994d41048166990ee9`。ZIP、成员、任务和摘录的来源与完整性见[清单](manifest.json)。这是CI打印的JUnit失败信息，没有另行下载原JUnit XML；完整逐案与载荷归档核验不由这段摘录替代。

## 本轮已知事实

原诊断在生产失效调用点同步记录到一次`34→35`，`dropped=0`、`samplingFailed=false`。它与本轮等待匹配的状态事件不同：

| 原记录 | eventType | contentChangeTypes | windowId | windowChanges |
| --- | ---: | ---: | ---: | ---: |
| UiAutomation匹配的控制条事件 | 2048（WINDOW_CONTENT_CHANGED） | 2（TEXT） | 859 | 0 |
| 使generation增长的事件 | 2048（WINDOW_CONTENT_CHANGED） | 4（CONTENT_DESCRIPTION） | 4 | 0 |

失效时的本地控制条仍attached/shown/laidOut，未请求layout；bounds为`[12,36][308,148]`且匹配已保存的视觉边界，原registration仍一致。第三轮控制lease检查也记录同窗口、同attachment。`eventMatchesVisual=false`明确显示失效事件不属于该控制条窗口。`originalCurrent=false`仅指重新发布后的动作identity对象不再是最初对象；它与仍为true的原registration/attachment事实不同，不能据此推断窗口被替换。

[生产规则](https://github.com/ecojust/Agent-OS/blob/eee60b1b273e458db92f3ed2391ee5a84d63e2fb/app/src/main/java/dev/agentos/app/AgentAccessibilityService.java#L1466)仅豁免实际稳定自有窗口的有限文本/描述事件；其他窗口事件仍保守推进generation。这次调用链是`onAccessibilityEvent:1469 → invalidateVisualEvents:385`，不是控制条几何变化回调。因此本轮记录支持“非自有事件按现有规则触发失效”，不支持“所匹配的自有文本事件被误分类”。内容描述变化本身是否改变实际像素，并未由该记录证明。

[测试第92行](https://github.com/ecojust/Agent-OS/blob/eee60b1b273e458db92f3ed2391ee5a84d63e2fb/app/src/androidTest/java/dev/agentos/app/VisualEventGenerationInstrumentedTest.java#L92)要求从初始baseline起全局generation始终不变。该断言同时依赖状态发布和期间没有其他窗口事件；本轮后者不成立。紧随其后的原guard存活断言没有执行，不能把它写成一次已经执行的guard失效验证。原截图规则在资源清理后采集，截图即使存在也不能单独证明事件到达时窗口4的身份。

## 仍然未知

现有诊断没有事件生成时间、包类别或窗口4的来源身份，无法判定该事件是在baseline前生成后迟到，还是第三轮期间新产生。不能凭较小windowId、content位或CI日志时间将其称为系统时钟、Fixture或某个准备阶段事件。

Fixture的内容描述仅在`CapturePatternActivity.PatternView`构造时设置；未发现周期任务或后续改写。测试在建立guard前已执行控制器队列排空、实际launch观察和UiAutomation静默等待。`waitForIdle(500, 8000)`只证明最近接收事件的静默区间，不保证其他进程未来不会再发送事件。这些代码事实不足以确认或排除迟到事件，也不足以确立本次数值偏好改动与该失败的因果关系。

## 后续合同方案，尚未实施

应独立评审测试合同修订：保留三次实际状态发布，每次都必须取得原服务判定点的自有cosmetic事件证据，并证明该事件处理前后generation不变。若发生真正的非自有事件，须逐项解释generation增量，并明确验证原guard失效且之后没有复活；没有外部增量时仍验证原guard存活。任何未解释增量、自有事件失效、证据丢失或布局回调失效都应失败，不能跳过某次正例、更新baseline掩盖失效或重试直到通过。

现有observer只记录失效，不能单凭“未记录”证明服务已经处理并豁免某个事件。上述方案因此需要另行设计与审查默认关闭的诊断，以及明确的测试body合同变更；若更改方法身份，还需同步固定专项选择器和预期名单并保留旧档案解释。本轮没有实施这些更改，没有放宽生产guard规则，没有重跑该用例。
