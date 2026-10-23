package com.yishou.app.companion

import com.yishou.app.companion.core.Phase
import com.yishou.app.companion.core.VoiceAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语音作答的开关：只在等回答时开麦；听不清退回打字，不算答错 */
class VoiceAnswerTest {

    @Test
    fun `只在等你答的时候开麦`() {
        assertTrue(VoiceAnswer.shouldListen(Phase.WAIT_ANSWER))
        listOf(
            Phase.OBSERVE, Phase.PREPARE, Phase.PAUSE_PENDING, Phase.ASK,
            Phase.REVIEW, Phase.RECORD, Phase.SUSPENDED, Phase.ENDED,
        ).forEach { p ->
            assertFalse(p.label + " 不该开麦", VoiceAnswer.shouldListen(p))
        }
    }

    @Test
    fun `空白和没拿到都不算回答`() {
        assertNull(VoiceAnswer.accept(null))
        assertNull(VoiceAnswer.accept(""))
        assertNull(VoiceAnswer.accept("   "))
        assertEquals("缺抽样这一步", VoiceAnswer.accept("  缺抽样这一步" + "\n"))
    }

    @Test
    fun `没听清要说还能打字`() {
        assertEquals("没听清，打字也行", VoiceAnswer.missed(null))
        assertEquals("没听清，打字也行", VoiceAnswer.missed("  "))
        assertTrue(VoiceAnswer.missed("听写超时").contains("听写超时"))
        assertTrue(VoiceAnswer.missed("听写超时").contains("打字也行"))
    }
}
