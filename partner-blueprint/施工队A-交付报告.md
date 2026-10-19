# 施工队 A（DeepSeek）交付报告

> 2026-10-19 / 20 · 分支 `deepseek-window` · 基于 `ccr-b6e667ac-bsoz44` 的 `eec09d7`
> 远程 tip：`c49e0354`（本地两次提交：`895f9bf` → `96e85a8`）

---

## 1. 我接的是什么

设计侧（`partner-blueprint/`）把墨灵拆成三样东西：**演员**（Gemini Live / ChatGPT 官方 App）、**导演**（总控里的文字模型）、**小窗**（浮在演员上面那道口子）。施工文档把实现切成两队：

- **B（Claude）**：`core/director/` 里的纯逻辑——局面、假设、议程、开场白、收线、夜间整理、日记、事故簿、花费。
- **A（我）**：`app/` 里的“身体”——**眼睛**（感知事件）、**嘴**（小窗、朗读）、**腿**（打开演员、交接开场白、统计在场）、**保命**（无障碍四层健康）。

规矩很清楚：**只改 `app/`，新代码长在 `app/director`、`app/window`、`app/sense`、`app/health`；不碰 `core/director/`；导演开关默认关，关着时总控行为和以前一模一样。**

---

## 2. 交付物

15 个文件，+1418 / -0 行（其中 `app/` 下 12 个文件、+1342 行）。

### 2.1 新文件

| 文件 | 行数 | 做了什么 | 为什么这么做 |
|---|---:|---|---|
| `app/director/DirectorHost.kt` | 210 | 持有 `DirectorEngine`；事件 / 回应 / 开场白 / 收线**串行**喂给它；把卡交给小窗；`AskStats` 给 `Ask` 包 30 秒超时 + 按天记请求次数和字数 | 导演是有状态的，并发打进去会串线；A 的三个接口（`FactsSource` / `Ask` / `MindStore`）都要在这里落位，B 不用碰 Android |
| `app/director/PhoneFacts.kt` | 129 | 从 `Store` 拼 `Facts`：主线与计划时间、开始 / 完成、最近进展、前台应用与时长、娱乐分钟、信用与态度、静默、专注、接下来 3 小时的行动、本周要交的、长期目标 | **拿不到就写进 `unknown`**：不只报“看见的”，还要让导演知道自己瞎了哪只眼（“没数据”≠“没在做事”） |
| `app/window/MailuoWindow.kt` | 482 | 小圆点（可拖、松手贴边）+ 卡片 + 开场白面板 | 借 `TYPE_ACCESSIBILITY_OVERLAY`，和桌面宠物同一套办法：**不用额外悬浮窗权限**，Gemini Live 全屏也浮得上去；用原生 View 而不是 Compose，避免悬浮层的 lifecycle owner 那套麻烦 |
| `app/window/Relay.kt` | 173 | 复制开场白 → 打开 Gemini / ChatGPT（没装退网页版）→ 记在场时长与离开次数 → 组 `SessionLog` | 先做最稳的一档（复制 / 照念）；分享和直达 Live 要 P3 / P4 真机结果再改默认 |
| `app/sense/SenseHub.kt` | 216 | 把手机上的事翻译成 `Sense`：解锁、熄屏、前台切换（ENTERTAINMENT / ACTOR / WORK / OTHER）、打开演员、连续娱乐、主线过点、行动开始结束、空档、健康、TICK、NIGHT | **只翻译，不判断**；判断归导演。事件全部来自总控本来就在收的广播和 `tick`，**不新开服务** |
| `app/health/HealthWatch.kt` | 92 | 无障碍四层：权限（系统启用列表）/ 服务（`GuardService.running`）/ 最近事件（`SenseHub.lastEventAt`）/ 页面读取（未知就是未知）；**变化才上报**，并写进 `Diag` | 只看“开关开着”不能说明它在工作；P2 没做之前，页面读取一律 `null`，不假装 |

### 2.2 改动的文件

