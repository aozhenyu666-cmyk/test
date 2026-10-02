package com.behaviordept.app.data

import com.behaviordept.app.study.Spacing
import com.behaviordept.app.util.Time
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/** 事件日志：所有行为都经过这里落成 Event。 */
class EventLog(private val dao: EventDao) {
    suspend fun log(type: String, refId: Long? = null, value: Double? = null, note: String = "", time: Long = Time.now()) {
        dao.insert(Event(time = time, type = type, refId = refId, value = value, note = note.take(80)))
    }

    fun observeSince(from: Long): Flow<List<Event>> = dao.observeSince(from)

    fun observeRecent(limit: Int = 300): Flow<List<Event>> = dao.observeRecent(limit)
}

object JsonLists {
    private val serializer = ListSerializer(String.serializer())
    fun encode(list: List<String>): String = Json.encodeToString(serializer, list)
    fun decode(text: String): List<String> = runCatching { Json.decodeFromString(serializer, text) }.getOrDefault(emptyList())
}

/** 学习单元的读写，以及每一步完成时该写的事件。 */
class StudyRepository(private val dao: StudyDao, private val events: EventLog) {
    fun observeUnits() = dao.observeUnits()
    fun observeUnit(id: Long) = dao.observeUnit(id)
    fun observeReviews(unitId: Long) = dao.observeReviews(unitId)
    fun observeAllReviews() = dao.observeAllReviews()
    fun observeTransfers(unitId: Long) = dao.observeTransfers(unitId)
    suspend fun allUnits() = dao.allUnits()
    suspend fun unit(id: Long) = dao.unit(id)

    suspend fun create(title: String, material: String): Long {
        val id = dao.insertUnit(StudyUnit(title = title.trim(), material = material.trim(), createdAt = Time.now()))
        events.log(EventType.UNIT_CREATED, id, note = title)
        return id
    }

    suspend fun updateContent(unit: StudyUnit, title: String, material: String) {
        dao.updateUnit(unit.copy(title = title.trim(), material = material.trim()))
    }

    suspend fun delete(unitId: Long) {
        dao.deleteReviewsOf(unitId)
        dao.deleteTransfersOf(unitId)
        dao.deleteUnit(unitId)
    }

    suspend fun savePreQuestions(unit: StudyUnit, questions: List<String>) {
        dao.updateUnit(unit.copy(preQuestions = questions.joinToString("\n") { it.trim() }))
        events.log(EventType.PREVIEW_DONE, unit.id, questions.size.toDouble(), unit.title)
    }

    suspend fun markStudied(unit: StudyUnit) {
        dao.updateUnit(unit.copy(studiedAt = Time.now()))
        events.log(EventType.STUDY_DONE, unit.id, note = unit.title)
    }

    /** 讲解批改完成：记下讲解和批改，第二天第一次自测。 */
    suspend fun saveExplanation(unit: StudyUnit, explanation: String, critique: String, mode: String) {
        val (level, next) = Spacing.afterExplain(Time.today())
        dao.updateUnit(
            unit.copy(
                explanation = explanation.trim(),
                critique = critique,
                critiqueMode = mode,
                explainedAt = Time.now(),
                intervalLevel = level,
                nextReviewAt = Time.startOf(next),
            ),
        )
        events.log(EventType.EXPLAIN_DONE, unit.id, note = unit.title)
    }

    suspend fun saveTransfer(unit: StudyUnit, example: String, critique: String, mode: String) {
        dao.insertTransfer(Transfer(unitId = unit.id, example = example.trim(), critique = critique, mode = mode, createdAt = Time.now()))
        if (unit.transferredAt == null) dao.updateUnit(unit.copy(transferredAt = Time.now()))
        events.log(EventType.TRANSFER_DONE, unit.id, note = unit.title)
    }

    /** 出好题后先存一条未完成的自测，中途退出也不会丢题。 */
    suspend fun startReview(unit: StudyUnit, questions: List<String>, mode: String): Review {
        val review = Review(
            unitId = unit.id,
            intervalDays = Spacing.days(unit.intervalLevel),
            questions = JsonLists.encode(questions),
            mode = mode,
            createdAt = Time.now(),
        )
        return review.copy(id = dao.insertReview(review))
    }

    suspend fun openReview(unitId: Long): Review? = dao.openReview(unitId)

    /** 以前出过的题，出新题时交给 AI 避免重复。 */
    suspend fun previousQuestions(unitId: Long): List<String> =
        dao.reviewsOf(unitId).flatMap { JsonLists.decode(it.questions) }

    suspend fun saveAnswers(review: Review, answers: List<String>, critique: String) {
        dao.updateReview(review.copy(answers = JsonLists.encode(answers), critique = critique))
    }

    /** 自己判定记得 / 模糊 / 忘了，按间隔规则排下一次。 */
    suspend fun finishReview(review: Review, unit: StudyUnit, answers: List<String>, critique: String, rating: String) {
        dao.updateReview(
            review.copy(
                answers = JsonLists.encode(answers),
                critique = critique,
                rating = rating,
                finishedAt = Time.now(),
            ),
        )
        val (level, next) = Spacing.afterReview(unit.intervalLevel, rating, Time.today())
        dao.updateUnit(unit.copy(intervalLevel = level, nextReviewAt = Time.startOf(next)))
        val value = when (rating) {
            Rating.REMEMBER -> 2.0
            Rating.FUZZY -> 1.0
            else -> 0.0
        }
        events.log(EventType.REVIEW_DONE, unit.id, value, unit.title)
    }
}

/** 专注模式的训练时段。 */
class SessionRepository(private val dao: SessionDao, private val events: EventLog) {
    suspend fun start(type: String, refId: Long?, title: String): Long {
        val now = Time.now()
        return dao.insert(TrainingSession(type = type, refId = refId, title = title, start = now, end = now))
    }

    suspend fun heartbeat(id: Long) = dao.touch(id, Time.now())

    suspend fun get(id: Long) = dao.get(id)

    /** 结束专注：写入时段终点和一条 SESSION 事件（分钟）。返回分钟数。 */
    suspend fun finish(id: Long, end: Long = Time.now()): Int {
        val s = dao.get(id) ?: return 0
        if (s.ended) return minutesOf(s.start, s.end)
        val closed = s.copy(end = end.coerceAtLeast(s.start), ended = true)
        dao.update(closed)
        val minutes = minutesOf(closed.start, closed.end)
        events.log(EventType.SESSION, closed.id, minutes.toDouble(), closed.title, time = closed.end)
        return minutes
    }

    /** App 被杀时留下的未结束时段，按最后一次心跳收尾。 */
    suspend fun closeAbandoned(exceptId: Long? = null) {
        dao.unfinished().filter { it.id != exceptId }.forEach { finish(it.id, it.end) }
    }

    private fun minutesOf(start: Long, end: Long): Int = ((end - start) / 60_000.0).roundToInt()
}
