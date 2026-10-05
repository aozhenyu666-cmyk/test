# Operit 主控体系：核心对话台 · 思考线 · 伴读 · 对话门

整体思路一句话：**让"动脑"成为每天的默认状态。** 靠三件事：一条记住你想到哪的思考线，一个像活人一样陪你看的伴读，一道离开前要过一下脑子的对话门。身体用现有的 Operit 工作流，不重建。

先读 [`docs/01_总体战略.md`](docs/01_总体战略.md)（一页），再读 [`docs/02_详细设计.md`](docs/02_详细设计.md)。

## 目录

| 路径 | 内容 | 给谁 |
|---|---|---|
| `docs/01_总体战略.md` | 一页纸战略：三件事、一个身体、三张脸，一周后看什么 | 你 |
| `docs/02_详细设计.md` | 组件、思考线写入、伴读流程、对话门状态机与参数、执行通道、工作流、验收 | 你 / 开发方 |
| `rounds/` | 现行各轮指令（总控计划、给 Codex 和 Operit A/B 的任务单） | 三方 |
| `gemini/伴读Gem设定.md` | Gemini「伴读」Gem 的设定正文、开场白、按材料追加提示 | 你（贴进 Gemini） |
| `roles/角色卡说明.md` | 新建「守门人」角色卡；小满只加两条 | 你 / Operit |
| `skill/zhukong/SKILL.md` | Operit 里的 AI 使用这些工具的规则 | Operit |
| `toolpkg/` | 主控中枢 ToolPkg 源码（`com.community.zhukong`） | Operit |
| `dist/` | 打包好的 `.toolpkg`、Skill 压缩包、SHA256SUMS | Operit |
| `tests/` | 电脑上的模拟测试；cognitive_core 用其 v0.2.0 的真实状态机 | 开发方 |

## 主控中枢提供的工具（前缀 `zhukong:`）

| 工具 | 作用 |
|---|---|
| `status` | 今日钥匙、冷却、进行中的门和伴读、通道绑定、思考线状态 |
| `configure` | 修改名单、问题、钥匙参数、伴读时间、说话方式、守门人会话、执行路由 |
| `gate_open` / `gate_answer` / `gate_submit` / `gate_decide` | 对话门：开门、逐题记录原话、面板一次提交、守门人决定 |
| `gate_voice` | 切到守门人会话并进入语音 |
| `gate_tick` | 定时：超时未答记失误并延长冷却；到期钥匙回锁并提醒 |
| `reading_start` / `reading_open_material` / `reading_finish` | 伴读：登记材料并打开 Gemini、打开材料、收线 |
| `thread_capture` | 任何 AI 对话、棋局、题目之后收一句线 |
| `reading_invite` / `thread_nudge` | 定时：伴读邀请、未收线提醒 |
| `install_workflows` | 创建/更新定时工作流（默认停用） |

## 安全边界

- 不直接冻结或解冻任何 App；真实执行只走绑定后的现有锁队列和解锁闸门。未绑定时钥匙只记账。
- 默认参数对齐现有解锁政策（每天 2 把、每把 10 分钟、最长 15 分钟）。放宽属于立法，由你决定。
- 只记录你的原话；思考线暂缓时不自动接续，收线先存本地，不会丢。
- Operit 永远不能进入重度名单。

## 开发

```bash
cd operit
npm test            # 电脑上的模拟测试（Node 18+，无依赖）
npm run pack        # 生成 dist/zhukong-<版本>.toolpkg、Skill 压缩包和 SHA256SUMS
```

电脑测试通过不代表真机通过。真机上尚未安装验证，见 `docs/02_详细设计.md` 第 8 节。
