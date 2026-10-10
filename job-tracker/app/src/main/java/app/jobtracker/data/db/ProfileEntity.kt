package app.jobtracker.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/** 简历与求职方向，表里只有一行 */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val resumeText: String,
    val direction: String,
    val updatedAt: Instant,
) {
    companion object {
        const val SINGLE_ROW_ID = 1
    }
}
