package com.behaviordept.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * PRD「数据模型」一节的全部实体。学习、竞技、运动都是“技能 + 模板”，所有行为都落成 Event。
 * 阶段 1 只用到学习相关实体、Session、Event 和 AiCall；其余实体先建表，后续阶段直接使用。
 */

object Template {
    const val LEARNING = "learning"
    const val COMPETITIVE = "competitive"
    const val MOTOR = "motor"
}

@Serializable
@Entity(tableName = "skill")
data class Skill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val template: String,
    val focusSubSkillId: Long? = null,
)

object SubSkillStatus {
    const val NOT_STARTED = "not_started"
    const val PRACTICING = "practicing"
    const val PASSED = "passed"
}

@Serializable
@Entity(tableName = "sub_skill", indices = [Index("skillId")])
data class SubSkill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val skillId: Long,
    val name: String,
    /** 可观察的“好的样子”。 */
    val standard: String,
    val status: String = SubSkillStatus.NOT_STARTED,
)

@Serializable
@Entity(tableName = "drill", indices = [Index("subSkillId")])
data class Drill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subSkillId: Long,
    val method: String,
    val minutes: Int,
    /** ai / manual */
    val source: String,
)

/** 学习单元：学习模板的核心对象。四步依次是 预习提问 → 读资料 → 合上讲一遍 → 举一反三。 */
@Serializable
@Entity(tableName = "study_unit", indices = [Index("nextReviewAt")])
data class StudyUnit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val skillId: Long? = null,
    val title: String,
    val material: String,
    /** 预习问题，一行一个。 */
    val preQuestions: String = "",
    /** 合上资料后写的讲解。 */
    val explanation: String = "",
    /** 讲解的批改（AI 红笔，或离线时的对照自查记录）。 */
    val critique: String = "",
    /** ai / self */
    val critiqueMode: String? = null,
    /** 读完资料的时间。 */
    val studiedAt: Long? = null,
    /** 讲完（批改完成）的时间。 */
    val explainedAt: Long? = null,
    /** 完成举一反三的时间。 */
    val transferredAt: Long? = null,
    /** 间隔等级，下标对应 1/3/7/14/30/60 天。 */
    val intervalLevel: Int = 0,
    val nextReviewAt: Long? = null,
    val createdAt: Long,
)

object Rating {
    const val REMEMBER = "remember"
    const val FUZZY = "fuzzy"
    const val FORGOT = "forgot"

    fun label(rating: String?): String = when (rating) {
        REMEMBER -> "记得"
        FUZZY -> "模糊"
        FORGOT -> "忘了"
        else -> "未判定"
    }

    fun mark(rating: String?): String = when (rating) {
        REMEMBER -> "✓"
        FUZZY -> "△"
        FORGOT -> "✗"
        else -> "·"
    }
}

/** 一次间隔自测。题目和作答都用 JSON 字符串数组保存。 */
@Serializable
@Entity(tableName = "review", indices = [Index("unitId")])
data class Review(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val unitId: Long,
    /** 这次自测所处的间隔天数，用于保持率曲线。 */
    val intervalDays: Int,
    val questions: String,
    val answers: String = "[]",
    val critique: String = "",
    /** ai / self */
    val mode: String,
    val rating: String? = null,
    val createdAt: Long,
    val finishedAt: Long? = null,
)

@Serializable
@Entity(tableName = "transfer", indices = [Index("unitId")])
data class Transfer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val unitId: Long,
    val example: String,
    val critique: String,
    /** ai / self */
    val mode: String,
    val createdAt: Long,
)

@Serializable
@Entity(tableName = "match_log", indices = [Index("skillId")])
data class MatchLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val skillId: Long,
    val time: Long,
    /** extracted / died */
    val result: String,
    val causeCategory: String? = null,
    val note: String = "",
)

object SessionType {
    const val NEW_UNIT = "new_unit"
    const val PREVIEW = "preview"
    const val STUDY = "study"
    const val EXPLAIN = "explain"
    const val TRANSFER = "transfer"
    const val REVIEW = "review"
}

/** 训练时段，专注模式产生。end 在专注过程中每隔一会儿刷新一次，App 被杀也能留下真实时长。 */
@Serializable
@Entity(tableName = "session")
data class TrainingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val refId: Long? = null,
    val title: String,
    val start: Long,
    val end: Long,
    val ended: Boolean = false,
)

@Serializable
@Entity(tableName = "rule")
data class Rule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** App 包名，逗号分隔。 */
    val packages: String,
    val dailyLimitMin: Int,
    val effectiveAt: Long,
    /** 冷静期：待生效的放宽值和生效时间。 */
    val pendingLimitMin: Int? = null,
    val pendingEffectiveAt: Long? = null,
)

@Serializable
@Entity(tableName = "usage_day", primaryKeys = ["date", "packageName"])
data class UsageDay(
    /** yyyy-MM-dd */
    val date: String,
    val packageName: String,
    val minutes: Int,
)

@Serializable
@Entity(tableName = "urge_log")
data class UrgeLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val reason: String,
    val startedTraining: Boolean = false,
)

object EventType {
    /** 一次专注结束，value = 分钟。 */
    const val SESSION = "session"
    const val UNIT_CREATED = "unit_created"
    const val PREVIEW_DONE = "preview_done"
    const val STUDY_DONE = "study_done"
    const val EXPLAIN_DONE = "explain_done"
    const val TRANSFER_DONE = "transfer_done"
    /** value：记得 2 / 模糊 1 / 忘了 0。 */
    const val REVIEW_DONE = "review_done"
    /** 设置页停留，value = 分钟，计入“折腾系统时间”。 */
    const val SETTINGS_TIME = "settings_time"
    const val AI_CALL = "ai_call"
    const val AI_FAILED = "ai_failed"
    const val REMINDER_SENT = "reminder_sent"
    const val DATA_IMPORTED = "data_imported"

    /** 算作“当天有训练”的事件。 */
    val TRAINING = setOf(SESSION, PREVIEW_DONE, STUDY_DONE, EXPLAIN_DONE, TRANSFER_DONE, REVIEW_DONE)

    fun label(type: String): String = when (type) {
        SESSION -> "专注训练"
        UNIT_CREATED -> "新建学习单元"
        PREVIEW_DONE -> "写下预习问题"
        STUDY_DONE -> "读完资料"
        EXPLAIN_DONE -> "合上讲一遍"
        TRANSFER_DONE -> "举一反三"
        REVIEW_DONE -> "间隔自测"
        SETTINGS_TIME -> "折腾系统"
        AI_CALL -> "AI 调用"
        AI_FAILED -> "AI 调用失败"
        REMINDER_SENT -> "发出提醒"
        DATA_IMPORTED -> "导入数据"
        else -> type
    }
}

/** 事件：所有图表和复盘的唯一数据源。 */
@Serializable
@Entity(tableName = "event", indices = [Index("time"), Index("type")])
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val type: String,
    val refId: Long? = null,
    val value: Double? = null,
    /** 给人看的一句话，例如单元标题。 */
    val note: String = "",
)

@Serializable
@Entity(tableName = "weekly_review")
data class WeeklyReview(
    /** yyyy-MM-dd，周一。 */
    @PrimaryKey val weekStart: String,
    val summary: String,
    val aiAnalysis: String = "",
    val confirmedFocus: String = "",
)

@Serializable
@Entity(tableName = "ai_call", indices = [Index("time")])
data class AiCall(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    /** 任务类型 + 提示词版本，例如 critique@v1。 */
    val taskType: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val estCost: Double? = null,
    val ok: Boolean = true,
)
