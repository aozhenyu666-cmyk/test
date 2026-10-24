package cn.companion.pet.data

import android.content.Context
import cn.companion.pet.core.vision.VisionEvidence
import cn.companion.pet.core.vision.VisionEvidenceSink
import kotlinx.serialization.json.Json

/**
 * 最近一次看图的证据（类型/大小/指纹/结论），不含图片、Key 或回复原文。
 * 调试包里可以用 `adb shell run-as cn.companion.pet cat shared_prefs/vision_evidence.xml` 读出来做验收。
 */
class VisionEvidencePrefs(context: Context) : VisionEvidenceSink {
    private val prefs = context.applicationContext.getSharedPreferences("vision_evidence", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun record(evidence: VisionEvidence) {
        prefs.edit().putString(KEY, json.encodeToString(VisionEvidence.serializer(), evidence)).apply()
    }

    override fun last(): VisionEvidence? = prefs.getString(KEY, null)?.let {
        runCatching { json.decodeFromString(VisionEvidence.serializer(), it) }.getOrNull()
    }

    private companion object {
        const val KEY = "last"
    }
}
