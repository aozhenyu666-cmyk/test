package cn.companion.pet.service

import cn.companion.pet.PetApp
import cn.companion.pet.a11y.PerceptionHub
import cn.companion.pet.a11y.ScreenCaptureGateway
import cn.companion.pet.core.partner.VisionTalk
import cn.companion.pet.core.vision.*
import cn.companion.pet.data.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.Executors

/** Question-aware, memory-only screenshots. Explicit upload permission applies to every call. */
class PartnerVision(private val app: PetApp) {
    data class Read(val summary: VisionSummary? = null, val error: String = "", val at: Long = System.currentTimeMillis(), val packageName: String = "")
    suspend fun capture(question: String, context: String): Read = withContext(Dispatchers.IO) {
        if (!app.settings.visionUploadEnabled) return@withContext Read(error = "看图上传未开启，可在设置 → 模型与看图中打开。")
        val setup = app.visionSetup()
        if (!setup.ready) return@withContext Read(error = "看图还没配好：" + setup.label())
        val active = PerceptionHub.activePackage()
        if (active == null || active == app.packageName || active in app.settings.excludeApps) return@withContext Read(error = "当前页面不适合截取，请先切到要一起看的内容。")
        val executor = Executors.newSingleThreadExecutor()
        val attempt = try { ScreenCaptureGateway.captureBlocking(PerceptionHub.service, executor, 5_000, emptyList()) }
            finally { executor.shutdown() }
        val image = attempt.jpeg
        if (attempt.status != CapturedStatus.OK || image == null) {
            app.visionEvidence.record(VisionEvidence(System.currentTimeMillis(), VisionOutcome.CAPTURE_FAILED, failure = attempt.error.ifBlank { attempt.status.name }))
            return@withContext Read(error = "没有截到画面：" + attempt.error.ifBlank { attempt.status.name })
        }
        if (attempt.packageName in app.settings.excludeApps || attempt.packageName == app.packageName || active != attempt.packageName) return@withContext Read(error = "页面已经切换，这次画面没有上传。")
        if (!app.budget.reserve(app.settings.dailyLimit, LocalDate.now().toString())) return@withContext Read(error = "今天的模型调用次数用完了。")
        app.stats.add(Stats.Kind.API_CALLS)
        val run = app.visionAnalyzer.analyzeDetailed(image, attempt.width, attempt.height, VisionTalk.prompt(question, context))
        app.visionEvidence.record(run.evidence)
        Read(run.summary, if (run.summary == null) "看图调用没有得到可用结果：" + run.evidence.failure else "", packageName = attempt.packageName)
    }
}
