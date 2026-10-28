package cn.companion.pet.core.partner

import java.security.MessageDigest
import kotlin.math.abs

/** Compare small luminance grids locally; retain no screenshot and spend no model call on a static frame. */
class FrameChangeGate(private val threshold: Double = 0.035) {
    private val baselines = linkedMapOf<String, IntArray>()
    fun changed(key: String, grid: IntArray): Boolean {
        val previous = baselines[key] ?: return true
        if (previous.size != grid.size || grid.isEmpty()) return true
        return previous.indices.sumOf { abs(previous[it] - grid[it]).toLong() }.toDouble() / (grid.size * 255.0) >= threshold
    }
    /** Commit only after a successful analysis, so an outage never consumes a new scene. */
    fun analyzed(key: String, grid: IntArray) {
        baselines.remove(key)
        baselines[key] = grid.copyOf()
        while (baselines.size > 8) baselines.remove(baselines.keys.first())
    }
}

object ObservationFingerprint {
    fun of(candidate: TopicCandidate): String {
        val normalized = candidate.source.name + "|" + candidate.anchor.lowercase().replace(Regex("\\s+"), "")
        return MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun screen(candidate: TopicCandidate): Boolean = candidate.source in listOf(TopicSource.SCREEN_TEXT, TopicSource.SCREEN_IMAGE)
}
