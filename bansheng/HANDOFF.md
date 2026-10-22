# 伴生：交接记录

更新：2026-10-22 · 对应 [产品说明](docs/01-product-brief.md) 与 [执行方案](docs/02-execution-plan.md) v1.0

## 1. 选型记录

**底座：N.E.K.O.**，提交 `80bdabd1960a0feaf3a2f43f2024b00bda9e4a86`（2026-10-22），Apache-2.0。没有改它的任何文件，伴生是一个标准插件。

选它而不换 AIRI 的理由（在云端 Linux 上实际启动后确认）：

| 需要 | N.E.K.O. 现成的 | 伴生怎么用 |
|---|---|---|
| 桌面角色、中文语音、TTS、用户开口打断 | 全有（Live2D/VRM/桌宠，Realtime 语音，外部 TTS） | 不碰，直接用 |
| 让角色主动说一句 | `push_message(ai_behavior="respond")` | 伴生决定要说时推一次，角色用自己的声音和人设说 |
| 只给角色喂上下文、不让它开口 | `push_message(ai_behavior="read")` | 每次理解更新后推一段讨论摘要，你直接和角色聊时它知道你们在聊什么 |
| 不在角色说话时插话、过期丢弃、同类合并 | 主动消息队列（`main_logic/proactive_delivery.py`）：等播放结束、`expires_in_s`、`coalesce_key` | 伴生的话设 12 秒过期、同一个合并键，旧的没说出口就被新的替换 |
| 插件调用大模型不自己管 Key | 插件模型槽（Plugin API），`ctx.models.get_client()` | 声明用途 `mind`，你在 N.E.K.O. 界面里绑定任意 OpenAI 兼容模型 |
| 插件框架 | 进程隔离、启停、日志、配置 | 用 |

AIRI 没有安装。N.E.K.O. 在一次实际运行和扩展点检查中没遇到实质障碍，按方案不再并行验证备选。

**其他复用：**

| 项目 | 用法 |
|---|---|
| Thoughtful Agents（Apache-2.0） | 借鉴“候选 → 1–5 表达意愿评分 → 阈值选择”，评分因素（相关、信息缺口、紧迫、连贯、新意、他人是否更该说）改写成中文放进 `engine/mind.py`；没有引入代码 |
| Council Bridge（未见许可） | 只参考思路（文字稳定一段时间 + 停止按钮判断生成结束），没有复制代码 |
| 多个开源 Gemini 导出工具 | 确认 Gemini 网页结构：`user-query` / `model-response` / `.query-text` / `message-content .markdown`，会话号在 `/app/<id>` |
| browsa | 看过，没有可直接用的 Gemini 适配，未引入 |
| LiveKit / Pipecat / Smart Turn / mem0 / Langfuse | 没有引入：语音链路和打断先用 N.E.K.O. 现成的，出现明确缺口再接 |

## 2. 启动方式

用户的正式步骤见 [README.md](README.md)。云端（Linux、无声音设备）验证用一条命令：

```bash
cd <N.E.K.O.目录> && uv sync            # 只需一次
bash bansheng/scripts/dev_neko_up.sh <N.E.K.O.目录>   # 假模型 + 补丁 + 启动 + 确认数据目录 + 打开“用户插件” + 启动伴生
python bansheng/scripts/smoke_http.py
```

脚本做的两件无头环境必需的事：调用 `/api/storage/location/select` 确认数据目录（首次运行时 N.E.K.O. 会等前端确认，角色不加载），以及 `POST :48915/agent/flags {"user_plugin_enabled": true}`。

## 3. 模块入口与六项约定

链路：**输入事件 → 当前会话理解 → 候选参与内容 → 是否交付 → 声音和桌面表现**

