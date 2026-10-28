# R0–R3 真机实测记录

设备：iQOO 12（V2307A），Android 16 / SDK 36，OriginOS 16.0
测试时间：2026-10-28 20:57–22:06
分支：`claude/r21-strict-takeover`
安装版本：vc45 / 0.8.5-partner-recovery-preview（本轮多次覆盖安装，数据保留）

---

## 一、决定性发现：主动开口被死会话永久堵死（已修，真机验证）

### 现象证据

修复前抓 logcat，连续 45 秒**每秒一条**：

```
I PetProactive: tick gate: session=TEXT
I PetProactive: tick gate: session=TEXT
...（一次都没有走到 presenceTick）
```

### 根因

`PetBrain.tick()` 第 53 行是致命闸门：

```kotlin
session?.let { handleSession(it, now); return }
```

而 `restoreSession()` 只要 `settings.sessionMode` 非空就无条件重建会话。手机上持久化的值是：

```xml
<string name="session_mode">TEXT</string>
<long name="session_started_at" value="1793176357884" />
```

这个会话早就 `state=COMPLETED / endReason=EXPIRED`，但 `session_mode` 从未被清掉
（`finishSession` 会清，但过期路径走不到那里）。于是**每次启动都复活一个死会话**，
`presenceTick` 永远执行不到 → 它就什么都说不了。

### 修法

`restoreSession` 增加陈旧自愈：`now - startedAt > 40 分钟` 就丢弃并清空持久化字段。

### 修复后验证

```
I PetProactive: proactive blocked: 离上次开口不到 108 秒 retryIn=785
```

从「永久卡死」变成「正常计冷却」——说明它真的开过口了。
并且 events 日志确认 21:28:43 说出了一句基于真实屏幕内容的话：

> Gemini Argon——氩气那个氩？这名字是你随手敲错的，还是它真这么叫。

`session_mode` 现在为空。

---

## 二、本轮修复清单

| # | 问题 | 修法 | 验证 |
|---|---|---|---|
| 1 | 死会话复活堵死 tick | `restoreSession` 40 分钟陈旧自愈 | ✅ 真机 |
| 2 | `partnerSampleJob` 完成/异常后不复位，`isActive` 误判永久阻塞 | `finally` 置空 | ✅ 真机 |
| 3 | 视觉与读屏挂在主动候选生成串行链上（"读图读半天才出字"） | 移到 20 秒旁路，只写 2 分钟有效缓存 | ✅ 真机 |
| 4 | `partner.busy` 无终态，可永久卡死 | 45 秒看门狗强制 `cancel` | ✅ 真机 |
| 5 | `presenceTick` 多条裸 `return`，界面永远停在"在想" | 全部补 `partner.waiting(原因, 下次时间)` | ✅ 真机 |
| 6 | 无「停止生成」按钮，busy 时只能退出页面 | `ChatActivity` 加停止按钮；输入框不再因 busy 禁用 | ✅ 已装 |
| 7 | `PartnerReply` 无动作出口（"想返回它做不了"） | 加 `PartnerAction` + `ActionIntent` + Back 路由 | ✅ 已装 |
| 8 | 动作误触：「我昨天返回的时候很烦」被判成 BACK | 白名单 + 从句排除（`的时候/之后/如果`） | ✅ 逻辑验证 |
| 9 | 分身识别只比包名 | `FocusContract.targetUserId` + `identityCertain` + `probeUserId` | ✅ 已装 |

---

## 三、R3 分身识别的诚实结论（重要）

### 本机实测到的事实

