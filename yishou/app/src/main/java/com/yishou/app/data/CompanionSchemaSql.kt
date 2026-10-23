package com.yishou.app.data

/**
 * 14 → 15 的建表语句单独放一份，迁移和测试都读它。
 *
 * 为什么不用字符串写死在 Migration 里：Room 导出的 schema JSON 才是准绳，
 * 两边对不上时迁移会在真机上炸，而这种错只在升级过的手机上才看得见。
 * 放在这儿，测试能直接拿它和 schema JSON 比对列名、非空、唯一索引。
 */
object CompanionSchemaSql {

    const val SESSION_TABLE = "companion_session"
    const val TURN_TABLE = "companion_turn"

    val CREATE: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `companion_session` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, " +
            "`goal` TEXT NOT NULL, `minutes` INTEGER NOT NULL, `gear` TEXT NOT NULL, `pkg` TEXT NOT NULL, " +
            "`materialSec` INTEGER NOT NULL, `askedCount` INTEGER NOT NULL, `missedCount` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_companion_session_startedAt` ON `companion_session` (`startedAt`)",
        "CREATE TABLE IF NOT EXISTS `companion_turn` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `turnId` INTEGER NOT NULL, " +
            "`askedAt` INTEGER NOT NULL, `move` TEXT NOT NULL, `question` TEXT NOT NULL, `criteria` TEXT NOT NULL, " +
            "`evidenceIds` TEXT NOT NULL, `sourceText` TEXT NOT NULL, `answer` TEXT NOT NULL, `verdict` TEXT NOT NULL, " +
            "`delta` TEXT NOT NULL, `answeredAt` INTEGER, `dedupeKey` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `imagePath` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_companion_turn_sessionId` ON `companion_turn` (`sessionId`)",
        "CREATE INDEX IF NOT EXISTS `index_companion_turn_askedAt` ON `companion_turn` (`askedAt`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_companion_turn_dedupeKey` ON `companion_turn` (`dedupeKey`)",
    )
}
