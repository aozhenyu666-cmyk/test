# dsh-go-proactive：Android ↔ DSH Agent 接口契约 v1

DSH 宿主插件。把手机端的一条主动事件交给**一个已经活着、正空闲**的 DSH 会话，作为一次 `followup` 回合，再把这回合的结果如实报回 App。

零依赖 ESM，和 `dsh-go-bridge` 一样可以按绝对路径加载：

```yaml
# --patch proactive.patch.yml
- insert:
    - id: dsh-go-proactive
      name: /opt/dsh-go/dsh-go-proactive/lib/index.js
```

## 已在真实引擎上核实的前提（@deepseek-ai/dsh 0.2.1-rc.4，dsh-go 锁定版本）

| 事实 | 出处 |
|---|---|
| `ctx.agents.get(id)` 只返回**活着的** agent；`status` 只有 `idle` / `running`（等审批、等回答也算 running） | `dsh-agent/lib/index.js` AgentRegistry.get、`dsh-agent-loop` ReactLoopAgent.status |
| `agent.followup(msg)` 在这个版本是**同步**的：放进 `next-turn` 收件箱并唤醒；agent 正在跑时，消息排到**当前回合之后**另开一轮 | `dsh-agent-loop` send/followup/wakeDriver |
| 收件箱消息只能是 `role: "user"`；`developer` 角色会让回合报错 | dsh-speak-up 的源码证据，`dsh-session` validateSessionEventData |
| 会话事件：`turn/start {turn}`、`user/message`（数据就是那条消息，含我们给的 `id`）、`assistant/message {turn, step, message:{content}, interrupted?}`、`turn/end {turn, reason:{kind}}`，kind ∈ completed / blocked / aborted / error / max-tokens | `dsh-agent-loop` turn()/step() |
| `ctx.on('session/event', (session, event))` 能收到上面所有事件 | `dsh-session` commit() |
| 插件自注册的路由**不自动鉴权**；要自己调 `connection.requestRejection(req)` | `dsh-client-connection`、`dsh-go-bridge` rejectionOf |

## 路由（前缀 `/dsh-go-proactive/v1`，全部要求 /api 同等凭据：无凭据 401、跨站 403、未就绪 503）

### `POST /request`

```json
{ "v": 1, "requestId": "8–128 位 [A-Za-z0-9_-]", "eventId": "同左",
  "kind": "app_enter", "payload": { "packageName": "com.example", "localTime": "09:15" },
  "sessionId": "可选，缺省用 /config 设的", "deadlineMs": 45000 }
```

| 应答 | 含义 |
|---|---|
| `202 {state:"accepted", …}` | 消息已进 agent 收件箱。**不是**回合开始，更不是生成成功 |
| `200 {…记录…}` | 同一个 requestId 重放：只报告，不再发第二次 |
| `409 no-session` | 没指定、也没配置目标会话 |
| `409 no-agent` | 会话不存在或没活着。**插件从不新建或唤醒会话** |
| `409 archived` | 会话已归档 |
| `409 agent-busy` | agent 正在跑（包括用户自己正在对话、等审批）或收件箱有待处理消息 |
| `409 desk-busy` | 插件已有一个在途请求（一次只处理一个） |
| `409 event-in-flight` | 同一个 eventId 已有在途请求 |
| `409 request-id-conflict` | requestId 已用于另一个 eventId |
| `409 followup-failed` | followup 当场抛错 |
| `400 bad-*` / `413` / `415` | 参数、大小、content-type 不对 |

payload 只放事实（包名、本地时间、计数），最多 16 个键、每个值 ≤ 200 字。**App 永远不发送屏幕内容。**

### `GET /result?requestId=…&waitMs=0–25000`

长轮询：状态变化或超时就返回。记录：

```json
{ "v": 1, "requestId": "…", "eventId": "…", "sessionId": "…", "state": "accepted|claimed|generated|failed|cancelled",
  "acceptedAt": 0, "claimedAt": null, "turn": null, "generatedAt": null,
  "text": "仅 generated 时有", "truncated": false, "failure": { "code": "…" }, "cancelledAt": null, "deadline": 0 }
```

| state | 证明了什么 |
|---|---|
| `accepted` | 消息在收件箱 |
| `claimed` | agent 循环认领了它（`user/message` 已写入会话日志），回合 `turn` 正在跑 |
| `generated` | 该回合以 completed / max-tokens 结束，且有可见文本。**只说明模型给出了文字，不说明用户看到了** |
| `failed` | `no-text`、`turn-error`、`turn-aborted`、`turn-blocked`、`agent-gone`、`deadline`、`followup-rejected` |
| `cancelled` | App 撤回；之后到达的文本永不交出（计入 `counters.lateText`） |

插件重启后记录丢失（只在内存），旧 requestId 查询返回 `404 not-found`，App 侧解释为 `lost`。

### `POST /cancel {requestId}`

记录变为 `cancelled`。**不中止**已经开始的回合：那一轮属于会话本身，它的文字会照常留在会话历史里，只是不再交给 App 展示。

### `GET /status`、`POST /config {sessionId}`

当前目标会话、活着的根 agent 列表、计数器；设置目标会话（存到 `$DSH_HOME/storages/dsh-go-proactive/config.json`）。

## 给模型的文字

```
（下面不是用户说的话，是 DSH Go 手机端发来的一条主动交互事件。请据此主动对用户说一两句自然、简短的话；
不要调用任何工具，尤其不要操作手机；不要提到“事件”“系统”这些字眼。）
kind=app_enter
packageName=com.example
```

角色只能是 user（见上表），所以用文字框住"这不是用户说的"——dsh-speak-up 已经踩过"被当成用户在说话"的坑。

## 测试

- `node --test test/core.test.mjs`：纯逻辑 10 项，不需要引擎。
- `node test/e2e-live.mjs <dsh可执行文件> <端口> <假模型端口> <假模型日志>`：起真实 `dsh web`，模型换成 `test/mock-messages.mjs`（本地 Messages API 假服务），26 项检查。结果见 `docs/loop/ROUND2.md`。
- `test/fixture-session/` 只给端到端测试用：新建会话、模拟用户发消息。**不要随插件发布。**
