package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R7 P0-1: 舱内分级的每一条规则各一个用例。
 * 前 5 个是用户在真机上被 4 级锁踢出去的那几类窗口，一个都不许再锁。
 */
class SessionPolicyTest {

    private val self = setOf("io.github.ccch1mneyyy.dsh_app", "io.github.ccch1mneyyy.dsh_app.fish")
    private val ime = setOf("com.sohu.inputmethod.sogou")
    private val gemini = setOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox")
    private val gallery = "com.google.android.providers.media.module"
    private val documents = "com.android.documentsui"
    private val gms = "com.google.android.gms"
    private val camera = "com.honor.camera"
    private val game = "com.tencent.tmgp.sgame"
    private val thirdParty = "com.example.reader"
    private val launcher = "com.hihonor.android.launcher"

    /** 默认的舱内事实：桌面靠运行时解析进来的 [launcher]，娱乐名单里只有 [game]。 */
    private fun facts(
        allow: Set<String> = gemini,
        whitelist: Set<String> = emptySet(),
        home: Set<String> = setOf(launcher) + SessionPolicy.NEUTRAL,
        entertainment: Set<String> = setOf(game),
        systemApp: Boolean = false,
        dwellMs: Long = 0L,
        graceMs: Long = SessionPolicy.DEFAULT_GRACE_MS,
        breakGlass: Boolean = false,
    ) = SessionFacts(
        allow = allow, whitelist = whitelist, home = home, entertainment = entertainment,
        self = self, ime = ime, systemApp = systemApp, dwellMs = dwellMs, graceMs = graceMs, breakGlass = breakGlass,
    )

    private fun judge(pkg: String, facts: SessionFacts) = SessionPolicy.judge(pkg, facts)

    // ------------------------------------------------------------ 被误拦的那五类

    @Test
    fun thePhotoPickerIsNotAnotherApp() {
        // 在 Gemini 里点「添加图片」：com.google.android.providers.media.module 是系统应用。
        assertEquals(SessionVerdict.ALLOW, judge(gallery, facts(systemApp = true)))
        assertEquals(SessionVerdict.ALLOW, judge("com.android.providers.media.module", facts(systemApp = true)))
    }

    @Test
    fun theFilePickerIsNotAnotherApp() {
        assertEquals(SessionVerdict.ALLOW, judge(documents, facts(systemApp = true)))
        assertEquals(SessionVerdict.ALLOW, judge("com.google.android.documentsui", facts(systemApp = true)))
    }

    @Test
    fun theGoogleServicesSheetIsNotAnotherApp() {
        // 账号选择 / 权限确认弹层。
        assertEquals(SessionVerdict.ALLOW, judge(gms, facts(systemApp = true)))
    }

    @Test
    fun theCameraIsNotAnotherApp() {
        assertEquals(SessionVerdict.ALLOW, judge(camera, facts(systemApp = true)))
    }

    @Test
    fun aLauncherThatIsNotInTheHardcodedListIsStillTheDesktop() {
        // 荣耀桌面不在 NEUTRAL 里，靠 resolveActivity 拿到的包名放行。
        assertEquals(SessionVerdict.ALLOW, judge(launcher, facts()))
        // 兜底名单本身也还认。
        assertEquals(SessionVerdict.ALLOW, judge("com.miui.home", facts(home = SessionPolicy.NEUTRAL)))
    }

    @Test
    fun anUnknownThirdPartyLauncherIsAllowedOnceItIsResolvedAsHome() {
        val unusual = "com.realme.launcher.oversea"
        assertEquals(SessionVerdict.ALLOW, judge(unusual, facts(home = setOf(unusual))))
    }

    // ------------------------------------------------------------ 该锁的还得锁

    @Test
    fun theEntertainmentAppIsLockedAtOnceEvenDuringTheGraceWindow() {
        // 灰机是用户自己点的娱乐 App：没有宽限，开门就锁。
        assertEquals(SessionVerdict.LOCK, judge(game, facts(dwellMs = 0L)))
        assertEquals(SessionVerdict.LOCK, judge(game, facts(dwellMs = 5_000L)))
    }

    @Test
    fun anEntertainmentAppThatShipsAsASystemAppIsStillLocked() {
        // 第 4 条先于第 5 条：别让「系统应用放行」变成绕过娱乐锁的后门。
        assertEquals(SessionVerdict.LOCK, judge(game, facts(entertainment = setOf(game), systemApp = true)))
    }

    @Test
    fun anUnknownThirdPartyAppGetsTheGraceWindowFirst() {
        assertEquals(SessionVerdict.GRACE, judge(thirdParty, facts(dwellMs = 1_000L)))
        assertEquals(SessionVerdict.GRACE, judge(thirdParty, facts(dwellMs = 44_999L)))
    }

    @Test
    fun anUnknownThirdPartyAppIsLockedAfterTheGraceWindow() {
        // 46 秒：超了宽限就升到锁。
        assertEquals(SessionVerdict.LOCK, judge(thirdParty, facts(dwellMs = 46_000L)))
        // 整 45 秒算到头（判定用严格小于）。
        assertEquals(SessionVerdict.LOCK, judge(thirdParty, facts(dwellMs = 45_000L)))
    }

