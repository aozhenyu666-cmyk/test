package com.yishou.app.assistant

import org.junit.Assert.*
import org.junit.Test

class ChatHistoryTest {
    @Test fun `恢复保留角色和内容 失败回复不混入下一轮上下文`() {
        val list = listOf(ChatLine("user", "我想先列条件"), ChatLine("assistant", "你已经确定了哪一条？"), ChatLine("error", "网络失败"))
        assertEquals(list, ChatHistory.decode(ChatHistory.encode(list)))
        assertEquals(list.take(2).map { it.role to it.text }, ChatHistory.context(list))
    }
    @Test fun `上下文从完整用户轮次开始且有总长度上限`() {
        val lines = (1..40).flatMap { listOf(ChatLine("user", "问$it" + "字".repeat(2000)), ChatLine("assistant", "答$it" + "字".repeat(2000))) }
        val result = ChatHistory.context(lines)
        assertTrue(result.sumOf { it.second.length } <= ChatHistory.MAX_CONTEXT)
        assertEquals("user", result.first().first)
        assertEquals("assistant", result.last().first)
    }
}
