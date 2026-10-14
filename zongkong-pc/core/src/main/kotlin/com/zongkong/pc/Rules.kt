package com.zongkong.pc

import kotlinx.serialization.Serializable

/**
 * 电脑上的东西分几类。娱乐、游戏、社交有每天的额度；学习工作、工具、其他只计时间不拦
 * （但算进电脑总时长）。
 */
enum class Cat(val label: String, val rank: Int) {
    GAME("游戏", 4),
    FUN("娱乐", 3),
    SOCIAL("社交", 2),
    OTHER("其他", 1),
    TOOL("工具", 0),
    WORK("学习工作", 0);

    /** 有额度、会被拦的类。 */
    val limited: Boolean get() = this == GAME || this == FUN || this == SOCIAL

    companion object {
        fun of(s: String): Cat = entries.firstOrNull { it.name == s || it.label == s } ?: OTHER
    }
}

/** 一个程序归哪类。[exe] 小写，例如 wegame.exe。 */
@Serializable
data class AppRule(val exe: String, val cat: String, val label: String = "")

/**
 * 一个网站：[domains] 用来在网络层拦（含视频、图片的子域名），[titleWords] 用来从浏览器窗口标题认出它。
 */
@Serializable
data class SiteRule(val label: String, val cat: String, val domains: List<String>, val titleWords: List<String> = emptyList())

/**
 * 管电脑的规则。收紧马上生效，放宽（额度加大、规则删掉、开关关掉）24 小时后才生效。
 */
@Serializable
data class PcRules(
    val apps: List<AppRule> = Defaults.APPS,
    val sites: List<SiteRule> = Defaults.SITES,
    /** 认网站时只看这些浏览器的窗口。 */
    val browsers: Set<String> = Defaults.BROWSERS,
    /** 每类每天最多几分钟（游戏 0 = 一律要申请）。 */
    val limits: Map<String, Int> = mapOf(Cat.FUN.name to 60, Cat.SOCIAL.name to 30, Cat.GAME.name to 0),
    /** 每天电脑一共用多久；超过以后每次开用都要总控同意（0 = 不限）。 */
    val totalMinutes: Int = 300,
    /** 一次放行最多几分钟。 */
    val maxPass: Int = 30,
    /** 会话制：用电脑先说这次做什么、用多久，到点锁屏对账。 */
    val sessions: Boolean = true,
    /** 一次会话最多几分钟（自己开的；总控可以按理由给）。 */
    val sessionMax: Int = 90,
    /** 夜里锁机。 */
    val curfew: Boolean = true,
    val curfewStart: Int = 23 * 60 + 30,
    val curfewEnd: Int = 7 * 60,
    /** 网站在网络层拦（系统代理的 PAC，不用管理员）。 */
    val netBlock: Boolean = true,
    /** 允许总控看屏幕：抽查、申请时截一张给你自己的 AI 看。 */
    val screenLook: Boolean = false,
    /** 连续用多久，总控主动问一句在做什么（0 = 不问）。 */
    val checkEvery: Int = 45,
    /** 开机自动启动。 */
    val autostart: Boolean = true,
    /** 锁住 AI 接口：换模型 24 小时后才生效。 */
    val modelLock: Boolean = true,
) {
    fun limit(c: Cat): Int? = if (c.limited) limits[c.name] else null
}

/** 前台窗口：程序名（小写）、标题、上级进程（由近到远）。 */
data class Fg(val exe: String, val title: String, val ancestors: List<String> = emptyList())

/** 认出来的“东西”：一个网站或一个程序。[key] 例如 site:B站、app:wegame.exe、web:chrome.exe。 */
data class Target(val key: String, val label: String, val cat: Cat, val site: SiteRule? = null)

object Classify {
    /** 游戏平台：由它们启动的程序都算游戏。 */
    private val LAUNCHERS = setOf("steam.exe", "wegame.exe", "epicgameslauncher.exe", "battle.net.exe", "leagueclient.exe", "riotclientservices.exe", "mihoyolauncher.exe", "hyp.exe")