| 阶段 | 位置 |
|---|---|
| 输入事件 | 网页：扩展 `capture.js` + `adapters.js` → `POST /events`；桌面角色：插件 `_watch_host_conversations` 轮询 N.E.K.O. `bus.conversations` → `Companion.observe_host_turn` |
| 会话理解 | `engine/state.py`（条目、来源、改口、路标），由 `engine/mind.py` 的一次模型调用更新 |
| 候选 | `engine/mind.py`（同一次调用产生 0–3 条，带依据和评分；`Policy.style` 切换对照风格） |
| 是否交付 | `engine/gate.py` 的 `decide()`，`engine/companion.py` 每 0.4 秒复查 |
| 声音和桌面 | `NekoDeliverer`：`rephrase` → `push_message(respond)`；`verbatim` → 补丁 `/api/bansheng/speak`；打断 → 补丁 `/api/bansheng/interrupt`；扩展 `overlay.js` 显示路标 |

| 约定 | 字段 |
|---|---|
| 会话身份 | `session_id = "gemini:<会话号>"` / `"chatgpt:<会话号>"`；没有网页讨论时，你和角色的直接对话归到 `neko:desktop`。状态按会话落盘，重开能恢复 |
| 说话者来源 | `speaker = user / main_ai / companion`；`source = gemini_web / chatgpt_web / neko_voice / neko_text / neko / bansheng / replay` |
| 当前交谈对象 | `addressee`：网页提问是 `main_ai`（伴生等主力 AI 回复后再想）；对桌面角色说的是 `companion`（角色自己回答，伴生只更新理解，不另插一句） |
| 状态修订 | `SessionState.revision`；每条候选带 `state_rev`，依据的条目失效就撤销 |
| 取消信号 | 用户新发言、主力 AI 新回复、思考期间有新话、依据失效、超时、静音、重复 → 记录原因；已在播的那句：你接着对主力 AI 说话或静音时调用打断 |
| 真实播放状态 | `Candidate` 上的 `delivered_ts / received_ts / spoken_ts / spoken_end_ts / spoken_text`。`rephrase` 模式由会话流里的 `proactive_instruction`（角色模型收到）和 `proactive_reply`（实际原话与起止时间）填写；`verbatim` 模式由补丁等前端报告播放结束（`audio_completed`）。汇总在 `GET /metrics` |

## 4. 已经验证的（真实 vs 模拟）

| 项目 | 怎么验证的 | 结论 |
|---|---|---|
| N.E.K.O. 在 Linux 启动三个服务 | 实际运行 launcher | 通过（无 Key 时角色模型连接失败，属预期） |
| 伴生插件被 N.E.K.O. 加载、启动、监听 48925 | 实际运行 | 通过 |
| 事件 → 思考 → 候选 → `push_message` 到达 N.E.K.O. 主服务 | 实际运行，日志 `proactive bridge forwarded: plugin=bansheng` | 通过（假模型） |
| 你在 N.E.K.O. 里对角色说的话进入伴生；“伴生，先别说了”关掉主动 | 实际运行：WebSocket 以前端身份发文字 → 会话流 → 插件 → 静音 | 通过（**要先打开“用户插件”开关**；语音输入走同一函数 `is_voice=True`，未用真麦克风测） |
| 打断角色 | 实际运行（补丁）：静音时前端在同一秒收到 `user_activity`；“伴生说话中你又对 Gemini 发了一句”时打断的正是那句的 `speech_id` | 通过（前端信号；扬声器是否真的停要你的电脑验证） |
| 原样说 | 实际运行（补丁）：前端收到这句文字，`audio_sent/audio_queued` 为 true | 通过（这里没配 TTS 声线，没有真实出声） |
| 补丁接口防护 | 没令牌 → 401；带浏览器 Origin → 403 | 通过 |
| 引擎行为（12 项）：约束 → 改口 → 失效；“已确认”要用户原话；用户继续说就撤销；思考期间有新话丢弃；叫停/恢复/嫌吵；旧历史不触发；刷新不重复；卡住降低门槛；重复抑制；对角色说话只更新理解；角色原话与候选配对和延迟统计 | `pytest bansheng/tests` | 通过（脚本模型，验证规则，不是接话质量） |
| 扩展 12 项 | Playwright 加载真实扩展，对模拟 Gemini 页面（同类 Trusted Types 限制），经过 N.E.K.O. 里的插件 | 通过 |
| 风格对照工具 | 示例材料 + 假模型，生成对比页 | 流程通过；**对比本身需要真实模型才有意义** |
| 真实 Gemini 网页采集、真实声音、接话质量 | — | **待验证** |

