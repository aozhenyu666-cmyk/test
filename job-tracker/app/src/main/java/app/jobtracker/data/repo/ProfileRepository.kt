package app.jobtracker.data.repo

import app.jobtracker.data.db.ProfileDao
import app.jobtracker.data.db.ProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock

class ProfileRepository(
    private val dao: ProfileDao,
    private val clock: Clock,
) {
    fun observe(): Flow<ProfileEntity?> = dao.observe()

    /** 没有简历时"分析"按钮不可点 */
    fun observeHasResume(): Flow<Boolean> = dao.observe().map { !it?.resumeText.isNullOrBlank() }

    suspend fun get(): ProfileEntity? = dao.get()

    suspend fun save(resumeText: String, direction: String) {
        dao.upsert(
            ProfileEntity(
                resumeText = resumeText.trim(),
                direction = direction.trim(),
                updatedAt = clock.instant(),
            ),
        )
    }
}
