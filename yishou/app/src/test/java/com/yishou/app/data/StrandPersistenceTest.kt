package com.yishou.app.data

import com.yishou.app.thread.*
import com.yishou.app.judge.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MemoryStrandDao : StrandDao {
    val rows = linkedMapOf<Long, StrandRow>()
    private val refs = linkedMapOf<Long, ReflexRow>()
    private val fades = linkedMapOf<String, FadeRow>()
    override suspend fun open() = rows.values.filter { !it.done }.maxByOrNull { it.updatedAt }
    override suspend fun get(id: Long) = rows[id]
    override suspend fun insert(row: StrandRow): Long = ((rows.keys.maxOrNull() ?: 0) + 1).also { rows[it] = row.copy(id = it) }
    override suspend fun update(row: StrandRow) { rows[row.id] = row }
    override suspend fun unfinished() = rows.values.filter { !it.done }
    override suspend fun all() = rows.values.toList()
    override fun observeAll(): Flow<List<StrandRow>> = flowOf(rows.values.toList())
    override suspend fun reflexes() = refs.values.toList()
    override suspend fun insertReflex(row: ReflexRow): Long = ((refs.keys.maxOrNull() ?: 0) + 1).also { refs[it] = row.copy(id = it) }
    override suspend fun updateReflex(row: ReflexRow) { refs[row.id] = row }
    override suspend fun fade(kind: String) = fades[kind]
    override suspend fun saveFade(row: FadeRow) { fades[row.kind] = row }
}

class StrandPersistenceTest {
    @Test fun `新线程有编号 换一个存取器还是同一锁题`() = runTest {
        val dao = MemoryStrandDao()
        val first = StrandStore(dao)
        val original = Strand(goal = "周六几点出发去车站", doneWhen = "选好时间并留出半小时余量", items = listOf(Item("I1", "最晚几点到车站"), Item("I2", "路上需要多久")), createdAt = 1, updatedAt = 1)
        val saved = first.save(original)
        assertTrue(saved.id > 0)
        val q = LockedQ("locked-1", "几点到车站才赶得上？", points = listOf(Point("P1", "时间")), need = 1, reference = "")
        first.save(saved.copy(pending = Pending(MoveKind.STEP, "I1", q)))
        val reopened = StrandStore(dao).open()!!
        assertEquals(saved.id, reopened.id)
        assertEquals(q, reopened.pending!!.q)
        assertEquals(1, dao.all().size)
        assertNull(StrandStore(dao).save(reopened.copy(done = true)).let { StrandStore(dao).open() })
    }
}
