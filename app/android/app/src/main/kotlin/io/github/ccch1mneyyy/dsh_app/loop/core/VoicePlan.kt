package io.github.ccch1mneyyy.dsh_app.loop.core

/**
 * B5 念出来这一条的定案：念什么、多快、多高。
 *
 * 插件在 `/result` body 的 `extra.voice` 里给「语速 / 音高 / 单条上限」——它只从情绪池的四根轴
 * 算得出来（火气高 → 语速 +15%，兴致低 → 音高 −10%），手机不该自己猜她心情。手机这边只负责
 * 把整段回复裁到第一句、套上限，剩下的（念不念、念不念得出来）在 `proactive/VoiceOut.kt`。
 *
 * 纯数据 + 纯函数，没有 Context、没有 TextToSpeech：裁字和钳位在真机上能不能念之前就该可测。
 */
data class VoicePlan(val text: String, val rate: Float, val pitch: Float) {

    companion object {
        /** 单条最多念这么多字：再长没人听完，也会盖过后面那几句。 */
        const val MAX_CHARS = 60

        /** 插件没给就是正常调子——不等于"别念"。 */
        const val DEFAULT_RATE = 1f
        const val DEFAULT_PITCH = 1f

        /**
         * TextToSpeech 收到 0 或负数会直接静音、太大会变声成花栗鼠，两端都钳住。
         * 正常区间的上限是 2.0（Android 自己也是这个量级）。
         */
        private const val MIN_RATE = 0.5f
        private const val MAX_RATE = 2f
        private const val MIN_PITCH = 0.5f
        private const val MAX_PITCH = 2f

        private val BREAKS = charArrayOf('。', '！', '？', '；', '\n', '!', '?', ';')

        /**
         * [extra] 是 `/result` body 里的 `extra`（可能没有）。返回 null 表示「这一条不念」：
         * 没给 voice、正文空的、或者第一句裁完什么都不剩——都退回纯文字。
         */
        fun of(extra: Any?, text: String): VoicePlan? {
            val voice = (extra as? Map<*, *>)?.get("voice") as? Map<*, *> ?: return null
            val body = brief(text) ?: return null
            return VoicePlan(
                body,
                (voice["rate"] as? Number)?.toFloat()?.coerceIn(MIN_RATE, MAX_RATE) ?: DEFAULT_RATE,
                (voice["pitch"] as? Number)?.toFloat()?.coerceIn(MIN_PITCH, MAX_PITCH) ?: DEFAULT_PITCH,
            )
        }

        /**
         * 要念的那一小段：不超过 [MAX_CHARS] 字，能截在句读点就截在句读点上——
         * 从"我说了多少"中间切断比少念半句更难听。
         */
        fun brief(text: String): String? {
            val t = text.trim()
            if (t.isEmpty()) return null
            if (t.length <= MAX_CHARS) return t
            for (i in 0 until MAX_CHARS) if (t[i] in BREAKS) return t.substring(0, i + 1)
            return t.substring(0, MAX_CHARS)
        }
    }
}
