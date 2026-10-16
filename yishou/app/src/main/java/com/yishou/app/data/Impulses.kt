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
 * 一次急刹：冲动上头时倒出来的那句话，被拆成“摄像机拍得到的”和“脑子里补的”，
 * 押一个最坏后果，再决定做不做。30 分钟后回来核对后果出没出现。
 */
@Entity(tableName = "impulse", indices = [Index("createdAt")])
data class Impulse(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 倒出来的原话 */
    val raw: String,
    /** 事实（JSON 数组） */
    val facts: String = "[]",
    /** 脑补（JSON 数组） */
    val fantasies: String = "[]",
    /** 要证明最大那条脑补，得有的那个硬证据 */
    val proof: String = "",
    /** 手里有没有这个证据；null 表示没答 */
    val hasProof: Boolean? = null,
    /** 押的最坏后果 */
    val worst: String = "",
    /** 取值见 [ImpulseDecision] */
    val decision: String = "",
    val createdAt: Long,
    /** 什么时候回来核对 */
    val checkAt: Long? = null,
    /** 核对结果：最坏后果出现了没有；null 表示还没核对 */
    val happened: Boolean? = null,
)

object ImpulseDecision {
    const val HOLD = "hold"
    const val GO = "go"
}

@Dao
abstract class ImpulseDao {
    @Insert
    abstract suspend fun insert(i: Impulse): Long

    @Update
    abstract suspend fun update(i: Impulse)

    @Query("SELECT * FROM impulse WHERE id = :id")
    abstract suspend fun get(id: Long): Impulse?

    /** 还没核对的（到没到点由界面按当前时间判断） */
    @Query("SELECT * FROM impulse WHERE checkAt IS NOT NULL AND happened IS NULL ORDER BY checkAt")
    abstract fun observePending(): Flow<List<Impulse>>

    @Query("SELECT * FROM impulse ORDER BY createdAt DESC LIMIT 200")
    abstract suspend fun recent(): List<Impulse>
}
