package cn.companion.pet.core.companion

import cn.companion.pet.core.memory.MemoryStore
import java.io.File
import java.time.LocalTime
import java.time.ZoneId

/**
 * 把旧的 today.md 一次性迁移成事件日志。原则：
 *
 * 1. 迁移前先备份原文件，迁完不删除 today.md，旧 Markdown 仍可阅读/编辑；
 * 2. 事件 id 由日期和行号确定，重复执行不会产生重复事件；
 * 3. 先迁移再写完成标记；标记存在后不再扫描，避免每次启动都读旧文件。
 *
 * 旧流水没有稳定的发生时间和类型，只能保守地标成 [EventKind.LEGACY_LINE]，
 * 发生时间取当天中午，day 取文件头的日期。不能假装知道精确时间。
 */
object LegacyEventImport {
    const val VERSION = "events-v1"

    fun ensure(memory: MemoryStore, log: EventLog, zone: ZoneId = log.zone): Int {
        val migrations = File(memory.root, "migrations")
        migrations.mkdirs()
        val marker = File(migrations, VERSION + ".done")
        if (marker.exists()) return 0

        val day = memory.todayDate()
        val lines = memory.todayLines()
        if (day == null || lines.isEmpty()) {
            marker.writeText("imported=0\n")
            return 0
        }

        val backup = File(migrations, "legacy-today-" + day + ".md")
        if (!backup.exists()) backup.writeText(memory.todayFile.readText())

        val existingIds = log.all().map { it.id }.toSet()
        val occurredAt = day.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        var imported = 0
        lines.forEachIndexed { index, line ->
            val id = "legacy-" + day + "-" + index
            if (id !in existingIds) {
                log.appendEvent(
                    CompanionEvent(
                        id = id,
                        occurredAt = occurredAt,
                        day = day.toString(),
                        kind = EventKind.LEGACY_LINE,
                        text = line,
                        source = "legacy_today",
                    ),
                )
                imported += 1
            }
        }
        marker.writeText("imported=" + imported + "\nday=" + day + "\n")
        return imported
    }
}
