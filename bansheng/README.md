# 伴生

常驻桌面的中文 AI 伙伴：旁听你和 Gemini / ChatGPT 的讨论，记住关键点，在你卡住、需要反驳或接不上话时自然地接一句。

不从零造轮子，由三块拼成：

| 部分 | 用什么 | 负责 |
|---|---|---|
| 桌面角色、声音、打断 | [N.E.K.O.](https://github.com/Project-N-E-K-O/N.E.K.O)（Apache-2.0） | Live2D/VRM 桌宠、中文实时语音、TTS、你开口时打断它、主动消息的播放闸门 |
| 伴生插件 | `neko_plugin/bansheng/` | 理解讨论、三行路标、准备候选、决定说不说、读你对角色说的话、诊断和延迟记录 |
| 伴生桥扩展 | `browser_extension/` | 从 Gemini / ChatGPT 网页读出对话，页面右下角显示三行路标 |
| 可选补丁 | `neko_patch/` | 给 N.E.K.O. 加本机接口：立刻打断角色说话、原样说一句（更快） |

```
Gemini 网页 ─(伴生桥扩展)─▶ 伴生插件 :48925 ─push_message / 补丁接口─▶ N.E.K.O. 角色开口
                              ▲                                          │
                              └──────── N.E.K.O. 会话流（你对角色说的话、角色实际说的话）
```

## 现在的状态（2026-10-22）

| 项目 | 状态 |
|---|---|
| 插件在 N.E.K.O. 里加载、收事件、让角色开口 | **已在 N.E.K.O. 里实际运行**（假模型） |
| 你对角色说的话进入伴生的理解；说“伴生，先别说了”会关掉主动 | **已在 N.E.K.O. 里实际运行**（文字输入；语音走同一条路径，未用真麦克风测） |
| 角色实际说出的原话、开口/说完时间记回伴生 | 引擎已测；需要真实角色模型才会产生记录 |
| 打断角色（静音、你接着和 Gemini 说话时） | **已在 N.E.K.O. 里实际运行**（需要补丁）：前端收到打断信号，对应同一句话 |
| 原样说模式（不经角色模型改写） | **已在 N.E.K.O. 里实际运行**（需要补丁）：前端收到文字、TTS 已排队；有没有出声要你的电脑验证 |
| 扩展采集、去重、旧历史、打字让出话权、浮层 | **自动测试 12/12**（模拟 Gemini 页面，经真实 N.E.K.O. 插件） |
| 真实 Gemini 网页、真实声音、接话质量、Gemini Live 共存 | 待你在自己电脑上验证 |

详细的选型、能力表、缺口见 [HANDOFF.md](HANDOFF.md)。

## 在 Windows 上跑起来

需要：[uv](https://docs.astral.sh/uv/)、Git、Python 3.11+、Chrome 或 Edge。

**1. 装 N.E.K.O.（源码方式）**

```powershell
git clone https://github.com/Project-N-E-K-O/N.E.K.O.git D:\N.E.K.O
cd D:\N.E.K.O
git checkout 80bdabd1960a0feaf3a2f43f2024b00bda9e4a86   # 本次验证过的版本，可省略
uv sync
.\build_frontend.bat
```

**2. 装伴生插件（加 `--patch` 装可选补丁，推荐）**

```powershell
python <本仓库>\bansheng\scripts\install_into_neko.py D:\N.E.K.O --patch
```

补丁只新增 `main_routers\bansheng_router.py`，并在 `app\main_server\web_app.py` 里加两行；`--unpatch` 可移除。更新 N.E.K.O. 后重新执行一次。

**3. 启动 N.E.K.O. 并配置**

```powershell
cd D:\N.E.K.O
uv run python launcher.py
```

- 浏览器打开 `http://localhost:48911`，第一次会让你确认数据目录；然后在 `/api_key` 页配置角色用的对话/语音服务。
- **打开 Agent 设置里的“用户插件”开关。** 不开的话 N.E.K.O. 不会把你对角色说的话转给插件。
- 插件管理 → **Plugin API**：新建模型槽，例如 Gemini API（[AI Studio](https://aistudio.google.com/) 申请 Key；网页订阅不等于 API）：
  协议 `openai_chat`，地址 `https://generativelanguage.googleapis.com/v1beta/openai/`，模型 `gemini-2.5-flash`。
- 插件列表里找到「伴生」，把 `mind` 用途绑定到这个模型槽，启动它。
- 也可以不用模型槽：启动 N.E.K.O. 前设置 `BANSHENG_LLM_BASE_URL` / `BANSHENG_LLM_API_KEY` / `BANSHENG_LLM_MODEL`。

**4. 装伴生桥扩展**：`chrome://extensions` → 开发者模式 → 加载已解压的扩展程序 → 选 `bansheng\browser_extension`。

**5. 用起来**：打开 `gemini.google.com` 照常聊。右下角卡片显示三行路标；伴生想说话时桌面角色开口，卡片里也显示那句话。

- 卡片上可以静音、调主动程度（关/少/正常/多）、点任意一行直接改字。
- 对角色说（或在网页里写）“伴生，先别说了”“安静”会让它停；“伴生，回来吧”恢复；说它“有点吵”自动少说一档。
- 你直接和角色聊的内容也会进入伴生对这场讨论的理解，但伴生不会在角色回答你时再另插一句。
- 诊断面板：`http://127.0.0.1:48925/`；延迟：`http://127.0.0.1:48925/metrics`。

### 两种说话方式

`plugin.toml` 里的 `[bansheng] voice_mode`，或环境变量 `BANSHENG_VOICE_MODE`：

| 模式 | 怎么说 | 优点 | 代价 |
|---|---|---|---|
| `rephrase`（默认） | 伴生给出“要表达的意思”，角色模型用自己的口吻说 | 声音、人设、记忆一致 | 多一次模型调用；最终措辞不完全由伴生决定 |
| `verbatim`（需要补丁） | 伴生的话原样交给 TTS | 少一次模型调用，更快；能拿到真实播放结束时间 | 措辞是伴生的风格，不是角色的 |

## 不装 N.E.K.O.，先试文字版

```powershell
set BANSHENG_LLM_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai/
set BANSHENG_LLM_API_KEY=你的key
set BANSHENG_LLM_MODEL=gemini-2.5-flash
python bansheng\scripts\run_standalone.py
```

没有声音和桌宠，伴生的话只出现在 Gemini 页面右下角卡片和终端里。`--mock` 用假模型只验证管道。

## 风格对照（接话好不好，用真实材料比）

```powershell
python bansheng\scripts\replay_compare.py bansheng\tests\fixtures\sample_discussion.json
python bansheng\scripts\replay_compare.py 从面板导出的会话.json
```

同一段讨论分别用“自然反应”和“总结与安排”两种风格回放，生成并排对比页。在想接话的句子上点“想接”，觉得烦或假的点“烦”“假”，再把标记复制给开发。会话可以从 `http://127.0.0.1:48925/export?session_id=...` 导出。

## 测试

```bash
python -m pytest bansheng/tests                                   # 引擎 12 项
python bansheng/scripts/run_standalone.py --mock --data /tmp/bs &   # 或用 N.E.K.O. 里的插件
node bansheng/tests/e2e_extension.mjs                             # 扩展 12 项
python bansheng/scripts/smoke_http.py                             # 回放一段三方交流
bash bansheng/scripts/dev_neko_up.sh <N.E.K.O.目录>                # Linux：一键起假模型 + N.E.K.O. + 插件
```

## 分工

小块、边界清楚的任务写成了给 DeepSeek 的指令：[docs/deepseek/](docs/deepseek/README.md)（含 Aider 命令）。核心由 Claude 负责。

## 目录

```
neko_plugin/bansheng/       复制到 N.E.K.O. 的 plugin/plugins/bansheng/
  __init__.py               N.E.K.O. 插件：启动引擎、交付、读会话流、调用补丁
  plugin.toml               插件声明（模型用途 mind、端口、主动程度、说话方式）
  engine/                   不依赖 N.E.K.O. 的核心，可以换底座
    events.py  state.py  mind.py  gate.py  companion.py  httpapi.py  ledger.py  llm.py
    static/dashboard.html   面板
neko_patch/bansheng_router.py   可选补丁
browser_extension/          伴生桥（Chrome/Edge MV3）
scripts/                    安装、独立运行、假模型、回放、风格对照、开发环境
tests/                      引擎单测、扩展端到端测试、示例材料
docs/                       产品说明、执行方案、Gemini 实验、DeepSeek 任务
```
