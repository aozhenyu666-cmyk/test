package com.behaviordept.app.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.behaviordept.app.ai.Section
import com.behaviordept.app.data.Rating
import com.behaviordept.app.study.preQuestionList
import com.behaviordept.app.ui.components.CardShape
import com.behaviordept.app.ui.components.ErrorPanel
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.InkButton
import com.behaviordept.app.ui.components.PaperCard
import com.behaviordept.app.ui.components.PenMark
import com.behaviordept.app.ui.components.RedPenBlock
import com.behaviordept.app.ui.components.RuledTextField
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.ui.theme.SerifSC

/* 专注模式里的每一步。每屏只有一个墨水色主按钮。 */

@Composable
private fun StepIntro(title: String, hint: String) {
    val p = Paper.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = p.ink)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = p.ink2)
    }
}

@Composable
private fun MaterialCard(material: String) {
    val p = Paper.colors
    PaperCard {
        SectionLabel("资料")
        SelectionContainer {
            Text(
                material,
                style = MaterialTheme.typography.bodyLarge,
                color = p.ink,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun QuestionList(label: String, questions: List<String>) {
    val p = Paper.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(label)
        questions.forEachIndexed { i, q ->
            Row {
                Text(
                    "${i + 1}",
                    fontFamily = SerifSC,
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.titleMedium,
                    color = p.ink2,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Text(q, style = MaterialTheme.typography.bodyLarge, color = p.ink)
            }
        }
    }
}

@Composable
private fun SelfNotes(vm: FocusViewModel, titles: List<String>) {
    val p = Paper.colors
    titles.forEach { t ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("【$t】", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = p.red)
            RuledTextField(
                value = vm.selfNotes[t].orEmpty(),
                onValueChange = { vm.selfNotes[t] = it },
                minLines = 2,
                placeholder = "一行一条，没有就空着",
            )
        }
    }
}

@Composable
private fun Busy(text: String) {
    val p = Paper.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(color = p.red, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = p.ink2)
    }
}

// —— 新建 ——
@Composable
fun NewUnitStep(vm: FocusViewModel) {
    StepIntro("新建学习单元", "一个单元是一次学得完的一小块。把资料原文粘贴进来，先别细看。")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("标题")
        RuledTextField(vm.newTitle, { vm.newTitle = it }, singleLine = true, placeholder = "例如：哈希表的冲突处理")
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("资料")
        RuledTextField(vm.newMaterial, { vm.newMaterial = it }, minLines = 8, placeholder = "粘贴资料文本（至少 20 字）")
    }
    vm.error?.let { ErrorPanel(it, onRetry = { vm.createUnit() }) }
    InkButton("保存，开始预习", onClick = { vm.createUnit() }, enabled = vm.canCreate, loading = vm.busy)
}

// —— 第 1 步 ——
@Composable
fun PreviewStep(vm: FocusViewModel) {
    StepIntro("先提问，再看资料", "看资料前写下 2–3 个你想弄明白的问题。读的时候带着它们找答案，讲的时候要回答它们。")
    vm.preQuestions.forEachIndexed { i, q ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel(if (i < 2) "问题 ${i + 1}" else "问题 3（可选）")
            RuledTextField(q, { vm.preQuestions[i] = it }, minLines = 1, placeholder = "我想知道……")
        }
    }
    vm.error?.let { ErrorPanel(it, onRetry = { vm.savePreview() }) }
    InkButton("写好了，去读资料", onClick = { vm.savePreview() }, enabled = vm.canSavePreview, loading = vm.busy)
}

// —— 第 2 步 ——
@Composable
fun StudyStep(vm: FocusViewModel) {
    val u = vm.unit ?: return
    StepIntro("带着问题读", "读完就合上。下一步要凭记忆讲一遍。")
    QuestionList("你的预习问题", u.preQuestionList())
    MaterialCard(u.material)
    vm.error?.let { ErrorPanel(it, onRetry = { vm.markStudied() }) }
    InkButton("读完了，合上资料", onClick = { vm.markStudied() }, loading = vm.busy)
}

// —— 第 3 步 ——
@Composable
fun ExplainStep(vm: FocusViewModel) {
    val u = vm.unit ?: return
    StepIntro("合上资料，讲一遍", "用自己的话写，像讲给一个没学过的人听。写不出来的地方，就是没懂的地方。")
    QuestionList("讲的时候要回答", u.preQuestionList())
    RuledTextField(
        vm.explanation,
        { vm.explanation = it },
        minLines = 10,
        enabled = !vm.materialRevealed,
        placeholder = "开始讲……（至少 30 字）",
    )
    if (vm.selfCheck) {
        Hint(vm.unavailableReason)
        if (!vm.materialRevealed) {
            InkButton("写完了，翻开资料对照", onClick = { vm.revealMaterial() }, enabled = vm.canSubmitExplanation)
        } else {
            MaterialCard(u.material)
            Hint("对照资料，把讲错的、漏掉的写下来，再看看预习问题答到没有。")
            SelfNotes(vm, FocusViewModel.SELF_EXPLAIN_TITLES)
            vm.error?.let { ErrorPanel(it, onRetry = { vm.submitSelfExplanation() }) }
            InkButton("自查完了", onClick = { vm.submitSelfExplanation() }, loading = vm.busy)
        }
    } else {
        if (vm.busy) Busy("红笔批改中…")
        vm.error?.let {
            ErrorPanel(it, onRetry = { vm.submitExplanation() }, onSelfCheck = { vm.switchToSelfCheck() })
        }
        InkButton(
            "交给红笔批改",
            onClick = { vm.submitExplanation() },
            enabled = vm.canSubmitExplanation,
            loading = vm.busy,
        )
    }
}

