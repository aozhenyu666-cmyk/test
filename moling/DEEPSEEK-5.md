# 墨灵 + 总控 · 给 DeepSeek 的施工文档（第五批：授权书，A1–A6）

> 2026-10-21。这一批让用户在**总控**里写“授权书”，墨灵按授权书直接让总控做事（60 秒内可以取消）。
> 规则和逻辑 Claude 已经写好并测过（总控 `core/moling/Authority.kt`、`Moling.kt`，墨灵 `Brain.kt`、`link/ZkLink.kt`），**你只做界面和 Android 胶水**。
> 这一批和第四批（`DEEPSEEK-4.md`）**可以分开、并行做**：两批碰的文件不重叠（见第 1 节）。第三批的 M13（`ZkBridge.result`）要先做完。

---

## 1. 开工前

1. 从 Claude 的分支拉一个新分支：`git fetch origin && git checkout -b deepseek-auth origin/ccr-b3c7616e-xlohkd`，再 `git merge origin/deepseek-moling`（拿到你第三批做好的部分）。
2. **只改这些文件**（第四批不会碰它们）：
   - 总控：`zongkong/app/src/main/java/com/zongkong/app/ui/` 下新建 `MolingScreens.kt`；`ui/MainActivity.kt` 加两条路由；`ui/SettingsScreens.kt` 加一个菜单项；`app/link/MolingBridge.kt`（只加函数，不改已有的）。
   - 墨灵：`app/ZkBridge.kt`、`app/ui/SettingsScreen.kt`、`app/ui/TodayScreen.kt` 的顶部状态行、`app/ui/ProposalCard.kt`。
3. **不改**两边的 `core/`（总控的 `core/moling/`、墨灵的 `com/moling/core/`）。觉得要改就写进 DEVLOG。
4. 总控的 CI 一绿就会更新手机上的总控下载链接：**每次推送前在本地跑完总控的单元测试**（`zongkong/` 下 `./gradlew testDebugUnitTest`）。
5. 不加任何权限。

### 你会用到的（都已写好）

总控 `com.zongkong.core.moling`：

| 用途 | 调用 |
|---|---|
| 授权书数据 | `store.moling.value.grants: GrantBook`（`grants: List<Grant>`、`pending: List<GrantChange>`） |
| 改一行 | `store.updateMoling { b -> val (g, msg) = Grants.set(b.grants, kind, next, now); b.copy(grants = g) }`，`next = null` 是删掉；`msg` 用 Toast 显示（“立即生效”或“24 小时后生效（冷静期）”） |
| 一行的字段 | `Grant(kind, maxMinutes, perDay, fromMin, toMin)`；`GrantKind` 六种，`label` 是中文名 |
| 给人看 | `Grants.describe(book)` |
| 建议列表 | `store.moling.value.orders: List<MolingOrder>`（`text`、`state.label`、`receivedAt`、`runAt`、`grant`、`result`） |
| 执行 / 不用 / 取消 | `MolingBridge.run(context, id)`（suspend）、`MolingBridge.decline(context, id)`、`MolingBridge.veto(context, id)` |
| 临时开关 | `store.moling.value.holds`；`Holds.strict(holds, now)`、`Holds.cutToday(holds, now, zone)` |

墨灵 `com.moling.core`：`ZkPort.sendAuto(command)`（你在 `ZkBridge` 里实现）、`ZkWire.toMapAuto(command)`、`ZkStatus.grants / holdUntil / cutToday / openCommitments`、`Proposal.auto`、`Proposal.receipt`。

---

## 2. A1：总控 · 授权书页

**建什么**（`ui/MolingScreens.kt` 的 `GrantsScreen(nav)`，路由 `settings/moling`；设置页“规则”一栏在“拦截名单”下面加 `MenuItem("墨灵的授权书", <一句摘要>, "settings/moling", nav)`，摘要：没有授权写“没有授权：墨灵的建议都要你点执行”，有就写“已授权 N 类”）：

1. 顶部说明（固定文字）：
   > 授权书里的事，墨灵可以不问你、直接让总控做：总控先通知你“60 秒后执行”，你可以取消。
   > 给得更多立即生效；收回或缩小是放宽，24 小时后才生效。紧急放行不受影响。
   > 拦截名单的改动和任何放宽，永远不能授权。
2. 六类各一张卡（`GrantKind.entries`）：开关 + 边界。
   - 开着时显示：每次最多 N 分钟（`FOCUS / STRICT / CUT_PLAY / HQ` 才有，步进 5，5–240）、每天最多 N 次（1–10）、时间段（两个时间选择器，`fromMin` / `toMin`，可以跨夜）。
   - 改完点卡片上的“保存”才调 `Grants.set`（不要每拨一下就保存，否则缩小会排一堆冷静期）。
   - 这一行有等着生效的收回（`grants.pending` 里有这个 kind）时，卡片底部一行小字：“收回 / 缩小在冷静期，X月X日 HH:mm 生效”，旁边“撤回”按钮 = 用原来的那一行再 `Grants.set` 一次（会立即生效，因为不比原来少）。
