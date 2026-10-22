# 伴生

常驻桌面的中文 AI 伙伴：旁听你和 Gemini / ChatGPT 的讨论，记住关键点，在你卡住、需要反驳或接不上话时自然地接一句。

不从零造轮子，由三块拼成：

| 部分 | 用什么 | 负责 |
|---|---|---|
| 桌面角色、声音、打断 | [N.E.K.O.](https://github.com/Project-N-E-K-O/N.E.K.O)（Apache-2.0，原样使用） | Live2D/VRM 桌宠、中文实时语音、TTS、你开口时打断它、主动消息的播放闸门 |
| 伴生插件 | `neko_plugin/bansheng/`（本目录） | 理解讨论、维护三行路标、准备候选、决定说不说、诊断记录 |
| 伴生桥扩展 | `browser_extension/`（本目录） | 从 Gemini / ChatGPT 网页读出对话，页面右下角显示三行路标 |

```
Gemini 网页 ─(伴生桥扩展)─▶ 伴生插件 :48925 ─push_message─▶ N.E.K.O. 角色开口
                              │
                              └─ 面板 http://127.0.0.1:48925/
```

## 现在的状态（2026-10-22）

| 项目 | 状态 |
|---|---|
| 伴生插件在 N.E.K.O. 里加载、启动、收事件、把“开口”消息交给 N.E.K.O. | **已在云端 Linux 实际运行**（用假模型） |
| 扩展在 Chromium 里采集、去重、标记旧历史、通知打字、显示浮层 | **已自动测试 12/12**，但页面是**模拟的 Gemini 结构** |
| 真实 Gemini 网页采集 | 待你在自己电脑上验证（云端没法登录 Gemini） |
| 角色用中文声音说出来、你开口打断它 | 待验证（云端没有麦克风、扬声器和模型 Key；这部分由 N.E.K.O. 现有链路负责） |
| 接话质量 | 待验证（还没接真实模型，测试里是脚本模型） |
| Gemini 原生语音（Live）共存 | 待验证 |

详细的选型理由、能力表和缺口见 [HANDOFF.md](HANDOFF.md)。

## 在 Windows 上跑起来

需要：Python 3.11 的 [uv](https://docs.astral.sh/uv/)、Git、Chrome 或 Edge。

**1. 装 N.E.K.O.（源码方式）**

```powershell
git clone https://github.com/Project-N-E-K-O/N.E.K.O.git D:\N.E.K.O
cd D:\N.E.K.O
git checkout 80bdabd1960a0feaf3a2f43f2024b00bda9e4a86   # 本次验证过的版本，可省略
uv sync
.\build_frontend.bat
```

**2. 把伴生插件放进去**

```powershell
python <本仓库>\bansheng\scripts\install_into_neko.py D:\N.E.K.O
```

**3. 启动 N.E.K.O. 并配置模型**

```powershell
cd D:\N.E.K.O
uv run python launcher.py
```

- 浏览器打开 `http://localhost:48911/api_key`，按 N.E.K.O. 的说明配置角色用的对话/语音服务。
- 打开插件管理 → **Plugin API**，新建一个模型槽。例如用 Gemini API（[AI Studio](https://aistudio.google.com/) 申请 Key；网页订阅不等于 API）：
  - 协议 `openai_chat`
  - 地址 `https://generativelanguage.googleapis.com/v1beta/openai/`
  - 模型 `gemini-2.5-flash`（或你想用的）
- 在插件列表里找到「伴生」，把它的 `mind` 用途绑定到这个模型槽，然后启动它。
- 也可以不用模型槽，改用环境变量 `BANSHENG_LLM_BASE_URL` / `BANSHENG_LLM_API_KEY` / `BANSHENG_LLM_MODEL`（启动 N.E.K.O. 前设置）。

**4. 装伴生桥扩展**

Chrome 打开 `chrome://extensions` → 右上角打开“开发者模式” → “加载已解压的扩展程序” → 选 `bansheng\browser_extension`。

**5. 用起来**

打开 `gemini.google.com`，照常聊天。右下角卡片显示三行路标；伴生想说话时，桌面角色会开口，卡片里也会显示那句话。

- 卡片上可以静音、调主动程度（关/少/正常/多）、点任意一行直接改字。最快的叫停方式是卡片上的「静音」。
- 你在网页对话里写的“先别说”“安静”这类短句也会让它停下；“伴生，回来吧”恢复；说它“有点吵”会自动少说一档。
- 目前插件还读不到你**对着桌面角色说的话**（那部分由 N.E.K.O. 自己回答），所以语音里说“别说了”暂时只能让角色当场停，不会关掉伴生的主动发言。这是下一步要补的地方。
- 完整诊断：`http://127.0.0.1:48925/`（每句话依据了哪段内容、为什么说或没说、模型耗时）。

## 不装 N.E.K.O.，先试文字版

```powershell
set BANSHENG_LLM_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai/
set BANSHENG_LLM_API_KEY=你的key
set BANSHENG_LLM_MODEL=gemini-2.5-flash
python bansheng\scripts\run_standalone.py
```

没有声音和桌宠，伴生说的话只出现在 Gemini 页面右下角卡片和终端里。只想验证管道可以加 `--mock`（假模型，不聪明）。

## 测试

```bash
python -m pytest bansheng/tests            # 引擎：记忆、改口、取消、叫停、去重（9 项）
python bansheng/scripts/run_standalone.py --mock --data /tmp/bs &   # 先起服务
node bansheng/tests/e2e_extension.mjs      # 扩展：在 Chromium 里对模拟 Gemini 页面（12 项）
python bansheng/scripts/smoke_http.py      # 对正在运行的伴生回放一段三方交流
```

## 目录

```
neko_plugin/bansheng/       复制到 N.E.K.O. 的 plugin/plugins/bansheng/
  __init__.py               N.E.K.O. 插件：启动引擎、把“开口”和“上下文”交给角色
  plugin.toml               插件声明（含模型用途 mind）
  engine/                   不依赖 N.E.K.O. 的核心，可以换底座
    events.py               输入事件：会话、说话者、来源、是否完整
    state.py                会话理解：主题/约束/已确认/未解/待考虑/改口，条条带来源
    mind.py                 一次模型调用：更新理解 + 准备 0–3 条候选
    gate.py                 播放前判断：取消条件、话权、门槛、冷却、重复
    companion.py            调度：防抖、思考、撤销、交付、持久化
    httpapi.py              本机接口（只接受扩展和本地请求）
    ledger.py               诊断记录
    static/dashboard.html   面板
browser_extension/          伴生桥（Chrome/Edge MV3）
scripts/                    安装、独立运行、假模型、回放
tests/                      引擎单测、扩展端到端测试
```
