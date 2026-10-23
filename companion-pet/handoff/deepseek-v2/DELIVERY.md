# DeepSeek V2 交付说明

日期：2026-10-23（Asia/Shanghai）
执行器：DeepSeek Harness
分支：`pet-v15/deepseek-continuity-v2`
基线：`a9cbe7cb55e02ab44d3d64d2245b18fe0da2f24e`
代码检查点：D0 `c1b211b`；D1-D3 `6488b85`；D4-D5 界面与交付 `18a9b6c`；之后又补了“遗忘内容不进入夜间整理”的修正（最终代码 SHA 见交付回复与 `git log -1`，本文档不再自引用）。

## 1. 产物

| 产物 | 路径 | 说明 |
|---|---|---|
| debug APK | `_pet-reports/deepseek-v2/app-debug.apk`（workspace 内绝对路径：`C:\Users\敖震宇\Documents\deepseek-harness\default-workspace\_pet-reports\deepseek-v2\app-debug.apk`） | 28,100,993 bytes |
| APK SHA-256 | `54B71E5EC9C294BA49DAB6CCD16E5ECB03BC111CFE8FD29B377C0BA5EFBF9220` | 重新计算于 IME inset 修正后的最终构建 |
| 仪器测试 APK | `_pet-reports/deepseek-v2/app-debug-androidTest.apk` | 2,307,422 bytes；**只编译，未执行** |
| 仪器测试 APK SHA-256 | `F1A8A1983F9E031338D092B6D327DDA19CAA8748A0BBD3DBA46EE4ECD05E55D4` | |
| lint 报告 | `_pet-reports/deepseek-v2/lint-results-debug.txt` | 0 errors / 5 warnings |
| 基线审计 | `companion-pet/handoff/deepseek-v2/BASELINE.md` | D0 源码位置与复现设计 |
| 状态记录 | `companion-pet/handoff/deepseek-v2/STATE.md` | 每阶段检查点与命令 |

CI：分支 `pet-v15/deepseek-continuity-v2` 的 `fix(pet): handle IME insets...` 与 `docs(pet): record final code SHA...` 两次运行均 **success**（`39966215171`、`39967136744`）。最终“遗忘过滤”修正提交的 CI 结果以 Actions 分支页面为准；本说明不把未完成的运行写成通过。分支全部运行见 `https://github.com/aozhenyu666-cmyk/test/actions`。
注意：`6488b85` 的 CI 第一次失败是 **Maven Central 返回 429 Too Many Requests**（解析 AGP 传递依赖），不是代码/测试失败；重跑被新 push 的并发组取消，随后 `a1e654d` / `18a9b6c` 的运行通过。

## 2. 实际执行结果

```text
:app:testDebugUnitTest          PASS   162 tests / 0 failures / 0 skipped
:app:lintDebug                  PASS   0 errors / 5 warnings
:app:assembleDebug              PASS   app-debug.apk
:app:assembleDebugAndroidTest   PASS   app-debug-androidTest.apk（仅编译）
:app:connectedDebugAndroidTest  未运行（本机无设备，adb 不可用）
```

构建环境：Windows 中文用户目录，JDK 22.0.2、Android SDK 35 / build-tools 35.0.0、Gradle 8.10.2、AGP 8.7.3、Kotlin 2.0.21。为避免中文路径影响 aapt2 与 Kotlin worker，构建在 ASCII 镜像副本中进行；CI 仍用原路径与 Temurin 17。

## 3. 本轮做了什么

### D0 基线审计

- 读 `PetBrain`、`ProbePrompt/ProbeReply`、`MemoryStore/Nightly/NightlyWorker`、`Stats/Settings/SecretStore`、`net/`、`PetLife`、`rhythm/`、`session/`、`MainActivity`、`ui/`、`overlay/`、测试与 CI。
- 记录 L1-L6 六类静态风险与复现设计；没有把风险写成已发生的真机故障。
- 基线实测 111 tests（不是旧文档的 87），lint 0 errors。

### D1 记录可靠性与预算

- 新增事件日志：稳定 id、`occurredAt`、按设备时区写入的归属日、类型、来源、conversationId；JSONL 按月追加，坏尾行读取时跳过。
- 旧 `today.md` 一次性迁移为事件：先备份、id 确定、幂等、不删除旧文件；保留旧 Markdown 阅读/编辑。
- `appendToday` 增加跨午夜保护：旧日内容先归档再重写日期头。
- 夜间整理：固定批次（日期 + 事件 id 列表 + profile/persona/tombstone 版本），请求期间不持锁；提交只归档批次内事件，新交流留待处理；档案版本冲突则作废批次、保留用户改动、下次重算。
- 提交协议：先写 journal（目标文件 before/after 哈希、归档行、事件 id），再写目标文件、归档、标记已归档；启动时 `recover()` 重做。目标文件被用户改过（既非 before 也非 after）时中止，不覆盖用户改动。
- 预算：`AtomicBudget` 统一探针、回复、想一想、回答、念头摘要、夜间整理；先预留再发请求，按真实 HTTP 尝试计数，客户端不重试，跨日归零，重启恢复；并发抢最后一个配额只成功一次。
- 测试覆盖跨午夜、请求中新增交流、请求中改/删档案、每个提交步骤故障注入恢复、重复 jobId、恢复期间新事件、预算并发。

### D2 连续交流

