package app.jobtracker.data.repo

import app.jobtracker.data.db.ApplicationDao
import app.jobtracker.data.db.ApplicationEntity
import app.jobtracker.data.model.ApplicationStatus
import app.jobtracker.data.model.MatchLevel
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDate

/** 从分析结果"加入投递"时需要的内容 */
data class NewApplication(
    val company: String,
    val title: String,
    val jdText: String,
    val matchLevel: MatchLevel,
    val analysisJson: String,
    val channel: String = "",
)

class ApplicationRepository(
    private val dao: ApplicationDao,
    private val clock: Clock,
) {
    fun observeAll(): Flow<List<ApplicationEntity>> = dao.observeAll()

    fun observeByStatus(status: ApplicationStatus): Flow<List<ApplicationEntity>> = dao.observeByStatus(status)

    /** 待跟进：当天到期和已逾期、未结束的记录 */
    fun observeDue(today: LocalDate = LocalDate.now(clock)): Flow<List<ApplicationEntity>> =
        dao.observeDue(today, ApplicationStatus.CLOSED)

    fun observe(id: Long): Flow<ApplicationEntity?> = dao.observeById(id)

    suspend fun get(id: Long): ApplicationEntity? = dao.getById(id)

    /** 新记录一律是"待投递" */
    suspend fun create(new: NewApplication): Long {
        val now = clock.instant()
        return dao.insert(
            ApplicationEntity(
                company = new.company.trim(),
                title = new.title.trim(),
                channel = new.channel.trim(),
                status = ApplicationStatus.PENDING,
                jdText = new.jdText,
                matchLevel = new.matchLevel,
                analysisJson = new.analysisJson,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** 保存修改，自动更新 updatedAt。已结束必须带结果，未结束不能带结果。 */
    suspend fun update(application: ApplicationEntity) {
        val closed = application.status == ApplicationStatus.CLOSED
        require(closed == (application.endResult != null)) {
            "已结束的记录必须选择结果，未结束的记录不能有结果"
        }
        dao.update(application.copy(updatedAt = clock.instant()))
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun getAllForExport(): List<ApplicationEntity> = dao.getAll()
}
