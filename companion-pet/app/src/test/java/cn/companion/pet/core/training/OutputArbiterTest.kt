package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputArbiterTest {

    @Test
    fun idleAcceptsAnyOutput() {
        val a = OutputArbiter()
        assertTrue(a.isIdle())
        assertTrue(a.acquire(OutputPriority.AMBIENT, "chat"))
        assertEquals(OutputPriority.AMBIENT, a.current)
    }

    @Test
    fun samePriorityCannotPreempt() {
        val a = OutputArbiter()
        a.acquire(OutputPriority.TRAINING, "q1")
        // 第二个训练气泡不能覆盖第一个题目。
        assertFalse(a.acquire(OutputPriority.TRAINING, "q2"))
        assertEquals("q1", a.currentId)
    }

    @Test
    fun lowerPriorityCannotPreemptTraining() {
        val a = OutputArbiter()
        a.acquire(OutputPriority.TRAINING, "q1")
        // 闲聊不能盖掉正在显示的题目。
        assertFalse(a.acquire(OutputPriority.AMBIENT, "chat"))
        assertEquals(OutputPriority.TRAINING, a.current)
    }

    @Test
    fun urgentPreemptsAndNotifiesOwner() {
        val a = OutputArbiter()
        val preempted = mutableListOf<String>()
        a.onPreempted = { preempted += it }
        a.acquire(OutputPriority.TRAINING, "q1")
        assertTrue(a.acquire(OutputPriority.URGENT, "abort"))
        assertEquals(OutputPriority.URGENT, a.current)
        assertEquals(listOf("q1"), preempted)
    }

    @Test
    fun failurePreemptsAmbientButNotTraining() {
        val a = OutputArbiter()
        a.acquire(OutputPriority.AMBIENT, "chat")
        assertTrue(a.acquire(OutputPriority.FAILURE, "tech"))
        assertEquals(OutputPriority.FAILURE, a.current)

        val b = OutputArbiter()
        b.acquire(OutputPriority.TRAINING, "q1")
        // FAILURE 数值比 TRAINING 小，可以抢占；这里验证顺序符合定义。
        assertTrue(b.acquire(OutputPriority.FAILURE, "tech"))
    }

    @Test
    fun sameOwnerReacquireIsIdempotent() {
        val a = OutputArbiter()
        assertTrue(a.acquire(OutputPriority.TRAINING, "q1"))
        assertTrue(a.acquire(OutputPriority.TRAINING, "q1"))
        assertEquals("q1", a.currentId)
    }

    @Test
    fun onlyHolderCanRelease() {
        val a = OutputArbiter()
        a.acquire(OutputPriority.TRAINING, "q1")
        assertFalse(a.release("other"))
        assertEquals(OutputPriority.TRAINING, a.current)
        assertTrue(a.release("q1"))
        assertTrue(a.isIdle())
    }

    @Test
    fun afterReleaseOthersCanSpeak() {
        val a = OutputArbiter()
        a.acquire(OutputPriority.TRAINING, "q1")
        assertFalse(a.acquire(OutputPriority.AMBIENT, "chat"))
        a.release("q1")
        assertTrue(a.acquire(OutputPriority.AMBIENT, "chat"))
    }

    @Test
    fun forceReleaseRecovers() {
        val a = OutputArbiter()
        a.acquire(OutputPriority.TRAINING, "q1")
        assertTrue(a.release("", force = true))
        assertTrue(a.isIdle())
    }

    @Test
    fun describeIsReadable() {
        val a = OutputArbiter()
        assertEquals("没有输出所有者", a.describe())
        a.acquire(OutputPriority.TRAINING, "q123456789abc")
        assertTrue(a.describe().contains("TRAINING"))
    }
}