## 5. 场景表进度

| 场景 | 现在 |
|---|---|
| 用户卡住 | 识别“不知道该抓哪个/卡住/说不清”等，降低门槛、跳过冷却；对主力 AI 说的卡住，会带到它回复之后再想。**接话质量待真实模型** |
| 主力 AI 回答很长 | 只在完整回复后想一次；长回复给 1–6 秒阅读余量；提示词禁止复述总结 |
| 用户安静阅读或停顿后继续 | 不用计时器插话；用户打字 3 秒内、主力 AI 生成中都不说；用户一发言就撤销，已在播的打断 |
| 用户切去查资料 | **未做**：没有读取其他页面；切窗口不被当作走神 |
| 用户反驳或改口 | 改口触发立即思考，`correction` + `retire` 让旧约束失效，依据失效的候选撤销 |
| 用户说“停”“先安静” | 网页文字、卡片按钮、对角色说都有效；装补丁时正在说的那句也立刻打断 |
| 主力 AI 发声 / 回声 | 文字通道不涉及；**Gemini Live 语音共存未验证** |
| 断线、刷新、重连 | 刷新不重复（已测）；思考期间有新话丢弃旧候选；N.E.K.O. 端 12 秒过期 |
| 蓝牙断开、空闲开口 | 由 N.E.K.O. 负责，未单独验证 |
| 结束后再打开 | 会话按会话号落盘，重开同一个 Gemini 对话自动恢复（已测）；导出 Markdown 笔记交给 DeepSeek（D2） |

## 6. 已知缺口与下一处改动

1. **在你的电脑上跑一次真实链路**：Windows + N.E.K.O.（`--patch`）+ 真实模型 + 真实 Gemini 页面。看采集是否稳定、伴生的话像不像人、什么时候烦；`/metrics` 会给出各段延迟。
2. **用真实模型跑风格对照**：`scripts/replay_compare.py`，先用示例材料，再用你真实的一场讨论；按你的标记调提示词（`engine/mind.py`）。
3. **延迟**：目前从回复完成到开口至少有 0.6 秒防抖 + 1–6 秒阅读余量 + 模型思考；`rephrase` 模式还要再加一次角色模型调用。拿到真实数据后再决定是否缩短阅读余量、默认改用 `verbatim`、或提前在主力 AI 生成中途就开始想。
4. **Gemini Live / 原生语音共存**：需要实测 Gemini 语音时网页上有没有文字稿；两边都占麦克风时怎么分辨说话者。
5. **用户正在对角色说话的实时信号**：会话流只在一句话结束后才到；说话中的状态（VAD）目前拿不到，伴生靠“用户一发言就撤销”兜底。
6. 一键包（Steam / exe 版 N.E.K.O.）里插件和补丁放在哪：未验证，目前只验证了源码运行。
7. 交给 DeepSeek 的 D1–D4（[docs/deepseek/](docs/deepseek/README.md)）：ChatGPT 测试、Markdown 归档、弹窗诊断、面板延迟表。

## 7. 你需要补充的最少信息

- 用的是 Windows 吗？Gemini 主要用网页版、手机 App，还是 Gemini Live 语音？
- 一个能调用的模型 API Key（Gemini API 的 AI Studio Key 即可），以及 N.E.K.O. 角色语音想用哪家。
