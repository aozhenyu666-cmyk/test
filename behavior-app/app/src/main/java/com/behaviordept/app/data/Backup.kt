package com.behaviordept.app.data

import androidx.room.withTransaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 导出 / 导入的 JSON 文件格式。API Key 不在其中。 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val exportedAt: Long,
    val skills: List<Skill> = emptyList(),
    val subSkills: List<SubSkill> = emptyList(),
    val drills: List<Drill> = emptyList(),
    val units: List<StudyUnit> = emptyList(),
    val reviews: List<Review> = emptyList(),
    val transfers: List<Transfer> = emptyList(),
    val matchLogs: List<MatchLog> = emptyList(),
    val sessions: List<TrainingSession> = emptyList(),
    val rules: List<Rule> = emptyList(),
    val usageDays: List<UsageDay> = emptyList(),
    val urgeLogs: List<UrgeLog> = emptyList(),
    val events: List<Event> = emptyList(),
    val weeklyReviews: List<WeeklyReview> = emptyList(),
    val aiCalls: List<AiCall> = emptyList(),
) {
    companion object {
        const val FORMAT = "behavior-dept-backup"
    }
}

class BackupService(private val db: AppDatabase) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun export(now: Long): String {
        val d = db.backupDao()
        val file = BackupFile(
            exportedAt = now,
            skills = d.skills(), subSkills = d.subSkills(), drills = d.drills(),
            units = d.units(), reviews = d.reviews(), transfers = d.transfers(),
            matchLogs = d.matchLogs(), sessions = d.sessions(), rules = d.rules(),
            usageDays = d.usageDays(), urgeLogs = d.urgeLogs(), events = d.events(),
            weeklyReviews = d.weeklyReviews(), aiCalls = d.aiCalls(),
        )
        return json.encodeToString(BackupFile.serializer(), file)
    }

    /** 解析失败抛 IllegalArgumentException，不会动现有数据。 */
    fun parse(text: String): BackupFile {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: Exception) {
            throw IllegalArgumentException("文件不是有效的备份 JSON：${e.message?.take(120)}")
        }
        require(file.format == BackupFile.FORMAT) { "这不是行为管理部导出的备份文件" }
        return file
    }

    /** 用备份整体替换本地数据。 */
    suspend fun restore(file: BackupFile) {
        val d = db.backupDao()
        db.withTransaction {
            d.clearSkill(); d.clearSubSkill(); d.clearDrill(); d.clearStudyUnit()
            d.clearReview(); d.clearTransfer(); d.clearMatchLog(); d.clearSession()
            d.clearRule(); d.clearUsageDay(); d.clearUrgeLog(); d.clearEvent()
            d.clearWeeklyReview(); d.clearAiCall()
            d.putSkills(file.skills); d.putSubSkills(file.subSkills); d.putDrills(file.drills)
            d.putUnits(file.units); d.putReviews(file.reviews); d.putTransfers(file.transfers)
            d.putMatchLogs(file.matchLogs); d.putSessions(file.sessions); d.putRules(file.rules)
            d.putUsageDays(file.usageDays); d.putUrgeLogs(file.urgeLogs); d.putEvents(file.events)
            d.putWeeklyReviews(file.weeklyReviews); d.putAiCalls(file.aiCalls)
        }
    }
}