    fun of(fg: Fg, rules: PcRules): Target {
        val exe = fg.exe.lowercase()
        if (exe in rules.browsers) {
            val title = fg.title.lowercase()
            rules.sites.firstOrNull { s -> s.titleWords.any { w -> w.isNotBlank() && title.contains(w.lowercase()) } }?.let {
                return Target("site:${it.label}", it.label, Cat.of(it.cat), it)
            }
            return Target("web:$exe", browserName(exe), Cat.OTHER)
        }
        rules.apps.firstOrNull { it.exe.lowercase() == exe }?.let { return Target("app:$exe", it.label.ifBlank { exe.removeSuffix(".exe") }, Cat.of(it.cat)) }
        val anc = fg.ancestors.map { it.lowercase() }
        anc.firstOrNull { it in LAUNCHERS }?.let { return Target("app:$exe", "${exe.removeSuffix(".exe")}（${it.removeSuffix(".exe")} 启动的游戏）", Cat.GAME) }
        rules.apps.firstOrNull { r -> Cat.of(r.cat) == Cat.GAME && r.exe.lowercase() in anc }?.let {
            return Target("app:$exe", "${exe.removeSuffix(".exe")}（${it.label.ifBlank { it.exe }} 启动）", Cat.GAME)
        }
        return Target("app:$exe", Defaults.WORK_APPS[exe] ?: exe.removeSuffix(".exe"), if (exe in Defaults.WORK_APPS) Cat.WORK else Cat.OTHER)
    }

    fun browserName(exe: String) = when (exe) {
        "chrome.exe" -> "Chrome"
        "msedge.exe" -> "Edge"
        "firefox.exe" -> "Firefox"
        "quark.exe" -> "夸克"
        "360se.exe", "360chrome.exe" -> "360 浏览器"
        "qqbrowser.exe" -> "QQ 浏览器"
        else -> exe.removeSuffix(".exe")
    }

    /** 按名字找规则里的网站或程序（AI 说“B站”“wegame”“娱乐”都能认）。返回 key、名字、类。 */
    fun find(ref: String, rules: PcRules): Target? {
        val r = ref.trim().lowercase().removePrefix("site:").removePrefix("app:")
        if (r.isEmpty()) return null
        Cat.entries.firstOrNull { it.label == ref.trim() || it.name.lowercase() == r }?.let { return Target("cat:${it.name}", it.label, it) }
        rules.sites.firstOrNull { s -> s.label.lowercase() == r || s.domains.any { it == r || r.endsWith(".$it") } || s.titleWords.any { it.lowercase() == r } }
            ?.let { return Target("site:${it.label}", it.label, Cat.of(it.cat), it) }
        rules.apps.firstOrNull { a -> a.exe.lowercase() == r || a.exe.lowercase() == "$r.exe" || a.label.lowercase() == r }
            ?.let { return Target("app:${it.exe.lowercase()}", it.label.ifBlank { it.exe }, Cat.of(it.cat)) }
        if (r.endsWith(".exe")) return Target("app:$r", r.removeSuffix(".exe"), Cat.OTHER)
        return null
    }
}

object Defaults {
    val BROWSERS = setOf(
        "chrome.exe", "msedge.exe", "firefox.exe", "quark.exe", "360se.exe", "360chrome.exe", "qqbrowser.exe",
        "sogouexplorer.exe", "opera.exe", "brave.exe", "2345explorer.exe", "liebao.exe", "vivaldi.exe", "arc.exe", "chromium.exe",
    )

    val SITES = listOf(
        SiteRule("B站", Cat.FUN.name, listOf("bilibili.com", "bilivideo.com", "bilivideo.cn", "hdslb.com", "biliapi.net", "b23.tv"), listOf("哔哩哔哩", "bilibili")),
        SiteRule("抖音", Cat.FUN.name, listOf("douyin.com", "douyinvod.com", "douyinpic.com", "iesdouyin.com"), listOf("抖音")),
        SiteRule("快手", Cat.FUN.name, listOf("kuaishou.com", "gifshow.com", "kwai.com"), listOf("快手")),
        SiteRule("YouTube", Cat.FUN.name, listOf("youtube.com", "youtu.be", "googlevideo.com", "ytimg.com"), listOf("youtube")),
        SiteRule("爱奇艺", Cat.FUN.name, listOf("iqiyi.com", "iq.com"), listOf("爱奇艺")),
        SiteRule("优酷", Cat.FUN.name, listOf("youku.com"), listOf("优酷")),
        SiteRule("腾讯视频", Cat.FUN.name, listOf("v.qq.com"), listOf("腾讯视频")),
        SiteRule("芒果TV", Cat.FUN.name, listOf("mgtv.com"), listOf("芒果tv")),
        SiteRule("虎牙", Cat.FUN.name, listOf("huya.com"), listOf("虎牙")),
        SiteRule("斗鱼", Cat.FUN.name, listOf("douyu.com"), listOf("斗鱼")),
        SiteRule("Twitch", Cat.FUN.name, listOf("twitch.tv"), listOf("twitch")),
        SiteRule("Netflix", Cat.FUN.name, listOf("netflix.com"), listOf("netflix")),
        SiteRule("红果短剧", Cat.FUN.name, listOf("hongguo.com"), listOf("红果")),
        SiteRule("小红书", Cat.SOCIAL.name, listOf("xiaohongshu.com", "xhscdn.com"), listOf("小红书")),
        SiteRule("微博", Cat.SOCIAL.name, listOf("weibo.com", "weibo.cn", "sinaimg.cn"), listOf("微博")),
        SiteRule("贴吧", Cat.SOCIAL.name, listOf("tieba.baidu.com"), listOf("贴吧")),
        SiteRule("知乎", Cat.SOCIAL.name, listOf("zhihu.com", "zhimg.com"), listOf("知乎")),
        SiteRule("豆瓣", Cat.SOCIAL.name, listOf("douban.com"), listOf("豆瓣")),
        SiteRule("X", Cat.SOCIAL.name, listOf("x.com", "twitter.com", "twimg.com"), listOf(" / x", "twitter")),
        SiteRule("Reddit", Cat.SOCIAL.name, listOf("reddit.com", "redd.it"), listOf("reddit")),
        SiteRule("4399", Cat.GAME.name, listOf("4399.com", "7k7k.com"), listOf("4399", "7k7k")),
    )

