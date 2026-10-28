# 同桌「伙伴 v1」实施计划（交给 GPT 执行）

日期：2026-10-28。核心逻辑由 Claude 完成（`core/partner/`，32 个单测已过）；本计划只要求**接线、界面、提示词落地和验收**。

## 0. 工作规则（先读）

- 只在 `claude/r21-strict-takeover` 上工作：`git fetch origin && git checkout -B claude/r21-strict-takeover origin/claude/r21-strict-takeover`，确认包含本文件所在提交后再开工。**不要**在 `deepseek/r21-proactive` 或新开的旁支上做，否则不会被合并。
- 只改 `companion-pet/`。不改 `.github/workflows/` 的发布门禁。
- 不许改回、不许放宽：`core/partner/` 的单测、`acceptAdvice` 的“PASS 不带不确定”规则、离线题库判分、严格守护的单一拦截拥有者（见 `handoff/claude-r21-strict/ACCEPTANCE.md` 第三节）。
- 每个任务单独提交；推送后 GitHub Actions「Companion Pet Android」必须通过。本地若无法构建，至少保证 CI 绿。
- 验收只用 `PASS-L`（逻辑测试）/ `WIRED`（已接线无自动化测试）/ `DEVICE-PASS` / `NOT_RUN` / `FAIL`。没真机就写 NOT_RUN。

## 1. 用户要什么（来自 10 问的回答，原话归纳）

1. **骑车、走路时有人一直陪他说话**：分析他的经历，给出见解和判断，**不是顺着他说好**。脑子乱、没在想事时也要聊。
2. **刷手机、看视频、玩游戏时，它也能看一眼、结合画面聊**，让他觉得这是朋友。
3. **看长视频/长文左耳进右耳出**：希望它把屏幕上的观点抓出来，之后**提问、回想**，帮他真的留下东西。
4. 频率要高：正常用手机一小时 **15–30 次**，越重要越积极；他不回应常常是在想事情，**不能因此就不说**。
5. 讨厌：泛泛而谈、一上来总结、总在反问。最想要：**向他提问**、**回想以前的事**。
6. 要一个**完整聊天页**（气泡只负责开场，点开进入），能连续多轮，切走再回来接着聊。
7. 要记住长期目标、观点、正在处理的问题、他的情况，并**主动回来问进展**；记忆要能看、能删。
8. 模型：DeepSeek 作主力；Gemini 更“跳脱”但贵，**平时不接**，以后作为可选模式。

## 2. 下一版只做三件事（Claude 建议，用户未定第 10 问时的默认）

| # | 目标 | 用户能亲自体验的通过标准 |
|---|---|---|
| P1 | **连续多轮聊天 + 完整聊天页 + 骑行语音模式** | ① 点开气泡进聊天页，同一个话题连续聊 10 轮不被打断、不自动收起；② 切到别的 App 20 分钟内回来，它接着刚才那句；③ 打开“骑行模式”，戴耳机全程不看屏幕，能语音来回聊 10 分钟 |
| P2 | **围绕真实内容主动开口** | ① 刷手机 1 小时，它主动开口 15–30 次，每次都能指出屏幕上/画面里/以前说过的**一个具体东西**；② 连续不理它，它会变慢但不会消失；③ 打字、通话、录音时绝不插话；④ 看一个视频时，它能说出画面里的一句字幕或一个细节并问一个具体问题 |
| P3 | **可见可删的记忆 + 主动跟进旧话题** | ① 他说过的目标/烦心事，几小时到一天后它会主动问进展，并提到上次说的原话；② 在“记忆”页能看到它记了什么、来自哪句话，删掉后它不再提；③ 它想长期记住一件事时会先问“要我记住吗” |

## 3. 已完成的核心逻辑（直接调用，不要重写）

| 文件 | 作用 | 关键 API |
|---|---|---|
| `core/partner/DialogueSession.kt` | 多轮对话，**无轮数上限**，收起/切走可恢复，上下文自动压缩，语音不确定标注 | `DialogueRules.start/add/away/resume/canResume/tick/close/needsCompaction/turnsToCompact/applyCompaction/contextLines` |
| `core/partner/ProactiveGovernor.kt` | 主动开口调度：按场景（骑行/阅读/视频/游戏/刷流/发呆）选来源、默认 20 次/小时、没回应退避但不停、硬安静规则 | `decide(signals, candidates)` → `Speak/Silent`；**确认显示/念出后**才调 `onShown(now)`；他回任何一句调 `onUserReplied()` |
| `core/partner/ThreadBook.kt` | 话题线（目标/烦心事/观点/材料/没聊完）：到期跟进、按重要度退避、暂停/关闭、来源记忆被删时联动 | `start/onUserTurn/onPetFollowUpShown/onFollowUpIgnored/pause/close/onFactForgotten/due`；`ThreadBookStore` 落盘 |
| `core/partner/ReplyContract.kt` | 说话规则提示词 + 本地校验：拦总结开头、先夸、连续反问、编造锚点、引用不存在的记忆、列清单、骑行超长 | `ReplyContract.systemRules(input)`、`parse(raw)`；`ReplyCheck.violations/ok/fixHint` |
| `core/partner/VisionTalk.kt` | 看图带着具体问题看、结果转成可聊锚点、显示完整结果 | `prompt(question, context)`、`toCandidate(...)`、`displayText(summary)` |

记忆不新建：事实仍用 `core/companion/FactStore`（确认/更正/忘记/墓碑）。话题线只引用 fact id。

## 4. 任务表

