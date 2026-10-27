package cn.companion.pet.core.probe

import cn.companion.pet.core.scene.Scene
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 说话方式：允许讲自己的事、允许有情绪、明确禁止编造。 */
class AlivenessPromptTest {

    private fun moment(ownLife: String = "", recent: List<String> = emptyList()) = ProbeMoment(
        timeText = "晚上九点", scene = Scene.NORMAL, foregroundLabel = "微信",
        trajectory = "在聊天", screenText = null,
        ownLife = ownLife, recentSayings = recent,
    )

    @Test
    fun `人设允许讲自己的事和有情绪`() {
        val p = DefaultPersona.TEXT
        assertTrue("要允许它讲自己读到的东西", p.contains("我自己") || p.contains("我的事"))
    }

    @Test
    fun `人设明确禁止编造`() {
        val p = DefaultPersona.TEXT
        assertTrue("必须明确禁止编造没读过的内容", p.contains("不许编") || p.contains("不编") || p.contains("没读到"))
    }

    @Test
    fun `提示词在有人设内容时带上它自己的生活`() {
        val msg = ProbePrompt.userMessage(emptyList(), "", moment(ownLife = "正在读《万物简史》，读到第三章"))
        assertTrue("它自己的事必须进提示词", msg.content.contains("万物简史"))
    }

    @Test
    fun `提示词带上最近说过的话，并要求不重复`() {
        val msg = ProbePrompt.userMessage(emptyList(), "", moment(recent = listOf("你刚才切了好几个应用")))
        assertTrue("最近说过的话必须回灌", msg.content.contains("你刚才切了好几个应用"))
        assertTrue("必须明确要求不许重复", msg.content.contains("不要重复") || msg.content.contains("不许重复"))
    }

    @Test
    fun `没有自己的生活内容时不出现空块`() {
        val msg = ProbePrompt.userMessage(emptyList(), "", moment())
        assertFalse("没内容就不该留空标题", msg.content.contains("# 我自己的生活"))
    }
}
