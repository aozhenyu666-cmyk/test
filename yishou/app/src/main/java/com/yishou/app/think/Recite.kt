package com.yishou.app.think

import com.yishou.app.data.DrillRow

/**
 * 精简记忆（背诵）：看一段时间 → 一手把原文收起来 → 你复述 → 本机逐字对照。
 * 对照不经过模型：按顺序能对上的字算“记住了”，漏掉的标红，多出来的标灰。标点和空格不算。
 */
object Recite {

    enum class Op { SAME, MISS, EXTRA, MARK }

    data class Piece(val text: String, val op: Op)

    data class Result(val pieces: List<Piece>, val matched: Int, val total: Int, val said: Int) {
        /** 0–100：对上的字 ÷ 原文和复述里较长的那个（多说的也扣分） */
        val score: Int get() = if (total == 0) 0 else matched * 100 / maxOf(total, said)
    }

    private fun isMark(c: Char) = !c.isLetterOrDigit()

    fun diff(original: String, said: String): Result {
        val a = original.filterNot(::isMark).lowercase()
        val b = said.filterNot(::isMark).lowercase()
        // 最长公共子序列
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in a.length - 1 downTo 0) for (j in b.length - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        // 走一遍，按原文顺序标出对上、漏掉，以及中间多说的
        val ops = mutableListOf<Pair<Char, Op>>()
        var i = 0
        var j = 0
        while (i < a.length || j < b.length) {
            when {
                i < a.length && j < b.length && a[i] == b[j] -> { ops += a[i] to Op.SAME; i++; j++ }
                j < b.length && (i == a.length || dp[i][j + 1] >= dp[i + 1][j]) -> { ops += b[j] to Op.EXTRA; j++ }
                else -> { ops += a[i] to Op.MISS; i++ }
            }
        }
        // 把原文的标点放回去（显示用），保持原文的样子
        val pieces = mutableListOf<Piece>()
        fun add(c: Char, op: Op) {
            val last = pieces.lastOrNull()
            if (last != null && last.op == op) pieces[pieces.lastIndex] = Piece(last.text + c, op) else pieces += Piece(c.toString(), op)
        }
        var k = 0
        for (c in original) {
            if (isMark(c)) {
                add(c, Op.MARK)
                continue
            }
            while (k < ops.size && ops[k].second == Op.EXTRA) {
                add(ops[k].first, Op.EXTRA)
                k++
            }
            if (k < ops.size) {
                add(c, ops[k].second)
                k++
            }
        }
        while (k < ops.size) {
            add(ops[k].first, Op.EXTRA)
            k++
        }
        return Result(pieces, dp[0][0], a.length, b.length)
    }

    /** 看多久：每个字 0.6 秒，最少 15 秒，最多 2 分钟 */
    fun readSeconds(text: String): Int = (text.count { !isMark(it) } * 0.6).toInt().coerceIn(15, 120)

    /**
     * 提示复述用的骨架：每句留开头两个字，其余换成“＿”，标点保留。
     * 想不起来时看一眼骨架，比看原文强。
     */
    fun skeleton(text: String): String {
        val sb = StringBuilder()
        var inSentence = 0
        for (c in text) {
            if (isMark(c)) {
                sb.append(c)
                inSentence = 0
            } else {
                sb.append(if (inSentence < 2) c else '＿')
                inSentence++
            }
        }
        return sb.toString()
    }

    /** 一段长素材切成好背的几小段（按句子攒，到 max 字左右断开） */
    fun chunks(text: String, max: Int = 90): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (s in com.yishou.app.card.Sentences.split(text)) {
            if (sb.isNotEmpty() && sb.length + s.length > max) {
                out += sb.toString()
                sb.clear()
            }
            sb.append(s)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** 间隔：连续背好（≥90 分）几次，下一次就隔几天 */
    val INTERVALS = listOf(1, 2, 4, 7, 15, 30)
    private const val DAY = 86_400_000L

    data class Due(val title: String, val text: String, val lastScore: Int, val lastAt: Long)

    /** 到期该再背的段落（按到期先后） */
    fun due(rows: List<DrillRow>, now: Long): List<Due> = rows
        .filter { it.lens == Lens.RECITE.name && !it.disputed }
        .mapNotNull { r -> runCatching { DrillKey.fromJson(r.key) }.getOrNull()?.let { k -> Triple(r, k.prompt, k.points.firstOrNull().orEmpty()) } }
        .filter { it.third.isNotEmpty() }
        .groupBy { Words.norm(it.third) }
        .mapNotNull { (_, xs) ->
            val sorted = xs.sortedBy { it.first.createdAt }
            val last = sorted.last().first
            val streak = sorted.reversed().takeWhile { it.first.score >= 90 }.size
            val wait = if (streak == 0) DAY / 2 else INTERVALS[(streak - 1).coerceAtMost(INTERVALS.lastIndex)] * DAY
            if (now - last.createdAt >= wait) Due(sorted.last().second, sorted.last().third, last.score, last.createdAt) else null
        }
        .sortedBy { it.lastAt }
}

/** 自带的几段背诵材料（都是公版古诗文），素材库空着时也能练。 */
object Classics {
    data class Piece(val title: String, val text: String)

    val ALL = listOf(
        Piece("静夜思 · 李白", "床前明月光，疑是地上霜。举头望明月，低头思故乡。"),
        Piece("春晓 · 孟浩然", "春眠不觉晓，处处闻啼鸟。夜来风雨声，花落知多少。"),
        Piece("登鹳雀楼 · 王之涣", "白日依山尽，黄河入海流。欲穷千里目，更上一层楼。"),
        Piece("江雪 · 柳宗元", "千山鸟飞绝，万径人踪灭。孤舟蓑笠翁，独钓寒江雪。"),
        Piece("出塞 · 王昌龄", "秦时明月汉时关，万里长征人未还。但使龙城飞将在，不教胡马度阴山。"),
        Piece("早发白帝城 · 李白", "朝辞白帝彩云间，千里江陵一日还。两岸猿声啼不住，轻舟已过万重山。"),
        Piece("枫桥夜泊 · 张继", "月落乌啼霜满天，江枫渔火对愁眠。姑苏城外寒山寺，夜半钟声到客船。"),
        Piece("春望 · 杜甫", "国破山河在，城春草木深。感时花溅泪，恨别鸟惊心。烽火连三月，家书抵万金。白头搔更短，浑欲不胜簪。"),
        Piece("登高 · 杜甫", "风急天高猿啸哀，渚清沙白鸟飞回。无边落木萧萧下，不尽长江滚滚来。万里悲秋常作客，百年多病独登台。艰难苦恨繁霜鬓，潦倒新停浊酒杯。"),
        Piece("论语 · 学而", "子曰：学而时习之，不亦说乎？有朋自远方来，不亦乐乎？人不知而不愠，不亦君子乎？"),
        Piece("论语 · 为政", "子曰：学而不思则罔，思而不学则殆。"),
        Piece("论语 · 为政", "子曰：温故而知新，可以为师矣。"),
    )
}