| ID | 任务 | 要改的地方 | 必须做到 | 验收 |
|---|---|---|---|---|
| G1 | **拆掉两轮上限和自动收起** | `PetBrain.onUserInput`（`if (c.petReply != null) return // 已经聊满两轮`，约 1513 行）、回答后的 `postDelayed{ hideBubble }`（约 967/1258/1307 行） | 普通对话改用 `DialogueSession`；回答后不自动收起，只有他点收起/闲置 30 分钟才结束；收起时 `away()`，再点桌宠 `resume()` | PASS-L（已有）+ WIRED；真机：连续 10 轮 |
| G2 | **完整聊天页** | 新建 `ui/ChatActivity`（Compose），桌宠气泡加“展开”；`PetApp` 持有当前 `DialogueSession` 并落盘 | 消息列表 + 输入框 + 语音键；从气泡点开时带着同一个 session；关掉再开还在；显示每句来源（打字/语音/它主动） | DEVICE |
| G3 | **骑行语音模式** | 设置页开关 + 通知栏快捷开关；`service/Speech.kt`（TTS）+ `VoiceInput`（STT）做循环 | 它说完自动开始听，听完自动回，不弹任何需要看的东西；`ProactiveGovernor` 用 `UsageContext.RIDING`（只语音、不聊画面）；关屏也能继续（已有麦克风前台服务类型）；**提示戴耳机、不要看屏幕** | DEVICE |
| G4 | **主动开口接入 Governor** | `PetBrain.presenceTick` / `speakFromIntentIfAny` | 每 5–10 秒组装候选：当前屏幕文字（`PerceptionHub.readScreen` 清洗后取一句原文作锚点）、到期话题线（`ThreadBook.due`）、目标、自己读到的（R3）、闲聊；`busyReason` 用现有 `presenceBusy()`；**修掉“说之前就 markSpoken”**：显示/念出成功才 `onShown` + `ledger.record`；`UsageContext` 由前台应用分类推出（`settings.appLists()` 已有学习/娱乐清单） | PASS-L（已有）+ WIRED；真机：1 小时 15–30 次 |
| G5 | **说话规则落地** | 主动开口与回应用的提示词（`ProbePrompt` / `replyRound`） | 用 `ReplyContract.systemRules` 生成规则段；模型回包先 `parse`，再 `ReplyCheck.violations`；不合格带 `fixHint` 重写**一次**；仍不合格就不发（主动开口）或发一句保守短句（回应） | PASS-L + WIRED |
| G6 | **主动看画面** | `core/vision/VisionAnalyzer` 的调用处 | 主动看图时用 `VisionTalk.prompt("", 话题背景)`；结果 `toCandidate` 进 Governor（看视频/游戏时权重高）；手动看图时把他的问题传进 `prompt`，界面用 `displayText`，并允许**围绕这张图继续追问**（追问进同一个 DialogueSession，锚点用上次的 elements）；截图频率受 Governor 节奏约束，不另起定时器 | WIRED + DEVICE |
| G7 | **话题线与记忆** | `PetApp` 持有 `ThreadBook`（`ThreadBookStore` 落盘）；`ReplyContract.parse` 的 `newThreadTitle/memoryProposal` | 模型提出 newThread → `ThreadBook.start`；他就某线说话 → `onUserTurn`（带一句进展）；主动提到期线 → 显示后 `onPetFollowUpShown`，等待窗口内没回 → `onFollowUpIgnored`；`memoryProposal` 不自动写入，弹一句“要我记住吗：……”，他点是才 `FactStore.recordSaid`；`FactStore.forget` 后调 `ThreadBook.onFactForgotten` | PASS-L（已有）+ WIRED |
| G8 | **记忆页** | 设置里已有 `MemoryTab`：增加“话题线”分区 | 列出事实（来源句、确认/推测）与话题线（重要度、下次跟进时间、上次进展）；每条可删除、可暂停、可关闭 | DEVICE |
| G9 | **视频/长文观点留存** | 屏幕文字采样 + `MaterialInbox` | 看视频/长文时，每次 Governor 允许开口前读一次屏幕（字幕/正文），把有观点的句子存为 `MATERIAL` 话题线的来源；之后用“回想”方式追问（“昨天那个视频里说‘先做两分钟’，你试了吗”）。不存整屏原文，只存被引用的那一句 | WIRED |
| G10 | **设置与诊断** | `SettingsTab` | 每小时主动次数（5–40，默认 20）；睡眠时段；“安静 30 分钟”快捷键；诊断显示：最近一小时开口次数、连续没回应次数、最近 5 次开口的来源和锚点、最近 5 次被拦的原因 | DEVICE |

### 推荐顺序

G1 → G5 → G4 → G7 → G2 → G3 → G6 → G9 → G8 → G10。
G1/G5/G4 不需要新界面，先做完就能感受到“接得住话、主动、不泛泛”。

## 5. 不要做 / 注意

- 不要做成“定时说一句”：没有具体锚点的不开口（闲聊除外），Governor 已经拦了，不要绕过。
- 不要因为他没回应就把频率降到 0；也不要无视“安静”键、打字、通话、录音。
- 不要把整屏原文、截图长期存盘；只存被引用的那一句和它的来源应用。
- 不要自动把推测写进长期记忆；`memoryProposal` 必须先问他。
- Gemini 只预留“模型档位”设置（endpoint/model/key 可换），**默认不用**；骑行模式默认 DeepSeek。
- 骑行时不弹需要看屏幕的东西；任何拦截层、训练题都不在骑行模式下弹出。

## 6. 交付

- 每个任务的提交 SHA、改动文件、调用链。
- 更新 `handoff/claude-r21-strict/ACCEPTANCE.md`，在其中新增「伙伴 v1」一节，逐条写 G1–G10 状态；APK 以 Release `tongzhuo-preview-claude-r21-strict-takeover` 为准，写下载后自己算的 SHA-256。
- 真机部分写清楚还需要用户亲自试哪几步（对应第 2 节的通过标准）。
