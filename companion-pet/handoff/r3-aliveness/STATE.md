# R3「活人感」状态记录

分支 `feature/r3-aliveness`｜本轮起点 `a9afee6`｜最新提交 `84f9a64`

## 做了什么

把同桌上从「规则触发 → 临时想一句」的克制提醒器，改成「手里攒着想说的事 → 挑时机说 → 说完划掉」的活人，并且真的有自己的阅读生活与情绪。

| 任务 | 内容 | 提交 |
|---|---|---|
| T1 | 它自己的生活：活动模型 + 按真实时间推进 + 缺勤上限一天 | `16071f2` `de69d7b` |
| T2 | DeepSeek Search 通道：可溯源结果 + 显式失败种类 | `12f1d6b` |
| T3 | 挑一件真东西读：**无来源即禁止产出** | `f6d59b7` |
| T4 | 想说的话队列 + 说过的话账本（防重样硬拦） | `76f282e` |
| T5 | 时刻识别 + 闭嘴规则（打字/全屏视频/三分钟内） | `218d9c0` |
| T6 | 按星期与时段分配活跃度，拒绝优先不可回退 | `77db7c7` |
| T7 | 人设允许讲自己的事、必须不编造 | `fd6355c` |
| T8 | 接线：意图优先 + 默认开启 | `84f9a64` |

## 本轮真实构建数字（2026-10-28 实测）

| 项 | 值 |
|---|---|
| JVM 测试 | **683 tests / 0 failures / 0 errors / 0 skipped**（93 suites） |
| 基线 | 611（R21 vc39）→ 本轮 **+72** |
| lint | **0 errors / 14 warnings**（基线 17 warnings，无新增） |
| assembleDebug | 成功 |
| APK 字节数 | **30,357,019** |
| APK SHA-256 | `C71859C1D2A40444E7D4EA39DD31CE1C99249E7A58ECE82396F71852FF14F6A1` |
| @Test 注解计数 | JVM 91 文件 / 680 个；instrumented 8 文件 / 10 个 |

> `testAnnotations` 680 与 `tests.total` 683 的差额来自循环/参数化生成的用例，两者口径不同，不可互相替代。

## 验证边界（必须如实看）

1. **真机 A01–A36 / U01–U12 全部 `NOT_RUN`** —— 无设备。本轮只有 JVM 单测与构建，没有任何真机行为证据。
2. **Search API 真实可用性未经用户密钥验证**。所有搜索路径都没有打过真实网络请求；T2 只验证了响应解析与失败分类，`NO_KEY` / `QUOTA` / `TIMEOUT` 的分支靠构造结果覆盖。
3. **`assembleDebugAndroidTest` 本轮未跑**，不得记成通过。
4. **本地构建需复制到 ASCII 路径**：仓库路径含中文（`敖震宇`），AGP 8.7.3 直接拒绝。本轮在 `C:\pet-verify2\companion-pet` 构建，并用逐文件 SHA-256 比对确认被测源码树与仓库一致（278 个 `app/src` 文件，277 完全相同，1 个仅注释文案不同、断言数值一致）。
5. **它自己的生活状态已落盘**（`OwnLifeStore`，原子替换 + 损坏另存备份）。但**该落盘路径与自读闭环是在最后一轮补的，未经过任何编译或测试验证**（按用户要求本轮不跑验证），需要 Claude 侧先跑一次构建。
6. **意图队列与账本仍是内存态**，重启清空。重启后不会重复说同一句（账本清空），但也记不得昨天说过什么。

## 未做（按计划明确排除）

- Notion 同步、向笔记本写入、GPT 中转服务器 —— 本轮不做。
- 原计划排除项之外，本轮最后补了 `OwnLifeStore`（持久化）与 `OwnLifeCycle`（搜索→挑选→并入状态的编排），并把 `SearchClient` 接进了真实调用链（`PetBrain.maybeReadOwnLife`）。**这部分是未经验证的代码**。

## 本轮发现的真实缺陷（已修，有测试）

1. **句末语气词「吗」未过滤** → 「是在找东西吗」与「是在找东西吧」归一化后不等，换个语气词就能把同一句话再说一遍。已在 `TextNormalize.PARTICLES` 补「吗」，并把受影响的相似度实测值重算为 0.8333 / 0.6471。
2. **「加尾注稀释」能绕过重复检测** → 只说句原样 + 补一句解释，对称 Jaccard 掉到 0.59（< 0.72）会被放行。改为 `overlap = max(Jaccard, 交集/较短者)`，稀释不再降级。
3. **测试自身的假绿**：`OwnLifePlanner` 的缺勤上限测试原本因为 `totalHint` 把两边都夹平而自证；已改为断言具体档数（24h = 48 档）且 `!= 144`。

## 最后补的一轮接线（未经编译/测试验证）

按用户明确要求「不用连接和审查测试」，以下改动**只做了静态自检（括号配平、符号有定义），没有跑过任何编译或测试**：

| 新增/改动 | 内容 |
|---|---|
| `core/life/OwnLifeStore.kt` | 生活状态落盘：原子替换、损坏文件另存备份、写失败保留旧状态。纯 I/O，无业务判断 |
| `core/life/OwnLifeCycle.kt` | 编排层：`fromSearch`（经 ReadingPicker 把关）→ `adopt`（同 id 覆盖、最多两件、不动情绪）→ `promptLine`（只陈述可溯源事实） |
| `PetApp` | `searchApi`（无 Key 时返回 null）、`ownLife` 改为经 Store 读写、`writeOwnLife` 单线程队列异步落盘、`advanceOwnLife` 无变化不写盘 |
| `PetBrain.maybeReadOwnLife` | 手上没在读的东西才去搜（45 分钟一次）；搜→模型挑→ReadingPicker 核对；任何失败静默 |
| `PetBrain.queueOwnLifeIntent` | 意图队列的**唯一真实来源**：只从可溯源的生活行生成，与刚说过的话太像就不入队 |
| `PetBrain.speakFromIntentIfAny` | 时刻信号从"恒 true"改为真实事件：`ownThoughtReadyAt`（真攒出新话）、`returnedToAppAt`（真回到桌宠应用）、`isTypingNow`（真实输入事件） |
| `ProbeMoment` 填充 | `ownLife` 与 `recentSayings` 真正喂进提示词（否则 T7 白做） |

### 已知未接项（诚实记录）

- **全屏视频检测没有可靠来源**（`SceneClassifier` 只有 LEARNING / NORMAL / EXCLUDED）。`isFullscreenVideoNow` 显式保持 `false`，闭嘴规则本身仍在 `MomentWatcher` 里，等接上真实检测改一行即可。
- **`OwnLifeStore` 尚未在设置页显示 `lastError`**，诊断信息已备好但没有 UI 入口。
- **`OwnLifeCycleTest`（12 个用例）从未跑过**，其中若干断言依赖精确字符串（如 `正在读《万物简史》，读到 3/20，感想：开头有点难`），很可能需要按实际输出微调。
