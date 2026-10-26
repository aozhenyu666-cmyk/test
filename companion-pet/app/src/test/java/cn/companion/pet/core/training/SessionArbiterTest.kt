package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionArbiterTest {

    @Test
    fun idleArbiterAcceptsFirstSession() {
        val a = SessionArbiter()
        assertTrue(a.isIdle())
        assertNull(a.acquire(SessionKind.TRAINING, "t1"))
        assertEquals(SessionKind.TRAINING, a.current)
        assertEquals("t1", a.currentId)
    }

    @Test
    fun secondTrainingIsRejected() {
        val a = SessionArbiter()
        a.acquire(SessionKind.TRAINING, "t1")
        val err = a.acquire(SessionKind.TRAINING, "t2")
        assertTrue(err != null)
        assertTrue(err!!.contains("训练"))
        assertEquals("t1", a.currentId)
    }

    @Test
    fun strictCannotTakeOverTraining() {
        // 关键：旧严格回合不能覆盖正在进行的训练。
        val a = SessionArbiter()
        a.acquire(SessionKind.TRAINING, "t1")
        val err = a.acquire(SessionKind.STRICT_THINK, "s1")
        assertTrue(err != null)
        assertEquals(SessionKind.TRAINING, a.current)
    }

    @Test
    fun trainingCannotStartDuringStrict() {
        val a = SessionArbiter()
        a.acquire(SessionKind.STRICT_THINK, "s1")
        val err = a.acquire(SessionKind.TRAINING, "t1")
        assertTrue(err != null)
        assertEquals(SessionKind.STRICT_THINK, a.current)
    }

    @Test
    fun externalAiIsAlsoExclusive() {
        val a = SessionArbiter()
        a.acquire(SessionKind.TRAINING, "t1")
        assertTrue(a.acquire(SessionKind.EXTERNAL_AI, "e1") != null)
        a.release(SessionKind.TRAINING, "t1")
        assertNull(a.acquire(SessionKind.EXTERNAL_AI, "e1"))
    }

    @Test
    fun sameSessionReacquireIsIdempotent() {
        val a = SessionArbiter()
        assertNull(a.acquire(SessionKind.TRAINING, "t1"))
        // 同一实例重复申请算成功，不报错、不换持有者。
        assertNull(a.acquire(SessionKind.TRAINING, "t1"))
        assertEquals("t1", a.currentId)
    }

    @Test
    fun releaseOnlyByHolder() {
        val a = SessionArbiter()
        a.acquire(SessionKind.TRAINING, "t1")
        // 别的类型/别的实例放不掉。
        assertFalse(a.release(SessionKind.STRICT_THINK, "s1"))
        assertFalse(a.release(SessionKind.TRAINING, "t2"))
        assertEquals(SessionKind.TRAINING, a.current)
        // 自己可以放。
        assertTrue(a.release(SessionKind.TRAINING, "t1"))
        assertTrue(a.isIdle())
    }

    @Test
    fun forceReleaseWorksForRecovery() {
        val a = SessionArbiter()
        a.acquire(SessionKind.TRAINING, "t1")
        assertTrue(a.release(SessionKind.NONE, force = true))
        assertTrue(a.isIdle())
    }

    @Test
    fun noneIsNotAcquirable() {
        val a = SessionArbiter()
        assertTrue(a.acquire(SessionKind.NONE, "x") != null)
        assertTrue(a.isIdle())
    }

    @Test
    fun describeIsReadable() {
        val a = SessionArbiter()
        assertEquals("无会话", a.describe())
        a.acquire(SessionKind.TRAINING, "abcdef1234")
        assertTrue(a.describe().contains("训练"))
        assertTrue(a.describe().contains("abcdef12"))
    }
}
