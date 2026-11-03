# 任务书：思维驾驶员 0.3 —— 内置拦截、无障碍自愈、出题去公式化

给接手的编码助手（GPT / Codex）。仓库 `aozhenyu666-cmyk/test`，分支 `ccr-8329c7b5-gr96ko`。先读本文件，再动手；不清楚的地方以代码为准。

## 0. 背景（一句话版）

- `dsh-driver-app/`：独立 Android APP「思维驾驶员」，包名 `com.dshdriver.app`，Kotlin，**不用 androidx**，`core/` 是纯 Kotlin 状态机（27 项 JVM 测试），`app/` 是 Android 外壳。CI：`.github/workflows/dsh-driver-app.yml`（只打包、上传 artifact）。
- 0.2.0 把拦截交给外部的 Curbox（开源拦截器），思维驾驶员只当它的答题验证方（`CheckActivity` + `Curbox.kt`，走 Curbox API v3）。
- **真机反馈**：Curbox 拦截本身很好，但只要在它弹出拦截页时把它划掉（杀进程），它的无障碍就被系统关掉，必须手动重开。"总控"APP 也经常被系统关掉无障碍。
- 用户决定：**学 Curbox 的拦截做法，内置进思维驾驶员**，并给思维驾驶员和总控都加上"无障碍被关后自动重开"。

## 1. 硬约束

1. **许可**：Curbox 是 GPL-3.0。直接复制它的代码进来，`dsh-driver-app` 必须整体以 GPL-3.0 发布（加 `dsh-driver-app/LICENSE` 全文 + 文件头注明来源）。如果不想 GPL，就只学思路、自己重写，不复制代码。**先选一种，在 PR 说明里写清楚。** 推荐：重写（需要的代码量不大，见第 2 节）。
2. 不要改 `dsh-driver-core/plugin` 的 HTTP 协议（APP 已在用 `/app/pair`、`/app/ping`、`/app/compose`、`/app/report`）。
3. 不要碰"总控/一手/哨兵"之外的项目；改总控时只加无障碍自愈，不重构它。
4. 每改一块就跑 `cd dsh-driver-app && ./gradlew :core:test :app:assembleDebug`（本机没 Android SDK 时至少 `gradle :core:test`），CI 绿了再继续。
5. 写进代码和文档的说法只写已验证的；没真机验证的标 NOT_TESTED。

## 2. 内置拦截（替代 Curbox，学它的做法）

Curbox 源码（只读参考）：https://github.com/curbox-app/curbox-android ，分支 `kt-rewrite`，提交 `b06520f4dc35018c184b65341fbc4ffe31b2eb08`。要看的几处：

| Curbox 文件 | 学什么 |
|---|---|
| `app/src/main/java/neth/iecal/curbox/blockers/AppBlocker.kt` 的 `doAppBlockerCheck`、`showWarningScreen` | 只看 `TYPE_WINDOW_STATE_CHANGED or TYPE_WINDOW_CONTENT_CHANGED`；**先 `performGlobalAction(GLOBAL_ACTION_HOME)` 把目标 App 送走，100ms 后再 `startActivity(拦截页, NEW_TASK or CLEAR_TASK)`**；冷却表按分组存、持久化、到期用定时器重查 |
| `app/src/main/java/neth/iecal/curbox/utils/PackageFinderTools.kt` 的 `isActivePackage` | 事件包名要再用 `rootInActiveWindow.packageName` 复核，不一致就不拦 |
| `app/src/main/java/neth/iecal/curbox/services/BaseBlockingService.kt` | 无障碍服务里起前台通知；每 30 秒心跳写"还活着"时间戳 |
| `app/src/main/java/neth/iecal/curbox/services/ServiceWatchdogJob.kt`、`ServiceProtectionManager` | JobScheduler 定期查无障碍是否还开着；能自愈就自愈，不能就发通知 |
| `app/src/main/AndroidManifest.xml` 里 `WarningActivity` | `excludeFromRecents="true"`、`launchMode="singleTop"` |

在思维驾驶员里要做的（**拦截名单**，不是允许名单——0.1.0 的允许名单在真机上误伤 Gemini、文件选择器、相机，已经证明不行）：