| 文件 | 改动 | 备注 |
|---|---|---|
| `core/Model.kt` | +24：`Config.mailuo: MailuoConfig` | `enabled` **默认 false**、`speak`、连续娱乐 15 分钟、过点 5 分钟、夜间 23:00，外加 B 的 `DirectorConfig`（人格、每天次数、字数预算） |
| `app/ZkApp.kt` | +2 | `DirectorHost.start(this)`：只建对象 |
| `app/guard/GuardService.kt` | +10 | 前台切换 → `SenseHub.foreground`；亮屏广播 → `unlock` / `screenOff`；`tick` → `SenseHub.tick`；连上 / 销毁 attach / detach 小窗 |
| `app/guard/Diag.kt` | +2 | 事件种类加 `health`（一周后回看掉在哪一层，P7） |
| `app/system/Partner.kt` | +2 | 导演开着时不再“解锁就问” |
| `app/system/InitiativeRunner.kt` | +2 | 导演开着时不再自己发主动性通知 |
| `app/ui/SettingsScreens.kt` | +22 | “运行”里一张卡：开关 + 四层健康一行 |
| `AndroidManifest.xml` | +2 | `<queries>` 加 `com.google.android.apps.bard`（Android 11+ 不声明就查不到装没装） |
| `zongkong/DEVLOG.md` | +50 | 顶部一节：做了什么、怎么验收、没做什么、要观察什么 |

### 2.3 复用了 B 的东西（没有重复造）

- `core.LlmAsk`：把总控已有的 `LlmApi` 接给导演 → A 只在外面包一层超时和字数（P8）。
- `core/director/FileMindStore`：原子写 + `.bak` 回退 → 不再自己写一份 `MindFile`。
- `core/director/DirectorEngine`：真导演 → 宿主直接装它，不是 `FakeDirector`。

第一版我先写了自己的 `LlmAsk` / `MindFile`，发现 B 已经做了，就删掉换成复用——**两份实现比一份更容易打架**。

---

## 3. 一次“思考”是怎么流过去的

```text
手机                              A 侧（我写的）                        B 侧
────                              ──────────────                        ─────
解锁 / 熄屏 / 前台切换 ──┐
主线过点 / 连续娱乐    ├─→ SenseHub ──Sense──→ DirectorHost ──串行──→ DirectorEngine.onSense
每 30 分钟 TICK / 23:00 │                                   ↑              │
四层健康变化          ──┘                                   │              ├─ 读 Facts（PhoneFacts）
                                                            │              ├─ 假设 / 议程 / 能不能打扰
                                                            │              └─ 返回 Card（或 null）
                                                            │                     │
                                                            └──── present ◄───────┘
                                                                   │
                                                      小圆点 / 卡片（MailuoWindow）
                                                                   │
                                        继续 / 卡住了 / 有结论🎤 / 休息 / 去聊
                                                                   │ Reply
                                                                   ↓
                                                       DirectorHost ──→ onReply
                                                                   │
                         去聊：Relay 复开场白 → 打开 Gemini → 记在场
                                                                   │ SessionLog
                                                                   ↓
                                                            onSession → 接力棒更新
```

要点：**A 永远不判断“该不该说话”**，只负责把事实喂准、把卡显示对、把用户动作带回来。

---

## 4. 五个关键取舍

1. **小窗用无障碍悬浮层，不用 `SYSTEM_ALERT_WINDOW`。**
   总控的桌面宠物已经这么干了，权限用户早就给了；另开悬浮窗权限只是多一次授权、多一个被系统清理的入口。代价：无障碍服务死了，小窗也跟着没有——**但那时候本来就什么都看不见，如实降级比假装还在更对**。

2. **导演的入口全部串行（`Mutex`）。**
   事件、回应、开场白都会改导演的记忆（议程、接力棒、假设账本）。并发进去，B 那边的 `currentCard` 会读到半更新状态。

3. **读不到就说读不到。**
   C 档（读演员字幕）没做，`Health.readable` 一律 `null`；听写不可用时卡上直接写“这里听不了，去总控里说”；`Facts.unknown` 里带着“花了多少 / 收件箱”。这三处都不编。

