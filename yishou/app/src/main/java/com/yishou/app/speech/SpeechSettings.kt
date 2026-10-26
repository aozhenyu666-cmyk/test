package com.yishou.app.speech

data class SpeechEndpoint(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val voice: String = "",
    val language: String = "zh",
    val format: String = "mp3",
) {
    val ready: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    override fun toString(): String = "SpeechEndpoint(model=$model, configured=$ready)"
}

/** 保留旧构造参数，让已有语音调用随设置升级，不必一起改写。 */
data class SpeechConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val sttModel: String = "",
    val ttsModel: String = "",
    val ttsVoice: String = "",
    val stt: SpeechEndpoint? = null,
    val tts: SpeechEndpoint? = null,
) {
    val input: SpeechEndpoint get() = stt ?: SpeechEndpoint(baseUrl, apiKey, sttModel)
    val output: SpeechEndpoint get() = tts ?: SpeechEndpoint(baseUrl, apiKey, ttsModel, voice = ttsVoice)
    val sttReady: Boolean get() = input.ready
    val ttsReady: Boolean get() = output.ready
    override fun toString(): String = "SpeechConfig(input=$input, output=$output)"
}

/** 迁移在加密设置中进行；标记区分“没迁移”和用户主动清空。 */
object SpeechSettings {
    fun inputValues(endpoint: SpeechEndpoint): Map<String, String> = values("stt", endpoint)
    fun outputValues(endpoint: SpeechEndpoint): Map<String, String> = values("tts", endpoint)

    fun migrate(stored: Map<String, String>): Map<String, String> {
        val legacy = SpeechConfig(
            stored["speech_base_url"].orEmpty(), stored["speech_api_key"].orEmpty(),
            stored["speech_stt_model"].orEmpty(), stored["speech_tts_model"].orEmpty(),
            stored["speech_tts_voice"].orEmpty(),
        )
        return stored +
            (if (stored["stt_configured"] == "1") emptyMap() else inputValues(legacy.input)) +
            (if (stored["tts_configured"] == "1") emptyMap() else outputValues(legacy.output))
    }

    fun read(stored: Map<String, String>): SpeechConfig {
        val current = migrate(stored)
        return SpeechConfig(stt = endpoint(current, "stt"), tts = endpoint(current, "tts"))
    }

    private fun values(prefix: String, endpoint: SpeechEndpoint): Map<String, String> = mapOf(
        "${prefix}_configured" to "1", "${prefix}_base_url" to endpoint.baseUrl.trim(),
        "${prefix}_api_key" to endpoint.apiKey.trim(), "${prefix}_model" to endpoint.model.trim(),
        "${prefix}_voice" to endpoint.voice.trim(), "${prefix}_language" to endpoint.language.trim(),
        "${prefix}_format" to endpoint.format.trim(),
    )

    private fun endpoint(stored: Map<String, String>, prefix: String) = SpeechEndpoint(
        baseUrl = stored["${prefix}_base_url"].orEmpty(), apiKey = stored["${prefix}_api_key"].orEmpty(),
        model = stored["${prefix}_model"].orEmpty(), voice = stored["${prefix}_voice"].orEmpty(),
        language = stored["${prefix}_language"] ?: "zh", format = stored["${prefix}_format"] ?: "mp3",
    )
}
