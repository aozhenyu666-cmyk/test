package cn.companion.pet.core.voice

/**
 * 手里攒着想说的话。
 *
 * [source] 是**开放字符串**，不是固定枚举：今天只有 `own_life` / `diary` / `rule`，
 * 以后加 Notion 页面、加任何一个新来源，都只是传一个新字符串，
 * 主链路（入队 / 挑选 / 划掉）一行都不用改。**新增来源不该需要修改本文件。**
 */
data class VoiceIntent(
    val id: String,
    /** 来源标签，开放结构，见类注释。 */
    val source: String,
    /** 想说的那句话本身。 */
    val summary: String,
    /** 支撑这句话的证据 id（活动 id、事件 id 等），用于溯源与去重，可为空。 */
    val sourceIds: List<String> = emptyList(),
    val createdAt: Long = 0L,
    /**
     * 过期时刻：太旧的话就不值得说了。
     *
     * 必填、且默认值刻意设为「已过期」而不是 `Long.MAX_VALUE`：
     * 直接构造时忘记填，宁可它立刻失效（fail-closed），也不要它永不失效（fail-open）。
     * 正常入队都走 [VoiceIntentQueue.enqueue]，那里一定会按 TTL 算出一个真实时刻。
     */
    val expiresAt: Long,
    /** 说过就写时间；非 null 即不可再被挑中。 */
    val spokenAt: Long? = null,
)
