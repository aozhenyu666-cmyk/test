package app.jobtracker.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.jobtracker.data.model.ApplicationStatus
import app.jobtracker.data.model.EndResult
import app.jobtracker.data.model.MatchLevel
import java.time.Instant
import java.time.LocalDate

/** 投递记录 */
@Entity(
    tableName = "applications",
    indices = [Index("status"), Index("nextFollowUpAt")],
)
data class ApplicationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val company: String,
    val title: String,
    val channel: String = "",
    val status: ApplicationStatus = ApplicationStatus.PENDING,
    val endResult: EndResult? = null,
    val jdText: String,
    val matchLevel: MatchLevel,
    /** 匹配分析的完整结果，原样保存 */
    val analysisJson: String,
    val appliedAt: LocalDate? = null,
    val interviewAt: LocalDate? = null,
    val nextFollowUpAt: LocalDate? = null,
    val followUpCount: Int = 0,
    val note: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)