3. 默认值（第一次打开开关时填进去）：FOCUS 45 分钟 / 3 次 / 08:00–23:00；STRICT 120 / 1 / 08:00–23:30；CUT_PLAY 30 / 1 / 08:00–23:00；HQ 30 / 1 / 08:00–22:00；PLAN 和 COMMIT 只有次数（10 次）和时间段（00:00–00:00 = 全天）。
4. 最下面：今天按授权执行了几条（`orders` 里 `grant` 不为空、今天、`state == DONE`），被你取消了几条（`VETOED`）。

**验收：** 打开 FOCUS、改成 30 分钟保存 → Toast“立即生效”；再改成 20 保存 → Toast“24 小时后生效”，卡片底部出现冷静期那行；点“撤回”→ 那行消失。

---

## 3. A2：总控 · 墨灵的建议列表

通知划掉了，建议就找不到——这一页解决它。

**建什么**（`MolingOrdersScreen(nav)`，路由 `moling/orders`；授权书页顶部一个按钮“墨灵的建议”进来；总控首页如果有 `PENDING` 或 `SCHEDULED` 的建议，在合适的位置放一条横幅“墨灵有 N 条建议等你”点进来——横幅放哪你看首页现有的写法决定）：

- 三组：**等你确认**（`PENDING`，按钮“执行 / 不用”）、**马上执行**（`SCHEDULED`，倒计时秒数 + “取消”）、**最近的**（其余，只显示 `state.label` 和 `result`，最近 20 条）。
- “执行”在协程里调 `MolingBridge.run`，期间按钮显示“在做…”。
- 每 1 秒刷新倒计时（只在这一页可见时）。

**验收：** 墨灵交一条建议，划掉通知 → 总控首页出现横幅 → 点进来能执行；授权内的那条显示倒计时，点“取消”→ 移到“最近的”，状态“你取消了”。

---

## 4. A3：总控 · 临时开关的显示

严管页和首页已经会显示“约好的严管，到 HH:mm”（`Reason.Hold`，Claude 做了）。这一步只补两处：
- 授权书页顶部一行：此刻生效的临时严管（到几点）和今天减了多少分钟娱乐（`Holds.strict` / `Holds.cutToday`），没有就不显示。
- 总控“节律”或首页里显示今天娱乐额度的地方：如果 `cutToday > 0`，在额度后面加“（墨灵约好减了 N 分钟）”。找不到合适的地方就只做第一处，写进 DEVLOG。

**验收：** 墨灵交一条“临时严管 30 分钟”并执行 → 授权书页顶部显示“临时严管到 HH:mm”；打开拦截名单里的应用被弹回，严管页写着“约好的严管”。

---

## 5. A4：墨灵 · 按授权书交

**改什么：**
- `ZkBridge.sendAuto(command)`：和 `send` 一样，只是 Bundle 用 `ZkWire.toMapAuto(command)`（多一个 `auto = true`）。回执用 `ZkWire.receipt(map, now)`（它会读出 `auto`）。
- 大脑已经会：规划者把排计划类、管家把收紧类直接交给总控（`Proposal.auto = true`，不经过“好”）；他点过“好”的也走 `sendAuto`。你不用改逻辑。
- `ProposalCard`：
  - `auto = true` 的卡片左上角一个小标“墨灵直接交的”。
  - `SENT` 时：`receipt` 是“授权内：总控倒计时执行”就显示“60 秒后执行，可以在总控里取消”；之后 `zkOutcome` 有了就显示它（“总控执行了”“你在总控里取消了”……）。
- 对话页 / 今天页：大脑直接交的提议也在卡片里（状态是 SENT），不需要“好 / 不用”按钮——`ProposalCard` 已经按状态显示，确认一下即可。

**验收：** 总控里给 FOCUS 授权；墨灵在管家等级时（调试页能看到等级）水流偏了 → 总控通知“墨灵马上要做 · 取消”，60 秒后专注锁开了；墨灵卡片显示“总控执行了”。参谋 / 规划者等级时，专注锁照旧是带“好 / 不用”的提议。

---

## 6. A5：墨灵 · 看得见授权书和临时开关

- 设置页“总控”那一栏下面：`lastStatus.grants` 原样显示（多行小字）；一个按钮“去总控改授权书”：`packageManager.getLaunchIntentForPackage("com.zongkong.app")?.putExtra("route", "settings/moling")` 打开（总控的 `MainActivity` 认 `route` 这个 extra）。
- 今天页最上面的状态行：`lastStatus.holdUntil > now` 时显示“严管到 HH:mm”；`cutToday > 0` 显示“今天娱乐 −N 分钟”；`openCommitments > 0` 显示“还有 N 条约定”。

**验收：** 设置页能看到授权书的文字，点按钮直接跳到总控的授权书页；临时严管期间今天页顶上写着“严管到 HH:mm”。

---

## 7. A6：真机联调清单

在 `moling/DEVLOG.md` 末尾写一节“授权书真机验收”，把 A1–A5 的验收按顺序排好，并写清楚每一步失败时去调试页看哪一块。

---

## 8. 底线

1. 不改两边的 `core/`；规则（授权书校验、冷静期、60 秒、哪些能授权）全在 core 里，界面只显示和调用。
2. 授权书页不能提供“立即收回”：收回一律走 `Grants.set`（它会自动排冷静期）。紧急放行入口不动。
3. 不加权限。
4. 总控推送前本地跑总控单元测试；墨灵推送前跑墨灵单元测试；CI 绿了再做下一步。
