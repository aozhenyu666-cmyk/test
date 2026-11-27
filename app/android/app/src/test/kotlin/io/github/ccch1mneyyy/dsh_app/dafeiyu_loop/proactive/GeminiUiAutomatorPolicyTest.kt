package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiUiAutomatorPolicyTest {
    private val geminiPkg = "com.google.android.apps.bard"
    private val searchPkg = "com.google.android.googlequicksearchbox"

    private fun inputNode(
        id: Int = 1,
        pkg: String = geminiPkg,
        text: String = "",
        hint: String = "Ask Gemini",
    ) = UiNodeSnapshot(
        id = id,
        packageName = pkg,
        className = "android.widget.EditText",
        text = text,
        hintText = hint,
        viewIdResourceName = "$pkg:id/prompt_input",
        isEditable = true,
        isClickable = true,
        supportsSetText = true,
        supportsPaste = true,
    )

    private fun sendNode(
        id: Int = 2,
        pkg: String = geminiPkg,
        desc: String = "发送",
    ) = UiNodeSnapshot(
        id = id,
        packageName = pkg,
        className = "android.widget.ImageButton",
        contentDescription = desc,
        viewIdResourceName = "$pkg:id/send_button",
        isClickable = true,
    )

    private fun liveNode(
        id: Int = 3,
        pkg: String = geminiPkg,
        desc: String = "开启 Live",
    ) = UiNodeSnapshot(
        id = id,
        packageName = pkg,
        className = "android.widget.ImageButton",
        contentDescription = desc,
        viewIdResourceName = "$pkg:id/gemini_live_button",
        isClickable = true,
    )

    @Test
    fun geminiPackageMatchingCoversBardAndGoogleQuickSearchOnly() {
        assertTrue(GeminiNodeMatcher.isGeminiPackage(geminiPkg))
        assertTrue(GeminiNodeMatcher.isGeminiPackage(searchPkg))
        assertFalse(GeminiNodeMatcher.isGeminiPackage("tv.danmaku.bili"))
        assertFalse(GeminiNodeMatcher.isGeminiPackage("com.tencent.mm"))
        assertFalse(GeminiNodeMatcher.isGeminiPackage(null))
    }

    @Test
    fun nodeMatcherFindsEditTextSendAndLiveButtonsSafely() {
        val unrelatedEdit = UiNodeSnapshot(
            id = 99,
            packageName = "tv.danmaku.bili",
            className = "android.widget.EditText",
            isEditable = true,
        )
        val feedbackBtn = UiNodeSnapshot(
            id = 98,
            packageName = geminiPkg,
            className = "android.widget.Button",
            text = "Send feedback",
            isClickable = true,
        )
        val endLiveBtn = UiNodeSnapshot(
            id = 97,
            packageName = geminiPkg,
            className = "android.widget.Button",
            text = "End Live",
            isClickable = true,
        )
        val nodes = listOf(unrelatedEdit, feedbackBtn, endLiveBtn, inputNode(), sendNode(desc = "Send"), liveNode(desc = "Gemini Live"))

        assertEquals(1, GeminiNodeMatcher.findInputNode(nodes)?.id)
        assertEquals(2, GeminiNodeMatcher.findSendButton(nodes)?.id)
        assertEquals(3, GeminiNodeMatcher.findLiveButton(nodes)?.id)

        // Never match End Live or Send feedback when real buttons are absent
        assertNull(GeminiNodeMatcher.findSendButton(listOf(feedbackBtn)))
        assertNull(GeminiNodeMatcher.findLiveButton(listOf(endLiveBtn)))
    }

    @Test
    fun nodeMatcherDetectsReplyStartedAndAlreadyInLiveScreen() {
        val brief = "【禁聊体系死命令】直接开考逻辑判断第一题"
        val streamingNodes = listOf(
            inputNode(text = ""),
            UiNodeSnapshot(
                id = 10,
                packageName = geminiPkg,
                className = "android.widget.Button",
                contentDescription = "停止生成",
                isClickable = true,
            ),
        )
        assertTrue(GeminiNodeMatcher.hasReplyStarted(streamingNodes, brief))
        assertFalse(GeminiNodeMatcher.isAlreadyInLiveScreen(streamingNodes))

        val inLiveNodes = listOf(
            UiNodeSnapshot(id = 11, packageName = geminiPkg, text = "Gemini Live", isVisibleToUser = true),
            UiNodeSnapshot(id = 12, packageName = geminiPkg, contentDescription = "正在聆听", isVisibleToUser = true),
        )
        assertTrue(GeminiNodeMatcher.isAlreadyInLiveScreen(inLiveNodes))
    }

    @Test
    fun stepPolicyWalksFullFourStepFlowToDone() {
        val brief = "【禁聊体系死命令】直接出 1 道加强削弱题考我"

        // Round 0: still on launcher -> waits
        val waitingLauncher = GeminiAutoStepPolicy.decide(
            GeminiStepInput(
                state = GeminiAutoState.WAIT_GEMINI_WINDOW,
                round = 0,
                elapsedMs = 300L,
                frontPackage = "com.bbk.launcher2",
                emergencyActive = false,
                brief = brief,
            ),
        )
        assertEquals(GeminiAutoState.WAIT_GEMINI_WINDOW, waitingLauncher.nextState)
        assertEquals(GeminiAutoAction.Wait, waitingLauncher.action)
        assertFalse(waitingLauncher.sawGeminiWindow)

        // Round 1: Gemini window arrives -> PASTE_AND_SEND_BRIEF
        val windowReady = GeminiAutoStepPolicy.decide(
            GeminiStepInput(
                state = GeminiAutoState.WAIT_GEMINI_WINDOW,
                round = 1,
                elapsedMs = 800L,
                frontPackage = geminiPkg,
                emergencyActive = false,
                brief = brief,
                nodes = listOf(inputNode(), liveNode()),
            ),
        )
        assertEquals(GeminiAutoState.PASTE_AND_SEND_BRIEF, windowReady.nextState)
        assertTrue(windowReady.sawGeminiWindow)

        // Round 2: writes brief into EditText
        val pasted = GeminiAutoStepPolicy.decide(
            GeminiStepInput(
                state = windowReady.nextState,
                round = 2,
                elapsedMs = 1_200L,
                frontPackage = geminiPkg,
                emergencyActive = false,
                brief = brief,
                briefWritten = false,
                sawGeminiWindow = true,
                nodes = listOf(inputNode(), liveNode()),
            ),
        )
        assertEquals(GeminiAutoState.PASTE_AND_SEND_BRIEF, pasted.nextState)
        assertTrue(pasted.action is GeminiAutoAction.PasteBrief)
        assertTrue(pasted.briefWritten)

        // Round 3: Send button appears -> clicks Send and enters WAIT_REPLY_START
        val sent = GeminiAutoStepPolicy.decide(
            GeminiStepInput(
                state = pasted.nextState,
                round = 3,
                elapsedMs = 1_600L,
                frontPackage = geminiPkg,
                emergencyActive = false,
                brief = brief,
                briefWritten = true,
                sawGeminiWindow = true,
                nodes = listOf(inputNode(text = brief), sendNode()),
            ),
        )
        assertEquals(GeminiAutoState.WAIT_REPLY_START, sent.nextState)
        assertTrue(sent.action is GeminiAutoAction.ClickSend)
        assertTrue(sent.sendClicked)

        // Round 4: Gemini starts replying -> transitions to CLICK_LIVE_BUTTON
        val replying = GeminiAutoStepPolicy.decide(
            GeminiStepInput(
                state = sent.nextState,
                round = 4,
                elapsedMs = 2_400L,
                frontPackage = geminiPkg,
                emergencyActive = false,
                brief = brief,
                briefWritten = true,
                sendClicked = true,
                sawGeminiWindow = true,
                nodes = listOf(
                    inputNode(text = ""),
                    UiNodeSnapshot(id = 20, packageName = geminiPkg, text = "好的，第一题：某城市推出新交通方案……"),
                    liveNode(),
                ),
            ),
        )
        assertEquals(GeminiAutoState.CLICK_LIVE_BUTTON, replying.nextState)

        // Round 5: clicks Live button -> DONE
        val clickedLive = GeminiAutoStepPolicy.decide(
            GeminiStepInput(
                state = replying.nextState,
                round = 5,
                elapsedMs = 3_000L,
                frontPackage = geminiPkg,
                emergencyActive = false,
                brief = brief,
                briefWritten = true,
                sendClicked = true,
                sawGeminiWindow = true,
                nodes = listOf(inputNode(text = ""), liveNode()),
            ),
        )
        assertEquals(GeminiAutoState.DONE, clickedLive.nextState)
        val liveAct = clickedLive.action as? GeminiAutoAction.ClickLive
        assertNotNull(liveAct)
        assertEquals(3, liveAct?.node?.id)
    }

    @Test
    fun stepPolicyGuardrailsAbortOnTimeoutMaxRoundsEmergencyAndForegroundSwitch() {
        val timedOut = GeminiAutoStepPolicy.decide(
            GeminiStepInput(
                state = GeminiAutoState.CLICK_LIVE_BUTTON,
                round = 5,
                elapsedMs = GeminiAutoStepPolicy.MAX_TIMEOUT_MS,
                frontPackage = geminiPkg,
                emergencyActive = false,
                brief = "test",
                sawGeminiWindow = true,
            ),
        )
        assertEquals(GeminiAutoState.ABORTED, timedOut.nextState)
        assertEquals(GeminiAutoAction.Abort("timeout"), timedOut.action)

        val maxedRounds = GeminiAutoStepPolicy.decide(
            GeminiStepInput(
                state = GeminiAutoState.WAIT_REPLY_START,
                round = GeminiAutoStepPolicy.MAX_ROUNDS,
                elapsedMs = 5_000L,
                frontPackage = geminiPkg,
                emergencyActive = false,
                brief = "test",
                sawGeminiWindow = true,
            ),
        )
        assertEquals(GeminiAutoState.ABORTED, maxedRounds.nextState)
        assertEquals(GeminiAutoAction.Abort("max_rounds"), maxedRounds.action)

        val emergencyAborted = GeminiAutoStepPolicy.decide(
            GeminiStepInput(
                state = GeminiAutoState.PASTE_AND_SEND_BRIEF,
                round = 2,
                elapsedMs = 1_000L,
                frontPackage = geminiPkg,
                emergencyActive = true,
                brief = "test",
                sawGeminiWindow = true,
            ),
        )
        assertEquals(GeminiAutoState.ABORTED, emergencyAborted.nextState)
        assertEquals(GeminiAutoAction.Abort("emergency_active"), emergencyAborted.action)

        val leftGemini = GeminiAutoStepPolicy.decide(
            GeminiStepInput(
                state = GeminiAutoState.PASTE_AND_SEND_BRIEF,
                round = 3,
                elapsedMs = 1_500L,
                frontPackage = "tv.danmaku.bili",
                emergencyActive = false,
                brief = "test",
                sawGeminiWindow = true,
            ),
        )
        assertEquals(GeminiAutoState.ABORTED, leftGemini.nextState)
        assertEquals(GeminiAutoAction.Abort("foreground_switched"), leftGemini.action)

        val stayedInOtherApp = GeminiAutoStepPolicy.decide(
            GeminiStepInput(
                state = GeminiAutoState.WAIT_GEMINI_WINDOW,
                round = 2,
                elapsedMs = 1_200L,
                frontPackage = "tv.danmaku.bili",
                emergencyActive = false,
                brief = "test",
                sawGeminiWindow = false,
            ),
        )
        assertEquals(GeminiAutoState.ABORTED, stayedInOtherApp.nextState)
        assertEquals(GeminiAutoAction.Abort("foreground_switched"), stayedInOtherApp.action)
    }

    @Test
    fun blankBriefSkipsPasteAndGoesStraightToClickLive() {
        val jumpToLive = GeminiAutoStepPolicy.decide(
            GeminiStepInput(
                state = GeminiAutoState.WAIT_GEMINI_WINDOW,
                round = 1,
                elapsedMs = 500L,
                frontPackage = searchPkg,
                emergencyActive = false,
                brief = "",
                nodes = listOf(liveNode(pkg = searchPkg)),
            ),
        )
        assertEquals(GeminiAutoState.CLICK_LIVE_BUTTON, jumpToLive.nextState)
        assertTrue(jumpToLive.sawGeminiWindow)
    }

    @Test
    fun shareScreenStepClicksGeminiShareButtonWhenRequestedAndRejectsSystemUi() {
        val shareBtnInGemini = UiNodeSnapshot(
            id = 40,
            packageName = geminiPkg,
            className = "android.widget.Button",
            contentDescription = "共享屏幕",
            viewIdResourceName = "$geminiPkg:id/live_screen_share_button",
            isClickable = true,
        )
        val systemUiDialogBtn = UiNodeSnapshot(
            id = 41,
            packageName = "com.android.systemui",
            className = "android.widget.Button",
            text = "共享屏幕",
            isClickable = true,
        )
        val stopSharingBtn = UiNodeSnapshot(
            id = 42,
            packageName = geminiPkg,
            className = "android.widget.Button",
            text = "停止共享",
            isClickable = true,
        )

        assertNull(GeminiNodeMatcher.findScreenShareButton(listOf(systemUiDialogBtn, stopSharingBtn)))
        assertEquals(40, GeminiNodeMatcher.findScreenShareButton(listOf(systemUiDialogBtn, stopSharingBtn, shareBtnInGemini))?.id)

        val clickLiveNextShare = GeminiAutoStepPolicy.decide(
            GeminiStepInput(
                state = GeminiAutoState.CLICK_LIVE_BUTTON,
                round = 5,
                elapsedMs = 3_000L,
                frontPackage = geminiPkg,
                emergencyActive = false,
                brief = "brief",
                briefWritten = true,
                sendClicked = true,
                sawGeminiWindow = true,
                requestScreenShare = true,
                nodes = listOf(liveNode()),
            ),
        )
        assertEquals(GeminiAutoState.CLICK_SCREEN_SHARE, clickLiveNextShare.nextState)
        assertTrue(clickLiveNextShare.action is GeminiAutoAction.ClickLive)
        assertTrue(clickLiveNextShare.liveClicked)

        val clickShare = GeminiAutoStepPolicy.decide(
            GeminiStepInput(
                state = GeminiAutoState.CLICK_SCREEN_SHARE,
                round = 6,
                elapsedMs = 3_800L,
                frontPackage = geminiPkg,
                emergencyActive = false,
                brief = "brief",
                briefWritten = true,
                sendClicked = true,
                sawGeminiWindow = true,
                requestScreenShare = true,
                liveClicked = true,
                nodes = listOf(shareBtnInGemini),
            ),
        )
        assertEquals(GeminiAutoState.DONE, clickShare.nextState)
        val shareAct = clickShare.action as? GeminiAutoAction.ClickScreenShare
        assertNotNull(shareAct)
        assertEquals(40, shareAct?.node?.id)
    }

    @Test
    fun missingShareScreenButtonFinishesGracefullyWithoutFailingLiveSession() {
        var step = GeminiAutoStepPolicy.decide(
            GeminiStepInput(
                state = GeminiAutoState.CLICK_SCREEN_SHARE,
                round = 6,
                elapsedMs = 3_500L,
                frontPackage = geminiPkg,
                emergencyActive = false,
                brief = "brief",
                sawGeminiWindow = true,
                requestScreenShare = true,
                liveClicked = true,
                shareWaitRounds = GeminiAutoStepPolicy.MAX_SHARE_WAIT_ROUNDS - 1,
                nodes = emptyList(),
            ),
        )
        assertEquals(GeminiAutoState.DONE, step.nextState)
        assertEquals(GeminiAutoAction.Finish("live_active_no_share_btn"), step.action)
    }

    @Test
    fun missingInputAfterRound5AndSlowReplyStillEnterLiveInsteadOfAborting() {
        val fallbackLive = GeminiAutoStepPolicy.decide(
            GeminiStepInput(
                state = GeminiAutoState.PASTE_AND_SEND_BRIEF,
                round = 5,
                elapsedMs = 3_000L,
                frontPackage = geminiPkg,
                emergencyActive = false,
                brief = "brief",
                briefWritten = false,
                sawGeminiWindow = true,
                nodes = listOf(liveNode()),
            ),
        )
        assertEquals(GeminiAutoState.CLICK_LIVE_BUTTON, fallbackLive.nextState)

        val slowReplyLive = GeminiAutoStepPolicy.decide(
            GeminiStepInput(
                state = GeminiAutoState.WAIT_REPLY_START,
                round = 7,
                elapsedMs = 4_200L,
                frontPackage = geminiPkg,
                emergencyActive = false,
                brief = "brief",
                briefWritten = true,
                sendClicked = true,
                replyWaitRounds = 3,
                sawGeminiWindow = true,
                nodes = listOf(inputNode(text = "still typing"), liveNode()),
            ),
        )
        assertEquals(GeminiAutoState.CLICK_LIVE_BUTTON, slowReplyLive.nextState)
    }
}
