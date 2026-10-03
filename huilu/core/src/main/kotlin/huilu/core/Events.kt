package huilu.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 只追加的事件日志是唯一的真相来源。当前局面（Situation）完全由事件重放得到，
 * 所以进程被杀、手机重启之后都能原样恢复；所有判断也都留下了可分析的记录。
 */
sealed class Event {
    abstract val at: Long

    data class ActionStarted(val action: Action) : Event() { override val at get() = action.startedAt }
    data class ActionRevised(override val at: Long, val actionId: String, val text: String, val endAt: Long, val reason: String) : Event()
    data class ActionEnded(override val at: Long, val actionId: String, val outcome: Outcome, val observation: Observation, val deviation: Deviation) : Event()

    data class CheckInOpened(val checkIn: CheckIn) : Event() { override val at get() = checkIn.openedAt }
    data class CheckInNote(override val at: Long, val checkInId: String, val text: String, val via: Via) : Event()
    data class CheckInAnswered(override val at: Long, val checkInId: String, val kind: AnswerKind, val text: String?, val via: Via) : Event()
    data class CheckInClosed(override val at: Long, val checkInId: String, val reason: CloseReason, val decision: String) : Event()

    /** 下一次值得重新观察的时间，以及为什么。 */
    data class NextCheck(override val at: Long, val nextAt: Long?, val reason: String) : Event()
    data class Rest(override val at: Long, val until: Long?) : Event()
    data class Mute(override val at: Long, val actionId: String) : Event()
    data class Block(override val at: Long, val actionId: String) : Event()
    /** 已经为这一段连续的娱乐前台开过检查，避免同一段偏离反复打扰。 */
    data class DriftSeen(override val at: Long, val pkg: String, val since: Long) : Event()

    /** 平台确认这些 App 已被真正暂停 / 已真正解除暂停。只有平台确认后才记录。 */
    data class Locked(override val at: Long, val packages: Set<String>) : Event()
    data class Unlocked(override val at: Long, val packages: Set<String>) : Event()

    data class InterventionRequested(val intervention: Intervention) : Event() { override val at get() = intervention.at }
    data class InterventionResult(override val at: Long, val interventionId: String, val result: ActResult, val detail: String) : Event()
    data class InterventionAftermath(override val at: Long, val interventionId: String, val aftermath: Aftermath, val detail: String) : Event()
}

object Codec {
    fun encode(e: Event): String = toJson(e).toString()

    /** 解码失败（未知类型、损坏的行）返回 null，调用方跳过即可：日志永远向前兼容。 */
    fun decode(line: String): Event? = try {
        fromJson(JSONObject(line))
    } catch (_: Exception) {
        null
    }

    fun toJson(e: Event): JSONObject {
        val o = JSONObject()
        when (e) {
            is Event.ActionStarted -> o.put("t", "action_started").put("action", action(e.action))
            is Event.ActionRevised -> o.put("t", "action_revised").put("actionId", e.actionId).put("text", e.text).put("endAt", e.endAt).put("reason", e.reason)
            is Event.ActionEnded -> o.put("t", "action_ended").put("actionId", e.actionId).put("outcome", e.outcome.name)
                .put("observation", obs(e.observation)).put("deviation", dev(e.deviation))
            is Event.CheckInOpened -> o.put("t", "checkin_opened").put("checkIn", checkIn(e.checkIn))
            is Event.CheckInNote -> o.put("t", "checkin_note").put("checkInId", e.checkInId).put("text", e.text).put("via", e.via.name)
            is Event.CheckInAnswered -> o.put("t", "checkin_answered").put("checkInId", e.checkInId).put("kind", e.kind.name)
                .put("text", e.text ?: JSONObject.NULL).put("via", e.via.name)
            is Event.CheckInClosed -> o.put("t", "checkin_closed").put("checkInId", e.checkInId).put("reason", e.reason.name).put("decision", e.decision)
            is Event.NextCheck -> o.put("t", "next_check").put("nextAt", e.nextAt ?: JSONObject.NULL).put("reason", e.reason)
            is Event.Rest -> o.put("t", "rest").put("until", e.until ?: JSONObject.NULL)
            is Event.Mute -> o.put("t", "mute").put("actionId", e.actionId)
            is Event.Block -> o.put("t", "block").put("actionId", e.actionId)
            is Event.DriftSeen -> o.put("t", "drift_seen").put("pkg", e.pkg).put("since", e.since)
            is Event.Locked -> o.put("t", "locked").put("packages", JSONArray(e.packages.sorted()))
            is Event.Unlocked -> o.put("t", "unlocked").put("packages", JSONArray(e.packages.sorted()))
            is Event.InterventionRequested -> o.put("t", "intervention_requested").put("intervention", intervention(e.intervention))
            is Event.InterventionResult -> o.put("t", "intervention_result").put("interventionId", e.interventionId).put("result", e.result.name).put("detail", e.detail)
            is Event.InterventionAftermath -> o.put("t", "intervention_aftermath").put("interventionId", e.interventionId).put("aftermath", e.aftermath.name).put("detail", e.detail)
        }
        if (e !is Event.ActionStarted && e !is Event.CheckInOpened && e !is Event.InterventionRequested) o.put("at", e.at)
        return o
    }

