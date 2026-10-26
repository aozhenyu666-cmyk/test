package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 验收审查发现的问题的回归测试（锁不释放、宽限不落盘、计时没真延长）。 */
class ReviewFixRegressionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun strictForceReleaseDoesNotFreeTrainingLock() {
        val a = SessionArbiter()
        assertEquals(null, a.acquire(SessionKind.TRAINING, "t1"))
        // endThink / strictAbort 用 force 释放严格陪想：不能顺手放掉训练的锁。
        assertFalse(a.release(SessionKind.STRICT_THINK, force = true))
        assertFalse(a.release(SessionKind.EXTERNAL_AI, force = true))
        assertTrue(a.isHeldBy(SessionKind.TRAINING, "t1"))
    }

    @Test
    fun forceStillSkipsInstanceIdForSameKind() {
        val a = SessionArbiter()
        a.acquire(SessionKind.STRICT_THINK, "strict-1")
        assertTrue(a.release(SessionKind.STRICT_THINK, force = true))
        assertTrue(a.isIdle())
    }

    @Test
    fun completedTrainingReleaseAllowsNextQuestion() {
        val a = SessionArbiter()
        a.acquire(SessionKind.TRAINING, "first")
        // 旧会话不释放时，“下一题”申请必然被挡。
        assertTrue(a.acquire(SessionKind.TRAINING, "second") != null)
        assertTrue(a.release(SessionKind.TRAINING, "first"))
        assertEquals(null, a.acquire(SessionKind.TRAINING, "second"))
    }

    @Test
    fun ambientCheckDoesNotHoldOutput() {
        val o = OutputArbiter()
        // 连续两句温和搭话都能说：allows 只检查，不占用。
        assertTrue(o.allows(OutputPriority.AMBIENT))
        assertTrue(o.allows(OutputPriority.AMBIENT))
        assertTrue(o.isIdle())
        // 题目在显示时，闲聊不能盖掉它。
        assertTrue(o.acquire(OutputPriority.TRAINING, "training-a"))
        assertFalse(o.allows(OutputPriority.AMBIENT))
        assertTrue(o.allows(OutputPriority.URGENT))
        // 训练结束释放后，下一题（不同 owner、同优先级）能拿到输出权。
        assertTrue(o.release("training-a"))
        assertTrue(o.acquire(OutputPriority.TRAINING, "training-b"))
    }

    @Test
    fun extensionActuallyWaitsGrantedDuration() {
        val t = TrainingTimer(firstSilenceMillis = 60_000)
        t.arm(0)
        assertTrue(t.extend(50_000, 90_000))
        // 原阈值 60 秒在 110 秒时早就到了；延长后要等到 140 秒。
        assertFalse(t.shouldNudge(110_000))
        assertFalse(t.shouldNudge(139_999))
        assertTrue(t.shouldNudge(140_000))
        // 进入下一阶段后恢复默认阈值。
        t.afterScaffold(140_000)
        assertTrue(t.shouldNudge(200_000))
    }

    @Test
    fun extensionNotAllowedDuringRecovery() {
        val t = TrainingTimer()
        t.arm(0)
        t.toRecovery()
        assertFalse(t.extend(1_000, 90_000))
    }

    @Test
    fun extensionQuotaSurvivesRestart() {
        val q = BuiltInQuestions.pick(emptySet())
        val c = TrainingController(TrainingSession(id = "s-ext", createdAt = 1, updatedAt = 1))
        c.bind(q)
        c.present(1)
        assertTrue(c.canExtend)
        val policy = ExtensionPolicy()
        var state = ExtensionState(c.current.extensionTimes, c.current.extensionMillis)
        repeat(2) {
            val (r, next) = policy.request(state, 0)
            assertTrue(r is ExtensionResult.Granted)
            state = next
            c.recordExtension(next.usedTimes, next.usedMillis, 2)
        }
        assertTrue(TrainingStore(tmp.root).save(c.current, q))
        val restored = (TrainingStore(tmp.root).restore(3) as TrainingStore.Restore.Resume).session
        val (again, _) = policy.request(ExtensionState(restored.extensionTimes, restored.extensionMillis), 0)
        // 重启后不能重新领到“我在想”。
        assertTrue(again is ExtensionResult.Denied)
    }

    @Test
    fun cannotExtendWhileEvaluating() {
        val q = BuiltInQuestions.pick(emptySet())
        val c = TrainingController(TrainingSession(id = "s-eval", createdAt = 1, updatedAt = 1))
        c.bind(q)
        c.present(1)
        c.submit(transcript = "任何人", now = 2)
        assertFalse(c.canExtend)
    }
}
