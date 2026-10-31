package com.yishou.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 一组训练：一份材料、一个主练动作（骰子的一面），几手连着练，最后独立做一道相似题。
 */
@Entity(tableName = "train_set", indices = [Index("startedAt")])
data class TrainSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 属于哪个领域（任务） */
    val taskId: Long,
    /** 主练的骰子面 1–5 */
    val face: Int,
    /** 开组时的帮助等级 0–3 */
    val level: Int,
    /** 今天练：…… */
    val goal: String,
    val material: String,
    /** 本组完成标准 */
    val standard: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** 取值见 [SetStatus] */
    val status: String,
    /** 组末用户自己说的“这组练会了什么” */
    val lesson: String = "",
    /** 热身成绩，例如“心算 4/5，41 秒”；没热身为空 */
    val warmup: String = "",
    /** 独立验证：1 独立做对，0 有帮助、改过或没做对，-1 还没走到 */
    val verified: Int = -1,
)

object SetStatus {
    const val ACTIVE = "active"
    const val DONE = "done"
    const val DROPPED = "dropped"
}

/** 一组里的一手。重做是同一手（idx 不变）的下一次尝试。 */
@Entity(tableName = "set_move", indices = [Index("setId"), Index("createdAt")])
data class SetMove(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val setId: Long,
    /** 第几手，从 1 开始；重做不加 */
    val idx: Int,
    /** 取值见 [MoveKind] */
    val kind: String,
    /** 第几次尝试：1 首次，2、3 重做 */
    val attempt: Int,
    val question: String,
    val say: String,
    val answer: String = "",
    /** 取值见 [MoveVerdict]；空表示还没答 */
    val verdict: String = "",
    /** 定位反馈：漏在哪、这次只改哪一步 */
    val gap: String = "",
    /** 过了以后压成的短标签，进结论链 */
    val conclusion: String = "",
    /** 用到的最高帮助：0 没有，1 线索或解释，2 半步，3 示范 */
    val help: Int = 0,
    val helpText: String = "",
    /** 改了两次还没过时，教练给出的正确这一步 */
    val correctStep: String = "",
    val createdAt: Long,
    val answeredAt: Long? = null,
)

object MoveKind {
    const val TRY = "try"
    const val RELAY = "relay"
    const val VERIFY = "verify"
}

object MoveVerdict {
    const val PENDING = ""
    const val PASS = "pass"
    const val REDO = "redo"
    const val UNCLEAR = "unclear"
    /** 改了两次没过，教练给出了正确的一步，接着往下走 */
    const val SHOWN = "shown"
}

/** 离开后回来，凭记忆说结论链：一共几节、说对几节。 */
@Entity(tableName = "chain_recall", indices = [Index("at")])
data class ChainRecall(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val setId: Long,
    val at: Long,
    val total: Int,
    val recalled: Int,
    /** 离开了多久（秒） */
    val awaySeconds: Long,
)

@Dao
abstract class CoachDao {
    @Insert
    abstract suspend fun insertSet(set: TrainSet): Long

    @Update
    abstract suspend fun updateSet(set: TrainSet)

    @Query("SELECT * FROM train_set WHERE status = 'active' ORDER BY startedAt DESC LIMIT 1")
    abstract suspend fun activeSet(): TrainSet?

    @Query("SELECT * FROM train_set WHERE id = :id")
    abstract suspend fun getSet(id: Long): TrainSet?

    @Query("SELECT * FROM train_set ORDER BY startedAt DESC LIMIT :n")
    abstract suspend fun recentSets(n: Int): List<TrainSet>

    @Query("SELECT * FROM train_set WHERE startedAt >= :since ORDER BY startedAt")
    abstract fun observeSetsSince(since: Long): Flow<List<TrainSet>>

    @Insert
    abstract suspend fun insertMove(move: SetMove): Long

    @Update
    abstract suspend fun updateMove(move: SetMove)

    @Query("SELECT * FROM set_move WHERE setId = :setId ORDER BY id")
    abstract suspend fun moves(setId: Long): List<SetMove>

    @Query("SELECT * FROM set_move WHERE createdAt >= :since ORDER BY id")
    abstract suspend fun movesSince(since: Long): List<SetMove>

    @Insert
    abstract suspend fun insertRecall(recall: ChainRecall): Long

    @Query("SELECT * FROM chain_recall WHERE at >= :since")
    abstract suspend fun recallsSince(since: Long): List<ChainRecall>
}
