package huilu.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import huilu.core.Assessor
import huilu.core.Engine
import huilu.core.Episode
import huilu.core.Insights
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 回顾：不是完成率，而是"原本以为会发生什么、实际发生了什么"。 */
class HistoryActivity : Activity() {
    private val app get() = App.of(this)
    private val expanded = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val eps = app.episodes()
        val ins = Insights.of(eps)
        page {
            text("偏差", 22f, bold = true, top = 8)
            if (ins.episodes == 0) text("还没有结束的行动。完成一轮之后，这里会显示偏离发生在哪里、什么时候、哪种干预有用。", 14f, C.MUTED)
            else summary(ins)

            title("每一轮")
            val day = SimpleDateFormat("M月d日", Locale.CHINA)
            var lastDay = ""
            eps.reversed().take(60).forEach { e ->
                val d = day.format(Date(e.action.startedAt))
                if (d != lastDay) { text(d, 13f, C.MUTED, bold = true, top = 12); lastDay = d }
                episode(e)
            }
            button("导出完整日志（JSONL）") { export() }
        }
    }

    private fun LinearLayout.summary(i: Insights) = card {
        val pct = { a: Int, b: Int -> if (b == 0) "—" else "${a * 100 / b}%" }
        text("${i.episodes} 轮行动，${i.driftedEpisodes} 轮出现偏离（${pct(i.driftedEpisodes, i.episodes)}）", 15f, bold = true)
        text(i.outcomes.entries.joinToString("  ") { "${it.key.label} ${it.value}" }, 14f, C.MUTED)
        i.medianOnset?.let { text("偏离通常发生在第 $it 分钟（${i.onsetMinutes.size} 次的中位数）", 14f, top = 8) }
        i.medianLatency?.let { text("从开始偏离到系统重新出现：中位 ${Assessor.dur(it)}（偏离阈值 ${app.prefs.driftSec} 秒）", 14f) }
        if (i.topDriftApps.isNotEmpty()) text("偏到：" + i.topDriftApps.joinToString("、") { "${app.platform.label(it.first)} ${Assessor.dur(it.second)}" }, 14f)
        if (i.checkIns > 0) {
            text("检查 ${i.checkIns} 次，未回应 ${i.unanswered} 次（${pct(i.unanswered, i.checkIns)}）", 14f, top = 8)
            text("判定：" + i.devKinds.entries.joinToString("  ") { "${it.key.label} ${it.value}" }, 14f, C.MUTED)
        }
        if (i.comparableReports > 0) text("自报「在做」但观察到偏离：${i.optimisticReports} / ${i.comparableReports}", 14f)
        if (i.levels.isNotEmpty()) {
            text("干预效果", 13f, C.MUTED, bold = true, top = 10)
            i.levels.entries.sortedBy { it.key.rank }.forEach { (lv, st) ->
                val after = if (st.aftermath.isEmpty()) "" else "；之后 " + st.aftermath.entries.joinToString(" ") { "${it.key.label}${it.value}" }
                text("${lv.label}：请求 ${st.requested}，确认生效 ${st.verified}，未生效 ${st.notVerified}$after", 14f)
            }
        }
        if (i.byAction.isNotEmpty()) {
            text("最容易偏离的行动", 13f, C.MUTED, bold = true, top = 10)
            i.byAction.take(3).forEach { (t, n, d) -> text("$t：$d / $n 轮偏离", 14f) }
        }
    }

    private fun LinearLayout.episode(e: Episode) = card {
        val open = e.action.id in expanded
        val w = e.whole
        text("${Engine.hm(e.action.startedAt)}  ${e.action.text}", 16f, bold = true)
        text("计划 ${e.action.plannedMin} 分 · 实际 ${e.actualMin ?: "进行中"} 分 · ${e.outcome?.label ?: "进行中"}", 14f, C.MUTED)
        if (w != null) text(w.reality, 14f, if (e.drifted) C.WARN else C.INK)
        if (e.revisions.isNotEmpty()) text("调整：" + e.revisions.joinToString("；"), 13f, C.MUTED)
        if (open) {
            e.checkIns.forEach { c ->
                text("${Engine.hm(c.openedAt)} ${c.trigger.label} · ${c.deviation.kind.label}", 14f, bold = true, top = 10)
                text(c.question, 14f)
                text(c.deviation.reality, 13f, C.MUTED)
                c.notes.forEach { text("· $it", 13f, C.MUTED) }
                text("→ ${c.answer?.label ?: (c.closeReason?.name ?: "等待中")}${c.answerText?.let { "（$it）" } ?: ""}${c.via?.let { " via ${it.name}" } ?: ""}", 14f)
                c.decision?.let { text(it, 13f, C.ACCENT) }
            }
            if (e.interventions.isNotEmpty()) {
                text("干预", 13f, C.MUTED, bold = true, top = 10)
                e.interventions.forEach { r ->
                    val i = r.intervention
                    text("${Engine.hm(i.at)} ${i.level.label}${i.target?.let { "（${app.platform.label(it)}）" } ?: ""}：" +
                        "${r.result?.label ?: "未回报"}${r.resultDetail?.let { " · $it" } ?: ""}" +
                        (r.aftermath?.let { "；之后${it.label}" } ?: ""), 13f)
                    text("理由：${i.reason}", 12f, C.MUTED)
                }
            }
        }
        button(if (open) "收起" else "展开检查与干预（${e.checkIns.size} / ${e.interventions.size}）") {
            if (open) expanded -= e.action.id else expanded += e.action.id
            render()
        }
    }

    private fun export() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "huilu-events.jsonl"), 7)
    }

    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (requestCode != 7 || resultCode != RESULT_OK || uri == null) return
        try {
            contentResolver.openOutputStream(uri)!!.use { out -> app.log.file.inputStream().use { it.copyTo(out) } }
            Toast.makeText(this, "已导出", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
