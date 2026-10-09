package com.yishou.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 学习任务。同一时间只有一个 isCurrent = true；切换当前任务时旧任务保留。 */
@Entity(tableName = "task")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 对象，例如“行测 · 资料分析” */
    val title: String,
    /** 目标 */
    val goal: String,
    /** 材料摘录，可空 */
    val material: String?,
    val isCurrent: Boolean,
    val createdAt: Long,
)

/**
 * 断点。每个任务一条，每轮结束后覆盖更新。
 *
 * pendingCoachMove：陪练已经出好、还没被回答的一手（判定请求返回的 next_coach_move）。
 * 为空时才发开局请求；手动改过任务或断点后会清空，下次重新开局。
 */
@Entity(tableName = "breakpoint")
data class Breakpoint(
    @PrimaryKey val taskId: Long,
    val known: String,
    val stuck: String,
    val nextQuestion: String,
    val pendingCoachMove: String?,
    val updatedAt: Long,
    /** 待回答的这一手练骰子的第几面（1–6），0 表示没标 */
    @ColumnInfo(defaultValue = "0") val pendingFace: Int = 0,
)

/** 一轮。原话原样保存。 */
@Entity(tableName = "round", indices = [Index("taskId"), Index("createdAt")])
data class Round(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    /** 取值见 [RoundSource] */
    val source: String,
    /** 入口思考页触发时的应用包名 */
    val triggerPackage: String?,
    /** 陪练这一轮的分析与问题 */
    val coachMove: String,
    /** 用户回答原话 */
    val userAnswer: String,
    val effective: Boolean,
    /** 取值见 Prompts.MOVE_TYPES */
    val moveType: String,
    val reason: String,
    val feedback: String,
    /** 是否经大模型判定；离线“保存并通过”时为 false */
    val judged: Boolean,
    val createdAt: Long,
    /** 这一轮练的是骰子的第几面（1–6），0 表示没标（v0.5 之前的记录） */
    @ColumnInfo(defaultValue = "0") val face: Int = 0,
)

object RoundSource {
    /** 入口思考页（M2） */
    const val GATE = "gate"
    /** 陪练窗口（M3） */
    const val WINDOW = "window"
    /** 在应用主页自己走的一轮 */
    const val HOME = "home"
}

/** 一次放行，endAt 之后自动失效（M2 使用）。 */
@Entity(tableName = "pass", indices = [Index("packageGroup"), Index("endAt")])
data class Pass(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageGroup: String,
    val startAt: Long,
    val endAt: Long,
    val roundId: Long?,
)

/** 每晚总结，每天一条（M4 使用）。date 形如 2026-10-08。 */
@Entity(tableName = "daily_summary")
data class DailySummary(
    @PrimaryKey val date: String,
    /** 条件 → 做法 → 预期结果；证据不足或当日无记录时为空 */
    val rule: String,
    val tomorrowQuestion: String,
    val note: String,
    val roundCount: Int,
    val effectiveCount: Int,
    val windowMinutes: Int,
)

/** 按骰子面统计的轮数（盘点页用）。 */
data class FaceCount(
    val face: Int,
    val total: Int,
    val effective: Int,
)
