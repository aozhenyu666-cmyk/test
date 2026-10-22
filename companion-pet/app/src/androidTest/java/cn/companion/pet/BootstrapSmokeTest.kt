package cn.companion.pet

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * H1 设备冒烟测试：Activity 能启动，且启动页文案可见。
 */
@RunWith(AndroidJUnit4::class)
class BootstrapSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bootstrapScreenShowsStatusText() {
        val expected = composeRule.activity.getString(R.string.bootstrap_status)
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }
}
