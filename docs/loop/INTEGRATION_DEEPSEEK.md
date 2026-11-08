# 给 DeepSeek 的集成指南（第二轮）

## 0. 分工与边界

- **内核归 Claude**：`app/android/.../loop/core/**`、`runtime/proactive/dsh-go-proactive/lib/**`、`loop/proactive/ProactiveRuntime.kt` 的命令语义。要改这些，先提问题给 Claude 审，不要直接改。
- **DeepSeek 负责**：下面第 3 节的 D1–D6；普通 Android/Flutter 工程、界面、构建、P0/P1 修复。
- 原 TASKS.md 的 **T4、T6、T8 由本轮内核取代**；**T5（把免打扰/每日上限塞进 TriggerRules）作废**——这些已在 `ProactiveScheduler` 里。T3（通知兜底）改为实现本轮的 `NotificationPort`（D2）。

## 1. 合并顺序

1. `0001-event-loop-v1.patch`（第一轮）
2. `0002-proactive-kernel.patch`：只加新文件，**任何时候都能合**。
3. `0003-proactive-android.patch`：新文件 + 共享文件小改。

`0003` 改到的共享文件和可能的冲突：

| 文件 | 改动 | 冲突风险 / 处理 |
|---|---|---|
| `phone/PhonePrompts.kt` | 加 `onBusyChanged` 字段，在 `schedule()` 开始和 `onFinished` 里各调一次 | 上游安全代码。**只能加这个回调，不许改仲裁逻辑**。冲突时保留上游版本，再手工加这 3 处 |
| `MainActivity.kt` | 注册 `ProactivePlugin` | 插件列表同一位置若有别的新增，按行合并 |
| `AndroidManifest.xml` | `RECEIVE_BOOT_COMPLETED`；`ProactivePageActivity`；`ProactiveWakeReceiver` | 若 DeepSeek 也加了接收器，注意 `exported` 和 intent-filter 不要合并错 |
| `loop/LoopRuntime.kt` | 窗口事件顺带 `ProactiveRuntime.onWindowActivity()`；连接/断开刷新闸门；前台进出权限页刷新闸门 | DeepSeek 若改 `LoopRuntime`（如 T4 旧方案），以本补丁为准 |
| `res/values{,-zh}/proactive_strings.xml` | 新文件 | 无 |

## 2. 本地检查

```sh
# 内核 JVM 测试（66 项）——在 app/android 下
./gradlew :app:testDebugUnitTest --tests 'io.github.ccch1mneyyy.dsh_app.loop.*'
# 插件
cd runtime/proactive/dsh-go-proactive && node --test test/core.test.mjs
# 插件 × 真实引擎（需要按 runtime/locks/dsh-0.2.1-rc.4 装好引擎；锁文件里的 npmmirror 可换成 registry.npmjs.org，完整性哈希不变）
node test/mock-messages.mjs &            # PORT=18901 LOG=/tmp/req.jsonl
node test/e2e-live.mjs <dsh> 18950 18901 /tmp/req.jsonl
```

## 3. 任务

### D1 Dart 侧 Agent 转发（普通）
新建 `lib/proactive/proactive_channel.dart`：
- 订阅 EventChannel `dsh/proactive/agent`。每条 `{op, requestId, path, body?}`：在**当前本机主机**的已认证连接上发 HTTP（`request`/`cancel` 是 POST JSON，`poll` 是 GET），**不要解释应答**，原样回 `MethodChannel('dsh/proactive').invokeMethod('agentHttp', {requestId, status, body})`；网络失败传 `status: 0`。
- 只在本机引擎（不是远程主机）上转发；远程主机时直接回 `status: 404`。
- 用户在任何会话里发出消息时调用 `userActivity`。
验收：Dart 单测用假 MethodChannel 验证：三种 op 的路径/方法/请求体、失败传 0、远程主机不转发。

### D2 通知界面 `NotificationPort`（普通，参照 `NotificationGate.showPrompt`）
- 独立渠道 `dsh-proactive`；按钮"回应"→ `ProactiveRuntime.onSurface(token, Answered("reply"))`，划掉 → `Dismissed`；`setTimeoutAfter(deadline-now)`。
- 每个令牌一个随机 action 的动态接收器、`RECEIVER_NOT_EXPORTED`；`cancel(token)` 取消通知并注销接收器；`cancelAll()` 按 tag 全清。
- **绝不**上报 `Shown`（通知不能证明被看见）。
- 在 `ProactivePlugin.onAttachedToEngine` 里设置 `ProactiveRuntime.notificationPort`。
验收：手动在设置页触发一次（D3），关闭无障碍后应走通知。

### D3 设置页「主动搭话」（普通）
通道 `dsh/proactive`：`status`、`setConfig`（字段同 `ProactiveScheduler.configMap`）、`receipts`。页面放在「手机操控 → 触发测试」旁边：开关、间隔范围、免打扰、每日上限；状态行显示 `wakeMode`（OPPORTUNISTIC 时写明"系统限制了后台，只有打开 DSH 时才会尝试"）、`transport`、`gate.hold`；回执列表显示 `outcome`、`engagement` 和六个时间戳。文案进 `lib/l10n/parts/proactive_{zh,en}.arb`。另外需要一个"目标会话"选择：调插件 `GET /status` 列出活着的会话，`POST /config` 保存。

### D4 打包宿主插件（普通）
`runtime/build-runtime.sh` 把 `runtime/proactive/dsh-go-proactive/{lib,package.json}` 装到 `/opt/dsh-go/dsh-go-proactive/`，并把它加进 `/etc/dsh-app/dsh-web.patch.yml`（与 dsh-go-bridge 相同的方式）。**不要**打包 `test/`。在 `runtime/test-host.sh` 里加一段跑 `e2e-live.mjs`。

### D5 第一轮事件改走内核（🔒 提交前 Claude 审）
`LoopRuntime.verifyNow` 的 `Show` 改为 `ProactiveRuntime.submit(EventRequest(source="A11Y_WINDOW", kind="app_enter", dedupeKey="app_enter:$pkg", needsAgent=false, fallbackText=…))`，保留 `LoopEngine` 的访问状态机和第一轮回执。

### D6 真机验证（人工）
按 ROUND2.md 第 6 节 NOT_TESTED 清单逐项补证据。

## 4. 不许做的事

- 不许直接调用 `PromptArbiter`/`PhonePrompts.present`/`PhoneTasks.begin` 来展示主动消息，也不许伪造任务所有权。
- 不许在 Kotlin 侧绕过 `Coordinator` 直接弹页面或发通知。
- 不许让插件新建或唤醒会话。
- 不许给主动消息加本地兜底文案来"假装"Agent 说了话（第一轮 app_enter 的固定测试文案除外）。
- 不许发送任何屏幕内容给 Agent。