1. `core/` 新增纯 Kotlin `BlockRules`：拦截名单（包名集合）、每个包的放行到期时间、`decide(pkg, now) -> Block | Allow`。写 JVM 测试：名单外放行；名单内无放行就拦；放行期内放行；到期后再拦；输入法、系统界面、本 APP 永远放行。
2. `app/WatchService`（现在只做诊断记录）改为真正拦截：
   - 窗口事件 → 用 `rootInActiveWindow.packageName` 复核 → `BlockRules.decide` → `Block` 时：`performGlobalAction(GLOBAL_ACTION_HOME)`，100ms 后 `startActivity(GateActivity, NEW_TASK|CLEAR_TASK)`，带上被拦包名。
   - 同一个包 1 秒内只处理一次（防连拦）。
3. 新的 `GateActivity`（把现在 `CheckActivity` 的出题/判卷界面搬过来复用；`CheckActivity` 保留给 Curbox 用，两者共用一个出题判卷组件）：答对 → 给这个包写放行到期时间（时长可设，默认 10 分钟）→ 打开那个 App；放弃 → 回桌面。`excludeFromRecents="true"`、`launchMode="singleTask"`、独立 `taskAffinity`。
4. 首页：选要拦的 App（列出可启动的 App 打勾）、放行时长、开关。存在 `Store` 里。
5. **不要**再做任何"允许名单 / 切走就拉回"逻辑。

## 3. 无障碍被关后自动重开（思维驾驶员 + 总控都要）

原理：无障碍服务崩溃或被强制停止后，系统会把它从已启用列表里删掉；App 自己已经死了，不能自救，只能靠"有权限写系统设置的那一方"把它写回去。

### 3.1 写回设置（核心）

```kotlin
// 需要 android.permission.WRITE_SECURE_SETTINGS（只能用 adb / Shizuku 授予，见第 5 节）
fun ensureA11y(ctx: Context, component: ComponentName): Boolean {
    if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return false
    val cr = ctx.contentResolver
    val me = component.flattenToString()
    val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    if (cur.split(':').any { it.equals(me, true) }) return true
    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (cur.isBlank()) me else "$cur:$me")
    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    return true
}
```

- 只追加自己的组件，**绝不覆盖**别人的（Curbox 的测试脚本就犯过"整串覆盖"的错，会把用户其他无障碍服务关掉）。
- 清单加 `<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" tools:ignore="ProtectedPermissions" />`。

### 3.2 什么时候调用

| 触发 | 实现 | 对"强制停止"有效吗 |
|---|---|---|
| APP 每次启动、首页每次 onResume | 直接调 | 用户手动打开 APP 时有效 |
| 前台服务里每 30 秒查一次 | 陪练/拦截开着时常驻 | 进程活着时有效 |
| JobScheduler 周期任务（最短 15 分钟） | `JobInfo.Builder(...).setPeriodic(15min).setPersisted(true)` | 无效（强制停止会清掉） |
| `BOOT_COMPLETED`、`MY_PACKAGE_REPLACED` 广播 | 静态接收器 | 重启后有效 |
| **另一个 APP 当守护**（推荐） | 让「哨兵」（`com.zongkong.sentinel`，本来就负责监测总控/一手）也拿到 `WRITE_SECURE_SETTINGS`，定时检查并写回**思维驾驶员、总控、一手**的无障碍。一个 APP 被强制停止，不影响另一个 APP 的定时任务 | **有效**（只要守护 APP 没同时被杀） |

总控、一手的包名和无障碍服务类名，请到各自源码里查（分支见仓库根 `README.md` 的导航表）。守护逻辑只放在哨兵里一份，别三个 APP 各写一份互相拉。

### 3.3 减少被杀的机会

- 拦截页 `excludeFromRecents="true"`，让它不出现在最近任务里，用户就没法"划掉"它。
- 试一下把无障碍服务放进独立进程（`android:process=":watch"`），拦截页在主进程：有些系统划掉任务只杀那个任务的进程。**这条要真机验证**，国产系统可能整包杀。
- 首页显示：无障碍状态、是否有 `WRITE_SECURE_SETTINGS`、最近一次自动重开的时间；没权限时给出一键复制的 adb 命令。

