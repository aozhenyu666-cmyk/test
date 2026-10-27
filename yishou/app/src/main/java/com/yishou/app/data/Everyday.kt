package com.yishou.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "assistant_chat", indices = [Index("updatedAt")])
data class AssistantChat(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val json: String, val updatedAt: Long)

@Entity(tableName = "library_category", indices = [Index(value = ["name"], unique = true)])
data class LibraryCategory(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(tableName = "library_item", primaryKeys = ["kind", "refId"],
    foreignKeys = [ForeignKey(entity = LibraryCategory::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE)], indices = [Index("categoryId")])
data class LibraryItem(val kind: String, val refId: Long, val categoryId: Long, val manual: Boolean)

@Dao
interface EverydayDao {
    @Query("SELECT * FROM assistant_chat ORDER BY updatedAt DESC") fun chats(): Flow<List<AssistantChat>>
    @Query("SELECT * FROM assistant_chat WHERE id = :id") suspend fun chat(id: Long): AssistantChat?
    @Insert suspend fun insertChat(row: AssistantChat): Long
    @Update suspend fun updateChat(row: AssistantChat)
    @Query("DELETE FROM assistant_chat WHERE id = :id") suspend fun deleteChat(id: Long)
    @Query("SELECT * FROM library_category ORDER BY id") fun categories(): Flow<List<LibraryCategory>>
    @Query("SELECT * FROM library_category ORDER BY id") suspend fun allCategories(): List<LibraryCategory>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCategory(row: LibraryCategory): Long
    @Query("UPDATE library_category SET name = :name WHERE id = :id") suspend fun renameCategory(id: Long, name: String)
    @Query("DELETE FROM library_category WHERE id = :id") suspend fun deleteCategory(id: Long)
    @Query("SELECT * FROM library_item") fun assignments(): Flow<List<LibraryItem>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun assign(row: LibraryItem)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun assignAutomatic(row: LibraryItem)
    @Query("UPDATE library_item SET categoryId = :target WHERE categoryId = :id") suspend fun moveCategory(id: Long, target: Long)
    @Query("SELECT card.* FROM card LEFT JOIN library_item i ON i.kind = 'card' AND i.refId = card.id WHERE i.refId IS NULL AND card.source != 'ai'") suspend fun unfiledCards(): List<CardRow>
    @Query("SELECT material.* FROM material LEFT JOIN library_item i ON i.kind = 'material' AND i.refId = material.id WHERE i.refId IS NULL AND material.source != 'builtin'") suspend fun unfiledMaterials(): List<Material>
    @Query("SELECT * FROM card WHERE source != 'ai' ORDER BY createdAt DESC") fun cards(): Flow<List<CardRow>>
    @Query("SELECT * FROM material WHERE source != 'builtin' ORDER BY createdAt DESC") fun materials(): Flow<List<Material>>
}
