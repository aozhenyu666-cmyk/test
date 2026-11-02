# P0-A 任务单 · 一手这一侧（给 DeepSeek，照做即可）

> **你只改 `aozhenyu666-cmyk/test` 仓库里的 `yishou/`**。DSH Go 那一侧已经由审查方写完，不要碰 dsh-go 仓库。
> 先读 `yishou/AGENTS.md`，那里的规矩全部适用（中文提交、不顺手重构、先纯逻辑再接 Android）。

## 这次要做成什么（一句话）

DSH Go 的拦截页上有个按钮「去一手答一道题」。点了以后，**一手**弹出它自己的思考页出一道题；答对并且判题通过后，一手把一张"凭证"交还给 DSH Go，DSH Go 核对凭证无误才放行 B站。

**一手决定"答没答对"，DSH Go 只核对"这张凭证是不是这一次拦截的"。** 你这一侧绝不能在没有真正通过时发凭证。

## 协议（一个字都不要改，DSH Go 那边已经按这个写死了）

DSH Go 一侧的定义在 dsh-go 仓库 `app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/loop/core/YishouProtocol.kt`，下面是同一份内容：

| 名字 | 值 |
|---|---|
| 版本 `VERSION` | `1` |
| 一手包名 | `com.yishou.app` |
| DSH Go → 一手 的 action `ACTION_TRAIN` | `com.yishou.app.action.TRAIN_FOR_DSH` |
| 一手 → DSH Go 的 action `ACTION_PASS` | `io.github.ccch1mneyyy.dsh_app.action.YISHOU_PASS` |
| `EXTRA_VERSION` | `dsh_protocol`（Int） |
| `EXTRA_EVENT_ID` | `dsh_event_id`（String） |
| `EXTRA_NONCE` | `dsh_nonce`（String，一次性密钥，**原样带回，不许改、不许记日志**） |
| `EXTRA_TARGET_PACKAGE` | `dsh_target_package`（String，比如 `tv.danmaku.bili`） |
| `EXTRA_TARGET_LABEL` | `dsh_target_label`（String，比如「哔哩哔哩」） |
| `EXTRA_REPLY_PACKAGE` | `dsh_reply_package`（String，DSH Go 自己的包名，凭证要发回这里） |
| `EXTRA_PASS_MINUTES` | `dsh_pass_minutes`（Int，1–120） |
| `EXTRA_REF` | `dsh_ref`（String，≤120 字，一手自己的记录编号，比如 `pass:17`） |

流程：

```
DSH Go  startActivity(Intent(ACTION_TRAIN).setPackage("com.yishou.app"))
        附带 VERSION / EVENT_ID / NONCE / TARGET_PACKAGE / TARGET_LABEL / REPLY_PACKAGE
一手    DshTrainActivity 校验 → 打开 GateActivity（DSH 模式）→ 出题、判题
        判题通过、PassIssuer 真的发出了 Pass 的那一刻（finishGrant）：
        startActivity(Intent(ACTION_PASS).setPackage(REPLY_PACKAGE))
        附带 VERSION / EVENT_ID / NONCE（原样）/ PASS_MINUTES / REF
        然后关掉思考页（DSH Go 会自己把 B站 打开）
```

---

## 规则

```
1. 从 ccr-a5172662-j1jcik 切出新分支 feat/dsh-handoff-2026-11-02。不 force push，不碰其他分支，不合并。
2. 每个 Y 任务一个提交，提交说明用中文。先写纯逻辑和 JVM 单元测试，再接 Android。
3. 只改下面点名的文件。不写报告文档，最后只在 yishou/docs/dsh-handoff.md 写一页（≤60 行）。
4. 推分支后看 yishou-android 这个 CI 的结果；单元测试或打包失败就停下，把失败日志原文贴出来，不要绕开。
5. 状态只写 DONE / STOPPED(原因)。没在手机上试过的，不许写"通过"。
```

---

## Y1 · 纯逻辑：解析请求、拼凭证（不碰 Android）

新建 `yishou/app/src/main/java/com/yishou/app/dsh/DshHandoff.kt`，**不得 import android.\***：

