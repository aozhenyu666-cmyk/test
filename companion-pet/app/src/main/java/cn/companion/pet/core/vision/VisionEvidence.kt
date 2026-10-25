package cn.companion.pet.core.vision

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * 一次“看一眼”到底走到了哪一步。
 * CAPTURE_FAILED 截图失败；NOT_CONFIGURED 没配好不发请求；UPLOAD_OFF 没允许上传；
 * REQUEST_FAILED 请求发了但失败（Key/模型/网络）；PARSE_FAILED 模型回了但解析不出；OK 拿到摘要。
 */
@Serializable
enum class VisionOutcome { CAPTURE_FAILED, NOT_CONFIGURED, UPLOAD_OFF, BUDGET, REQUEST_FAILED, PARSE_FAILED, OK }

/**
 * 看图请求的证据：只有类型、大小、指纹和结论，不含图片、Base64、Key 或模型回复原文。
 * imageBlocks 是交给网络层的图片块数量（网络层把每个都发成 `image_url` 内容块），
 * 用来区分“真的把图发出去了”和“只凭屏幕文字回答”。
 */
@Serializable
data class VisionEvidence(
    val at: Long,
    val outcome: VisionOutcome,
    val model: String = "",
    val host: String = "",
    val imageBlocks: Int = 0,
    val mime: String = "",
    val imageBytes: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    /** 图片 SHA-256 的前 12 位，只用于对照是不是同一张图。 */
    val imageSha256: String = "",
    val durationMs: Long = 0,
    /** 失败原因（已脱敏的短句），成功时为空。 */
    val failure: String = "",
    /** 回复里的摘要字数；不存摘要本身。 */
    val summaryChars: Int = 0,
    /** 图上画了几个编号框。 */
    val marks: Int = 0,
) {
    fun label(): String = buildString {
        append(
            when (outcome) {
                VisionOutcome.OK -> "成功"
                VisionOutcome.CAPTURE_FAILED -> "截图失败"
                VisionOutcome.NOT_CONFIGURED -> "看图未配置，没发请求"
                VisionOutcome.UPLOAD_OFF -> "没允许上传，没发请求"
                VisionOutcome.BUDGET -> "今日额度用完，没发请求"
                VisionOutcome.REQUEST_FAILED -> "请求失败"
                VisionOutcome.PARSE_FAILED -> "模型回了但解析不出"
            },
        )
        if (imageBlocks > 0) {
            append(" · 发出 ").append(imageBlocks).append(" 张图（").append(mime).append("，")
                .append(imageBytes / 1024).append(" KB")
            if (width > 0) append("，").append(width).append("×").append(height)
            append("，指纹 ").append(imageSha256)
            if (marks > 0) append("，标号 ").append(marks).append(" 个")
            append("）")
        }
        if (model.isNotBlank()) append(" · ").append(model).append(" @ ").append(host)
        if (durationMs > 0) append(" · ").append(durationMs).append(" ms")
        if (failure.isNotBlank()) append(" · ").append(failure)
    }

    companion object {
        fun sha256Prefix(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(12)
    }
}

/** 证据的去处（Android 上存 SharedPreferences）；测试里用内存实现。 */
interface VisionEvidenceSink {
    fun record(evidence: VisionEvidence)
    fun last(): VisionEvidence?
}
