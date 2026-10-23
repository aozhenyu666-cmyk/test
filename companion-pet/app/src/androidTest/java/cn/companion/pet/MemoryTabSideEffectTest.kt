package cn.companion.pet

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.companion.pet.core.companion.EventKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * B1 回归：打开“记忆”页只应读取，不能清空当天事件。
 * 旧实现把 clearDay 直接写在 Column 组合体里，页面一打开就删数据。
 */
@RunWith(AndroidJUnit4::class)
class MemoryTabSideEffectTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun openingMemoryTabDoesNotClearTodayEvents() {
        val app = PetApp.instance
        val day = LocalDate.now()
        app.events.append(EventKind.EXCHANGE, "- synthetic side-effect check", source = "instrumented")
        val expected = app.events.forDay(day).size

        composeRule.onNodeWithText("记忆").performClick()
        composeRule.waitForIdle()
        assertEquals("Opening memory tab must not delete events", expected, app.events.forDay(day).size)

        composeRule.onNodeWithText("开始").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("记忆").performClick()
        composeRule.waitForIdle()
        assertEquals("Re-entering memory tab must still not delete events", expected, app.events.forDay(day).size)
    }
}
