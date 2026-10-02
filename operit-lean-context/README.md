# 精简上下文：Operit AI 插件 + Skill

给 [Operit AI](https://github.com/AAswordman/Operit) 用的省 token 插件，主要解决三件事：

1. **上下文太长**：每次发送前自动压缩旧的工具结果、旧的长消息，超长对话按批次截断，自动总结也写得更短
2. **每轮固定开销大**：可选「简洁回答」规则和「精简工具说明」，减少每轮都要带上的系统提示 / 工具说明
3. **测试对话、一问一答就结束的对话太多**：一个工具找出来，确认后批量删除；长对话还能一键「接力」到新对话

**只改发给模型的那份内容，手机上的聊天记录一个字都不动。** 随时可以在输入框菜单里关掉。

安装包：[`dist/lean_context-0.1.0.toolpkg`](dist/lean_context-0.1.0.toolpkg)

---

## 安装

### 插件（推荐）
1. 把 `dist/lean_context-0.1.0.toolpkg` 下载到手机
2. Operit → 工具包 / 插件管理 → 导入外部包，选这个文件
3. 确认「精简上下文」和子包「精简上下文工具」都是启用状态
4. 回到聊天，点输入框旁的菜单，能看到「精简上下文」几个开关就装好了

> 需要支持 ToolPkg 的 Operit 版本（ToolPkg API 1.0.0）。

### Skill（可选，和插件配合更好，也可以单独用）
把 `skills/lean-chat/` 整个文件夹复制到手机的 `Download/Operit/skills/` 下，
即 `Download/Operit/skills/lean-chat/SKILL.md`。它教 AI 什么时候该建议接力新对话、怎么写交接摘要、怎么排查 token 消耗。

---

## 输入框菜单里的开关

| 开关 | 作用 |
|---|---|
| **精简上下文** | 总开关。描述里会显示上次压缩效果，例如「上次 152k→38k 字」 |
| **精简强度 ▶ 标准** | 点一下在 轻度 / 标准 / 激进 之间切换 |
| **简洁回答** | 往系统提示末尾加 5 行规则：直接回答、不贴工具原文、按需读文件、少做重复调用 |
| **精简工具说明** | 去掉每个工具的长说明（details / notes），描述截到 160 字。工具一个不少，只是说明变短 |

## 三档强度

| | 轻度 | 标准（默认） | 激进 |
|---|---|---|---|
| 原样保留最近几轮 | 6 | 3 | 2 |
| 原样保留最近几个工具结果 | 10 | 6 | 3 |
| 更早的工具结果截到 | 2000 字 | 800 字 | 300 字 |
| 更早的 AI 回复 / 用户消息截到 | 4000 字 | 1500 字 | 600 / 800 字 |
| 最多发送多少轮 | 不限 | 30 | 10 |
| 历史总字数上限 | 不限 | 不限 | 4 万字 |
| 精简工具说明 | 关 | 关 | 开 |
| 自动总结目标字数 | 2000 | 1200 | 800 |

压缩的细节：

- **截断是「留头留尾、中间省略」**，并标上 `[精简: 省略 N 字]`，AI 知道这里被压过，需要时会重新调用工具取原文
- **工具调用和工具结果总是成对处理**，XML 外壳原样保留，不会破坏原生 Tool Call 的配对
- **同一个工具返回了完全相同的大段内容**（比如反复读同一个文件），后面的只留一句「与前面一次结果相同」
- **按批次推进（照顾缓存）**：DeepSeek、Claude 等按前缀命中缓存。压缩边界每 4 轮（激进 2 轮）才往后挪一次，中间几轮发出去的前缀完全一样，缓存照样命中
- 超过「最多发送轮数」时整轮整轮地丢，并在第一条保留的用户消息前加一句「更早的 N 轮没有发送给你」
- 系统提示、自动总结生成的摘要永远保留；当前这条用户消息永远不改

想微调某个数字，可以在 Operit 的环境变量里设 `LEAN_CTX_OVERRIDES`，例如：

```json
{"keepFloors": 4, "toolResultMax": 500, "maxFloors": 20}
```

---

## AI 可以调用的工具

插件启用后，直接用自然语言让 AI 做就行：

| 你可以说 | 工具 | 说明 |
|---|---|---|
| 「看看精简模式现在什么状态」 | `lean_status` | 当前设置 + 上次压缩前后的字数 |
| 「精简调成激进」「关掉简洁回答」 | `set_lean_mode` | 改开关和强度 |
| 「帮我清理一下测试对话和一问一答的对话」 | `clean_trivial_chats` | **先只列出来**，你确认后才删除。默认找「只有 ≤2 条消息、2 小时没动过」的对话，也可以按标题匹配（如 `测试\|test\|新对话`）。当前对话永远不删 |
| 「这个对话太长了，接力到新对话」 | `handoff_new_chat` | AI 先写 300~800 字交接摘要，插件新建对话、起好标题「接力·原标题」，把摘要作为第一条消息发过去。旧对话保留 |
| 「自动总结调成上下文用到一半就总结」 | `tune_auto_summary` | 默认只查看；确认后写入当前对话模型的「自动总结阈值」 |

---

## 推荐搭配的 Operit 设置

插件管得住「每一轮开始时」发出去的内容，但 **Operit 不允许插件改同一轮里工具调用之间的上下文**
（一轮里 AI 连续调用几十次工具时，中间的请求不会经过插件钩子）。这部分要靠 Operit 自带的自动总结兜住：

- 打开模型配置里的 **自动总结**，**阈值调到 0.5 左右**（或者直接让 AI 调 `tune_auto_summary`）
- 插件会让自动总结的输入先瘦身（大段工具输出先截短）、输出控制在目标字数内，总结本身也更省

其他省 token 的习惯：
- 关掉用不上的工具包和 MCP —— 每个启用的工具都会把说明塞进每一轮的提示词
- 角色卡设定、记忆 / 用户画像不要写得太长，它们也是每轮都带
- 一个话题聊完就新开对话；聊长了用「接力」

---

## 开发

```bash
cd operit-lean-context
npm test        # 29 个单元测试：压缩逻辑、钩子、子包工具（用假宿主 API）
npm run pack    # 生成 dist/lean_context-<version>.toolpkg
```

也可以用 Operit 仓库自带的钩子调试器直接跑：

```bash
node <Operit>/tools/toolpkg/toolpkg_hook_runner.js --source . \
  --kind prompt_finalize --event before_finalize_prompt --payload @payload.json --pretty
```

文件结构：

```
manifest.json            包描述（toolpkg_id: com.lean.context_slimmer）
main.js                  注册钩子：发送前压缩、估算压缩、系统提示、工具说明、自动总结、输入菜单开关
lib/compactor.js         压缩算法（纯函数，无宿主依赖）
lib/config.js            三档预设 + 环境变量读写
packages/lean_tools.js   子包：5 个工具
skills/lean-chat/        Skill（单独复制到手机）
test/                    单元测试
scripts/pack.js          打包脚本（无依赖）
```

用到的 Operit 钩子：`registerPromptFinalizeHook`、`registerPromptEstimateFinalizeHook`、
`registerSystemPromptComposeHook`、`registerToolPromptComposeHook`、`registerSummaryGenerateHook`、
`registerInputMenuTogglePlugin`。