- `FactStore`：稳定 `factKey`、版本、`supersededBy`、`deleted`、来源事件 id；区分“他说过/我推测”；纠正使旧版本失效；忘记软删除并写 tombstone；猜测不覆盖已确认；夜间档案行同步为事实时过滤 tombstone。
- `MemoryRecall`：本地关键词 + 中文单字/二元组，按重叠度与时间衰减排序；最多 6 条/800 码点；tombstone、已删除、已替代、与最近交流重复的不进入；相关度不够不补满。
- `CompanionContextAssembler`：persona 作为 system 指令；召回、最近交流、素材都是带来源的 user 数据；最近 12 条/2400 码点；整个动态上下文 ≤5000 码点；素材只作临时字段，不落事件日志。
- 默认关闭：`Settings.continuityEnabled` 默认 false；关闭时仍走旧的整份 profile 路径。记忆页新增“它记住的事”，可确认、更正、忘记；忘记同时写 tombstone 和 FORGET 事件。
- 测试：昨天兴趣今天召回、不相关不注入、纠正只剩新事实、删除后重启/重放不复活、素材指令不越权、emoji 截断、5000 码点上限、来源标注。

### D3 有来由的状态与主动交流

- `PetStateStore`：精力/兴趣/亲近感三个 0..1 维度，确定性事件增量 + 6 小时半衰期回归基线；持久化、跨重启；忽略只降低兴趣，不产生“受伤/索取回应”；不增加网络请求。
- `TopicQueue` + `TopicGate`：来源必须带事件 id；过期、已分享、已忽略不重复；安静时段、用户不在、30 分钟冷却、最近交流去重后才进入同一条开口路径；没有内容就不说。
- 念头入队；夜间整理把日记开头入队（来源为本批事件）。
- `MaterialAdapter` 只有本地 fixture：来源 URL/ID、抓取时间、摘要、有没有字幕；无字幕时提示“不要说看完了视频”；没有素材时提示词不提供“刚刷到”的依据。
- 测试：状态边界、衰减、重启、忽略不惩罚；话题来源必需、过期、一次性、忽略不重复、闸门；素材无字幕/无来源。

### D4 原生界面

- 新增 `ui/theme/Tokens.kt`、`CompanionTheme.kt`：paper/panel/ink/ink2/rule/chip/signal/onSignal/danger 语义色，深浅两套；Material primary 用墨色避免黄色焦点；tight 切角 4.2/4.8dp；中文正文 17sp、辅助 13-15sp、标题 22-30sp。
- 新增 `ui/components/CompanionUi.kt`：Eyebrow、PageHeader、GroupTitle、Rule、Panel、SettingRow、ToggleRow、SourceTag、CutAction、EmptyNote；48dp 触控、Switch 语义、点击行 Role。
- 重做 MainActivity：四页导航保留，自定义 Tab（selectable + Role.Tab），统一主题。
- 迁移开始页：真实服务状态、启动/暂停/停止、权限行、最近交流。
- 设置页分组细线化；记忆页分页标题、来源标签、事实确认/更正/忘记；统计页真实数据、范围说明、无数据不伪造趋势。
- 未交付原创 Lottie；角色仍是 Canvas 占位，且没有持续循环动画，系统“减少动画”对新增界面无额外可停的运动。

## 4. 数据、升级与回退

- 数据目录不变：`filesDir/memory/`。新增 `events/`、`nightly/`、`facts.json`、`state.json`、`topics.json`、`migrations/`。
- 首次启动迁移旧 `today.md`：备份到 `migrations/legacy-today-<日期>.md`，事件 id 为 `legacy-<日期>-<行号>`，重复执行不重复导入；旧文件保留。
- 升级前建议在系统设置里备份应用数据（或至少导出 `filesDir/memory/`）。卸载会清空应用私有目录。
- 新功能默认关：连续性开关在“设置 → 连续性”。关掉后回到旧的整份 profile 路径；旧 Markdown 仍可读可编辑。
- 回退到旧 APK：旧二进制只读旧 Markdown，不会读取新事件/facts；升级后如果又回退，新事件不会出现在旧版的“今天”，但旧 `today.md`/profile/日记/信仍在。需要完整历史时先备份 `filesDir/memory/`，回退后再恢复。不要直接删除 `events/` 来“回退”，那会丢新交流。
- 数据格式版本：事件 `events-v1`；facts/state/topics 为当前 JSON 结构，字段向后兼容（未知字段忽略）。

## 5. 待测（真机/设备）

本机没有可用设备：`adb` 守护进程因 Temp 目录权限无法启动，未连接 iQOO 12，也没有模拟器。以下均为**待测**，不写成已通过：

1. 24 小时常驻、最近任务清理后恢复、vivo/iQOO 自启动与后台高耗电。
2. 悬浮窗、无障碍开关、输入法/键盘、锁屏心跳、通知暂停/停止。
3. 跨午夜记录归属；整理请求期间新增交流/改档案；强杀后的 journal 恢复。
4. 预算上限与并发请求（真机观察请求次数）。
5. 昨天兴趣今天召回、纠正、忘记；重启后不复活。
6. 主动话题：安静时段零请求、忽略后降频、没有来源保持安静。
7. 360/430dp、浅/深色、字体 1.0/1.3、TalkBack、键盘遮挡、返回导航、系统减少动画。
8. `:app:connectedDebugAndroidTest`（BootstrapSmokeTest、EventLogInstrumentedTest、SecretStoreInstrumentedTest）。

## 6. 已知限制（如实）

- 召回是本地词面匹配，不承诺识别所有同义改写；历史自由文本仍需人工确认。
- 夜间整理仍是每天一次请求的整批方式；多天积压会按天逐个处理，一天只处理最早一批。
- 预算不承诺多进程强一致；本应用所有入口在同一进程。
- 事实抽取主要来自夜间档案标签与用户显式操作，不对每句闲聊自动做语义抽取。
- 界面未在真机做视觉/无障碍验收；仅 Preview/编译与代码约束。
- 占位角色不是正式美术；未使用任何游戏角色或未授权素材。