    val APPS = listOf(
        AppRule("steam.exe", Cat.GAME.name, "Steam"),
        AppRule("wegame.exe", Cat.GAME.name, "WeGame"),
        AppRule("epicgameslauncher.exe", Cat.GAME.name, "Epic"),
        AppRule("battle.net.exe", Cat.GAME.name, "战网"),
        AppRule("leagueclient.exe", Cat.GAME.name, "英雄联盟"),
        AppRule("league of legends.exe", Cat.GAME.name, "英雄联盟"),
        AppRule("yuanshen.exe", Cat.GAME.name, "原神"),
        AppRule("genshinimpact.exe", Cat.GAME.name, "原神"),
        AppRule("starrail.exe", Cat.GAME.name, "星穹铁道"),
        AppRule("zenlesszonezero.exe", Cat.GAME.name, "绝区零"),
        AppRule("valorant.exe", Cat.GAME.name, "无畏契约"),
        AppRule("cs2.exe", Cat.GAME.name, "CS2"),
        AppRule("dota2.exe", Cat.GAME.name, "DOTA2"),
        AppRule("minecraft.exe", Cat.GAME.name, "我的世界"),
        AppRule("dnplayer.exe", Cat.GAME.name, "雷电模拟器"),
        AppRule("ldplayer.exe", Cat.GAME.name, "雷电模拟器"),
        AppRule("mumuplayer.exe", Cat.GAME.name, "MuMu 模拟器"),
        AppRule("nemuplayer.exe", Cat.GAME.name, "MuMu 模拟器"),
        AppRule("hd-player.exe", Cat.GAME.name, "BlueStacks"),
        AppRule("哔哩哔哩.exe", Cat.FUN.name, "B站客户端"),
        AppRule("bilibili.exe", Cat.FUN.name, "B站客户端"),
        AppRule("douyin.exe", Cat.FUN.name, "抖音客户端"),
        AppRule("qyclient.exe", Cat.FUN.name, "爱奇艺"),
        AppRule("youkuclient.exe", Cat.FUN.name, "优酷"),
        AppRule("qqlive.exe", Cat.FUN.name, "腾讯视频"),
        AppRule("huyaclient.exe", Cat.FUN.name, "虎牙"),
        AppRule("douyu.exe", Cat.FUN.name, "斗鱼"),
    )

    /** 学习工作类程序：只计时间，不拦，给总控判断“在不在干正事”。 */
    val WORK_APPS = mapOf(
        "code.exe" to "VS Code", "idea64.exe" to "IntelliJ IDEA", "pycharm64.exe" to "PyCharm", "devenv.exe" to "Visual Studio",
        "winword.exe" to "Word", "excel.exe" to "Excel", "powerpnt.exe" to "PowerPoint", "onenote.exe" to "OneNote",
        "wps.exe" to "WPS", "et.exe" to "WPS 表格", "wpp.exe" to "WPS 演示", "notion.exe" to "Notion", "obsidian.exe" to "Obsidian",
        "typora.exe" to "Typora", "acrobat.exe" to "Acrobat", "acrord32.exe" to "Acrobat", "sumatrapdf.exe" to "SumatraPDF",
        "xmind.exe" to "XMind", "matlab.exe" to "MATLAB", "rstudio.exe" to "RStudio", "zotero.exe" to "Zotero", "anki.exe" to "Anki",
        "windowsterminal.exe" to "终端", "cursor.exe" to "Cursor",
    )

    /** 锁机时也能用的：输入法、系统界面、总控自己。 */
    val ALWAYS = setOf("zongkong.exe", "explorer.exe", "lockapp.exe", "searchhost.exe", "shellexperiencehost.exe", "startmenuexperiencehost.exe", "textinputhost.exe", "ctfmon.exe", "java.exe", "javaw.exe")
}