```kotlin
package com.yishou.app.dsh

object DshHandoff {
    const val VERSION = 1
    const val ACTION_TRAIN = "com.yishou.app.action.TRAIN_FOR_DSH"
    const val ACTION_PASS = "io.github.ccch1mneyyy.dsh_app.action.YISHOU_PASS"
    const val EXTRA_VERSION = "dsh_protocol"
    const val EXTRA_EVENT_ID = "dsh_event_id"
    const val EXTRA_NONCE = "dsh_nonce"
    const val EXTRA_TARGET_PACKAGE = "dsh_target_package"
    const val EXTRA_TARGET_LABEL = "dsh_target_label"
    const val EXTRA_REPLY_PACKAGE = "dsh_reply_package"
    const val EXTRA_PASS_MINUTES = "dsh_pass_minutes"
    const val EXTRA_REF = "dsh_ref"

    /** 没有对应应用组时用这个组名；它的放行时长走默认值。 */
    const val DSH_GROUP = "DSH Go"

    /** DSH Go 的包名：正式版本身，或者带后缀的测试版（.r4、.mine 等）。 */
    private const val DSH_PACKAGE = "io.github.ccch1mneyyy.dsh_app"

    data class Request(
        val eventId: String,
        val nonce: String,
        val targetPackage: String,
        val targetLabel: String,
        val replyPackage: String,
    )

    /** 只检查形状，任何一项不对就返回 null。get 按名字取一个 extra。 */
    fun parseRequest(get: (String) -> Any?): Request? {
        if ((get(EXTRA_VERSION) as? Number)?.toInt() != VERSION) return null
        val eventId = (get(EXTRA_EVENT_ID) as? String)?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
        val nonce = (get(EXTRA_NONCE) as? String)?.takeIf { it.length in 16..64 } ?: return null
        val target = (get(EXTRA_TARGET_PACKAGE) as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 255 } ?: return null
        val reply = (get(EXTRA_REPLY_PACKAGE) as? String)
            ?.takeIf { it == DSH_PACKAGE || it.startsWith("$DSH_PACKAGE.") } ?: return null
        val label = (get(EXTRA_TARGET_LABEL) as? String)?.take(64)?.takeIf { it.isNotBlank() } ?: target
        return Request(eventId, nonce, target, label, reply)
    }

    /** 发回 DSH Go 的凭证内容。minutes 夹到 1..120。 */
    fun passExtras(req: Request, minutes: Int, ref: String): Map<String, Any> = linkedMapOf(
        EXTRA_VERSION to VERSION,
        EXTRA_EVENT_ID to req.eventId,
        EXTRA_NONCE to req.nonce,
        EXTRA_PASS_MINUTES to minutes.coerceIn(1, 120),
        EXTRA_REF to ref.take(120),
    )
}
```

新建测试 `yishou/app/src/test/java/com/yishou/app/dsh/DshHandoffTest.kt`，至少 7 个用例：
1. 一份完整的请求能解析出来，五个字段都对；
2. 版本是 2 → null；
3. nonce 只有 8 位 → null；
4. reply 是 `com.evil.app` → null；
5. reply 是 `io.github.ccch1mneyyy.dsh_app.r4` → 能解析；
6. 没有 label → label 等于包名；
7. `passExtras` 原样带回 eventId 和 nonce，minutes=500 时变成 120，minutes=0 时变成 1。

**验收**：`./gradlew testMainlineDebugUnitTest --tests 'com.yishou.app.dsh.*'` 全过（本机没有 SDK 就推分支看 CI，并贴出 CI 里这个测试类的结果）。

## Y2 · 入口：一个只做校验的跳板 Activity

1. 新建 `yishou/app/src/main/java/com/yishou/app/dsh/DshTrainActivity.kt`：

```kotlin
package com.yishou.app.dsh

import android.app.Activity
import android.os.Bundle
import com.yishou.app.gate.GateActivity

/**
 * DSH Go 请一手出一道题。这里只校验请求，然后打开思考页；
 * 思考页本身不导出，别的应用不能直接带着参数打开它。
 */
class DshTrainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent?.extras
        @Suppress("DEPRECATION")
        val req = if (savedInstanceState == null && extras != null) DshHandoff.parseRequest { extras.get(it) } else null
        if (req != null) startActivity(GateActivity.dshIntent(this, req))
        finish()
    }
}
```

2. `yishou/app/src/main/AndroidManifest.xml` 里，在 GateActivity 那段后面加：

```xml
<!-- DSH Go 请一手出一道题（见 dsh/DshHandoff.kt）。导出是因为 DSH Go 是另一个应用；
     这里只校验请求，真正的思考页仍然不导出。 -->
<activity
    android:name=".dsh.DshTrainActivity"
    android:exported="true"
    android:excludeFromRecents="true"
    android:noHistory="true"
    android:theme="@android:style/Theme.NoDisplay">
    <intent-filter>
        <action android:name="com.yishou.app.action.TRAIN_FOR_DSH" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

3. `GateActivity.kt` 的 `companion object` 里加：

```kotlin
private const val EXTRA_DSH = "dsh_request"   // 只在一手内部用，存的是整个 Request

