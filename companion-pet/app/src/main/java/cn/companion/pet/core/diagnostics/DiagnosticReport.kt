package cn.companion.pet.core.diagnostics

data class DiagnosticInputs(
    val versionName: String,
    val versionCode: Long,
    val now: Long,
    val accessibilityConnected: Boolean,
    val screenshotStatus: String,
    val screenshotReason: String,
    val visionConfigured: Boolean,
    val visionUploadEnabled: Boolean,
    val budgetRemaining: Int,
    val foregroundPackage: String?,
    val foregroundAllowed: Boolean,
    val shizukuStatus: String,
    val capabilityStatus: String,
    val capabilityReason: String,
    val autonomyEnabled: Boolean,
    val goalStatus: String,
    val pendingCount: Int,
    val lastAction: String,
)

/** 可复制的脱敏诊断：不含 Key、截图/Base64、聊天原文、输入文本或密码。 */
object DiagnosticReport {
    fun render(input: DiagnosticInputs): String = buildString {
        appendLine("Companion Pet 诊断报告")
        appendLine("时间：" + input.now)
        appendLine("版本：" + input.versionName + " (" + input.versionCode + ")")
        appendLine("无障碍：" + yesNo(input.accessibilityConnected))
        appendLine("截图：" + input.screenshotStatus + (if (input.screenshotReason.isBlank()) "" else " · " + input.screenshotReason))
        appendLine("视觉模型已配置：" + yesNo(input.visionConfigured) + "；上传开关：" + yesNo(input.visionUploadEnabled))
        appendLine("今日剩余模型预算：" + input.budgetRemaining)
        appendLine("前台：" + (input.foregroundPackage ?: "unknown") + "；在白名单：" + yesNo(input.foregroundAllowed))
        appendLine("Shizuku：" + input.shizukuStatus + "；固定能力：" + input.capabilityStatus + (if (input.capabilityReason.isBlank()) "" else " · " + input.capabilityReason))
        appendLine("自主模式：" + yesNo(input.autonomyEnabled))
        appendLine("目标存储：" + input.goalStatus)
        appendLine("待确认动作：" + input.pendingCount)
        appendLine("最近动作：" + input.lastAction)
        appendLine("")
        appendLine("本报告已脱敏：不含 API Key、截图/Base64、聊天原文、输入文本。")
    }

    private fun yesNo(v: Boolean) = if (v) "是" else "否"
}
