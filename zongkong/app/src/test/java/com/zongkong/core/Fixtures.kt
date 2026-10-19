package com.zongkong.core

import java.time.LocalDate
import java.time.ZoneId

val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 2026-10-09（周五）某个钟点的时间戳。 */
fun at(hour: Int, minute: Int = 0, date: LocalDate = LocalDate.of(2026, 10, 9)): Long =
    date.atTime(hour, minute).atZone(ZONE).toInstant().toEpochMilli()

fun gate(
    id: String = "g",
    open: Int = 4 * 60,
    deadline: Int = 10 * 60,
    block: BlockMode = BlockMode.AFTER_DEADLINE,
    verify: VerifyMode = VerifyMode.TEXT,
    minChars: Int = 10,
    days: Set<Int> = Gate.ALL_DAYS,
) = Gate(
    id = id, dept = Dept.PLAN, title = "关卡$id", instruction = "做事", openAt = open, deadline = deadline,
    block = block, verify = verify, minChars = minChars, days = days,
)

fun config(vararg gates: Gate, silenceHours: Int = 0, quota: Int = 0) =
    Config(gates = gates.toList(), silenceHours = silenceHours, dailyQuotaMin = quota)

fun passVerdict() = Verdict(true, "字数", "ok")
