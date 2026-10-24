# B 阶段：受限工具执行

基线：A 提交之后；版本 0.3.3 / versionCode 6。
目标：让“说它做了什么”有可核对结果，而不是新增一段宣称角色聪明的提示词。

## 第一批允许的动作

| kind | 用途 | 适配器 |
|---|---|---|
| `READ_MEMORY` | 读取可见记忆（profile + live facts，按上限） | `AndroidActionAdapters` |
| `READ_MATERIALS` | 读取收件箱活跃素材 | 同上 |
| `SAVE_NOTE` | 保存用户明确要求的念头 | ThoughtNotes + NOTE 事件 |
| `OPEN_ASSISTANT` | 打开已配置的 Gemini/ChatGPT/千问 | Assistants + GeminiLauncher |
| `SPEAK` | 通过系统 TTS 播报 | Speech（必须设置里已打开） |

明确不包含：无障碍写操作、任意 Shell、外部评论/私信、QQ 群发、读取私有目录、新增权限。

## 结果与安全

- 四态：`SUCCESS / UNAVAILABLE / FAILED / CANCELLED`，带短 reason 与可选 data，界面不能只显示“已执行”。
- 每个动作有 execution ID；同一 ID 只执行一次，重试返回同一结果；并发同 ID 也只会调用一次 adapter。
- 可取消；`cancelAll` 在锁屏时由 PetBrain 调用，正在执行的动作统一返回 CANCELLED。
- 参数白名单与长度上限；未知参数、空文本、超长参数直接 FAILED。
- 主动决策第一轮只做本地 dry-run：`ActionPlanner` 检查安静时段、用户在场、预算、来源、冷却，输出准备做什么/依据/为何现在；不启动模型循环。
- 没有模型工具调用；本地动作不消耗 AtomicBudget。本轮没有“模型尝试次数”可记录，动作次数与既有模型请求路径保持分开。
- 真实打开 AI App 的结果来自 GeminiLauncher；fake 测试里的 SUCCESS 不冒充真机已打开。

## 文件

- `core/action/ActionRunner.kt`：kind/request/result/status、adapter、执行器、取消、dry-run planner
- `service/AndroidActionAdapters.kt`：五个现有能力适配器
- `PetApp.kt`：全局 `actions`
- `service/PetBrain.kt`：锁屏 `cancelAll()`
- `core/action/ActionRunnerTest.kt`：7 项

## 验证

```text
:app:testDebugUnitTest          PASS   217 tests / 0 failures
:app:lintDebug                  PASS   0 errors / 5 warnings
:app:assembleDebug              PASS   28,415,017 bytes
:app:assembleDebugAndroidTest   PASS   仪器 APK 仅编译
```

APK SHA-256：`2F4B7E94BC8B66D8A9F68926B3A975FEBE83551A83CBF69EAC8FF25F4590FF9A`

新增 `ActionRunnerTest` 7 项：成功/失败/不可用/参数校验、同 ID 只执行一次、并发同 ID 只执行一次、取消晚到成功、cancelAll、dry-run 条件、adapter UNAVAILABLE。

## 未执行 / 边界

- 没有真机点击/打开 App/TTS 证据；本轮验证全部是 fake adapter 的 JVM 测试，不能声称真实 App 已打开。
- 没有模型工具调用；不新增每轮请求，也不消耗预算。若以后接入，必须先走 AtomicBudget，并限制最多 3 次尝试。
- 本轮没有新增工具 UI 入口；`PetApp.actions` 已可被调用，界面操作入口留给下一轮或现有调试入口。
- 动作结果不会自动变成角色台词；仍需接线方决定如何展示。