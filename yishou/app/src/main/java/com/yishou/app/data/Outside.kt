package com.yishou.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 在别的刷题 App（粉笔之类）里做的一次练习：从它的练习报告截图读出来，或者手填。
 * 一手不碰那个 App 的数据，只认你给的截图。
 */
@Entity(tableName = "outside", indices = [Index("createdAt")])
data class Outside(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** App 名字（粉笔……） */
    val app: String,
    /** 科目 / 题型（资料分析……） */
    val subject: String,
    val questions: Int,
    /** 做对几道（认不出时为空） */
    val correct: Int?,
    /** 用时秒数（认不出时为空） */
    val seconds: Int?,
    val createdAt: Long,
    /** 一手这边从跳过去到回来记录的时间（兜底的用时） */
    val startedAt: Long? = null,
    val jobId: Long? = null,
)