fun dshIntent(context: Context, req: com.yishou.app.dsh.DshHandoff.Request): Intent {
    val group = (context.applicationContext as com.yishou.app.YishouApp)
        .settings.app.value.groupOf(req.targetPackage) ?: com.yishou.app.dsh.DshHandoff.DSH_GROUP
    return Intent(context, GateActivity::class.java)
        .putExtra(EXTRA_PACKAGE, req.targetPackage)
        .putExtra(EXTRA_GROUP, group)
        .putExtra(EXTRA_DSH + "_event", req.eventId)
        .putExtra(EXTRA_DSH + "_nonce", req.nonce)
        .putExtra(EXTRA_DSH + "_reply", req.replyPackage)
        .putExtra(EXTRA_DSH + "_label", req.targetLabel)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
```

（先确认 `YishouApp` 里 `settings` 的真实写法——`GateService.check()` 里是 `app.settings.app.value`，照它的写法来。）

**为什么用 B站 原来的组**：一手放行是按组发的。用同一个组，一手自己的门禁之后看到这张放行，也不会再拦一次 B站。

**验收**：`./gradlew assembleMainlineDebug` 通过（或 CI 打包通过）。

## Y3 · 判题通过的那一刻发回凭证

1. `GateActivity.startFromIntent()` 里，在 `vm.start(pkg, group, label)` 之前读出 DSH 请求：

```kotlin
val dsh = intent.getStringExtra(EXTRA_DSH + "_event")?.let { ev ->
    com.yishou.app.dsh.DshHandoff.Request(
        eventId = ev,
        nonce = intent.getStringExtra(EXTRA_DSH + "_nonce").orEmpty(),
        targetPackage = pkg.orEmpty(),
        targetLabel = intent.getStringExtra(EXTRA_DSH + "_label").orEmpty(),
        replyPackage = intent.getStringExtra(EXTRA_DSH + "_reply").orEmpty(),
    )
}
vm.dsh = dsh
```

2. `GateViewModel.kt` 加 `var dsh: com.yishou.app.dsh.DshHandoff.Request? = null`，以及一个状态位 `dshSent`（放进 `GateState`，默认 false）。

3. **只在 `finishGrant(pass, event)` 里发凭证**。这是 PassIssuer 真的发出 Pass 之后唯一会走到的地方；其他任何地方都不许发：

```kotlin
private fun finishGrant(pass: com.yishou.app.data.Pass, event: String) {
    // ……原有的几行保持不动……
    sendDshPass(pass)
}

/** 判题通过、Pass 已经写进数据库之后，把凭证交还给 DSH Go。测试页和早上模式不会走到这里。 */
private fun sendDshPass(pass: com.yishou.app.data.Pass) {
    val req = dsh ?: return
    if (_state.value.isTest) return
    val minutes = ((pass.endAt - pass.startAt) / 60_000L).toInt()
    val extras = com.yishou.app.dsh.DshHandoff.passExtras(req, minutes, "pass:" + pass.id)
    val i = android.content.Intent(com.yishou.app.dsh.DshHandoff.ACTION_PASS).setPackage(req.replyPackage)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    extras.forEach { (k, v) -> when (v) { is Int -> i.putExtra(k, v); is String -> i.putExtra(k, v) } }
    val ok = runCatching { yishou.startActivity(i) }.isSuccess   // yishou = 这个 ViewModel 里已有的 Application 引用
    dsh = null                                                   // 一张凭证只发一次
    _state.update { it.copy(dshSent = ok) }
}
```

（`yishou` 是 GateViewModel 里已有的 Application 引用，第 44 行在用。如果它的类型不能 `startActivity`，用 `getApplication<Application>()`。）

4. `GateActivity` 的界面：`s.passMinutes != null` 这一支里，**如果是 DSH 模式**（`vm.dsh` 发出前不为空，或 `s.dshSent == true`）：
   - 文字改成「过了，正在回到「${s.appLabel}」」；
   - 不显示「回到 xx」按钮，**直接 `finishAndRemoveTask()`**。B站 由 DSH Go 负责打开，一手**不要**再调用 `returnToApp()`，否则会打开两次。
   - 用 `LaunchedEffect(s.dshSent) { if (s.dshSent) finishAndRemoveTask() }` 实现。
5. DSH 模式下的「先回桌面」按钮和返回键：改成只 `finishAndRemoveTask()`，**不要**发 HOME。这样用户放弃答题时会回到 DSH Go 的拦截页，DSH Go 会收回这次交接。按钮文字改成「回到拦截页」。

**验收**：
- `grep -rn "ACTION_PASS" yishou/app/src/main/java` 只出现在 `DshHandoff.kt` 和 `sendDshPass` 里；
- `grep -rn "sendDshPass" yishou/app/src/main/java` 只有两处：定义一处、`finishGrant` 里调用一处；
- CI 单元测试和打包都通过。

## Y4 · 说明页

新建 `yishou/docs/dsh-handoff.md`（≤60 行），写清楚：
- 协议表（照抄上面的）；
- 手机上怎么用：
  1. 先装一手（`com.yishou.app` 主线版），再装 DSH Go R4；
  2. **在一手的关注名单里，去掉要交给 DSH Go 拦截的那些 APP**（比如 B站）。否则两个 App 会各弹一个页面；
  3. 拦截页上点「去一手答一道题」→ 一手出题 → 答对 → 自动回到 B站；
- 已知限制：一手进程在答题时被系统杀掉，凭证就发不出去；DSH Go 那边 15 分钟后会把这次记为"已拦截"，不会放行。

## 交付

推送 `feat/dsh-handoff-2026-11-02` 后回报：

```
分支 / HEAD（git ls-remote 原文）
Y1..Y4 每项：改动文件 | CI 里相关测试 / 打包的最后几行 | DONE / STOPPED(原因)
```

**不要合并到 `ccr-a5172662-j1jcik`。** 审查方核对后由负责人决定合并。