    @Test
    fun theGraceWindowComesFromThePlugin() {
        assertEquals(SessionVerdict.LOCK, judge(thirdParty, facts(dwellMs = 31_000L, graceMs = 30_000L)))
        assertEquals(SessionVerdict.GRACE, judge(thirdParty, facts(dwellMs = 31_000L, graceMs = 60_000L)))
        // 插件的值写坏了也不许变成"永不升锁"或者"立刻锁"以外的怪东西：
        // 0 就是没有宽限，立刻锁。
        assertEquals(SessionVerdict.LOCK, judge(thirdParty, facts(dwellMs = 0L, graceMs = 0L)))
    }

    @Test
    fun nothingIsLockedWhileTheBreakGlassIsOpen() {
        assertEquals(SessionVerdict.ALLOW, judge(game, facts(breakGlass = true)))
        assertEquals(SessionVerdict.ALLOW, judge(thirdParty, facts(dwellMs = 600_000L, breakGlass = true)))
    }

    // ------------------------------------------------------------ 放行名单

    @Test
    fun theCellarListAllowsAllOfGemini() {
        assertEquals(SessionVerdict.ALLOW, judge("com.google.android.apps.bard", facts()))
        assertEquals(SessionVerdict.ALLOW, judge("com.google.android.googlequicksearchbox", facts()))
        // 宽限到期也不影响白名单。
        assertEquals(SessionVerdict.ALLOW, judge("com.google.android.apps.bard", facts(dwellMs = 600_000L)))
    }

    @Test
    fun theUsersOwnWhitelistStillCountsInsideTheCabin() {
        assertEquals(SessionVerdict.ALLOW, judge(thirdParty, facts(whitelist = setOf(thirdParty), dwellMs = 600_000L)))
    }

    @Test
    fun theWhitelistBeatsTheEntertainmentList() {
        // 顺序是"白名单先于娱乐目标"：他手动放行的 App 不该被别人的 block 名单盖掉。
        assertEquals(SessionVerdict.ALLOW, judge(game, facts(whitelist = setOf(game))))
        assertEquals(SessionVerdict.ALLOW, judge(game, facts(allow = setOf(game))))
    }

    // ------------------------------------------------------------ 从来不算切 App 的

    @Test
    fun theEmergencyAppsAreNeverLocked() {
        assertEquals(SessionVerdict.ALLOW, judge("com.android.dialer", facts()))
        assertEquals(SessionVerdict.ALLOW, judge("com.eg.android.AlipayGphone", facts()))
    }

    @Test
    fun herOwnPagesAreNotAnotherApp() {
        assertEquals(SessionVerdict.ALLOW, judge("io.github.ccch1mneyyy.dsh_app", facts()))
        assertEquals(SessionVerdict.ALLOW, judge("io.github.ccch1mneyyy.dsh_app.fish", facts()))
    }

    @Test
    fun theKeyboardAndSystemDialogsAreLayers() {
        assertEquals(SessionVerdict.ALLOW, judge("com.sohu.inputmethod.sogou", facts()))
        // 没被系统列出来的输入法靠名字兜底。
        assertEquals(SessionVerdict.ALLOW, judge("com.google.android.inputmethod.latin", facts()))
        assertEquals(SessionVerdict.ALLOW, judge("com.android.permissioncontroller", facts()))
        assertEquals(SessionVerdict.ALLOW, judge("android", facts()))
    }

    // ------------------------------------------------------------ 顺序与边界

    @Test
    fun theEmergencyListBeatsEverything() {
        // 急事底线被写进娱乐名单也还是放行——这是用户认可的底线。
        assertEquals(SessionVerdict.ALLOW, judge("com.android.dialer", facts(entertainment = setOf("com.android.dialer"))))
        assertEquals(SessionVerdict.ALLOW, judge("com.android.dialer", facts(breakGlass = false, systemApp = true)))
    }

    @Test
    fun goingBackToGeminiResetsTheClock() {
        // 宽限期是"连续停留"：回到白名单 App 之后，dwell 归零，再出去重新算 45 秒。
        assertEquals(SessionVerdict.GRACE, judge(thirdParty, facts(dwellMs = 40_000L)))
        assertEquals(SessionVerdict.ALLOW, judge("com.google.android.apps.bard", facts(dwellMs = 40_000L)))
        assertEquals(SessionVerdict.GRACE, judge(thirdParty, facts(dwellMs = 0L)))
    }

    @Test
    fun aNegativeDwellIsTreatedAsZero() {
        // 时钟被改过（或者 elapsed 回绕）时不许当场锁人。
        assertEquals(SessionVerdict.GRACE, judge(thirdParty, facts(dwellMs = -5_000L)))
    }

    @Test
    fun theDefaultGraceIsFortyFiveSeconds() {
        assertEquals(45_000L, SessionPolicy.DEFAULT_GRACE_MS)
        assertEquals(45_000L, SessionFacts().graceMs)
        assertEquals(SessionPolicy.DEFAULT_GRACE_MS, SessionFacts().graceMs)
    }

    @Test
    fun theEmergencyListIsNotTheDesktopList() {
        // 两张表各管一件事：共用一个 set 会让"回桌面"被当成"打急事电话"。
        assertEquals(true, SessionPolicy.NEVER_LOCK.intersect(SessionPolicy.NEUTRAL).isEmpty())
    }
}
