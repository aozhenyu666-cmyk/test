package com.behaviordept.app.record

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.behaviordept.app.AppContainer
import com.behaviordept.app.ai.AiAvailability
import com.behaviordept.app.ai.AiException
import com.behaviordept.app.ai.Parsers
import com.behaviordept.app.data.WeekSummary
import com.behaviordept.app.ui.appViewModel
import com.behaviordept.app.ui.components.ChoiceChip
import com.behaviordept.app.ui.components.ErrorPanel
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.InkButton
import com.behaviordept.app.ui.components.LineButton
import com.behaviordept.app.ui.components.PaperCard
import com.behaviordept.app.ui.components.QuietButton
import com.behaviordept.app.ui.components.RedPenBlock
import com.behaviordept.app.ui.components.RuledTextField
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.util.Time
import kotlinx.coroutines.launch
import java.time.LocalDate

class WeeklyViewModel(private val c: AppContainer) : ViewModel() {
    var week by mutableStateOf(defaultWeek())
        private set
    var summary by mutableStateOf<WeekSummary?>(null)
        private set
    var analysis by mutableStateOf("")
        private set
    var focus by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var saved by mutableStateOf(false)
        private set
    var aiReady by mutableStateOf(true)
        private set

    init {
        load()
    }

    /** 周一到周三默认复盘上一周，周四以后复盘本周。 */
    private fun defaultWeek(): LocalDate {
        val today = Time.today()
        val ws = Time.weekStart(today)
        return if (today.dayOfWeek.value <= 3) ws.minusWeeks(1) else ws
    }

    fun pickWeek(ws: LocalDate) {
        week = ws
        load()
    }

    private fun load() {
        viewModelScope.launch {
            aiReady = c.coach.availability() == AiAvailability.READY
            summary = c.weekly.summarize(week)
            val existing = c.weekly.saved(week)
            analysis = existing?.aiAnalysis.orEmpty()
            focus = existing?.confirmedFocus.orEmpty()
            saved = existing != null
            error = null
        }
    }

    fun analyze() {
        val s = summary ?: return
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val text = c.coach.weeklyReview(s.text)
                analysis = text
                Parsers.sections(text).firstOrNull { it.title == "下周唯一重点" }?.lines?.firstOrNull()?.let { focus = it }
            } catch (e: AiException) {
                error = e.message
            } catch (e: Exception) {
                error = "出错了：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun confirm() {
        val s = summary ?: return
        if (focus.isBlank()) return
        viewModelScope.launch {
            c.weekly.save(week, s.text, analysis, focus)
            saved = true
        }
    }

    fun fullText(): String = buildString {
        append(summary?.text.orEmpty())
        if (analysis.isNotBlank()) {
            appendLine()
            appendLine()
            appendLine("## 分析")
            append(analysis)
        }
        if (focus.isNotBlank()) {
            appendLine()
            appendLine()
            append("## 下周唯一重点\n- $focus")
        }
    }
}

/** 周复盘：数据摘要 + 旗舰模型分析“断在哪、下周唯一重点”，确认后写入（PRD M6）。 */
@Composable
fun WeeklyScreen(onBack: () -> Unit) {
    val vm = appViewModel { WeeklyViewModel(it) }
    val p = Paper.colors
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        QuietButton("‹ 返回", onBack)
        Text("周复盘", style = MaterialTheme.typography.headlineLarge, color = p.ink)
        val thisWeek = Time.weekStart()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChoiceChip("上周", selected = vm.week == thisWeek.minusWeeks(1), modifier = Modifier.weight(1f)) { vm.pickWeek(thisWeek.minusWeeks(1)) }
            ChoiceChip("本周", selected = vm.week == thisWeek, modifier = Modifier.weight(1f)) { vm.pickWeek(thisWeek) }
        }

        val s = vm.summary
        if (s == null) {
            Hint("正在整理数据…")
            return@Column
        }
        PaperCard {
            SectionLabel("数据摘要 · 全部来自记录")
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(s.text.replace("# ", "").replace("## ", ""), style = MaterialTheme.typography.bodyMedium, color = p.ink)
            }
        }

        if (vm.analysis.isNotBlank()) {
            RedPenBlock(Parsers.sections(vm.analysis), heading = "教练分析")
        } else if (vm.aiReady) {
            LineButton(if (vm.busy) "旗舰模型分析中…" else "请教练分析（旗舰档）", onClick = { vm.analyze() }, enabled = !vm.busy)
        } else {
            Hint("AI 不可用（没配置或没网）。自己看着数据，写下下周唯一的重点。")
        }
        vm.error?.let { ErrorPanel(it, onRetry = { vm.analyze() }) }

        SectionLabel("下周唯一重点")
        RuledTextField(vm.focus, { vm.focus = it }, minLines = 2, placeholder = "一句话，能执行、能检查")
        if (vm.saved) {
            Text("已确认。下周按这个重点练。", style = MaterialTheme.typography.titleSmall, color = p.red)
        }
        InkButton(if (vm.saved) "更新" else "确认下周重点", onClick = { vm.confirm() }, enabled = vm.focus.isNotBlank())
        LineButton(if (copied) "已复制" else "复制整份复盘（给 Claude 或其他部门）", onClick = {
            copy(context, vm.fullText())
            copied = true
        })
        Spacer(Modifier.height(24.dp))
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("周复盘", text))
}
