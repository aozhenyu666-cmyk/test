# 同桌双形态训练（批次 A + B + C）

日期：2026-10-25。基线 `9d9ad27`（0.6.2-preview / 29）。
分支 `feature/companion-dual-mode-training`，版本 **0.7.0-preview / 30**。只改 companion-pet/。

## 一、模块核验（全部存在，非占位）

FocusGuard / ForegroundGate / GoalSupervisor / AgentLoop / StuckDetector /
IgnoreEscalation / ThinkPrompt / StrictSessionMachine 全部真实存在。
`interactionMode`、题库原来不存在，本轮新增。

## 二、用户明确的强制要求（规则，不是建议）

`core/training/EnforcementRules.kt`

| 触发 | 动作 |
|---|---|
| 技术故障 | `DOWNGRADE_TECHNICAL`，不计违规 |
| 正常回答 | `NONE` |
| 无待答问题 | `NONE` |
| 第 1 次拒绝/未答/编造 | `ONE_MORE_CHANCE` |
| 第 2 次起（WARM） | **`FORCE_COACH`：强制切严格刷题** |
| 已在 COACH 再违规 | **`LOCK_COACH_SLOT`：锁当前槽位，不退化** |

保证：不会自动退回温和；技术故障不怪用户；紧急退出始终可用；不扩大到整机锁控。

## 三、训练的四个闭环

### 1. 主动开场 → 单步动作 → 精确反馈
`openTraining()` 立即出题（题干 + 第一问），不留空白。点选句子 / 打字 / 说“我不会”。

### 2. 认知门禁（不审判用户）
`TrainingPrompt` 输出结构化建议：verdict / evidenceRefs / missingStep / nextPrompt / assistanceLevel / uncertainty。
只有标签时追问具体跳跃；已有具体理由时不因含标签词而拒绝。CLOSED 题引外部信息回题设；OPEN 题允许质疑前提。

### 3. 卡住 → 支架 → 恢复（`core/training/TrainingTimer.kt`）
- 首次 25 秒（长题按字数加读题时间，封顶 20 秒）→ 给二选一支架
- 支架后 20 秒 → 一次提示
- 再 20 秒 → 进入 RECOVERY，停止催促
- 录音、转写、模型请求、外部打断、用户说“在想”期间**暂停计时**
- 单调时钟（`elapsedRealtime`），不跟随系统时间

### 4. 跨应用强制（`core/training/TrainingFocusBridge.kt`）
训练未完成且离开到非允许 App → `BLOCK_BACK_TO_TRAINING`，气泡给“回到训练”。
允许的阅读/资料 App 不拦；前台不明不拦（避免误伤）；训练结束不拦、不延长。

## 四、持久化恢复

`core/training/TrainingStore.kt`：
- 落盘时机：出题、提交、支架、恢复、完成、结束
- 恢复：回到原题原槽位，清空过期 pendingRequestId，返回空窗毫秒数
- 完成/结束的会话不复活（墓碑）
- 文件损坏报错不崩

`core/training/RestGrant.kt`（练习换休息）：
- `FIXED_FOCUS` 永不产生娱乐许可
- `PRACTICE_FOR_BREAK` 一次训练只发一次（sessionId+eventId 幂等）
- 未过期不叠加；到期标记撤销；重启不重复发放

## 五、验证

JVM：**492 tests / 0 failures**（基线 441 + 训练相关 51）
lint：0 errors / 15 warnings
APK：30,805,325 bytes，SHA-256 `145E56D7C92D22F533E6D72E213539AD1D4A7B90B46B4E6444C1013735B7084D`

新增测试文件：
- TrainingControllerTest 14
- EnforcementRulesTest 8
- TrainingTimerTest 8
- TrainingStoreTest 7
- TrainingFocusBridgeTest 7
- RestGrantTest 7

## 六、入口与开关

- 桌宠菜单 → **开始陪练**（立即出题）
- 设置 → **陪练与专注策略**：练习换休息开关 + 休息分钟数
- 设置 → 严格陪伴：原有严格回合（0.6.2 的 StrictSession）保留不变

## 七、仍未完成 / 未验证

### 未实现
- **点选句子高亮**：当前是“选第 1 句”按钮，不是可点文本
- **语音全双工**：仍走按住说话 + 打字
- **题库导入**：无文件选择器（批次 C 的一手导出适配）
- **拦截层 UI**：跨应用时用气泡提示 + “回到训练”，未接 FocusOverlay 全屏层
- **第三槽**：数据结构支持，首批题目只用两槽
- **NFC 锚点**：按报告定义为可选增强，未做

### 未验证（全部 NOT_RUN）
- 真机全部场景（无设备、无模拟器）
- 25/20/20 秒参数在真机上的实际体感
- iQOO 上“回到训练”能否真的把 MainActivity 拉到前台
- 跨应用拦截在真实 App 切换下的触发时机
- 杀进程恢复后题号槽位是否真的对上

## 八、回退

- 训练入口是菜单新增项，不点不影响原有陪伴
- `focusPolicyType` 默认 FIXED_FOCUS（不产生休息许可）
- 强制规则只在训练进行中触发，不影响普通聊天
- 不改动无障碍拦截、防卸载、专注契约、StrictSession