| 项 | 实测结果 |
|---|---|
| 分身机制 | vivo 应用分身，`UserInfo{999:应用分身}`（XSpace），**不是** 工作资料 |
| 分身里装了什么 | User 999 共 8 个应用：`com.tencent.mm`、`com.hpbr.bosszhipin`、`com.xingin.xhs` 等 |
| **Gemini 有没有分身** | **没有**。`pm list packages --user 999` 里没有 `com.google.android.apps.bard` |
| 真正有两个实例的 | **微信**：`u0_a410`（机主）与 `u999_a410`（分身）**同时运行** |
| 无障碍服务跨用户范围 | `dumpsys accessibility → currentUserId=0`，**只绑定机主** |
| 分身启动路径 | 先落到 `com.vivo.doubleinstance/.DoubleAppResolverActivity` 选择器 |

### 由此得出的能力边界（不粉饰）

1. **桌宠看不到分身窗口。** 无障碍只绑定机主用户，所以 User 999 里的屏幕内容、可点节点一律拿不到。
2. **桌宠在分身里执行不了动作。** 同上，`DeviceCommand.Back` 只能作用于机主窗口。
3. **只比包名必然出错。** `com.tencent.mm` 有两个实例，`isTarget(pkg)` 会把它们当成同一个。
4. **分身选择器本身会被误判成"离开目标"**：用户点分身时前台先变成 `vivo.doubleinstance`，
   守护会认为他离开了目标应用，可能触发拉回——这就是「我点分身它就还是控制着」的机制之一。

### 因此本轮对 R3 的处理

- `FocusContract` 增加 `targetUserId: Int?`（null = 不确定）与 `identityCertain`。
- `AndroidDeviceController.probeUserId(pkg)`：从 `runningAppProcesses` 取该包进程的 uid，
  换算 `uid / 100000` 得到 userId。**拿不到就返回 null，绝不用 0 冒充机主。**
- 设置页「专注守护」新增一行身份说明：
  - 探测不到 → `身份不确定：探测不到 … 的实例，不自动拉回`
  - userId 0 → `身份确定：守护机主实例`
  - userId 非 0 → `身份不确定：目标在分身用户 N，桌宠的无障碍只绑定机主，看不到也操作不了分身；这一项如实标为未支持`

**R3 的结论是 `BLOCKED_CAPABILITY`，不是 PASS。**
分身守护在当前权限模型下做不到；本轮只做到「不再假装做到了」。

---

## 四、环境坑（会重复咬人，记下来）

1. **adb 必须从 ASCII 路径运行。** SDK 放在含中文的工作区下，`adb start-server` 直接
   `cannot open ...\adb.log: Permission denied` 起不来。解法：把 `adb.exe` + 两个 dll 复制到 `C:\adbwork` 再跑。
2. **OriginOS 会拦截 `am start`**，把启动请求转给系统设置页（`Activity: com.android.settings/...`）。
   用 `monkey -p <pkg> -c android.intent.category.LAUNCHER 1` 可以绕过。
3. **覆盖安装前后 `force-stop` 的顺序会把应用标成 `enabled=0`（禁用）。**
   需要 `pm enable cn.companion.pet` 恢复，否则启动无反应。
4. **构建必须复制到 ASCII 路径**：AGP 8.7.3 拒绝含中文的项目路径。

---

## 五、仍未验证 / 未完成

| 项 | 状态 |
|---|---|
| R2 完整验收（10 分钟 ≥3 次自动开口） | **NOT_RUN** —— 需要一次连续 10 分钟观察，本轮只验证了链路通了、在正常计冷却 |
| 60 分钟频率与原因记录 | **NOT_RUN** |
| 打字/通话/安静期间 0 次插话 | **NOT_RUN**（本轮无人工配合） |
| 打字机「返回上一页」真机端到端 | **PARTIAL** —— 系统层 Back 已验证可用（设置页子页→上级），App 内动作识别已验证逻辑；但**未在聊天页实际说"返回上一页"验证** |
| 「停止生成」真机点击 | **NOT_RUN**（需人工在聊天页操作） |
| R3 分身守护 | **BLOCKED_CAPABILITY** |
| R4 减界面与代码整理 | 未开始 |
| 24 小时真实使用 | 未开始 |
