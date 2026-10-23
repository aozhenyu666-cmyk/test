package cn.companion.pet.core.companion

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Clock
import kotlin.math.pow

@Serializable
data class PetState(
    val energy: Double = 0.60,
    val interest: Double = 0.50,
    val closeness: Double = 0.35,
    val updatedAt: Long = 0L,
    val lastReason: String = "",
)

/** 真实交流事件。状态只由这些本地事件驱动，不增加模型请求。 */
enum class StateEvent {
    USER_REPLIED,
    USER_NOTE,
    PROACTIVE_IGNORED,
    NIGHTLY_DONE,
    QUIET_REST,
}

/**
 * 精力 / 兴趣 / 亲近感三个有界维度。这是角色表现参数，不是对模型真实情感的宣称。
 *
 * 规则是确定的：事件给一个有上限的增量，其余时间向基线回归（半衰期默认 6 小时）。
 * 跨重启从文件恢复，衰减按真实经过时间计算。没有任何网络调用。
 */
class PetStateStore(
    private val root: File,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val halfLifeHours: Double = 6.0,
) {
    private val file = File(root, "state.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    fun current(now: Long = clock.millis()): PetState = synchronized(lock) {
        val stored = read()
        stored.decay(now, halfLifeHours)
    }

    fun apply(event: StateEvent, now: Long = clock.millis()): PetState = synchronized(lock) {
        // 先按真实经过时间衰减，再加事件增量；否则 24 小时的空档会被一次事件抹掉（M1）。
        val base = read().decay(now, halfLifeHours)
        val next = when (event) {
            StateEvent.USER_REPLIED -> base.copy(
                closeness = (base.closeness + 0.06).coerceAtMost(1.0),
                interest = (base.interest + 0.04).coerceAtMost(1.0),
                lastReason = "他回了一句",
            )
            StateEvent.USER_NOTE -> base.copy(
                interest = (base.interest + 0.05).coerceAtMost(1.0),
                lastReason = "他记下一个念头",
            )
            StateEvent.PROACTIVE_IGNORED -> base.copy(
                interest = (base.interest - 0.03).coerceAtLeast(0.0),
                lastReason = "这次没得到回应",
            )
            StateEvent.NIGHTLY_DONE -> base.copy(
                energy = (base.energy - 0.04).coerceAtLeast(0.0),
                lastReason = "整理完今天的事有点累",
            )
            StateEvent.QUIET_REST -> base.copy(
                energy = (base.energy + 0.02).coerceAtMost(1.0),
                lastReason = "安静了一会儿",
            )
        }
        val updated = next.copy(updatedAt = now)
        write(updated)
        updated
    }

    /** 调试/展示用：一句人话，明确这是表现参数。 */
    fun summary(state: PetState): String {
        val energy = if (state.energy >= 0.66) "有精神" else if (state.energy >= 0.33) "一般" else "有点累"
        val interest = if (state.interest >= 0.66) "有兴趣" else if (state.interest >= 0.33) "平常" else "兴致不高"
        val close = if (state.closeness >= 0.66) "很熟" else if (state.closeness >= 0.33) "在熟悉" else "还客气"
        return "精力" + energy + "，兴趣" + interest + "，亲近" + close + "（表现参数）"
    }

    private fun read(): PetState {
        if (!file.exists()) return PetState(updatedAt = clock.millis())
        return runCatching { json.decodeFromString(PetState.serializer(), file.readText()) }
            .getOrElse { PetState(updatedAt = clock.millis()) }
    }

    private fun write(state: PetState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "." + file.name + ".tmp")
        tmp.writeText(json.encodeToString(PetState.serializer(), state))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "rename failed: state.json" }
        }
    }
}

fun PetState.decay(now: Long, halfLifeHours: Double): PetState {
    if (updatedAt <= 0L || now <= updatedAt || halfLifeHours <= 0.0) return copy(updatedAt = now)
    val hours = (now - updatedAt) / 3_600_000.0
    val factor = 2.0.pow(-hours / halfLifeHours)
    fun toward(value: Double, baseline: Double) = (baseline + (value - baseline) * factor).coerceIn(0.0, 1.0)
    return copy(
        energy = toward(energy, 0.60),
        interest = toward(interest, 0.50),
        closeness = toward(closeness, 0.35),
        updatedAt = now,
    )
}
