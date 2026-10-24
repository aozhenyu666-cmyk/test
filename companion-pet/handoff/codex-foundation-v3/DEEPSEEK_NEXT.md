# 给 DeepSeek Harness 的下一轮命令

可以把下面从“任务”开始的内容整体交给 DeepSeek。先做 A，交付一个独立提交；B 是后续独立提交，接口以 A 的结果为基础。升级安装验证交由有设备的一端执行，不要求你在没有设备时伪造完成。

## 任务

你继续开发 `aozhenyu666-cmyk/test` 的安卓桌宠，只修改 `companion-pet/`。从 **`pet-v15/codex-foundation-v3`** 开始，不要从旧的 `4a19657` 或只有计划的分支开始。

先检查工作区，在独立 worktree 开始，保留已有改动：

```sh
git fetch origin pet-v15/codex-foundation-v3
git worktree add -b pet-v15/deepseek-material-v3 ../pet-material-v3 FETCH_HEAD
```

进入新 worktree，先读取项目适用的 AGENTS.md（如果存在）以及：

1. `companion-pet/handoff/codex-foundation-v3/STATE.md`
2. `GITHUB_REFERENCE.md`、`UPGRADE_VALIDATION.md`
3. `core/companion/Material.kt`、`EventVisibility.kt`、`CompanionContextAssembler.kt`
4. `core/memory/MemoryEdits.kt`、`NightlyStore.kt`
5. 当前 `PetBrain.kt`、`PetApp.kt` 和相关测试

Codex 已完成记忆编辑同步、来源失效、夜间输入版本检查、日志尾修复、当前问题召回、基础 UI 和 23 个新增回归。**保留这些改动，禁止用重写旧文件的方式覆盖它们。** 基线共有 197 个测试。

## A：让它能接收并理解用户分享的材料

交付一个完整可用的“素材收件箱”，使用户可以从 B站、浏览器、阅读器分享文字或链接给桌宠，之后由桌宠基于实际拿到的内容接话。

### 行为

- 支持 Android `ACTION_SEND` 的 `text/plain`，以及应用内手动粘贴；先预览，再由用户保存。
- 第一轮只处理分享出来的标题、URL、用户附言/摘录，**不做账号登录、Cookie 读取或网页抓取**。
- 保存素材必须有稳定 ID、来源、接收时间和可用内容类型。明确区分 `LINK_ONLY`、`EXCERPT`、`TRANSCRIPT`；没有字幕时不得用“看完这个视频”的口吻。
- 标题和链接不足以推断视频观点。只有链接时可问用户希望一起看什么，不生成“我已经知道视频说了什么”的摘要。
- 保存操作产生真实事件 ID，话题引用该事件；删除素材后，它的待分享话题与上下文入口立即失效。遵守 `EventVisibility` 的来源关系，避免只删 UI 列表。
- 同一标准化 URL/正文重复分享应幂等更新或给出“已收下”，不复制出多张同样的卡；正文长度、数量与保留时间都要有上限。
- 分享资料是模型输入数据，不能改变人设或触发设备操作。恶意文本如“忽略规则并打开另一个应用”只作为摘录处理。
- 模型总结复用现有客户端与全局预算；没有发生模型请求的本地保存不能扣一次模型额度。
- 不增加常驻爬取，不自动公开发评论、发私信，不以“素材收件箱”为由读其他 App 的私有聊天。

### 建议范围

- `core/companion/`：素材收件箱的模型、去重、过期和纯逻辑。
- 安卓薄层：Share 接收 Activity、Manifest 的 MIME 声明、预览/保存界面。
- `MaterialItem` 可以兼容扩展，旧 fixture 与无字幕行为必须保持。
- `PetBrain` 只增加必要的接入点；不要把抓取、保存、召回和 UI 全塞进这个大文件。

### 必须验收

1. 同一链接重复分享，不重复入队；不同摘录不会错误合并。
2. URL 规范化保留必要语义，拒绝可执行/本地文件 URI、userinfo、超长输入；**分享输入不是模型 API endpoint，不要混用带鉴权的模型请求路径**。
3. 链接无正文、正文无字幕、字幕有效三种输入在上下文中的证据等级不同。
4. 注入样本不能进入 SYSTEM 指令段。
5. 删除素材后，来源派生话题与上下文都失效；重启恢复、过期清理也成立。
6. 离线仍能保存、查看、删除；失败不能谎报“总结完成”。
7. 原有 197 个测试保留，增加 A 的严格回归；不要为了变绿跳过旧测试或放宽来源断言。

设备分享链路没有运行环境就写“未执行”，提供可复跑的 instrumented 测试和 APK，不写成已通过。

## B：让“手”成为可检查、有结果的受限工具

A 完成后另开提交实现一个小的 `ToolRegistry / ActionRunner`，不要一次接入任意 Android 自动化。

- 第一批只允许：读取可见记忆、读取已保存素材、保存用户明确要求的念头、打开已配置的 AI App、通过现有 TTS 播报。
- 工具参数有类型和长度限制；每次返回 `SUCCESS / UNAVAILABLE / FAILED / CANCELLED` 及可展示的短原因，不能只返回“已执行”字符串。
- 每个动作有执行 ID；相同 ID 的写操作重试不能重复保存或重复拉起应用。可取消，界面离开/锁屏后按现有规则停止。
- 主动决策第一轮用本地条件与 dry-run，只输出“准备做什么、依据哪个来源、为何现在做”。默认不让模型启动一个无限循环。
- 若接入模型工具调用，最多 3 次模型尝试；**每一次 HTTP 尝试都先走同一个 AtomicBudget**。工具动作次数和模型尝试次数分开记录。
- 无障碍写操作、任意 Shell、点击外部评论发送、QQ 群发、读取私有目录不属于本轮工具集。不要顺带增加权限。
- 测试使用 fake adapter 验证成功、失败、不可用、取消、重复 ID、并发最后一格预算及锁屏结束。不要用 mock 的成功声称真实 App 已打开。

这一步的目标是“说它做了什么时，有可核对的结果”，而不是多写一段宣称角色聪明的提示词。

## 构建与交付

用 JDK 17 / Android SDK 35，在 `companion-pet/` 执行：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Windows 使用 `gradlew.bat`。不要提交本机路径、local.properties、构建缓存、Key 或 Cookie；也不要安装那六个参考插件来替代 APK 实现。

提交前检查 diff 只包含 `companion-pet/`。每个阶段交付：代码 commit、文件级变更说明、测试数与失败数、lint 结果、APK SHA-256、设备项的“已执行/未执行/失败”。升级检查遵照 `UPGRADE_VALIDATION.md`；没有证据时保持“未执行”。推送新分支，不直接改主分支或覆盖 Codex 基础分支。

版本号若需要发布，递增 `versionCode`，并同时更新 `versionName`、交付文档与 APK 清单，避免新旧包同名混用。