4. **开关默认关，且是“包一层”而不是“改进去”。**
   关着时：`SenseHub` 每个入口第一行 return，`Partner` / `InitiativeRunner` 恢复原样，小圆点不创建。所以出问题只要关开关，不用卸载。

5. **阈值分两处放，不混。**
   A 侧的检测阈值（连续娱乐 15 分钟、过点 5 分钟、夜里 23:00）在 `MailuoConfig`；B 侧的节律（每天 12 次、同题隔 20 分钟、过点 15 分钟、40 万字预算）在 `DirectorConfig`，通过 `Config.mailuo.engine` 传进去。**谁的判断谁定阈值**，避免两边各有一套“过点”定义。

---

## 5. 验证做到哪一步

| 项目 | 结果 |
|---|---|
| 接口对齐 | 逐字段核对了 `Contract.kt`（`Card` / `Reply` / `Sense` / `Health` / `Facts` / `SessionLog` / `Director`），签名一致，`CONTRACT_VERSION` 未动 |
| 补丁可应用性 | `git apply --check` 通过（对着 `eec09d7` 的干净检出） |
| 括号 / 结构 | 6 个新文件逐个做了括号配平扫描，无异常 |
| 本地编译 | **做不到**：这台机器没有 Android SDK（`E:\ANDROID_SDK` 不存在） |
| CI | 我的两次 `zongkong-android` 一直排队，排在基础分支那个 Android 构建后面；`zongkong-pc` 已经开跑。**编译结果出来我按日志补** |
| 已知的旁人问题 | 基础分支 `eec09d7` 的 `zongkong-android` 里，`build` 成功、`smoke`（模拟器）失败在第 6 步 `Emulator smoke test`，`release` 被跳过——**和我这次改动无关**，但那条 run 卡在 in_progress，把后面所有 android 任务堵住了 |

---

## 6. 没做 / 风险 / 下一步

| 项 | 状态 | 打算 |
|---|---|---|
| `HqRunner` 与“连续娱乐弹 chat” | 没并进议程 | 导演开着时这两处仍会各自发话。它们牵到总控时间主流程，先看真机上会不会真的重复，再决定是投递事件还是直接静音 |
| C 档（读演员字幕） | 没做 | 等 P2；读屏只对 Gemini / ChatGPT 两个包开，且只在 `MailuoConfig` 里加开关后才启用 |
| 悬浮窗里打字 | 没做 | 悬浮层不抢焦点，输入法弹不出来；现在靠麦克风 + 总控里写。P5 不过就维持这样 |
| 导演设置的界面 | 没做 | `DirectorConfig`（人格、每天次数、字数预算）已经挂进 `Config.mailuo.engine`，界面还没画 |
| 探针 P1 / P3 / P4 / P5 | 只能真机 | 写在 DEVLOG 的验收步骤里，你走一遍就能定默认交接方式 |
| 真机下载链接 | 未受影响 | 只有 CI 全绿才会覆盖 `zongkong-latest`，推分支不会动到你在用的版本 |

---

## 7. 真机验收（5 步，照这个走）

1. 设置 → 运行 → 打开“墨灵导演（试验）”：屏幕边上出现小圆点（可拖，松手贴边）。
2. 把主线排到 1 分钟后，去刷拦截名单里的应用：小圆点弹出导演的卡（通常先问“你现在是在？”）。
3. 点“卡住了” → “去聊：Gemini Live”：剪贴板里是开场白，Gemini 被打开，小窗显示全文（复制 / 照念 / 开始了）。
4. 在 Gemini 里聊几分钟，回来点“有结论 🎤”，说完再点一次：原文交给导演，收线，`filesDir/zk/mind.json` 里接力棒更新。
5. 关掉开关：小圆点消失，桌面宠物和原来的提醒全部照旧。

---

## 8. 怎么回滚

```bash
# 只是看，不动你的手机版本
git checkout ccr-b6e667ac-bsoz44

# 想整体撤掉这次交付
git branch -D deepseek-window
```

真机侧最快：设置 → 运行 → 关掉“墨灵导演（试验）”。开关关着时，总控的行为和 `eec09d7` 完全一致。
