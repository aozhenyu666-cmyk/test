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

见 [README.md](README.md)。云端验证时用的命令：

```bash
uv sync                                   # N.E.K.O. 目录
python bansheng/scripts/install_into_neko.py <N.E.K.O.目录>
BANSHENG_LLM_BASE_URL=http://127.0.0.1:48999/v1 BANSHENG_LLM_API_KEY=x BANSHENG_LLM_MODEL=mock-mind \
  uv run python launcher.py              # 另开 python bansheng/scripts/mock_llm_server.py 48999
curl -X POST http://127.0.0.1:48916/plugin/bansheng/start
python bansheng/scripts/smoke_http.py
```

## 3. 模块入口与六项约定

链路：**输入事件 → 当前会话理解 → 候选参与内容 → 是否交付 → 声音和桌面表现**

| 阶段 | 位置 |
|---|---|
| 输入事件 | 扩展 `capture.js` + `adapters.js` → `POST /events` → `engine/events.py`、`SessionState.accept` |
| 会话理解 | `engine/state.py`（条目、来源、改口、路标），由 `engine/mind.py` 的一次模型调用更新 |
| 候选 | `engine/mind.py`（同一次调用产生 0–3 条，带依据和评分） |
| 是否交付 | `engine/gate.py` 的 `decide()`，`engine/companion.py` 每 0.4 秒复查 |
| 声音和桌面 | `neko_plugin/bansheng/__init__.py` 的 `NekoDeliverer` → N.E.K.O.；扩展 `overlay.js` 显示路标 |

| 约定 | 字段 |
|---|---|
| 会话身份 | `session_id = "gemini:<会话号>"` / `"chatgpt:<会话号>"`，状态按会话落盘，重开能恢复 |
| 说话者来源 | `speaker = user / main_ai / companion`，`source = gemini_web / chatgpt_web / replay …` |
| 当前交谈对象 | `addressee`；网页里的提问固定是 `main_ai`，伴生此时不另起一轮，等主力 AI 回复后一起想 |
| 状态修订 | `SessionState.revision`；每条候选带 `state_rev`，依据的条目失效就撤销 |
| 取消信号 | 用户新发言、主力 AI 新回复、思考期间有新话、依据失效、超时、静音、重复 → 记录原因 |
| 真实播放状态 | **缺口**：只知道“已交给 N.E.K.O.”（`submitted`），不知道是否真的播完 |

## 4. 已经验证的（真实 vs 模拟）

| 项目 | 怎么验证的 | 结论 |
|---|---|---|
| N.E.K.O. 在 Linux 启动三个服务 | 实际运行 launcher | 通过（无 Key 时模型连接失败，属预期） |
| 伴生插件被 N.E.K.O. 加载、启动、监听 48925 | 实际运行 | 通过 |
| 事件 → 思考 → 候选 → `push_message` 到达 N.E.K.O. 主服务 | 实际运行，日志 `proactive bridge forwarded: plugin=bansheng` | 通过（**模型是假模型**） |
| 角色真的用声音说出来 | — | **待验证**：云端没有前端会话、麦克风、扬声器和对话模型 Key |
| 引擎行为：约束 → 关联 → 改口后旧约束失效；“已确认”必须有用户原话；准备了但用户继续说而撤销；思考期间有新话丢弃旧候选；叫停/恢复/嫌吵；旧历史不触发；刷新不重复；卡住时降低门槛；重复抑制 | `pytest bansheng/tests`，9 项 | 通过（脚本模型，验证的是规则，不是接话质量） |
| 扩展：旧历史标 backfill、打字让出话权、流式只发一次完整回复、浮层显示路标和伴生的话、刷新不重复 | Playwright 加载真实扩展，对**模拟的** Gemini 页面（页面启用了和 Gemini 同类的 Trusted Types 限制），12 项 | 通过 |
| 真实 Gemini 网页采集 | — | **待验证**（云端没法登录） |
| 非法来源拦截 | 带 `Origin: https://evil.example` 或非本机 `Host` 的请求 | 403 通过 |

## 5. 场景表进度

| 场景 | 现在 |
|---|---|
| 用户卡住 | 识别“不知道该抓哪个/卡住/说不清”等，降低门槛、跳过冷却；提示词要求给具体切入点。**接话质量待真实模型** |
| 主力 AI 回答很长 | 只在完整回复后想一次；长回复给 1–6 秒阅读余量；提示词禁止复述总结 |
| 用户安静阅读或停顿后继续 | 不用计时器插话；用户打字 3 秒内、主力 AI 生成中都不说；用户一发言就撤销 |
| 用户切去查资料 | **未做**：没有读取其他页面；切窗口不被当作走神 |
| 用户反驳或改口 | 改口触发立即思考，`correction` + `retire` 让旧约束失效，依据失效的候选撤销 |
| 用户说“停”“先安静” | 网页文字和卡片按钮有效；**对着角色语音说暂时不关主动**（见缺口） |
| 主力 AI 发声 / 回声 | 文字通道不涉及；**Gemini Live 语音共存未验证** |
| 断线、刷新、重连 | 刷新不重复（已测）；思考期间有新话丢弃旧候选；N.E.K.O. 端 12 秒过期 |
| 蓝牙断开、空闲开口 | 由 N.E.K.O. 负责，未单独验证 |
| 结束后再打开 | 会话状态按会话号落盘，重开同一个 Gemini 对话自动恢复（已测落盘恢复） |

## 6. 已知缺口与下一处改动

按优先级：

1. **在你的电脑上跑一次真实链路**：Windows + N.E.K.O. + 真实模型 + 真实 Gemini 页面。重点看采集是否稳定、伴生说的话是否像人、什么时候烦。记录“你说完 → 第一个字的声音”的时间。
2. **读到你对角色说的话**：`self.bus.conversations`（N.E.K.O. 插件 SDK 的只读会话流）看起来能拿到角色对话，需在真实语音会话里确认里面有没有用户转写，再接成 `speaker=user, addressee=companion` 事件。接上后语音“别说了”能关主动，伴生也能把你对角色说的话算进理解。
3. **真实播放状态与中断**：N.E.K.O. 没有给插件的“立即停止角色语音”和“播放完成”回执；目前静音时只能停止后续发言。需要在 N.E.K.O. 加一个小接口，或从 bus 事件里找 `voice_play_end`。
4. **Gemini Live / 原生语音共存**：需要实测：Gemini 语音时网页上有没有文字稿；两边都占麦克风时怎么分辨说话者。
5. **角色会改写伴生的话**：伴生给出“要表达的意思”，N.E.K.O. 角色模型用自己的口吻说。好处是声音和人设一致；坏处是最终说的字和伴生准备的不完全一样，诊断记录里记的是“意思”。
6. **接话质量调提示词**：用产品说明里的两种策略（偏总结 vs 自然反应）在同一段真实材料上对照；可以用 `scripts/smoke_http.py` 的方式把真实会话导出后回放。
7. 一键包（Steam / exe 版 N.E.K.O.）里插件放在哪：未验证，目前只验证了源码运行。

## 7. 你需要补充的最少信息

- 用的是 Windows 吗？Gemini 主要用网页版、手机 App，还是 Gemini Live 语音？
- 一个能调用的模型 API Key（Gemini API 的 AI Studio Key 即可），以及 N.E.K.O. 角色语音想用哪家。