    fun fromJson(o: JSONObject): Event? {
        val at = o.optLong("at")
        return when (o.getString("t")) {
            "action_started" -> Event.ActionStarted(action(o.getJSONObject("action")))
            "action_revised" -> Event.ActionRevised(at, o.getString("actionId"), o.getString("text"), o.getLong("endAt"), o.optString("reason"))
            "action_ended" -> Event.ActionEnded(at, o.getString("actionId"), enumOr(o.optString("outcome"), Outcome.UNCONFIRMED),
                obs(o.getJSONObject("observation")), dev(o.getJSONObject("deviation")))
            "checkin_opened" -> Event.CheckInOpened(checkIn(o.getJSONObject("checkIn")))
            "checkin_note" -> Event.CheckInNote(at, o.getString("checkInId"), o.getString("text"), enumOr(o.optString("via"), Via.APP))
            "checkin_answered" -> Event.CheckInAnswered(at, o.getString("checkInId"), AnswerKind.valueOf(o.getString("kind")),
                str(o, "text"), enumOr(o.optString("via"), Via.APP))
            "checkin_closed" -> Event.CheckInClosed(at, o.getString("checkInId"), enumOr(o.optString("reason"), CloseReason.ANSWERED), o.optString("decision"))
            "next_check" -> Event.NextCheck(at, long(o, "nextAt"), o.optString("reason"))
            "rest" -> Event.Rest(at, long(o, "until"))
            "mute" -> Event.Mute(at, o.getString("actionId"))
            "block" -> Event.Block(at, o.getString("actionId"))
            "drift_seen" -> Event.DriftSeen(at, o.getString("pkg"), o.getLong("since"))
            "locked" -> Event.Locked(at, strings(o.optJSONArray("packages")).toSet())
            "unlocked" -> Event.Unlocked(at, strings(o.optJSONArray("packages")).toSet())
            "intervention_requested" -> Event.InterventionRequested(intervention(o.getJSONObject("intervention")))
            "intervention_result" -> Event.InterventionResult(at, o.getString("interventionId"), ActResult.valueOf(o.getString("result")), o.optString("detail"))
            "intervention_aftermath" -> Event.InterventionAftermath(at, o.getString("interventionId"), Aftermath.valueOf(o.getString("aftermath")), o.optString("detail"))
            else -> null
        }
    }

    private fun action(a: Action) = JSONObject().put("id", a.id).put("text", a.text).put("why", a.why).put("plannedMin", a.plannedMin)
        .put("startedAt", a.startedAt).put("endAt", a.endAt).put("mode", a.expect.mode.name)
        .put("targetApps", JSONArray(a.expect.targetApps.toList())).put("strict", a.strict)

    private fun action(o: JSONObject) = Action(
        id = o.getString("id"), text = o.getString("text"), why = o.optString("why"), plannedMin = o.getInt("plannedMin"),
        startedAt = o.getLong("startedAt"), endAt = o.getLong("endAt"),
        expect = Expectation(enumOr(o.optString("mode"), EnvMode.ANY), strings(o.optJSONArray("targetApps")).toSet()),
        strict = o.optBoolean("strict"),
    )

