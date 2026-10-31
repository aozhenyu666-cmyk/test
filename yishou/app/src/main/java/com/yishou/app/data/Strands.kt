package com.yishou.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.yishou.app.reflex.Reflex
import com.yishou.app.reflex.FadeState

@Entity(tableName = "strand", indices = [Index("done"), Index("updatedAt")])
data class StrandRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val json: String, val done: Boolean, val updatedAt: Long)

@Entity(tableName = "reflex", indices = [Index("dueAt")])
data class ReflexRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val signal: String, val action: String, val source: String, val createdAt: Long, val dueAt: Long, val reps: Int) {
    fun value() = Reflex(id, signal, action, source, createdAt, dueAt, reps)
    companion object { fun from(r: Reflex) = ReflexRow(r.id, r.signal, r.action, r.source, r.createdAt, r.dueAt, r.reps) }
}

@Entity(tableName = "fade")
data class FadeRow(@PrimaryKey val kind: String, val wins: Int, val misses: Int) {
    fun value() = FadeState(wins, misses)
}

@Dao
interface StrandDao {
    @Query("SELECT * FROM strand WHERE done = 0 ORDER BY updatedAt DESC, id DESC LIMIT 1")
    suspend fun open(): StrandRow?
    @Query("SELECT * FROM strand WHERE id = :id")
    suspend fun get(id: Long): StrandRow?
    @Insert suspend fun insert(row: StrandRow): Long
    @Update suspend fun update(row: StrandRow)
    @Query("SELECT * FROM strand WHERE done = 0 ORDER BY updatedAt DESC, id DESC")
    suspend fun unfinished(): List<StrandRow>
    @Query("SELECT * FROM strand ORDER BY updatedAt DESC")
    suspend fun all(): List<StrandRow>
    @Query("SELECT * FROM strand ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<StrandRow>>
    @Query("SELECT * FROM reflex ORDER BY dueAt, id")
    suspend fun reflexes(): List<ReflexRow>
    @Insert suspend fun insertReflex(row: ReflexRow): Long
    @Update suspend fun updateReflex(row: ReflexRow)
    @Query("SELECT * FROM fade WHERE kind = :kind")
    suspend fun fade(kind: String): FadeRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveFade(row: FadeRow)
}
