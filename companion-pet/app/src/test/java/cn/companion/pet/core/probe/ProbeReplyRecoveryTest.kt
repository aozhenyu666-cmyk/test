package cn.companion.pet.core.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 模型输出被 max_tokens 截断时，JSON 没有收尾的 }，但气泡要用的 hook_text 往往已经完整。
 * 这种情况不能整句丢掉，让桌宠无声无息地“哑掉”。
 */
class ProbeReplyRecoveryTest {

    @Test
    fun truncatedJsonKeepsCompleteHookText() {
        val cut = """{"hook_text": "这段推导第三步是不是跳了？", "context_summary": "他在看一篇关于线性代数特征值的讲义，卡在"""
        val reply = ProbeReplyParser.parse(cut)
        assertNotNull(reply)
        assertEquals("这段推导第三步是不是跳了？", reply!!.hookText)
        assertEquals(false, reply.skip)
    }

    @Test
    fun truncatedInsideHookTextIsStillDropped() {
        // hook_text 本身没写完：宁可这次不说，也不显示半句话。
        assertNull(ProbeReplyParser.parse("""{"hook_text": "这段推导第三步是不"""))
    }

    @Test
    fun recoveredHookKeepsEscapes() {
        val cut = """```json
{"hook_text": "他说\"慢慢来\"也行", "mood": "happy", "context_summary": "很长很长"""
        val reply = ProbeReplyParser.parse(cut)
        assertEquals("他说\"慢慢来\"也行", reply!!.hookText)
        assertEquals(Mood.HAPPY, reply.mood)
    }

    @Test
    fun repliesHaveRoomForTheFullJsonFormat() {
        // 30 字气泡 + 100 字摘要 + 字段名，中文按约 1 token/字估算，150 不够。
        assertEquals(true, ProbePrompt.REPLY_MAX_TOKENS >= 400)
        assertEquals(true, ProbePrompt.TIMEOUT_MILLIS >= 20_000L)
    }
}
