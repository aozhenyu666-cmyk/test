package com.yishou.app.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.data.CardAttempt
import com.yishou.app.data.Deed
import com.yishou.app.data.DrillRow
import com.yishou.app.data.Material
import com.yishou.app.data.Job
import com.yishou.app.data.JobStep
import com.yishou.app.data.StepResult
import com.yishou.app.deed.Deeds
import com.yishou.app.settings.AppZone
import com.yishou.app.think.DrillStats
import com.yishou.app.ui.theme.StoneMark
import com.yishou.app.window.WindowClock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val dayStart = WindowClock.startOfDay(WindowClock.today(System.currentTimeMillis(), AppZone.zone), AppZone.zone)

    val cards: StateFlow<List<CardAttempt>> = yishou.database.cards().observeAttemptsSince(dayStart)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val drills: StateFlow<List<DrillRow>> = yishou.database.think().observeDrillsSince(dayStart)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val deeds: StateFlow<List<Deed>> = yishou.database.think().observeDeeds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val materials: StateFlow<List<Material>> = yishou.database.think().observeMaterials()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val steps: StateFlow<List<JobStep>> = yishou.database.jobs().observeStepsDoneSince(dayStart)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val bankLeft: StateFlow<Int> = yishou.database.cards().observeBank().map { xs -> xs.count { it.usedAt == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val outside: StateFlow<List<com.yishou.app.data.Outside>> = yishou.database.jobs().observeOutsideSince(dayStart)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val lines: StateFlow<List<com.yishou.app.data.Line>> = yishou.database.lines().observeLines()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val moves: StateFlow<List<com.yishou.app.data.Move>> = yishou.database.lines().observeMovesSince(dayStart)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val jobs: StateFlow<List<Job>> = yishou.database.jobs().observeJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 海龟汤、残局、刷着练、伴读：和每晚总结看同一本账 */
    val elsewhere: StateFlow<List<WheelMark>> = kotlinx.coroutines.flow.combine(
        yishou.database.soups().observeSince(dayStart),
        yishou.database.endgames().observePlayedSince(dayStart),
        yishou.database.scrollQuizzes().observeSince(dayStart),
        yishou.database.partners().observeSince(dayStart),
    ) { soups, endgames, quizzes, partners ->
        val entries = soups.map { com.yishou.app.summary.LedgerFrom.soup(it, "") } +
            endgames.mapNotNull { com.yishou.app.summary.LedgerFrom.endgame(it) } +
            quizzes.mapNotNull { com.yishou.app.summary.LedgerFrom.scroll(it) } +
            quizzes.mapNotNull { com.yishou.app.summary.LedgerFrom.scrollRedo(it) } +
            partners.map { com.yishou.app.summary.LedgerFrom.partner(it) }
        entries.filter { it.at >= dayStart }.map { WheelMark(it.at, MarkKind.DRILL, com.yishou.app.summary.Ledger.good(it.mark)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val threadMarks = yishou.database.strands().observeAll().map { rows ->
        rows.flatMap { row -> runCatching { com.yishou.app.thread.Strand.fromJson(row.id, org.json.JSONObject(row.json)).moves }.getOrDefault(emptyList()) }
            .filter { it.at >= dayStart }.map { WheelMark(it.at, MarkKind.DRILL, it.passed) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val start: Long get() = dayStart

    /** “来一手”这次挑中的 */
    private val _pick = kotlinx.coroutines.flow.MutableStateFlow<com.yishou.app.pool.Next.Pick?>(null)
    val pick: StateFlow<com.yishou.app.pool.Next.Pick?> = _pick.asStateFlow()
    private val skipped = mutableSetOf<com.yishou.app.pool.Next.Mode>()

    /** 重新看一眼池子，挑下一件（回到首页时调） */
    fun refreshPick() = viewModelScope.launch {
        skipped.clear()
        _pick.value = com.yishou.app.pool.Next.pick(yishou.pool.snapshot())
    }

    /** 不想做这个：换一个（换完一圈就从头来） */
    fun another() = viewModelScope.launch {
        _pick.value?.let { skipped += it.mode }
        val p = com.yishou.app.pool.Next.pick(yishou.pool.snapshot(), skipped)
        if (p.mode == com.yishou.app.pool.Next.Mode.EMPTY && skipped.isNotEmpty()) {
            skipped.clear()
            _pick.value = com.yishou.app.pool.Next.pick(yishou.pool.snapshot())
        } else _pick.value = p
    }

    /** 点了开始：记下这次做的练法，返回要去的那一页 */
    fun go(p: com.yishou.app.pool.Next.Pick): String {
        yishou.pool.did(p.mode)
        return yishou.pool.route(p)
    }
}

/** 今天落在时之轮上的子 */
fun todayMarks(cards: List<CardAttempt>, drills: List<DrillRow>, deeds: List<Deed>, steps: List<JobStep>, dayStart: Long): List<WheelMark> =
    steps.filter { it.result != StepResult.SKIP }.mapNotNull { s -> s.doneAt?.let { WheelMark(it, MarkKind.DEED, s.result == StepResult.OK) } } +
        cards.filter { it.verified }.map { WheelMark(it.createdAt, MarkKind.CARD, it.firstCorrect && it.help == 0) } +
        drills.filter { !it.disputed }.map { WheelMark(it.createdAt, MarkKind.DRILL, DrillStats.good(it)) } +
        deeds.flatMap { d ->
            val a = d.actual
            listOfNotNull(
                WheelMark(d.createdAt, MarkKind.DEED, true).takeIf { d.createdAt >= dayStart },
                if (a != null && (d.resolvedAt ?: 0) >= dayStart) WheelMark(d.resolvedAt ?: d.createdAt, MarkKind.DEED, Deeds.good(d.estimate, a)) else null,
            )
        }

/**
 * 首页：一只时之轮，三个入口：练功底（题库）、练思维（素材）、做事（一步一步）。
 * 底下是潮汐，点一下水面会荡开。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onEndgame: () -> Unit,
    onSoup: () -> Unit,
    onPartner: () -> Unit,
    onLine: (Long?) -> Unit,
    onLive: (Long) -> Unit,
    onBank: () -> Unit,
    onThink: () -> Unit,
    onJobs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWindow: () -> Unit,
    onRoute: (String) -> Unit = {},
    vm: HomeViewModel = viewModel(),
) {
    val pick by vm.pick.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshPick() }
    val app = LocalContext.current.applicationContext as YishouApp
    val cards by vm.cards.collectAsStateWithLifecycle()
    val drills by vm.drills.collectAsStateWithLifecycle()
    val deeds by vm.deeds.collectAsStateWithLifecycle()
    val materials by vm.materials.collectAsStateWithLifecycle()
    val steps by vm.steps.collectAsStateWithLifecycle()
    val bankLeft by vm.bankLeft.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val outside by vm.outside.collectAsStateWithLifecycle()
    val lines by vm.lines.collectAsStateWithLifecycle()
    val moves by vm.moves.collectAsStateWithLifecycle()
    val elsewhere by vm.elsewhere.collectAsStateWithLifecycle()
    val threadMarks by vm.threadMarks.collectAsStateWithLifecycle()
    val prefs by app.settings.app.collectAsStateWithLifecycle()
    val llm by app.settings.llm.collectAsStateWithLifecycle()
    val marks = remember(cards, drills, deeds, steps, outside, moves, elsewhere, threadMarks) {
        todayMarks(cards, drills, deeds, steps.filter { st -> outside.none { it.jobId == st.jobId } }, vm.start) +
            outside.map { o -> WheelMark(o.createdAt, MarkKind.CARD, o.correct == null || o.correct * 10 >= o.questions * 6) } +
            moves.filter { it.verdict != com.yishou.app.data.LineVerdict.PARKED }.map { m -> WheelMark(m.createdAt, MarkKind.DRILL, m.verdict == com.yishou.app.data.LineVerdict.OK) } +
            elsewhere + threadMarks
    }
    val tide = remember { TideState() }
    val tideHeight = 240.dp

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // 在最外层先看一眼点击（不拦下），落在水面上就荡一圈
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (down.position.y > size.height - tideHeight.toPx()) tide.poke(down.position.x, down.position.y)
                }
            },
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {}
        TideBackdrop(Modifier.fillMaxSize(), tide, tideHeight)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StoneMark(24.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (com.yishou.app.system.Variant.chalu) "一手·${com.yishou.app.system.Variant.name}" else "一手",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(AppZone.format("M月d日", System.currentTimeMillis()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "设置") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Entry("来一手", pick?.reason ?: "从素材池接下一手", MaterialTheme.colorScheme.primary,
                    { pick?.let { onRoute(vm.go(it)) } ?: onRoute("pool") }, Modifier.weight(1f))
                Entry("想一件事", "把一件事接着想定", MaterialTheme.colorScheme.tertiary,
                    { onRoute("strand") }, Modifier.weight(1f))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val fresh = materials.count { it.uses == 0 }
                Entry(
                    title = "题库与素材",
                    sub = "什么都往这放：${materials.size} 段文章" + (if (fresh > 0) "（$fresh 段没练）" else "") + " · $bankLeft 道题没做",
                    accent = MaterialTheme.colorScheme.tertiary,
                    onClick = { onRoute("library") },
                    modifier = Modifier.weight(1f),
                )
                Entry(
                    title = "语音伙伴",
                    sub = "一起看一段，它问你答",
                    accent = MaterialTheme.colorScheme.secondary,
                    onClick = onPartner,
                    modifier = Modifier.weight(1f),
                )
            }

            Button(onClick = { onRoute("assistant") }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("AI 助手 · 直接聊") }
            EntertainmentGateCard(onSettings = { onRoute("watched") })

            var more by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
            TextButton(onClick = { more = !more }) { Text(if (more) "收起更多" else "更多（不再更新）") }
            if (more) {
            // 刷抖音、贴吧时也练：看这一屏问一句，通知里回
            ScrollCard()

            // 想自己挑练法时用；平时点上面的“来一手”就够了
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSoup) { Text("海龟汤") }
                TextButton(onClick = onEndgame) { Text("残局") }
                TextButton(onClick = onThink) { Text("练思维") }
                TextButton(onClick = onBank) { Text("刷题") }
                TextButton(onClick = { onLine(null) }) { Text("主线") }
                TextButton(onClick = onJobs) { Text("做事") }
                TextButton(onClick = onOpenWindow) { Text("陪练窗口") }
            }

            lines.firstOrNull { it.closedAt == null }?.let { open ->
                MainLine(
                    line = open,
                    today = com.yishou.app.line.Lines.today(moves),
                    onLine = onLine,
                    onLive = onLive,
                )
            }

            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimeWheel(
                    marks = marks,
                    windowStart = if (prefs.windowEnabled) prefs.windowHour * 60 + prefs.windowMinute else null,
                    windowMinutes = prefs.windowMinutes,
                    modifier = Modifier.fillMaxWidth(0.72f),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${marks.size}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Light)
                        Text(if (marks.isEmpty()) "今天还没落子" else "今天落子", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Legend()

            if (!llm.isComplete) {
                Text("还没有填写大模型接口：出题、判卷都要用它。", color = MaterialTheme.colorScheme.error)
                Button(onClick = onOpenSettings) { Text("去设置") }
            }

            Spacer(Modifier.height(tideHeight))
        }
    }
}

/** 首页最上面：“来一手”——挑中了什么、为什么，一个“开始”，不想做就“换一个” */
@Composable
private fun NextCard(p: com.yishou.app.pool.Next.Pick?, onGo: (com.yishou.app.pool.Next.Pick) -> Unit, onAnother: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (p == null) "来一手" else "来一手 · ${p.mode.label}", style = MaterialTheme.typography.headlineSmall)
            if (p == null) {
                Text("正在看素材池……", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(p.reason, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onGo(p) }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text(if (p.mode == com.yishou.app.pool.Next.Mode.EMPTY) "去收素材" else "开始")
                    }
                    if (p.mode != com.yishou.app.pool.Next.Mode.EMPTY) TextButton(onClick = onAnother) { Text("换一个") }
                }
            }
        }
    }
}

@Composable
private fun Legend() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        listOf(
            "功底" to MaterialTheme.colorScheme.onBackground,
            "思维" to MaterialTheme.colorScheme.tertiary,
            "做事" to MaterialTheme.colorScheme.secondary,
        ).forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                androidx.compose.foundation.Canvas(Modifier.width(8.dp).height(8.dp)) { drawCircle(color) }
                Spacer(Modifier.width(5.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Entry(title: String, sub: String, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.Canvas(Modifier.width(28.dp).height(3.dp)) { drawRect(accent) }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2)
        }
    }
}


/** 首页最上面：现在这一局。断点就是下一问，点一下接着下；或者直接语音对练。 */
@Composable
private fun MainLine(line: com.yishou.app.data.Line?, today: com.yishou.app.line.Lines.Today, onLine: (Long?) -> Unit, onLive: (Long) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().clickable { onLine(line?.id) },
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("主线", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
                Text(com.yishou.app.line.Lines.line(today), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (line == null) {
                Text("还没开一局", style = MaterialTheme.typography.headlineSmall)
                Text("你现在在想、在学、在纠结的那件事，开成一局，一手一手往下走。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { onLine(null) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("开一局") }
            } else {
                Text("「${line.anchor}」", style = MaterialTheme.typography.titleMedium)
                Text("下一问：${line.nextQuestion}", style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onLine(line.id) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("接着下") }
                    OutlinedButton(onClick = { onLive(line.id) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("语音对练") }
                }
            }
        }
    }
}
