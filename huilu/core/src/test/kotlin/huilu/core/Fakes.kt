package huilu.core

class FakeClock(var t: Long) : Clock {
    override fun now() = t
}

/** 按时间脚本切换前台 App 的假手机。 */
class FakePlatform(private val clock: FakeClock) : Platform {
    private val segs = mutableListOf<Pair<Long, String?>>()
    var avail = mutableSetOf(Level.NOTIFY, Level.INTERRUPT, Level.HOME, Level.BLOCK)
    var state = SensorState.OK
    private val names = mapOf(
        "course" to "网课", "bili" to "B站", "douyin" to "抖音", "wechat" to "微信",
        "launcher" to "桌面", "huilu" to "回路",
    )

    fun switchTo(pkg: String?) {
        segs.removeAll { it.first >= clock.t }
        segs += clock.t to pkg
    }

    override fun foreground(now: Long): Foreground {
        if (state != SensorState.OK) return Foreground(null, now, state)
        val idx = segs.indexOfLast { it.first <= now }
        if (idx < 0) return Foreground(null, now)
        val pkg = segs[idx].second
        var since = segs[idx].first
        var j = idx - 1
        while (j >= 0 && segs[j].second == pkg) { since = segs[j].first; j-- }
        return Foreground(pkg, since)
    }

    override fun observe(from: Long, to: Long): Observation {
        if (state != SensorState.OK) return Observation.unknown(from, to, state)
        val apps = LinkedHashMap<String, Long>()
        val first = LinkedHashMap<String, Long>()
        for ((i, s) in segs.withIndex()) {
            val pkg = s.second ?: continue
            val a = maxOf(s.first, from)
            val b = minOf(segs.getOrNull(i + 1)?.first ?: to, to)
            if (b > a) {
                apps[pkg] = (apps[pkg] ?: 0) + (b - a)
                if (pkg !in first) first[pkg] = a
            }
        }
        return Observation(from, to, apps, first)
    }

    override fun available() = avail
    override fun label(pkg: String) = names[pkg] ?: pkg
    override fun isNeutral(pkg: String) = pkg == "launcher" || pkg == "huilu"
}

/** 把引擎、假时钟、假手机放在一起，按 15 秒一步推进时间，就像前台服务那样。 */
class Sim(
    val settings: Settings = Settings(distractors = setOf("bili", "douyin")),
    val log: MemoryLog = MemoryLog(),
    val clock: FakeClock = FakeClock(T0),
    val phone: FakePlatform = FakePlatform(clock),
) {
    private var n = 0
    var engine = Engine(log, clock, phone, { settings }, { "id${n++}" })
    val effects = mutableListOf<Effect>()

    fun advance(minutes: Double) {
        val end = clock.t + (minutes * MIN).toLong()
        while (clock.t < end) {
            clock.t = minOf(clock.t + 15_000, end)
            effects += engine.tick()
        }
    }

    fun restart() {
        engine = Engine(log, clock, phone, { settings }, { "id${n++}" })
    }

    val s get() = engine.situation
    val prompts get() = effects.filterIsInstance<Effect.Intervene>()

    companion object {
        const val T0 = 1_760_000_000_000L
    }
}
