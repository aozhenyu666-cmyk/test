package cn.companion.pet

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 设备冒烟测试：设置页能启动，引导项可见。 */
@RunWith(AndroidJUnit4::class)
class BootstrapSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun startTabShowsOverlayStep() {
        composeRule.onNodeWithText("开始").assertIsDisplayed()
        composeRule.onNodeWithText("悬浮窗", substring = true).assertIsDisplayed()
    }
}
