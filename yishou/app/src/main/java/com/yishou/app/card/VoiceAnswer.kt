package com.yishou.app.card

/**
 * 把口语化的语音回答拆成“选项、把握、依据句、错因”。
 * 例如“我选 B，挺有把握的，依据第三句”“D 太绝对了”“三和四两句”。
 */
object VoiceAnswer {
    data class Parsed(
        val option: Int? = null,
        val conf: Conf? = null,
        val sentences: Set<Int> = emptySet(),
        val flaw: String? = null,
        /** “不改”“还是这个” */
        val keep: Boolean = false,
    )

    private val CN = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10)

    private val FLAW_WORDS = listOf(
        Flaws.NONE to listOf("没毛病", "没问题", "是对的", "正确的", "没错"),
        "与原文相反" to listOf("与原文相反", "说反了", "反了", "相反"),
        "无中生有" to listOf("无中生有", "没提到", "没有提到", "原文没有", "没说"),
        "偷换概念" to listOf("偷换概念", "偷换", "换概念"),
        "范围扩大" to listOf("范围扩大", "扩大", "范围大了", "以偏概全"),
        "绝对化" to listOf("绝对化", "太绝对", "绝对", "说死了"),
        "因果倒置" to listOf("因果倒置", "因果反", "倒因为果"),
        "推理方向错" to listOf("推理方向错", "方向错", "方向反", "肯定后件", "否定前件"),
        "结构不同" to listOf("结构不同", "结构不一样", "结构不像"),
        "答非所问" to listOf("答非所问", "不相关", "无关"),
    )

    fun parse(raw: String): Parsed {
        val t = raw.replace(" ", "").replace("，", ",").replace("。", ",")
        return Parsed(
            option = option(t),
            conf = conf(t),
            sentences = sentences(t),
            flaw = FLAW_WORDS.firstOrNull { (_, words) -> words.any { it in t } }?.first,
            keep = listOf("不改", "不换", "还是这个", "还选", "保持").any { it in t },
        )
    }

    private fun option(t: String): Int? {
        // 拉丁字母：前后不能紧挨着别的字母（避免把英文单词里的字母当选项）
        Regex("(?<![A-Za-z])([ABCDabcd])(?![A-Za-z])").find(t)?.let { return Card.index(it.groupValues[1]) }
        Regex("第([一二三四1234])(个|项|个选项)").find(t)?.let { m ->
            val c = m.groupValues[1][0]
            return ((CN[c] ?: c.digitToIntOrNull()) ?: return null) - 1
        }
        return null
    }

    private fun conf(t: String): Conf? {
        // 先认“低”：“不确定”里也有“确定”
        if (listOf("不确定", "没把握", "不太确定", "瞎猜", "猜的", "把握低", "低把握").any { it in t }) return Conf.LOW
        if (listOf("把握中", "中等", "把握一般", "一般", "还行", "有点把握").any { it in t }) return Conf.MID
        if (listOf("把握高", "高把握", "很确定", "非常确定", "有把握", "确定", "肯定").any { it in t }) return Conf.HIGH
        // “B，高”“B高”这种只说一个字的
        val single = t.split(',').map { it.trim() } + listOfNotNull(Regex("[ABCDabcd]([高中低])").find(t)?.groupValues?.get(1))
        return when {
            "高" in single -> Conf.HIGH
            "中" in single -> Conf.MID
            "低" in single -> Conf.LOW
            else -> null
        }
    }

    private fun sentences(t: String): Set<Int> {
        val out = mutableSetOf<Int>()
        "①②③④⑤⑥⑦⑧⑨⑩".forEachIndexed { i, c -> if (c in t) out += i + 1 }
        // “第三句”“第3句”“三和四句”“第三、四句”
        // “两”只当“两句”的量词，不当序号
        Regex("第?([一二三四五六七八九十0-9]+(?:[和、,跟与][一二三四五六七八九十0-9]+)*)两?句").findAll(t).forEach { m ->
            m.groupValues[1].split('和', '、', ',', '跟', '与').forEach { part -> num(part)?.let { out += it } }
        }
        return out
    }

    private fun num(s: String): Int? {
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it }
        if (s.length == 1) return CN[s[0]]
        if (s.length == 2 && s[0] == '十') return 10 + (CN[s[1]] ?: return null)
        return null
    }
}
