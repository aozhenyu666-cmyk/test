package com.behaviordept.app.data

import android.content.Context
import com.behaviordept.app.guard.RuleLogic
import com.behaviordept.app.guard.UsageReader
import com.behaviordept.app.training.Seeds
import com.behaviordept.app.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** 防线：规则（带冷静期）、用时读取、冲动登记。 */
class GuardRepository(
    private val context: Context,
    private val dao: GuardDao,
    private val eventDao: EventDao,
    private val events: EventLog,
) {
    fun observeRules(): Flow<List<Rule>> = dao.observeRules()
    fun observeUsageSince(date: LocalDate): Flow<List<UsageDay>> = dao.observeUsageSince(date.toString())
    fun observeUrgesSince(from: Long): Flow<List<UrgeLog>> = dao.observeUrgesSince(from)

    suspend fun rules() = dao.rules()

    /** 第一次打开 v2 时放进默认规则：抖音 + B站 + 贴吧 + 小红书 + 红果短剧，每天合计 30 分钟。 */
    suspend fun seedIfEmpty() {
        if (dao.rules().isNotEmpty()) return
        dao.insertRule(
            Rule(
                name = "娱乐 App",
                packages = Seeds.ENTERTAINMENT_PACKAGES.joinToString(","),
                dailyLimitMin = 30,
                effectiveAt = Time.now(),
            ),
        )
    }

    suspend fun addRule(name: String, limit: Int, packages: List<String>) {
        val id = dao.insertRule(Rule(name = name.trim(), packages = packages.joinToString(","), dailyLimitMin = limit, effectiveAt = Time.now()))
        events.log(EventType.RULE_CHANGED, id, limit.toDouble(), "新增规则：$name")
    }

    /** 修改：收紧立即生效，放宽进入 24 小时冷静期。 */
    suspend fun editRule(old: Rule, name: String, limit: Int, packages: List<String>) {
        val updated = RuleLogic.edit(old, name, limit, packages, Time.now())
        dao.updateRule(updated)
        val note = if (updated.hasPending) "放宽，24 小时后生效" else "收紧，立即生效"
        events.log(EventType.RULE_CHANGED, old.id, limit.toDouble(), "${updated.name}：$note")
    }

    suspend fun requestDelete(rule: Rule) {
        dao.updateRule(RuleLogic.requestDelete(rule, Time.now()))
        events.log(EventType.RULE_CHANGED, rule.id, null, "${rule.name}：申请删除，24 小时后生效")
    }

    suspend fun cancelPending(rule: Rule) {
        dao.updateRule(RuleLogic.cancelPending(rule))
        events.log(EventType.RULE_CHANGED, rule.id, null, "${rule.name}：撤回放宽")
    }

    /** 冷静期到了的修改生效。 */
    suspend fun applyDue(now: Long = Time.now()) {
        dao.rules().forEach { r ->
            val applied = RuleLogic.applyDue(r, now)
            when {
                applied == null -> dao.deleteRule(r.id)
                applied != r -> dao.updateRule(applied)
            }
        }
    }

    val hasUsagePermission: Boolean get() = UsageReader.hasPermission(context)

    /** 从系统读今天和昨天的用时写进 UsageDay；已经过完的日子各写一条 USAGE_DAY 事件。 */
    suspend fun sync(now: Long = Time.now()) {
        if (!hasUsagePermission) return
        val today = Time.dateOf(now)
        val rules = dao.rules()
        for (offset in 0L..1L) {
            val date = today.minusDays(offset)
            val minutes = withContext(Dispatchers.IO) { UsageReader.minutesOn(context, date, now) }
            if (minutes.isNotEmpty()) {
                dao.putUsage(minutes.map { (pkg, m) -> UsageDay(date.toString(), pkg, m) })
            }
            if (offset == 1L) writeDayEvents(date, minutes, rules)
        }
    }

    private suspend fun writeDayEvents(date: LocalDate, minutes: Map<String, Int>, rules: List<Rule>) {
        val start = Time.startOf(date)
        val end = Time.startOf(date.plusDays(1))
        rules.forEach { r ->
            if (eventDao.countOfRefBetween(EventType.USAGE_DAY, r.id, start, end) == 0) {
                val m = RuleLogic.minutesFor(r, minutes)
                events.log(EventType.USAGE_DAY, r.id, m.toDouble(), "${r.name}：$m / ${r.dailyLimitMin} 分钟", time = end - 1)
            }
        }
    }

    /** 今天每条规则的用时（直接读系统，最新）。 */
    suspend fun today(now: Long = Time.now()): List<RuleToday> {
        val usage = if (hasUsagePermission) {
            withContext(Dispatchers.IO) { UsageReader.minutesOn(context, Time.dateOf(now), now) }
        } else {
            emptyMap()
        }
        return dao.rules().map { RuleToday(it, RuleLogic.minutesFor(it, usage)) }
    }

    suspend fun logUrge(reason: String): Long {
        val id = dao.insertUrge(UrgeLog(time = Time.now(), reason = reason))
        events.log(EventType.URGE, id, note = reason)
        return id
    }

    suspend fun markUrgeStarted(id: Long) = dao.markUrgeStarted(id)

    fun appName(pkg: String): String =
        Seeds.KNOWN_APP_NAMES[pkg] ?: UsageReader.appLabel(context, pkg) ?: pkg.substringAfterLast('.')
}
