package huilu.core

data class InterventionRecord(
    val intervention: Intervention,
    val result: ActResult? = null,
    val resultDetail: String? = null,
    val aftermath: Aftermath? = null,
    val aftermathDetail: String? = null,
)

/** 一轮行动从开始到结束的完整记录。 */
data class Episode(
    val action: Action,
    val revisions: List<String> = emptyList(),
    val checkIns: List<CheckIn> = emptyList(),
    val interventions: List<InterventionRecord> = emptyList(),
    val endedAt: Long? = null,
    val outcome: Outcome? = null,
    val whole: Deviation? = null,
    /** 每次偏离从开始到系统重新出现隔了多久（毫秒）。 */
    val driftLatencies: List<Long> = emptyList(),
) {
    val actualMin: Int? get() = endedAt?.let { ((it - action.startedAt) / MIN).toInt() }
    val drifted: Boolean get() = whole?.kind == DevKind.DRIFT || checkIns.any { it.deviation.kind == DevKind.DRIFT }
}

/** 把事件日志整理成按轮次组织的历史。 */
object Timeline {
    fun build(events: List<Event>): List<Episode> {
        val eps = LinkedHashMap<String, Episode>()
        val ciOwner = HashMap<String, String>()
        val ivOwner = HashMap<String, String>()
        var current: String? = null
        fun upd(id: String?, f: (Episode) -> Episode) { if (id != null) eps[id]?.let { eps[id] = f(it) } }
        fun updCi(ciId: String, f: (CheckIn) -> CheckIn) = upd(ciOwner[ciId]) { e -> e.copy(checkIns = e.checkIns.map { if (it.id == ciId) f(it) else it }) }
        fun updIv(ivId: String, f: (InterventionRecord) -> InterventionRecord) =
            upd(ivOwner[ivId]) { e -> e.copy(interventions = e.interventions.map { if (it.intervention.id == ivId) f(it) else it }) }

        for (ev in events) when (ev) {
            is Event.ActionStarted -> { eps[ev.action.id] = Episode(ev.action); current = ev.action.id }
            is Event.ActionRevised -> upd(ev.actionId) { it.copy(action = it.action.copy(text = ev.text, endAt = ev.endAt), revisions = it.revisions + ev.reason) }
            is Event.ActionEnded -> { upd(ev.actionId) { it.copy(endedAt = ev.at, outcome = ev.outcome, whole = ev.deviation) }; if (current == ev.actionId) current = null }
            is Event.CheckInOpened -> { ciOwner[ev.checkIn.id] = ev.checkIn.actionId; upd(ev.checkIn.actionId) { it.copy(checkIns = it.checkIns + ev.checkIn) } }
            is Event.CheckInNote -> updCi(ev.checkInId) { it.copy(notes = it.notes + ev.text) }
            is Event.CheckInAnswered -> updCi(ev.checkInId) { it.copy(answeredAt = ev.at, answer = ev.kind, answerText = ev.text, via = ev.via) }
            is Event.CheckInClosed -> updCi(ev.checkInId) { it.copy(closedAt = ev.at, closeReason = ev.reason, decision = ev.decision) }
            is Event.InterventionRequested -> {
                val owner = ev.intervention.checkInId?.let(ciOwner::get) ?: current
                if (owner != null) {
                    ivOwner[ev.intervention.id] = owner
                    upd(owner) { it.copy(interventions = it.interventions + InterventionRecord(ev.intervention)) }
                    ev.intervention.checkInId?.let { cid -> updCi(cid) { c -> c.copy(prompts = c.prompts + 1, lastPromptAt = ev.at) } }
                }
            }
            is Event.DriftSeen -> upd(current) { it.copy(driftLatencies = it.driftLatencies + (ev.at - ev.since)) }
            is Event.InterventionResult -> updIv(ev.interventionId) { it.copy(result = ev.result, resultDetail = ev.detail) }
            is Event.InterventionAftermath -> updIv(ev.interventionId) { it.copy(aftermath = ev.aftermath, aftermathDetail = ev.detail) }
            else -> {}
        }
        return eps.values.toList()
    }
}

data class LevelStats(val requested: Int, val verified: Int, val notVerified: Int, val aftermath: Map<Aftermath, Int>)

/** 长期分析：偏差在哪里、什么时候、偏到哪、哪种干预有用、自报准不准。 */
data class Insights(
    val episodes: Int,
    val outcomes: Map<Outcome, Int>,
    val driftedEpisodes: Int,
    val checkIns: Int,
    val unanswered: Int,
    val devKinds: Map<DevKind, Int>,
    val onsetMinutes: List<Int>,
    val topDriftApps: List<Pair<String, Long>>,
    /** 回答"在做"、但观察到偏离的次数 / 可比较的回答次数。 */
    val optimisticReports: Int,
    val comparableReports: Int,
    val levels: Map<Level, LevelStats>,
    /** 按行动内容统计偏离率（至少出现两次的行动）。 */
    val byAction: List<Triple<String, Int, Int>>,
    val driftLatencies: List<Long> = emptyList(),
) {
    val medianOnset: Int? get() = onsetMinutes.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
    val medianLatency: Long? get() = driftLatencies.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    companion object {
        fun of(episodes: List<Episode>): Insights {
            val ended = episodes.filter { it.outcome != null }
            val cis = episodes.flatMap { it.checkIns }.filter { it.closedAt != null }
            val driftApps = HashMap<String, Long>()
            ended.forEach { e -> e.whole?.let { d -> d.topDistractor?.let { driftApps[it] = (driftApps[it] ?: 0) + d.distractorMs } } }
            val onsets = episodes.mapNotNull { e ->
                e.checkIns.firstOrNull { it.deviation.kind == DevKind.DRIFT }?.deviation?.onsetMin
            }
            val comparable = cis.filter { it.answer != null && it.deviation.kind != DevKind.UNKNOWN && it.trigger != Trigger.END }
            val ivs = episodes.flatMap { it.interventions }
            val levels = ivs.groupBy { it.intervention.level }.mapValues { (_, l) ->
                LevelStats(
                    requested = l.size,
                    verified = l.count { it.result == ActResult.VERIFIED },
                    notVerified = l.count { it.result != null && it.result != ActResult.VERIFIED },
                    aftermath = l.mapNotNull { it.aftermath }.groupingBy { it }.eachCount(),
                )
            }
            val byAction = ended.groupBy { it.action.text.trim().lowercase() }.filter { it.value.size >= 2 }
                .map { (k, v) -> Triple(k, v.size, v.count { it.drifted }) }
                .sortedByDescending { it.third.toDouble() / it.second }
            return Insights(
                episodes = ended.size,
                outcomes = ended.groupingBy { it.outcome!! }.eachCount(),
                driftedEpisodes = ended.count { it.drifted },
                checkIns = cis.size,
                unanswered = cis.count { it.closeReason == CloseReason.UNANSWERED },
                devKinds = cis.groupingBy { it.deviation.kind }.eachCount(),
                onsetMinutes = onsets,
                topDriftApps = driftApps.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
                optimisticReports = comparable.count { it.answer == AnswerKind.ON_TRACK && it.deviation.kind == DevKind.DRIFT },
                comparableReports = comparable.size,
                levels = levels,
                byAction = byAction,
                driftLatencies = episodes.flatMap { it.driftLatencies },
            )
        }
    }
}
