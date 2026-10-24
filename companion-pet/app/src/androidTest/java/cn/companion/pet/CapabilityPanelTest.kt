package cn.companion.pet

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import cn.companion.pet.core.action.*
import cn.companion.pet.ui.CapabilityPanel
import cn.companion.pet.ui.theme.CompanionTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic UI tests; compiling these does not verify TTS, app launching, or a real device. */
class CapabilityPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noteNeedsConfirmationAndBusyRejectsDoubleTap() {
        var calls = 0
        var received: Map<String, String>? = null
        var state by mutableStateOf(ActionSessionState())
        compose.setContent { CompanionTheme {
            CapabilityPanel(state, { kind, args ->
                calls++; received = args
                state = ActionSessionState(ActionPhase.PENDING, ActionRequest("synthetic", kind, args))
                true
            }, {}, {}, {})
        } }
        compose.onNodeWithText("保存念头").performClick()
        compose.onNodeWithText("想记下什么").performTextInput("合成念头")
        compose.onNodeWithText("预览念头").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithText("确认保存").performClick()
        compose.onNodeWithText("预览念头").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls); assertEquals("合成念头", received!!["text"]) }
    }

    @Test fun restoringUiDoesNotSubmitAndUnavailableRemainsVisible() {
        var calls = 0
        val restore = StateRestorationTester(compose)
        val state = ActionSessionState(ActionPhase.FINISHED, ActionRequest("old", ActionKind.SPEAK),
            ActionResult("old", ActionKind.SPEAK, ActionStatus.UNAVAILABLE, "语音引擎不可用"))
        restore.setContent { CompanionTheme { CapabilityPanel(state, { _, _ -> calls++; true }, {}, {}, {}) } }
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("语音引擎不可用").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, calls) }
    }
}
