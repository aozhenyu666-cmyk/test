package com.yishou.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 存下来的一张题卡。json 是完整题卡（含答案），verified 表示出题后另做一遍答案对得上。 */
@Entity(tableName = "card", indices = [Index("createdAt"), Index("skill")])
data class CardRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val skill: String,
    val difficulty: Int,
    val json: String,
    val verified: Boolean,
    /** 取值见 [CardSource] */
    val source: String,
    val createdAt: Long,
    /** 第一次拿出来做的时间；null 表示还没做过 */
    val usedAt: Long? = null,
)

object CardSource {
    const val AI = "ai"
    /** 用户拍照导入的真题 */
    const val PHOTO = "photo"
    /** 题包导入的（GPT、Gemini、粉笔……），答案来自题包 */
    const val BANK = "bank"
}

object CardMode {
    const val GATE = "gate"
    const val HOME = "home"
    const val WINDOW = "window"
}

/** 做一张题卡的结果。只有 verified 的才计入成绩。 */
@Entity(tableName = "card_attempt", indices = [Index("cardId"), Index("createdAt")])
data class CardAttempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val skill: String,
    val difficulty: Int,
    /** 取值见 [CardMode] */
    val mode: String,
    /** 关屏阶梯：1 同屏，2 看完收起可记标签，3 收起不许记 */
    val level: Int,
    val bet: Int,
    /** 高 / 中 / 低 */
    val conf: String,
    val evidenceOk: Boolean,
    /** 多查了几项、查对几项 */
    val checks: Int,
    val checksOk: Int,
    val final: Int,
    val firstCorrect: Boolean,
    val finalCorrect: Boolean,
    /** 0 自己完成，1 指区域，2 排除两项，3 看解析 */
    val help: Int,
    /** 错了时自己点的错因 */
    val cause: String,
    val durationMs: Long,
    val verified: Boolean,
    val createdAt: Long,
)

@Dao
abstract class CardDao {
    @Insert
    abstract suspend fun insertCard(card: CardRow): Long

    @Update
    abstract suspend fun updateCard(card: CardRow)

    @Query("SELECT * FROM card WHERE id = :id")
    abstract suspend fun getCard(id: Long): CardRow?

    /** 还没做过、核对过答案的题卡（备着用） */
    @Query("SELECT * FROM card WHERE usedAt IS NULL AND verified = 1 ORDER BY createdAt")
    abstract suspend fun unused(): List<CardRow>

    /** 题库里还没做过的（含没写答案、要做完点官方答案的） */
    @Query("SELECT * FROM card WHERE usedAt IS NULL AND source = 'bank' ORDER BY createdAt")
    abstract suspend fun unusedBank(): List<CardRow>

    @Query("SELECT * FROM card WHERE source = 'bank' ORDER BY createdAt DESC")
    abstract fun observeBank(): Flow<List<CardRow>>

    @Query("SELECT * FROM card ORDER BY createdAt DESC LIMIT :n")
    abstract suspend fun recent(n: Int): List<CardRow>

    @Insert
    abstract suspend fun insertAttempt(attempt: CardAttempt): Long

    @Update
    abstract suspend fun updateAttempt(attempt: CardAttempt)

    @Query("SELECT * FROM card_attempt WHERE createdAt >= :since ORDER BY createdAt")
    abstract suspend fun attemptsSince(since: Long): List<CardAttempt>

    @Query("SELECT * FROM card_attempt WHERE createdAt >= :since ORDER BY createdAt")
    abstract fun observeAttemptsSince(since: Long): Flow<List<CardAttempt>>

    @Query("SELECT * FROM card_attempt WHERE createdAt >= :from AND createdAt < :to ORDER BY createdAt")
    abstract suspend fun attemptsBetween(from: Long, to: Long): List<CardAttempt>
}
