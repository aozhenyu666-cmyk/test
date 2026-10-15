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
 * 回响队列里的一条：你自己写下的规则（收口）、差值（预期和现实差在哪）、主线回到主干时的结论。
 * 不放在晚上的总结里等你去看，而是在下一次同类场景开局的头几秒推到你面前。
 */
@Entity(tableName = "echo", indices = [Index("scene"), Index("createdAt")])
data class Echo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    /** 取值见 [EchoKind] */
    val kind: String,
    /** 在哪类场景推出来：某个 App 的包名、card、line、job；空表示哪都可以 */
    val scene: String = "",
    val createdAt: Long,
    val shownAt: Long? = null,
    /** 推出来几次 */
    val shown: Int = 0,
    /** 你确认（读了一遍 / 点了记得）几次 */
    val confirmed: Int = 0,
)

object EchoKind {
    /** 收口：下次遇到 X，先做 Y */
    const val RULE = "rule"
    /** 差值：本来想 A，漏看了 B，下次见到 C 先做 D */
    const val DELTA = "delta"
    /** 主线回到主干时的结论 */
    const val CONCLUSION = "conclusion"

    fun label(k: String) = when (k) {
        RULE -> "你的规则"
        DELTA -> "上次的差值"
        else -> "你的结论"
    }
}

@Dao
abstract class EchoDao {
    @Insert
    abstract suspend fun insert(e: Echo): Long

    @Update
    abstract suspend fun update(e: Echo)

    @Query("SELECT * FROM echo ORDER BY createdAt DESC LIMIT 300")
    abstract suspend fun recent(): List<Echo>

    @Query("SELECT * FROM echo ORDER BY createdAt DESC LIMIT 300")
    abstract fun observe(): Flow<List<Echo>>
}
