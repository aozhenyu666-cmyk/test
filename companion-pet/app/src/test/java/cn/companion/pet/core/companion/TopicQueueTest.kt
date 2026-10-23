package cn.companion.pet.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class TopicQueueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-23T04:00:00Z"), zone)
    private val now = clock.millis()

    private fun queue() = TopicQueue(tmp.root, clock)

    @Test
    fun topicNeedsRealSource() {
        assertNull(queue().enqueue("没有来源", emptyList()))
        assertNotNull(queue().enqueue("昨天说的猫", listOf("e1")))
    }

    @Test
    fun expiredTopicIsNotOffered() {
        val q = queue()
        q.enqueue("过期", listOf("e1"), ttlMillis = 1000, now = now - 5000)
        assertTrue(q.candidates(now).isEmpty())
    }

    @Test
    fun sharedTopicIsOnlyOfferedOnce() {
        val q = queue()
        val t = q.enqueue("只分享一次", listOf("e1"))!!
        assertEquals(1, q.candidates(now).size)
        q.markShared(t.id, now)
        q.markShared(t.id, now + 1)
        assertTrue(q.candidates(now).isEmpty())
    }

    @Test
    fun dismissedTopicDoesNotRepeat() {
        val q = queue()
        val t = q.enqueue("被忽略的话题", listOf("e1"))!!
        q.dismiss(t.id)
        assertTrue(q.candidates(now).isEmpty())
    }

    @Test
    fun gateRespectsQuietCooldownAndDuplicates() {
        val q = queue()
        val gate = TopicGate(q)
        q.enqueue("昨天说的猫", listOf("e1"), now = now - 31 * 60 * 1000)

        assertNull(gate.next(now, quiet = true, userActive = true, lastProactiveAt = 0))
        assertNull(gate.next(now, quiet = false, userActive = false, lastProactiveAt = 0))
        assertNull(gate.next(now, quiet = false, userActive = true, lastProactiveAt = 0, recentTexts = setOf("昨天说的猫")))
        assertNotNull(gate.next(now, quiet = false, userActive = true, lastProactiveAt = 0))

        // 刚入队的话题至少等 30 分钟，避免一入队就开口。
        val fresh = TopicQueue(tmp.newFolder("fresh"), clock)
        fresh.enqueue("刚想到的话题", listOf("e2"))
        assertNull(TopicGate(fresh).next(now, quiet = false, userActive = true, lastProactiveAt = 0))
    }
}