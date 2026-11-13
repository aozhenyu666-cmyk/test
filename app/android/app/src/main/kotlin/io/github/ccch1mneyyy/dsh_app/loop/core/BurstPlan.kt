package io.github.ccch1mneyyy.dsh_app.loop.core

/**
 * B4 连发的手机侧计划。
 *
 * 插件在 `/result` body 的 `extra.burst` 里给出「这一轮发几条、每条多长、间隔多久」，这里把它变成
 * 真的能按时间发出去的分段：第一段立刻上屏，第 2 段起按 [delaysMs] 依次补进同一个浮层。
 * 连发不是让模型一次生成一长段，而是同一句话被排成几条说完。
 *
 * 纯数据 + 纯函数，没有 Handler 也没有窗口——按 [delaysMs] 真投递在 `ProactiveRuntime`。
 * 情绪不能影响锁、额度、判定，所以这里也不参与任何判定，只决定「怎么把这句说完」。
 */
data class BurstPlan(val segments: List<String>, val delaysMs: List<Long>) {

    companion object {
        /** 一轮最多几条：再多就不像说话了。 */
        const val MAX_SEGMENTS = 3
        const val DEFAULT_DELAY_MS = 4_000L

        private const val MIN_CHARS = 12
        private const val DEFAULT_CHARS = 60
        private const val MAX_CHARS = 200
        private const val MAX_DELAY_MS = 30_000L
        private val BREAKS = charArrayOf('。', '！', '？', '；', '\n', '!', '?', ';')

        /**
         * [extra] 是 `/result` body 里的 `extra`（可能没有）。返回 null 表示「这一轮只发一条」：
         * 没有 burst、插件说要发一条、或者正文本来就切不出两段——都走原来的一次性投递。
         */
        fun of(extra: Any?, text: String): BurstPlan? {
            val burst = (extra as? Map<*, *>)?.get("burst") as? Map<*, *> ?: return null
            val want = (burst["burst"] as? Number)?.toInt()?.coerceIn(1, MAX_SEGMENTS) ?: return null
            if (want < 2) return null
            // channel 不拦连发：`voice` 的意思是"这几条还要念出来"，念在 B5，没接之前照样分段上屏。
            // 按 channel 拦会让火气最高的那几档（hot/blaze/eager 都是 voice）永远不连发，正好反了。
            val maxChars = (burst["maxChars"] as? Number)?.toInt()?.coerceIn(MIN_CHARS, MAX_CHARS) ?: DEFAULT_CHARS
            val segments = split(text, want, maxChars)
            if (segments.size < 2) return null
            val given = (burst["delayMs"] as? List<*>).orEmpty()
                .mapNotNull { (it as? Number)?.toLong()?.coerceIn(0, MAX_DELAY_MS) }
            return BurstPlan(segments, List(segments.size - 1) { given.getOrElse(it) { DEFAULT_DELAY_MS } })
        }

        /**
         * 按句读点切成正好 [want] 段（素材不够就少于 [want]）：每段尽量不超过 [maxChars]，
         * 但**至少保住一段的话**，宁可一段超长也不把一句话从中截断。
         */
        private fun split(text: String, want: Int, maxChars: Int): List<String> {
            val pieces = pieces(text)
            if (pieces.isEmpty()) return emptyList()
            val out = ArrayList<String>(want)
            var i = 0
            while (i < pieces.size && out.size < want) {
                val groupsLeft = want - out.size
                // 剩下的句子刚好够一段一句：不用再合并。
                if (pieces.size - i <= groupsLeft) {
                    out.add(pieces[i])
                    i++
                    continue
                }
                // 贪心装满 [maxChars]，但要给后面几段各留至少一句。
                var j = i
                var len = 0
                while (j < pieces.size - (groupsLeft - 1) &&
                    (j == i || len + pieces[j].length <= maxChars)
                ) {
                    len += pieces[j].length
                    j++
                }
                out.add(pieces.subList(i, j).joinToString(""))
                i = j
            }
            // 走不到：循环只在 out.size == want 或句子用尽时停。
            if (i < pieces.size) out[out.size - 1] = out[out.size - 1] + pieces.subList(i, pieces.size).joinToString("")
            return out
        }

        /**
         * 小句，句读点留在句尾——切完再拼回去时标点不会跑到下一句开头。
         * 整句只有标点/空白的丢掉：那不是"一句"，拿它去凑连发只会发出几个「。」。
         */
        private fun pieces(text: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            for (ch in text) {
                cur.append(ch)
                if (ch in BREAKS) {
                    out.add(cur.toString())
                    cur.setLength(0)
                }
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out.map { it.trim() }
                .filter { piece -> piece.any { it !in BREAKS && !it.isWhitespace() } }
        }
    }
}
