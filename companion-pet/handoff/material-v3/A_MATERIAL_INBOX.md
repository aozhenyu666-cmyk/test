# A 阶段：素材收件箱

基线：`pet-v15/codex-foundation-v3` / `cd49e160b1652fd10244006153f835c4a3f8acf5`
阶段：A（本提交）；B 见后续独立提交。
版本：0.3.2 / versionCode 5。

## 行为

- Android `ACTION_SEND` `text/plain` 接收 Activity（`.MaterialShareActivity`）：先预览标题/链接/摘录/附言与类型，再点保存。
- 应用内手动粘贴入口在“记忆 → 素材收件箱”，同一套 `MaterialDraftParser` / `MaterialInbox`。
- 只保存用户主动给的标题、URL、摘录/字幕、附言；不登录、不抓网页、不读 Cookie、不读其它 App 聊天。
- 内容类型：`LINK_ONLY` / `EXCERPT` / `TRANSCRIPT`；只有链接时提示“不能假装知道内容”，话题只问想一起看什么。
- 保存生成真实 `MATERIAL` 事件；话题的 `sourceIds` 引用该事件。删除/过期追加 `FORGET`，`EventVisibility` 使派生话题失效；收件箱本身移除后上下文也不再有该材料。
- 同一规范化 URL 幂等更新，不复制卡片；无 URL 的不同摘录按文本 key 分开；URL 拒绝 `file:`/`content:`/`javascript:`/userinfo/超长。
- 上限：30 条、正文 4000 码点、标题 200、附言 500、URL 2048、保留 14 天。
- 保存不调用模型、不扣预算；材料只进入上下文组装器的 user 数据段。
- 旧 `MaterialItem` fixture 保留；没有字幕仍不会说“看完了”。

## 文件

- `core/companion/MaterialInbox.kt`（模型/URL 规范/去重/过期/上下文块/事件）
- `core/companion/CompanionEvent.kt`（新增 `MATERIAL`）
- `core/companion/CompanionContextAssembler.kt`（共享材料块，超预算时整块丢弃）
- `MaterialShareActivity.kt`、`AndroidManifest.xml`（ACTION_SEND text/plain）
- `ui/MaterialInboxSection.kt`、`ui/MemoryTab.kt`（手动粘贴、列表、删除）
- `PetApp.kt`、`service/PetBrain.kt`（收件箱/组装器/话题共享实例）
- `core/companion/MaterialInboxTest.kt`、`androidTest/MaterialShareInstrumentedTest.kt`

## 验证

本机 ASCII 构建副本，JDK 22 / SDK 35 / Gradle 8.10.2 / AGP 8.7.3：

```text
:app:testDebugUnitTest          PASS   209 tests / 0 failures
:app:lintDebug                  PASS   0 errors / 5 warnings
:app:assembleDebug              PASS   28,190,415 bytes
:app:assembleDebugAndroidTest   PASS   仪器 APK 仅编译
```

APK SHA-256：`BDBDE415FC5D62C519A1ED6033C63B6D91CF3538631D52ED8E5A1B5A31CC53EB`

新增 `MaterialInboxTest` 11 项：同链接幂等/更丰富正文更新、不同摘录不合并、URL 拒绝、三种证据等级、System 注入隔离、删除使话题/上下文失效、过期/重启、30 条上限、解析、空保存、长度上限与预算不扣。

## 未执行 / 边界

- 设备分享链路（从 B站/浏览器 ACTION_SEND 到预览保存）**未执行**；`MaterialShareInstrumentedTest` 只编译。
- 没有从真实 B站/浏览器抓取；不登录、不读字幕，除非用户自己分享字幕。
- 没有单独“模型总结”按钮；材料在下次交流上下文中按证据等级进入，发生模型请求时沿用现有预算。
- 无设备时不能声称保存链路真机通过。