// —— 第 4 步 ——
@Composable
fun TransferStep(vm: FocusViewModel) {
    val u = vm.unit ?: return
    StepIntro("举一反三", "找一个表面完全不同、底层道理相同的例子，说清楚它和「${u.title}」哪里一样。")
    RuledTextField(
        vm.example,
        { vm.example = it },
        minLines = 6,
        enabled = !vm.materialRevealed,
        placeholder = "我想到的例子是……它们底层都是……（至少 15 字）",
    )
    if (vm.selfCheck) {
        Hint(vm.unavailableReason)
        if (!vm.materialRevealed) {
            InkButton("写完了，翻开资料对照", onClick = { vm.revealMaterial() }, enabled = vm.canSubmitExample)
        } else {
            MaterialCard(u.material)
            Hint("逐层对照：这个类比在哪里成立，在哪里会失效？")
            SelfNotes(vm, FocusViewModel.SELF_TRANSFER_TITLES)
            vm.error?.let { ErrorPanel(it, onRetry = { vm.submitSelfExample() }) }
            InkButton("自查完了", onClick = { vm.submitSelfExample() }, loading = vm.busy)
        }
    } else {
        if (vm.busy) Busy("红笔点评中…")
        vm.error?.let {
            ErrorPanel(it, onRetry = { vm.submitExample() }, onSelfCheck = { vm.switchToSelfCheck() })
        }
        InkButton("请红笔点评", onClick = { vm.submitExample() }, enabled = vm.canSubmitExample, loading = vm.busy)
    }
}

// —— 间隔自测 ——
@Composable
fun ReviewStep(vm: FocusViewModel) {
    val u = vm.unit ?: return
    StepIntro("不看资料，回答", "每次都是新题。答不上来就写“不记得”，比硬猜更有用。")
    if (vm.selfCheck) Hint(vm.unavailableReason)
    when (vm.reviewPhase) {
        ReviewPhase.LOADING -> {
            val err = vm.error
            if (err != null) {
                ErrorPanel(err, onRetry = { vm.loadQuestions() }, onSelfCheck = { vm.switchToSelfCheck() })
            } else {
                Busy("正在出题…")
            }
        }
        ReviewPhase.ANSWERING, ReviewPhase.GRADING -> {
            vm.questions.forEachIndexed { i, q ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuestionTitle(i, q)
                    RuledTextField(
                        vm.answers.getOrElse(i) { "" },
                        { if (i < vm.answers.size) vm.answers[i] = it },
                        minLines = 3,
                        enabled = vm.reviewPhase == ReviewPhase.ANSWERING,
                        placeholder = "作答",
                    )
                }
            }
            if (vm.reviewPhase == ReviewPhase.GRADING) Busy("红笔批改中…")
            vm.error?.let {
                ErrorPanel(it, onRetry = { vm.submitAnswers() }, onSelfCheck = { vm.switchToSelfCheck() })
            }
            InkButton(
                if (vm.selfCheck) "交卷，翻开资料对照" else "交卷，请红笔批改",
                onClick = { vm.submitAnswers() },
                enabled = vm.canSubmitAnswers,
                loading = vm.busy,
            )
        }
        ReviewPhase.RATING -> {
            if (vm.grade != null) {
                GradeBlock(vm)
            } else {
                vm.questions.forEachIndexed { i, q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        QuestionTitle(i, q)
                        Text(vm.answers.getOrElse(i) { "" }, style = MaterialTheme.typography.bodyMedium, color = Paper.colors.ink2)
                    }
                }
                MaterialCard(u.material)
            }
            RatingPicker(suggested = vm.grade?.suggestion, enabled = !vm.busy) { vm.rate(it) }
            vm.error?.let { Hint(it) }
        }
    }
}

@Composable
private fun QuestionTitle(index: Int, question: String) {
    val p = Paper.colors
    Row {
        Text(
            "第${index + 1}题",
            fontFamily = SerifSC,
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.titleMedium,
            color = p.ink2,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(question, style = MaterialTheme.typography.titleMedium, color = p.ink)
    }
}

/** 自测批改：逐题的题目、作答和红笔，最后是建议。 */
@Composable
fun GradeBlock(vm: FocusViewModel) {
    val g = vm.grade ?: return
    val p = Paper.colors
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        g.items.forEach { item ->
            val i = item.index - 1
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.questions.getOrNull(i)?.let { QuestionTitle(i, it) }
                vm.answers.getOrNull(i)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.ink2) }
                RedPenBlock(listOf(Section("${item.mark} 第 ${item.index} 题", listOf(item.comment.ifBlank { "—" }))))
            }
        }
        RedPenBlock(
            listOf(Section("建议：${Rating.label(g.suggestion)}", listOf(g.reason.ifBlank { "—" }))),
        )
    }
}

/** 自己判定：记得 / 模糊 / 忘了。AI 建议的那一项用墨水实底标出来。 */
@Composable
private fun RatingPicker(suggested: String?, enabled: Boolean, onPick: (String) -> Unit) {
    val p = Paper.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("你自己判定")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Rating.REMEMBER, Rating.FUZZY, Rating.FORGOT).forEach { r ->
                val isSuggested = r == suggested
                Box(
                    Modifier
                        .weight(1f)
                        .height(64.dp)
                        .let { if (isSuggested) it.background(p.ink, CardShape) else it.background(p.page, CardShape).border(1.dp, p.divider, CardShape) }
                        .clickable(enabled = enabled) { onPick(r) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PenMark(Rating.mark(r), 24.dp, if (isSuggested) p.page else p.red)
                        Text(
                            Rating.label(r),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSuggested) p.page else p.ink,
                        )
                    }
                }
            }
        }
        Hint("记得：间隔升一级　模糊：间隔不变　忘了：明天再测")
    }
}
