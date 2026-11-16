package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.focus

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * 识别「披着系统外衣的娱乐/信息流/浏览器」包名，防止专时/锁机模式下因 `FLAG_SYSTEM` 被预装浏览器、
 * 系统视频、应用商店、负一屏等绕过；同时绝不误伤指纹生物识别、权限框、电话、输入法、相机、地图与桌面。
 */
object SystemBypassClassifier {
    /** 厂商预装浏览器 / 视频 / 应用商店 / 游戏中心 / 负一屏信息流已知包名。 */
    val KNOWN_SYSTEM_BYPASS = setOf(
        "com.android.browser",
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "com.miui.browser",
        "com.heytap.browser",
        "com.coloros.browser",
        "com.vivo.browser",
        "com.huawei.browser",
        "com.miui.video",
        "com.xiaomi.video",
        "com.heytap.yoli",
        "com.coloros.video",
        "com.vivo.video",
        "com.huawei.himovie",
        "com.miui.player",
        "com.heytap.music",
        "com.bbk.appstore",
        "com.xiaomi.market",
        "com.heytap.market",
        "com.oppo.market",
        "com.huawei.appmarket",
        "com.android.vending",
        "com.xiaomi.gamecenter",
        "com.nearme.gamecenter",
        "com.vivo.game",
        "com.huawei.gamebox",
        "com.miui.personalassistant",
        "com.heytap.pictorial",
        "com.google.android.youtube",
        "com.google.android.googlequicksearchbox",
    )

    /** 关键安全系统组件：无论任何严格模式都放行，避免锁死手机基础交互。 */
    val ESSENTIAL_SYSTEM = setOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.lbe.security.miui",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.vivo.permissionmanager",
        "com.huawei.systemmanager",
        "com.android.camera",
        "com.android.camera2",
        "com.miui.gallery",
        "com.google.android.apps.photos",
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.captiveportallogin",
        "com.google.android.captiveportallogin",
    )

    private val BYPASS_SUBSTRINGS = listOf(
        "browser", "market", "appstore", "gamecenter", "gamebox", "video", "movie", "novel", "comic", "news", "feed",
    )

    @Volatile private var cachedBypasses: Pair<Long, Set<String>>? = null
    private const val CACHE_TTL_MS = 10 * 60_000L

    /** 纯函数判定（JVM 单测用）：不依赖 Context 即可判定是否属于可绕过的系统包。 */
    fun isBypassPackage(pkg: String, extraResolved: Set<String> = emptySet()): Boolean {
        val p = pkg.trim().lowercase()
        if (p.isEmpty() || p in ESSENTIAL_SYSTEM) return false
        if (p in KNOWN_SYSTEM_BYPASS || p in extraResolved) return true
        return BYPASS_SUBSTRINGS.any { p.contains(it) }
    }

    /** Android 运行时通过 PackageManager 动态识别本机所有浏览器与商店，带 10 分钟缓存。 */
    fun resolvedBypasses(context: Context, now: Long = System.currentTimeMillis()): Set<String> {
        cachedBypasses?.let { if (now - it.first < CACHE_TTL_MS) return it.second }
        val pm = context.packageManager
        val out = LinkedHashSet<String>(KNOWN_SYSTEM_BYPASS)
        runCatching {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            @Suppress("DEPRECATION")
            val flags = PackageManager.MATCH_ALL
            for (ri in pm.queryIntentActivities(webIntent, flags)) {
                ri.activityInfo?.packageName?.takeIf { it.isNotBlank() && it !in ESSENTIAL_SYSTEM }?.let(out::add)
            }
        }
        runCatching {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.example"))
            @Suppress("DEPRECATION")
            val flags = PackageManager.MATCH_ALL
            for (ri in pm.queryIntentActivities(marketIntent, flags)) {
                ri.activityInfo?.packageName?.takeIf { it.isNotBlank() && it !in ESSENTIAL_SYSTEM }?.let(out::add)
            }
        }
        cachedBypasses = now to out
        return out
    }
}
