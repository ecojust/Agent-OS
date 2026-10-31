# Android 16 r1 WMS 属性记录边界：固定源码核对

固定 frameworks/base `99b01a65cc4c104933788b3143285ab6bae65827`，六个文件的下载 URL、字节数、SHA256 和引用行见 [固定源码索引](aosp16-wms-source-index.json)。这是公开版本的输出结构证据；没有证明 API36 实测镜像逐字相同，也没有恢复已按契约丢弃的原始 dump。

`RootWindowContainer.dumpWindowsNoHeader` 的正式记录统一使用 `  Window #N ...:`，随后调用 `WindowState.dump`。`WindowState.dump` 无条件打印一行 `mAttrs=`，其值可能跨多行；不存在“这个普通正式记录因 dumpAll=false 而省略 mAttrs”的分支。`windows` 命令本身将 dumpAll 固定为 true。

最具体的额外来源是正式清单之后的四类尾部：`Hiding System Alert Windows:`、`Windows force removing:`、`Windows waiting to destroy their surface:`、`Windows waiting to resize:`。它们在各自集合非空时出现，条目头分别是 `#N`、`Removing #N`、`Destroy #N`、`Resizing #N`，且 dumpAll=true 时再次调用 `WindowState.dump`。旧诊断只按 `Window #N` 分割，因此尾部再次打印的 mAttrs 会留在最后一条正式记录的分段中；“唯一 mAttrs”检查会将这一段判为不可解析。若有11条正式记录，前10条都合法且尾部附加至少一份完整窗口 dump，这一路径可以产生10条已解析、1条未解析的计数形状。这是源码允许的候选解释，不是实际 API36 根因证明；必须等新增固定原因/边界元数据。

`Clients waiting for these windows to be drawn:` 尾部只打印 WindowContainer 名称及 `Waiting #N` 引用，没有再次调用 WindowState.dump，因此单独存在该节并不足以解释重复 mAttrs。`WindowContainer.dump` 本体也不再次调用 WindowState.dump 或打印 mAttrs；它打印动画、方向、LocalInsetsSources 和安全区域元数据。

其他不可排除的格式缺口包括：未闭合或截断的属性文本；多个属性块；嵌套深度或8192字符范围超限；顶层类型缺失/重复/非法。LayoutParams 的合法结构明确含 ty 之前的 `sim={...}` 和可递归的 `paramsForRotation`；额外集合可使文本增长。`ViewDebug.intToString` 的未知值后备输出是十进制 Integer.toString，不是十六进制0x；负值会带负号而不符合当前 `[A-Z_0-9]`，但本次没有任何实测窗口使用负类型的证据。已知类型映射名和正整数后备值符合当前字符范围。

仅凭旧的10/1计数无法区分这些原因。原因元数据宜记录有限枚举、属性块数量、主记录序号/是否最后一条、固定尾部标题出现情况及相对位置，避免保存任意窗口标题或原文。此轮不要更改分段或准入规则；补充证据后再决定是否需要独立的解析边界修正。

固定引用：

- [正式清单 Window # 和 w.dump](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/wm/RootWindowContainer.java#1086)。
- [Hiding 尾部](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/wm/WindowManagerService.java#6826)、[Removing 尾部](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/wm/WindowManagerService.java#6841)、[Destroy 尾部](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/wm/WindowManagerService.java#6856)、[Resizing 尾部](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/wm/WindowManagerService.java#6873)。
- [正常 WindowState 的 mAttrs 输出](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/wm/WindowState.java#3974)。
- [LayoutParams sim 和 ty](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/view/WindowManager.java#5843)、[rotation 递归](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/view/WindowManager.java#6021)。
- [ViewDebug 十进制后备](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/view/ViewDebug.java#1628)、[WindowContainer.dump 本体](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/wm/WindowContainer.java#3190)。

旧10+1记录未保存未解析分段的序号或这些尾部标记，因此不能确认哪一段、哪一条尾部路径实际出现。

即使后续实测确认未解析记录来自这些尾部，也不能简单丢弃尾部：尾部可能包含主清单中没有的窗口。需要时应另行解析尾部引用及完整记录，核对是否为主清单中同一固定身份的重复输出，并检查类型、display、owner/package及相应对象字段是否完整一致；额外、未知或冲突记录继续保持不可用。如果后续诊断明确仅描述主清单范围，也必须写清范围，不能把忽略尾部称为全局唯一IME。当前步骤只新增失败原因元数据，不修改分段、选择或准入规则；是否修正及其验收契约要等新实测证据后决定。
