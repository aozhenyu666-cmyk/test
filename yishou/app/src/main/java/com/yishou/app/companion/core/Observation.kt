package com.yishou.app.companion.core

/**
 * 一段材料是怎么进来的。视频声音和画面分开记，是因为「听到的原文」和「看到的画面」
 * 证据强度不一样：画面里看不清的图表不能当原文事实用。
 */
enum class Channel(val label: String) {
    VIDEO_AUDIO("视频声音"),
    USER_VOICE("我说话"),
    SCREEN("画面"),
    MEMO("目标"),
}

/** 这段材料有多可信。识别半截、画面糊掉都要标出来，后面判断才不硬气。 */
enum class Quality { GOOD, PARTIAL, WEAK }

/**
 * 时间轴上的一个观察。
 *
 * `elapsedStart/EndMs` 是「这一场开始以来的毫秒数」，不是墙上时间——用户改系统时间、时区变了都不影响对齐；
 * `wallTimeMs` 只用来显示「几点几分听到的」。
 */
data class Observation(
    val id: Long,
    val sessionId: Long,
    /** 换视频、跳播、换目标就加一；旧段的结果一律不许用在新段上 */
    val sourceEpoch: Int,
    val appPackage: String,
    val channel: Channel,
    val elapsedStartMs: Long,
    val elapsedEndMs: Long,
    val wallTimeMs: Long,
    val text: String,
    val quality: Quality = Quality.GOOD,
    /** 这段之前缺了多久（没声音 / 没画面 / 识别失败）：缺口要能看见，不能当连续的 */
    val gapsBeforeMs: Long = 0,
) {
    val durationMs: Long get() = (elapsedEndMs - elapsedStartMs).coerceAtLeast(0)
}

/** 播放进度是从哪儿来的 */
enum class PositionSource(val label: String) {
    MEDIA_SESSION("播放器"),
    PLAYER_STATE("播放器状态"),
    NONE("没有进度"),
}

/**
 * 播放位置。拿不到进度时宁可只说「本次观察的第几分钟」，也不冒充视频时间码——
 * 用户拿着错误的时间码回去找，比不给更糟。
 */
data class MediaPosition(val positionMs: Long?, val source: PositionSource) {

    fun label(elapsedMs: Long): String = when {
        positionMs != null && source != PositionSource.NONE -> "${stamp(positionMs)}（${source.label}）"
        else -> "本次观察的第 ${elapsedMs / 60_000} 分 ${(elapsedMs % 60_000) / 1_000} 秒"
    }

    private fun stamp(ms: Long): String {
        val s = (ms / 1_000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}

/** 按停顿切出来的一段话：将来做段落摘要、找依据都用它 */
data class Segment(
    val id: Long,
    val channel: Channel,
    val fromMs: Long,
    val toMs: Long,
    val text: String,
    val evidenceIds: List<Long>,
)

/** 交给模型的上下文切片：只有证据、范围和有效期，不夹带推断 */
data class ContextSlice(
    val epoch: Int,
    val evidenceIds: List<Long>,
    val fromMs: Long,
    val toMs: Long,
    val text: String,
    val segments: List<Segment>,
    val gapsMs: Long,
    /** 过了这个时间就不能拿它提问了：内容已经过去，突然出声只会打断人 */
    val expiresAtMs: Long,
) {
    val isEmpty: Boolean get() = evidenceIds.isEmpty() || text.isBlank()
}