    fun obs(x: Observation): JSONObject {
        val apps = JSONObject()
        x.apps.forEach { (k, v) -> apps.put(k, v) }
        val first = JSONObject()
        x.firstSeen.forEach { (k, v) -> first.put(k, v) }
        return JSONObject().put("from", x.from).put("to", x.to).put("apps", apps).put("firstSeen", first).put("state", x.state.name)
    }

    fun obs(o: JSONObject) = Observation(
        from = o.getLong("from"), to = o.getLong("to"), apps = longMap(o.optJSONObject("apps")),
        firstSeen = longMap(o.optJSONObject("firstSeen")), state = enumOr(o.optString("state"), SensorState.OK),
    )

    private fun dev(d: Deviation) = JSONObject().put("kind", d.kind.name).put("targetMs", d.targetMs).put("distractorMs", d.distractorMs)
        .put("otherMs", d.otherMs).put("idleMs", d.idleMs).put("topDistractor", d.topDistractor ?: JSONObject.NULL)
        .put("topOther", d.topOther ?: JSONObject.NULL).put("onsetMin", d.onsetMin ?: JSONObject.NULL).put("reality", d.reality)

    private fun dev(o: JSONObject) = Deviation(
        kind = enumOr(o.optString("kind"), DevKind.UNKNOWN), targetMs = o.optLong("targetMs"), distractorMs = o.optLong("distractorMs"),
        otherMs = o.optLong("otherMs"), idleMs = o.optLong("idleMs"), topDistractor = str(o, "topDistractor"), topOther = str(o, "topOther"),
        onsetMin = if (o.isNull("onsetMin")) null else o.optInt("onsetMin"), reality = o.optString("reality"),
    )

    private fun checkIn(c: CheckIn) = JSONObject().put("id", c.id).put("actionId", c.actionId).put("actionText", c.actionText)
        .put("trigger", c.trigger.name).put("openedAt", c.openedAt).put("minuteOfAction", c.minuteOfAction)
        .put("observation", obs(c.observation)).put("deviation", dev(c.deviation)).put("question", c.question)
        .put("level", c.level.name).put("choices", JSONArray(c.choices.map { it.name }))

    private fun checkIn(o: JSONObject) = CheckIn(
        id = o.getString("id"), actionId = o.getString("actionId"), actionText = o.optString("actionText"),
        trigger = enumOr(o.optString("trigger"), Trigger.SCHEDULED), openedAt = o.getLong("openedAt"),
        minuteOfAction = o.optInt("minuteOfAction"), observation = obs(o.getJSONObject("observation")),
        deviation = dev(o.getJSONObject("deviation")), question = o.optString("question"),
        level = enumOr(o.optString("level"), Level.NOTIFY),
        choices = strings(o.optJSONArray("choices")).mapNotNull { n -> AnswerKind.values().firstOrNull { it.name == n } },
    )

    private fun intervention(i: Intervention) = JSONObject().put("id", i.id).put("at", i.at).put("level", i.level.name)
        .put("checkInId", i.checkInId ?: JSONObject.NULL).put("target", i.target ?: JSONObject.NULL).put("reason", i.reason)
        .put("probeAt", i.probeAt ?: JSONObject.NULL)

    private fun intervention(o: JSONObject) = Intervention(
        id = o.getString("id"), at = o.getLong("at"), level = Level.valueOf(o.getString("level")),
        checkInId = str(o, "checkInId"), target = str(o, "target"), reason = o.optString("reason"), probeAt = long(o, "probeAt"),
    )

    private fun str(o: JSONObject, k: String): String? = if (!o.has(k) || o.isNull(k)) null else o.getString(k)
    private fun long(o: JSONObject, k: String): Long? = if (!o.has(k) || o.isNull(k)) null else o.getLong(k)
    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    private fun longMap(o: JSONObject?): Map<String, Long> {
        if (o == null) return emptyMap()
        val m = LinkedHashMap<String, Long>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            m[k] = o.getLong(k)
        }
        return m
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}
