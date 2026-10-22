package cn.companion.pet.core.life

import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/** 角色自己的状态。只影响待机动画和语气，不影响开口节奏。 */
enum class LifeState(val label: String) {
    AWAKE("清醒"),
    SLEEPY("犯困"),
    READING("在看书"),
    DAZING("在发呆"),
    WRITING("在写日记"),
}

/**
 * 生物钟：两次开口之间随机换状态。深夜和午后更容易犯困，晚上更容易写日记。
 */
class PetLife(private val zone: ZoneId = ZoneId.systemDefault(), private val random: Random = Random.Default) {
    var state: LifeState = LifeState.AWAKE
        private set
    private var nextChangeAt = 0L

    /** 到点就换状态；返回是否换了。 */
    fun tick(now: Long): Boolean {
        if (now < nextChangeAt) return false
        nextChangeAt = now + random.nextLong(40_000L, 150_000L)
        val next = pick(now)
        val changed = next != state
        state = next
        return changed
    }

    private fun pick(now: Long): LifeState {
        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        val weights = mutableMapOf(
            LifeState.AWAKE to 4, LifeState.READING to 3, LifeState.DAZING to 2,
            LifeState.SLEEPY to 1, LifeState.WRITING to 1,
        )
        if (hour >= 23 || hour < 7 || hour in 13..14) weights[LifeState.SLEEPY] = 5
        if (hour in 20..23) weights[LifeState.WRITING] = 3
        val total = weights.values.sum()
        var r = random.nextInt(total)
        for ((s, w) in weights) {
            if (r < w) return s
            r -= w
        }
        return LifeState.AWAKE
    }
}

/**
 * 每天最多 2 次“有分量的开口”。只在白天，随机触发，单独计数。
 */
class WeightyMoments(private val perDay: Int = 2, private val random: Random = Random.Default) {
    private var dateKey = ""
    private var used = 0

    fun usedToday(today: String): Int {
        if (today != dateKey) { dateKey = today; used = 0 }
        return used
    }

    /** 这次开口要不要做成有分量的。 */
    fun shouldUse(today: String, hour: Int, chance: Double = 0.12): Boolean {
        if (usedToday(today) >= perDay) return false
        if (hour !in 10..22) return false
        return random.nextDouble() < chance
    }

    fun markUsed(today: String) {
        usedToday(today)
        used += 1
    }

    fun restore(today: String, count: Int) { dateKey = today; used = count }
}
