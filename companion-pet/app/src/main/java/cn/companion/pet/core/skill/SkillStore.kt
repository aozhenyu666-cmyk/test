package cn.companion.pet.core.skill

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** 技能存储：一个 JSON 文件，原子写入；损坏时另存备份、不覆盖。最多 30 个技能、每个 30 步。 */
class SkillStore(root: File, private val maxSkills: Int = 30) {
    private val file = File(root, "skills/skills.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile var lastError: String? = null
        private set

    fun list(): List<Skill> = synchronized(lock) { read() }

    fun get(id: String): Skill? = synchronized(lock) { read().firstOrNull { it.id == id } }

    fun create(name: String, now: Long): Skill? = synchronized(lock) {
        val all = read()
        val clean = name.trim().take(40)
        if (clean.isEmpty()) { lastError = "名字不能为空"; return null }
        if (all.size >= maxSkills) { lastError = "技能最多 $maxSkills 个"; return null }
        val skill = Skill(UUID.randomUUID().toString(), clean, emptyList(), now, now)
        if (!write(all + skill)) return null
        skill
    }

    fun delete(id: String): Boolean = update(id) { null }

    fun rename(id: String, name: String, now: Long): Boolean =
        name.trim().take(40).takeIf { it.isNotEmpty() }?.let { n -> update(id) { it.copy(name = n, updatedAt = now) } } ?: false

    fun addStep(id: String, step: SkillStep, now: Long): Boolean = update(id) {
        if (it.steps.size >= SkillRunner.MAX_STEPS) { lastError = "一个技能最多 ${SkillRunner.MAX_STEPS} 步"; it }
        else it.copy(steps = it.steps + step.copy(id = step.id.ifBlank { UUID.randomUUID().toString() }), updatedAt = now)
    }

    fun removeStep(id: String, stepId: String, now: Long): Boolean = update(id) {
        it.copy(steps = it.steps.filterNot { s -> s.id == stepId }, updatedAt = now)
    }

    /** 上移（delta=-1）/下移（delta=1）一步。 */
    fun moveStep(id: String, stepId: String, delta: Int, now: Long): Boolean = update(id) {
        val i = it.steps.indexOfFirst { s -> s.id == stepId }
        val j = i + delta
        if (i < 0 || j !in it.steps.indices) it
        else it.copy(steps = it.steps.toMutableList().apply { add(j, removeAt(i)) }, updatedAt = now)
    }

    fun recordRun(id: String, result: SkillRunner.RunResult, now: Long): Boolean = update(id) {
        it.copy(lastRunAt = now, lastRunOk = result.ok, lastRunLog = result.log.take(40).map { l -> l.take(160) })
    }

    private fun update(id: String, change: (Skill) -> Skill?): Boolean = synchronized(lock) {
        val all = read()
        if (all.none { it.id == id }) { lastError = "找不到这个技能"; return false }
        write(all.mapNotNull { if (it.id == id) change(it) else it })
    }

    private fun read(): List<Skill> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(Skill.serializer()), file.readText()) }.getOrElse {
            val backup = File(file.parentFile, "skills.corrupt-" + System.currentTimeMillis() + ".json")
            lastError = if (file.renameTo(backup)) "技能文件损坏，已另存为 " + backup.name else "技能文件损坏"
            emptyList()
        }
    }

    private fun write(items: List<Skill>): Boolean = try {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "." + file.name + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(Skill.serializer()), items))
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw IllegalStateException("rename") }
        true
    } catch (_: Exception) { lastError = "技能写入失败"; false }
}
