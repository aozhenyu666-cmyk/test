package app.jobtracker.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalTime

/**
 * 设置，表里只有一行。
 * API 密钥不在这张表里：它单独加密保存在 EncryptedSharedPreferences（模块 2），
 * 这样数据库文件和导出内容里都不会出现密钥。
 */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val apiBaseUrl: String,
    val strongModel: String,
    val fastModel: String,
    val reminderTime: LocalTime,
    /** 渠道名 -> 应用包名 */
    val channelApps: Map<String, String> = emptyMap(),
) {
    companion object {
        const val SINGLE_ROW_ID = 1
    }
}
