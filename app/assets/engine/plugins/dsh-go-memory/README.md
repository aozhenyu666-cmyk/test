# dsh-go-memory（手机本地记忆权威）

大肥鱼的长期记忆放在**手机本地**，不是 PC、不是 SurfCore、也不是任何笔记软件。
这个插件就是唯一的写入口和读入口。

## 权威在哪

```
$DSH_HOME/fish-memory/
├── fish-memory.sqlite     ← 权威库（SQLite，WAL）
├── memory.json            ← 只有在运行时没有 node:sqlite 时才用
├── export/                ← 面板里「导出 Markdown」的产物（只读镜像）
└── .imported-fish-memory  ← 旧库迁移完成的标记
```

`DSH_HOME` 默认是 `~/.dsh`，在手机上就是 DSH 运行环境里的家目录。
文件权限 0600，目录 0700；没有网络监听，没有账号，没有云同步。

权威规则只有一条：**任何地方要改记忆，都必须走这里的工具或面板。**

## 存了什么

| 表 | 作用 |
|---|---|
| `facts` | 事实 / 偏好 / 承诺 / 事件。带 scope、importance 0-2、pinned、key、expiresAt、hits |
| `topics` | 还没聊完的话题：状态 open/done/deferred/dropped，带下次可问时间和尝试次数 |
| `episodes` | 共同经历（`kind=event` 时自动记一条），按天取 |
| `tombstones` | 忘掉的原文哈希；同一句话不会悄悄被记回来 |
| `hits` | 召回痕迹（谁在什么时候因为什么被想起），最多 2000 条 |

### 一条记忆的生命周期

- **同 key 只留最新**：`她.身高` 从 170 改成 172 时，旧条目标 `superseded`，不删除、可回溯。
- **纠正**：`memory_correct` 写一条新版本，旧版本标 `corrected`，面板里能看到「改过」。
- **忘掉**：`memory_forget` 标 `forgotten` + 写 tombstone；同一句话再记会被拒绝，除非 `force`。
- **淡忘**：没钉住的条目按 45 天半衰计算清晰度（最低 0.18），只影响召回排序，不删数据。
- **过期**：带 `expiresInDays` 的临时状态到点后不再召回、也不再出现在面板的「有效」里。

### 作用域

- `session:<会话 id>`：默认。只在这个会话 + 全局可见。
- `global`：所有会话可见（用户说「以后都记住」、称呼、承诺、身体禁忌用这个）。
- `workspace:<键>`：预留给按工作区隔离，目前只存不特殊处理。

`memory_search(scope=all)` 才能跨所有会话，平时不要用。

## 五个工具

| 工具 | 什么时候用 |
|---|---|
| `remember` | 有长期价值的事：重要的话、约定、喜好、身体/情绪状态、共同经历 |
| `memory_search` | 用户问「你还记得…」时先查，别凭印象编 |
| `memory_correct` | 用户说「不是这样 / 你记错了 / 现在改成…」 |
| `memory_forget` | 用户明确说「忘掉这个 / 别记这个」 |
| `memory_topic` | 记/查/完成/搁置/放掉「还没聊完的事」 |

工具说明里写死了两条产品规则：

1. **一条只装一件事**，日常琐事不记；
2. **记的时候安静地记**，回复里不说「记下了」「我记住了」。

## 原生面板

- 输入框旁的「记忆」按钮，点开面板（不再占状态条：多个插件状态条每 6 秒轮播，看起来像闪）。
- 面板：搜索、最近条目、点开一条看全文 / 钉住 / 忘掉、纠正表单、未完成话题（完成/搁置/放掉）、记一条新的、导出 Markdown。
- 面板里的写入和工具走同一套函数，不存在两套逻辑。

## 自动召回

每轮请求装配时，用这个会话最近一句用户消息当线索：

- 取本会话 + 全局的 active 事实，按 `相关度 ×（重要度 + 钉住 + 新鲜度 + 命中） × 清晰度` 排序，取前 6 条；
- 附 3 个未完成话题、当天 3 条共同经历；
- 以 `systemPrompt.context`（对话末尾）注入，**不碰系统提示词**，保住前缀缓存；
- `interpolate: false`：记忆里出现 `{{ }}` 也不会被当模板解析；
- 1.5 秒内同一会话重复求值直接复用，避免一次请求重复检索/落盘。

## 迁移

第一次打开时，把旧的 `dsh-fish-memory` 会话库（`$DSH_HOME/fish-memory/<会话id>.json` 里的 `memories`）搬进权威库：

- 旧 `importance` 0~1 → 0/1/2 三档；
- `pinned` / `firstMet` → 钉住；
- 统一落到 `global`，打 `迁移` 标签；
- **只读旧文件，不删不改**；失败下次再试，成功写 `.imported-fish-memory`。

## 参考与许可

- [dsh-fish-memory](https://github.com/Yuuyuko-uu/dsh-fish-memory)（Yuuyuko-uu 插件许可 1.2，允许修改与再分发）：
  借鉴了「按会话分库、同样的事实只留最新、相关度×（基础+新鲜+重要+情绪）、用 context 注入保缓存」这些规则。没有复制源码。
- [Muse](https://github.com/muse-ai/muse)（GPL-3.0，**只做设计参考**）：
  借鉴了 importance 0/1/2、pinned、expires_at、tombstone、scope 隔离、主动介入要有冷却和额度。
  没有复制任何代码，因此本插件保持 MIT。

## 限制（明说）

- 笔记软件（Obsidian / Logseq）目前是**单向镜像**：面板导出 Markdown，导入笔记软件；不做双向同步。
- 没有加密数据库（SQLite 明文，靠 Android 应用沙箱 + 0600）。要更高强度得另做 SQLCipher。
- 主动回合的「该不该现在问」还需要和 `dsh-speak-up` / `dsh-fish-strict` 的额度、安静时段合流，v0.1 只提供 `nextTopic()` 查询接口。
