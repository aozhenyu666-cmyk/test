package com.yishou.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 素材：你从别处分享过来、粘贴、拍下的一段文字，或者让模型写的一段。
 * 同一段素材可以换着练法练好几次。图片只用来转写，不保存。
 */
@Entity(tableName = "material", indices = [Index("createdAt")])
data class Material(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val text: String,
    /** 取值见 [MaterialSource] */
    val source: String,
    val createdAt: Long,
    val usedAt: Long? = null,
    val uses: Int = 0,
)

object MaterialSource {
    const val SHARE = "share"
    const val PASTE = "paste"
    const val PHOTO = "photo"
    const val SCREEN = "screen"
    const val MADE = "made"
    const val TYPED = "typed"

    fun label(s: String) = when (s) {
        SHARE -> "分享来的"
        PASTE -> "粘贴的"
        PHOTO -> "拍的"
        SCREEN -> "截屏"
        MADE -> "教练写的"
        else -> "自己写的"
    }
}

/**
 * 练一次的记录。lens 是练法名（见 think.Lens），比价记作 [DrillRow.COMPARE]。
 * key 是作答前就锁住的答案（JSON），checks 是逐条判定（JSON）。
 */
@Entity(tableName = "drill", indices = [Index("createdAt"), Index("materialId")])
data class DrillRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val materialId: Long?,
    val lens: String,
    val key: String,
    val answer: String,
    val checks: String,
    /** 0–100 */
    val score: Int,
    /** 站立场：被追问后的回应 */
    val reply: String = "",
    /** 收口的一句话 */
    val takeaway: String = "",
    /** 你觉得锁住的答案本身有问题：不计入统计 */
    val disputed: Boolean = false,
    val durationMs: Long,
    val createdAt: Long,
) {
    companion object {
        const val COMPARE = "COMPARE"
    }
}

/** 实操：做事前估一个数，做完对照 */
@Entity(tableName = "deed", indices = [Index("createdAt")])
data class Deed(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** 打算怎么做 / 买什么（可空） */
    val plan: String,
    val estimate: Double,
    /** 元、分钟、个…… */
    val unit: String,
    val createdAt: Long,
    val actual: Double? = null,
    /** 对照时写的一句：差在哪 */
    val note: String = "",
    val resolvedAt: Long? = null,
)

@Dao
abstract class ThinkDao {

    @Insert
    abstract suspend fun insertMaterial(m: Material): Long

    @Update
    abstract suspend fun updateMaterial(m: Material)

    @Delete
    abstract suspend fun deleteMaterial(m: Material)

    @Query("SELECT * FROM material WHERE id = :id")
    abstract suspend fun getMaterial(id: Long): Material?

    @Query("SELECT * FROM material ORDER BY createdAt DESC LIMIT 200")
    abstract fun observeMaterials(): Flow<List<Material>>

    @Query("SELECT * FROM material ORDER BY createdAt DESC LIMIT :n")
    abstract suspend fun recentMaterials(n: Int): List<Material>

    @Insert
    abstract suspend fun insertDrill(d: DrillRow): Long

    @Update
    abstract suspend fun updateDrill(d: DrillRow)

    @Query("SELECT * FROM drill WHERE createdAt >= :since ORDER BY createdAt")
    abstract suspend fun drillsSince(since: Long): List<DrillRow>

    @Query("SELECT * FROM drill WHERE createdAt >= :since ORDER BY createdAt")
    abstract fun observeDrillsSince(since: Long): Flow<List<DrillRow>>

    @Insert
    abstract suspend fun insertDeed(d: Deed): Long

    @Update
    abstract suspend fun updateDeed(d: Deed)

    @Delete
    abstract suspend fun deleteDeed(d: Deed)

    @Query("SELECT * FROM deed ORDER BY createdAt DESC LIMIT 200")
    abstract fun observeDeeds(): Flow<List<Deed>>
}
