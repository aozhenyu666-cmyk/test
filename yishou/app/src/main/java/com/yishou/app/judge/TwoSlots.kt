package com.yishou.app.judge

import com.yishou.app.think.Words

/**
 * 先说要点这一步的两个槽：先说【主体/话题】是谁，再说【对策/方向】是什么。
 *
 * 为什么要拆：直接对着空白框写整句主旨，启动阻力太大——想不出第一句就退出去了。
 * 拆成两个短槽，每个只要几个词，先把「谁 + 往哪走」落下来，再拼成一句交给判卷。
 * 两个槽的位置正好也是主旨题的思维骨架：主体 + 落在主体上的动作。
 *
 * 这里只做本机的机械活：拼句子、看槽里填得够不够。像不像、对不对仍然交给判卷。
 */
data class Slots(val subject: String = "", val direction: String = "") {
    val filled get() = subject.isNotBlank() && direction.isNotBlank()
    /** 拼成一句给人读、给判卷看的话；只填了一个槽也能交 */
    fun sentence(): String = when {
        subject.isBlank() && direction.isBlank() -> ""
        subject.isBlank() -> direction.trim()
        direction.isBlank() -> subject.trim()
        else -> subject.trim() + "，要" + direction.trim()
    }.trim()
}

object TwoSlots {

    /** 主体槽至少这么多实在字：一个词起步（“生产性服务业”这种六字词是常态，两字是下限） */
    const val MIN_SUBJECT = 2

    /** 方向槽至少这么多：要求是“往哪走”，两三个字通常是“发展”“加强”这类空话，四个字起步 */
    const val MIN_DIRECTION = 4

    /**
     * 这个槽填得怎么样。返回 null 表示可以收；否则是给用户的一句话。
     * 只挡明显填不动的：空的、太短的、纯套话。
     */
    fun checkSubject(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return "先填【主体】：这段话在说谁、说什么东西。"
        if (Words.substance(t) < MIN_SUBJECT) return "主体再具体一点，写出它叫什么。"
        return null
    }

    fun checkDirection(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return "再填【对策/方向】：这个主体最后要往哪走。"
        if (Words.substance(t) < MIN_DIRECTION) return "方向写具体：不是“要发展”，是往哪个方向走。"
        if (com.yishou.app.sharp.Sharp.hollow(t) != null) return "这句是套话，换成文段里那个具体的走向。"
        return null
    }

    /**
     * 两个槽能不能交：至少填了一个、且填了的那一个过本机检查。
     * 只填一个也算交（判卷会说还缺哪一层联系）。
     */
    fun canSubmit(s: Slots): Boolean {
        if (!s.filled && s.subject.isBlank() && s.direction.isBlank()) return false
        if (s.subject.isNotBlank() && checkSubject(s.subject) != null) return false
        if (s.direction.isNotBlank() && checkDirection(s.direction) != null) return false
        return true
    }

    /** 还差哪一槽（用来在界面上拦住“交”的按钮并说明原因） */
    fun why(s: Slots): String? = when {
        s.subject.isBlank() && s.direction.isBlank() -> "两个空都还空着"
        s.subject.isNotBlank() -> checkSubject(s.subject)
        else -> checkDirection(s.direction)
    }

    /**
     * 没交够时给的一句追问：不判对错，只指出缺的那一层联系，让人补一个词再交。
     * missed：判卷说没命中的要点提示（可能为空）。
     */
    fun nudge(s: Slots, missed: List<String>): String = when {
        s.subject.isBlank() -> "先把主体补上：这段在说谁？"
        s.direction.isBlank() -> "主体有了，还差它往哪走：文段最后落在哪个动作上？"
        missed.isNotEmpty() -> "基本对了，还差一层联系：" + missed.first()
        else -> "把主体和它要干的事连起来说一遍，再交一次。"
    }
}