## 4. 出题去公式化（用户反馈：现在的追问太短、太像、太容易忽略）

方向（用户明确说"仅供参考、不要引入太多复杂度"，做最小改动）：

1. **掷骰子决定"这一次是什么类型的互动"**，不是只换说法。类型至少 5 种，每次随机且最近 3 次不重复：
   - 回忆：从他自己的历史（议程、上次结论、报告）里拿一条具体内容，让他复述或纠正；
   - 反驳：给出一个和他上次结论相反的说法，让他指出错在哪；
   - 教 Gemini：让他把刚才的要点念给 Gemini，并要求 Gemini 挑一个漏洞；
   - 小任务：30 秒内能做完的具体动作（找出题干里的一个关键词并念出来）；
   - 连接：把现在的题和他的主线目标连起来说一句。
2. **先给素材，再提问**：卡片上先出现一条"关于他本人的具体内容"（一句他说过的结论 / 一条议程 / 一条大肥鱼记忆），再问。不要只有一句问题。
3. 插件 `dsh-driver-core/plugin/lib/app-routes.js` 的 `/app/compose`：把记忆注入从 5 条提到 10–15 条，加入最近 5 次 `reports.jsonl` 的结论；提示词里写明骰子类型和"必须引用一条具体素材"；APP 端在 `ComposeRequest` 加一个 `kind` 字段传骰子结果（插件里不认识就忽略，保持兼容）。
4. 加一个"公式化黑名单"：生成的问题和"依据是什么 / 下一步是什么 / 说一个词"这类模板相似度 ≥ 0.6 就重出一次，还像就换骰子。

用识图判断"他是不是在用 Gemini Live"：**本轮不做**，只可以调研有没有现成项目，写成一页结论。

## 5. 权限与需要人做的事（编码助手做不了的）

| 事项 | 为什么助手做不了 | 谁来做、怎么做 |
|---|---|---|
| 给 APP 授予 `WRITE_SECURE_SETTINGS` | 这是系统保护权限，只能 adb / Shizuku 授予 | 用户：电脑连手机执行 `adb shell pm grant com.dshdriver.app android.permission.WRITE_SECURE_SETTINGS`；总控、一手、哨兵各执行一次（换包名）。没电脑：手机上开「无线调试」，用 Shizuku 或 LADB 执行同样的命令。**卸载重装后要重新授予** |
| 打开无障碍、悬浮窗、通知、忽略电池优化、国产系统的「自启动」「后台弹出界面」 | 只能用户在系统设置里点 | 用户 |
| 真机测试 | 助手没有手机 | 用户；测完把首页「诊断」截图发回 |
| 建固定下载地址（GitHub Release） | 上一个助手（Claude）发布公开 Release 被安全策略拦下 | 用户同意后，在 `dsh-driver-app.yml` 加发布步骤（参考一手的 `yishou-android.yml`），或用户手动建 Release |
| 在本机编译 Android APK | Claude 的云环境下载不到 Android SDK；靠 GitHub Actions 编译 | 你的环境如果有 Android SDK 可以本地编；否则一样靠 CI |
| Device Owner / 锁定任务模式 | 要恢复出厂设置后用 adb 设置，普通手机不适用 | 不做 |

## 6. 验收

- `core` 测试全绿，新增 `BlockRules` 测试；CI 打出 APK。
- 真机清单（用户来测）：
  1. 拦截名单里的 App 打开 → 回桌面 → 弹答题页；答对放行 N 分钟；到期再开被拦。
  2. 在答题页把它划掉 / 清后台 → 无障碍是否还开着；没开的话 30 秒内（前台服务）或下次打开 APP 时是否自动重开。
  3. 到设置里强制停止思维驾驶员 → 哨兵是否在下一个检查周期把无障碍写回。
  4. 总控同样测第 2、3 条。
  5. Gemini、文件选择器、相机都不受影响（它们不在拦截名单里）。
- PR 说明里写清：选了 GPL 还是重写；哪些 NOT_TESTED。
