package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R7 P0-1 超额项：2026-11-11 真机录到的包名序列回放。
 *
 * 数据来源：V2307A 上按「开 Live → 添加图片 → 添加文件 → 回桌面 → 回 Gemini → 切抖音」走一遍，
 * 每 350ms 采一次前台包（`dumpsys activity activities` 的 topResumedActivity）。原样贴在这里，
 * 断言整段回放里只有娱乐 App 会被锁——用户被踢出去的那几个窗口一个都不许再锁。
 */
class SessionPolicyReplayTest {

    private val ime = setOf("com.sohu.inputmethod.sogou")
    private val self = setOf("io.github.ccch1mneyyy.dsh_app.fish")
    private val allow = setOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox")
    private val entertainment = setOf("com.ss.android.ugc.aweme", "com.tencent.tmgp.sgame")

    /** 一次前台变化。systemApp 按真机上 `pkgFlags` 的实际值填（见下方注释）。 */
    private data class Step(val at: String, val pkg: String, val systemApp: Boolean, val want: SessionVerdict)

    private val recorded = listOf(
        // 开连麦之前他在刷 Twitter：不在娱乐名单里，宽限期放行，不锁。
        Step("20:53:22", "com.twitter.android", systemApp = false, want = SessionVerdict.GRACE),
        // 桌面：com.bbk.launcher2 的 pkgFlags 是 SYSTEM + UPDATED_SYSTEM_APP。
        Step("20:54:11", "com.bbk.launcher2", systemApp = true, want = SessionVerdict.ALLOW),
        // Gemini 本体在 Google App 里：pkgFlags 没有 SYSTEM，靠插件 allow 放行。
        Step("20:54:13", "com.google.android.googlequicksearchbox", systemApp = false, want = SessionVerdict.ALLOW),
        // 「添加图片」→ 系统照片选择器（/apex/.../Photopicker.apk，pkgFlags 有 SYSTEM）。
        Step("20:54:20", "com.android.photopicker", systemApp = true, want = SessionVerdict.ALLOW),
        Step("20:54:39", "com.google.android.googlequicksearchbox", systemApp = false, want = SessionVerdict.ALLOW),
        // 「添加文件」→ vivo 自带文件管理器（/data/app 下，但 pkgFlags 有 SYSTEM + UPDATED_SYSTEM_APP）。
        Step("20:54:42", "com.android.filemanager", systemApp = true, want = SessionVerdict.ALLOW),
        Step("20:54:45", "com.google.android.googlequicksearchbox", systemApp = false, want = SessionVerdict.ALLOW),
        Step("20:54:48", "com.android.filemanager", systemApp = true, want = SessionVerdict.ALLOW),
        Step("20:55:03", "com.google.android.googlequicksearchbox", systemApp = false, want = SessionVerdict.ALLOW),
        // 按 Home 回桌面，再点回 Gemini。
        Step("20:55:08", "com.bbk.launcher2", systemApp = true, want = SessionVerdict.ALLOW),
        Step("20:55:20", "com.google.android.googlequicksearchbox", systemApp = false, want = SessionVerdict.ALLOW),
        Step("20:55:24", "com.bbk.launcher2", systemApp = true, want = SessionVerdict.ALLOW),
        // 抖音：娱乐目标，开门就锁。
        Step("20:55:27", "com.ss.android.ugc.aweme", systemApp = false, want = SessionVerdict.LOCK),
    )

    private fun facts(systemApp: Boolean) = SessionFacts(
        allow = allow, whitelist = emptySet(), home = setOf("com.bbk.launcher2") + SessionPolicy.NEUTRAL,
        entertainment = entertainment, self = self, ime = ime, systemApp = systemApp,
    )

    @Test
    fun theRecordedSequenceOnlyLocksTheEntertainmentApp() {
        val locked = recorded.filter { step ->
            SessionPolicy.judge(step.pkg, facts(step.systemApp)) == SessionVerdict.LOCK
        }.map { it.pkg }
        assertEquals(listOf("com.ss.android.ugc.aweme"), locked)
    }

    @Test
    fun everyStepJudgesTheWayTheFlowSaysItShould() {
        for (step in recorded) {
            assertEquals("@${step.at} ${step.pkg}", step.want, SessionPolicy.judge(step.pkg, facts(step.systemApp)))
        }
    }
}
