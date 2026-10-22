package com.zongkong.app.data

import android.content.Context
import android.util.Log
import com.zongkong.core.Config
import com.zongkong.core.DayClock
import com.zongkong.core.DayLog
import com.zongkong.core.Engine
import com.zongkong.core.Policy
import com.zongkong.core.Status
import com.zongkong.core.ZkJson
import com.zongkong.core.FocusLock
import com.zongkong.core.work.Work
import com.zongkong.core.work.WorkRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * 本机存储：config.json 一份配置，days/yyyy-MM-dd.json 每天一份记录。
 * 修改在内存里同步完成（加锁），写文件放到后台单线程，写入时先写临时文件再改名，避免写坏。
 */
class Store(private val context: Context) {
    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor()
    private val dir = File(context.filesDir, "zk").apply { mkdirs() }
    private val daysDir = File(dir, "days").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("zk", Context.MODE_PRIVATE)

    /** 北京时间。 */
    val zone: ZoneId get() = DayClock.BEIJING

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<Config> = _config.asStateFlow()

    private val _today = MutableStateFlow(loadDay(DayClock.dateKey(System.currentTimeMillis(), zone)))
    val today: StateFlow<DayLog> = _today.asStateFlow()

    // ---------- 事项、行动、记录（和 Notion 双向同步的那部分） ----------

    private val workLock = Any()
    private val _work = MutableStateFlow(loadWork())
    val work: StateFlow<Work> = _work.asStateFlow()

    /** 原子地改 Work。同步引擎和界面都通过这里改，互不覆盖。 */
    fun updateWork(f: (Work) -> Work): Work = synchronized(workLock) {
        val next = f(_work.value)
        if (next != _work.value) {
            _work.value = next
            val text = ZkJson.encodeToString(Work.serializer(), next)
            io.execute { writeAtomic(File(dir, "work.json"), text) }
        }
        next
    }

    val workRef: WorkRef = object : WorkRef {
        override fun get(): Work = _work.value
        override fun update(f: (Work) -> Work): Work = updateWork(f)
    }

    private fun loadWork(): Work {
        val f = File(dir, "work.json")
        if (!f.exists()) return Work()
        return try {
            ZkJson.decodeFromString(Work.serializer(), f.readText())
        } catch (e: Exception) {
            Log.e(TAG, "work.json 读不了，备份后从空开始", e)
            f.copyTo(File(dir, "work.broken.${System.currentTimeMillis()}.json"), overwrite = true)
            Work()
        }
    }

    // ---------- 计划 ----------

    private val _plans = MutableStateFlow(loadPlans())
    val plans: StateFlow<com.zongkong.core.plan.PlanBook> = _plans.asStateFlow()

    fun updatePlans(f: (com.zongkong.core.plan.PlanBook) -> com.zongkong.core.plan.PlanBook): com.zongkong.core.plan.PlanBook = synchronized(workLock) {
        val next = f(_plans.value)
        if (next != _plans.value) {
            _plans.value = next
            val text = ZkJson.encodeToString(com.zongkong.core.plan.PlanBook.serializer(), next)
            io.execute { writeAtomic(File(dir, "plans.json"), text) }
        }
        next
    }

    private fun loadPlans(): com.zongkong.core.plan.PlanBook {
        val f = File(dir, "plans.json")
        if (!f.exists()) return com.zongkong.core.plan.PlanBook()
        return try {
            ZkJson.decodeFromString(com.zongkong.core.plan.PlanBook.serializer(), f.readText())
        } catch (e: Exception) {
            Log.e(TAG, "plans.json 读不了，备份后从空开始", e)
            f.copyTo(File(dir, "plans.broken.${System.currentTimeMillis()}.json"), overwrite = true)
            com.zongkong.core.plan.PlanBook()
        }
    }

    // ---------- 电脑（手机 ↔ 电脑互通） ----------

    private val _device = MutableStateFlow(loadDevice())
    val device: StateFlow<com.zongkong.core.device.DeviceBook> = _device.asStateFlow()

    fun updateDevice(f: (com.zongkong.core.device.DeviceBook) -> com.zongkong.core.device.DeviceBook): com.zongkong.core.device.DeviceBook = synchronized(workLock) {
        val next = f(_device.value)
        if (next != _device.value) {
            _device.value = next
            val text = ZkJson.encodeToString(com.zongkong.core.device.DeviceBook.serializer(), next)
            io.execute { writeAtomic(File(dir, "device.json"), text) }
        }
        next
    }

    private fun loadDevice(): com.zongkong.core.device.DeviceBook {
        val f = File(dir, "device.json")
        if (!f.exists()) return com.zongkong.core.device.DeviceBook()
        return try {
            ZkJson.decodeFromString(com.zongkong.core.device.DeviceBook.serializer(), f.readText())
        } catch (e: Exception) {
            Log.e(TAG, "device.json 读不了", e)
            com.zongkong.core.device.DeviceBook()
        }
    }

    // ---------- 总控时间 ----------

    private val _hq = MutableStateFlow(loadHq())
    val hq: StateFlow<com.zongkong.core.hq.HqBook> = _hq.asStateFlow()

    fun updateHq(f: (com.zongkong.core.hq.HqBook) -> com.zongkong.core.hq.HqBook): com.zongkong.core.hq.HqBook = synchronized(workLock) {
        val next = f(_hq.value)
        if (next != _hq.value) {
            _hq.value = next
            val text = ZkJson.encodeToString(com.zongkong.core.hq.HqBook.serializer(), next)
            io.execute { writeAtomic(File(dir, "hq.json"), text) }
        }
        next
    }

    private fun loadHq(): com.zongkong.core.hq.HqBook {
        val f = File(dir, "hq.json")
        if (!f.exists()) return com.zongkong.core.hq.HqBook()
        return try {
            ZkJson.decodeFromString(com.zongkong.core.hq.HqBook.serializer(), f.readText())
        } catch (e: Exception) {
            Log.e(TAG, "hq.json 读不了", e)
            com.zongkong.core.hq.HqBook()
        }
    }

    // ---------- 总控者的档案 ----------

    private val _dossier = MutableStateFlow(loadDossier())
    val dossier: StateFlow<com.zongkong.core.boss.Dossier> = _dossier.asStateFlow()

    fun updateDossier(f: (com.zongkong.core.boss.Dossier) -> com.zongkong.core.boss.Dossier): com.zongkong.core.boss.Dossier = synchronized(workLock) {
        val next = f(_dossier.value)
        if (next != _dossier.value) {
            _dossier.value = next
            val text = ZkJson.encodeToString(com.zongkong.core.boss.Dossier.serializer(), next)
            io.execute { writeAtomic(File(dir, "dossier.json"), text) }
        }
        next
    }

    private fun loadDossier(): com.zongkong.core.boss.Dossier {
        val f = File(dir, "dossier.json")
        if (!f.exists()) return com.zongkong.core.boss.Dossier()
        return try {
            ZkJson.decodeFromString(com.zongkong.core.boss.Dossier.serializer(), f.readText())
        } catch (e: Exception) {
            Log.e(TAG, "dossier.json 读不了", e)
            com.zongkong.core.boss.Dossier()
        }
    }

    /** 现在的信用和态度。 */
    fun credibility(now: Long = System.currentTimeMillis()): Int = com.zongkong.core.boss.Boss.credibility(_dossier.value, _hq.value.sessions, now, _work.value.events)
    fun stance(now: Long = System.currentTimeMillis()): com.zongkong.core.boss.Stance = com.zongkong.core.boss.Boss.stance(_dossier.value, _hq.value.sessions, now, _work.value.events)

    // ---------- 脉络（谋划思考部的知识结构） ----------

    private val _vein = MutableStateFlow(loadVein())
    val vein: StateFlow<com.zongkong.core.vein.Vein> = _vein.asStateFlow()

    fun updateVein(f: (com.zongkong.core.vein.Vein) -> com.zongkong.core.vein.Vein): com.zongkong.core.vein.Vein = synchronized(workLock) {
        val next = f(_vein.value)
        if (next != _vein.value) {
            _vein.value = next
            val text = ZkJson.encodeToString(com.zongkong.core.vein.Vein.serializer(), next)
            io.execute { writeAtomic(File(dir, "vein.json"), text) }
        }
        next
    }

    private fun loadVein(): com.zongkong.core.vein.Vein {
        val f = File(dir, "vein.json")
        if (!f.exists()) return com.zongkong.core.vein.Vein()
        return try {
            ZkJson.decodeFromString(com.zongkong.core.vein.Vein.serializer(), f.readText())
        } catch (e: Exception) {
            Log.e(TAG, "vein.json 读不了", e)
            com.zongkong.core.vein.Vein()
        }
    }

    // ---------- 信息收集部的漏斗 ----------

    private val _intake = MutableStateFlow(loadJson("intake.json", com.zongkong.core.info.IntakeBook.serializer()) ?: com.zongkong.core.info.IntakeBook())
    val intake: StateFlow<com.zongkong.core.info.IntakeBook> = _intake.asStateFlow()

    fun updateIntake(f: (com.zongkong.core.info.IntakeBook) -> com.zongkong.core.info.IntakeBook): com.zongkong.core.info.IntakeBook = synchronized(workLock) {
        val next = f(_intake.value)
        if (next != _intake.value) {
            _intake.value = next
            val text = ZkJson.encodeToString(com.zongkong.core.info.IntakeBook.serializer(), next)
            io.execute { writeAtomic(File(dir, "intake.json"), text) }
        }
        next
    }

    // ---------- 强督导与缓行 ----------

    private val _control = MutableStateFlow(loadJson("control.json", com.zongkong.core.control.ControlBook.serializer()) ?: com.zongkong.core.control.ControlBook())
    val control: StateFlow<com.zongkong.core.control.ControlBook> = _control.asStateFlow()

    fun updateControl(f: (com.zongkong.core.control.ControlBook) -> com.zongkong.core.control.ControlBook): com.zongkong.core.control.ControlBook = synchronized(workLock) {
        val next = f(_control.value)
        if (next != _control.value) {
            _control.value = next
            val text = ZkJson.encodeToString(com.zongkong.core.control.ControlBook.serializer(), next)
            io.execute { writeAtomic(File(dir, "control.json"), text) }
        }
        next
    }

    // ---------- 账本（试验） ----------

    private val _ledger = MutableStateFlow(loadJson("ledger.json", com.zongkong.core.ledger.LedgerBook.serializer()) ?: com.zongkong.core.ledger.LedgerBook())
    val ledger: StateFlow<com.zongkong.core.ledger.LedgerBook> = _ledger.asStateFlow()

    fun updateLedger(f: (com.zongkong.core.ledger.LedgerBook) -> com.zongkong.core.ledger.LedgerBook): com.zongkong.core.ledger.LedgerBook = synchronized(workLock) {
        val next = f(_ledger.value)
        if (next != _ledger.value) {
            _ledger.value = next
            val text = ZkJson.encodeToString(com.zongkong.core.ledger.LedgerBook.serializer(), next)
            io.execute { writeAtomic(File(dir, "ledger.json"), text) }
        }
        next
    }

    // ---------- 墨灵的建议（墨灵交来、等你确认的指令） ----------

    private val _moling = MutableStateFlow(loadJson("moling.json", com.zongkong.core.moling.MolingBook.serializer()) ?: com.zongkong.core.moling.MolingBook())
    val moling: StateFlow<com.zongkong.core.moling.MolingBook> = _moling.asStateFlow()

    fun updateMoling(f: (com.zongkong.core.moling.MolingBook) -> com.zongkong.core.moling.MolingBook): com.zongkong.core.moling.MolingBook = synchronized(workLock) {
        val next = f(_moling.value)
        if (next != _moling.value) {
            _moling.value = next
            val text = ZkJson.encodeToString(com.zongkong.core.moling.MolingBook.serializer(), next)
            io.execute { writeAtomic(File(dir, "moling.json"), text) }
        }
        next
    }

    // 以思代锁：训练换来的娱乐分钟账本（发过的题、回执、通行）。单独一份文件，不进 Config / 备份字段。
    private val _training = MutableStateFlow(loadJson("training.json", com.zongkong.core.moling.TrainingBook.serializer()) ?: com.zongkong.core.moling.TrainingBook())
    val training: StateFlow<com.zongkong.core.moling.TrainingBook> = _training.asStateFlow()

    fun updateTraining(f: (com.zongkong.core.moling.TrainingBook) -> com.zongkong.core.moling.TrainingBook): com.zongkong.core.moling.TrainingBook = synchronized(workLock) {
        val next = f(_training.value)
        if (next != _training.value) {
            _training.value = next
            val text = ZkJson.encodeToString(com.zongkong.core.moling.TrainingBook.serializer(), next)
            io.execute { writeAtomic(File(dir, "training.json"), text) }
        }
        next
    }

    private val trainingRng = java.security.SecureRandom()

    /** 墨灵的 v2 请求（MolingProvider 验过签名后调用）：同一套规则、同一本账。 */
    fun trainingCall(method: String, request: Map<String, String>, now: Long = System.currentTimeMillis()): Map<String, String> {
        val status = status(now)
        var resp: Map<String, String> = emptyMap()
        updateTraining { b ->
            val (next, r) = com.zongkong.core.moling.TrainingWire.handle(method, request, _config.value.training, b, status, now, zone, trainingRng)
            resp = r
            next
        }
        return resp
    }

    /** 总控自己的拦截页「做一局训练」：发题（返回的题带答案，只给总控自己的界面判分前保存，界面上不要显示 answer）。 */
    fun trainingIssue(pkg: String, now: Long = System.currentTimeMillis()): com.zongkong.core.moling.TrainingOutcome<com.zongkong.core.moling.TrainingChallenge> {
        val status = status(now)
        var out: com.zongkong.core.moling.TrainingOutcome<com.zongkong.core.moling.TrainingChallenge>? = null
        updateTraining { b ->
            com.zongkong.core.moling.Training.issue(_config.value.training, b, status, pkg, "zk-" + now.toString(36), now, zone, trainingRng).also { out = it }.book
        }
        return out!!
    }

    /** 总控自己的拦截页交卷。[answers] = 题号 → 选项 id。 */
    fun trainingSubmit(challengeId: String, nonce: String, answers: Map<String, String>, now: Long = System.currentTimeMillis()): com.zongkong.core.moling.TrainingReceipt {
        val status = status(now)
        var out: com.zongkong.core.moling.TrainingReceipt? = null
        updateTraining { b ->
            com.zongkong.core.moling.Training.submit(_config.value.training, b, status, "zk-submit-$challengeId", challengeId, nonce, answers, now, zone).also { out = it.value }.book
        }
        return out!!
    }

    private fun <T> loadJson(name: String, ser: kotlinx.serialization.KSerializer<T>): T? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return try {
            ZkJson.decodeFromString(ser, f.readText())
        } catch (e: Exception) {
            Log.e(TAG, "$name 读不了", e)
            null
        }
    }

    /** 今天（北京时间的自然日），脉络的日期都用它。 */
    fun dateToday(now: Long = System.currentTimeMillis()): java.time.LocalDate = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    // ---------- 设备时间线（无障碍服务自己记的：前台应用切换、亮屏熄屏） ----------

    private val timelineDir = File(dir, "timeline").apply { mkdirs() }

    /** 记一条：从 [at] 起前台是 [pkg]（Timeline.OFF / ON / GAP 是特殊值）。按自然日分文件，留 8 天。 */
    fun logSwitch(at: Long, pkg: String) {
        val day = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
        io.execute {
            try {
                File(timelineDir, "$day.txt").appendText("$at $pkg\n")
            } catch (e: Exception) {
                Log.w(TAG, "写时间线失败", e)
            }
        }
    }

    /** [from, to] 之间的记录，外加 from 之前最后一条（知道开始时前台是什么）。 */
    fun switches(from: Long, to: Long): List<com.zongkong.core.boss.DevEvent> {
        val start = java.time.Instant.ofEpochMilli(from).atZone(zone).toLocalDate().minusDays(1)
        val end = java.time.Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        val all = mutableListOf<com.zongkong.core.boss.DevEvent>()
        var d = start
        while (!d.isAfter(end)) {
            val f = File(timelineDir, "$d.txt")
            if (f.exists()) f.readLines().forEach { line ->
                val sp = line.indexOf(' ')
                if (sp > 0) line.substring(0, sp).toLongOrNull()?.let { all += com.zongkong.core.boss.DevEvent(it, line.substring(sp + 1).trim()) }
            }
            d = d.plusDays(1)
        }
        val before = all.filter { it.at <= from }.maxByOrNull { it.at }
        return listOfNotNull(before) + all.filter { it.at in (from + 1)..to }.sortedBy { it.at }
    }

    /** 删掉 8 天前的时间线和过期的截图。 */
    fun prune(now: Long = System.currentTimeMillis()) {
        io.execute {
            val keep = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(8).toString()
            timelineDir.listFiles()?.filter { it.name.removeSuffix(".txt") < keep }?.forEach { it.delete() }
            val cutoff = now - _config.value.boss.keepDays.coerceAtLeast(1) * 86_400_000L
            shotsDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    // ---------- 抽查截图 ----------

    val shotsDir: File = File(context.filesDir, "shots").apply { mkdirs() }

    /** 截图文件名：时间_包名.jpg。 */
    fun shots(from: Long, to: Long): List<com.zongkong.core.boss.Shot> =
        (shotsDir.listFiles() ?: emptyArray()).mapNotNull { f ->
            val name = f.name.removeSuffix(".jpg")
            val at = name.substringBefore('_').toLongOrNull() ?: return@mapNotNull null
            if (at !in from..to) null else com.zongkong.core.boss.Shot(at.toString(36), at, name.substringAfter('_', ""), f.absolutePath)
        }.sortedBy { it.at }

    /** 上一次真正和总控交流的时刻（说话、做事、交关卡、做完总控时间）。第一次打开时记为现在。 */
    var lastContact: Long
        get() = prefs.getLong("last_contact", 0L).let { if (it == 0L) System.currentTimeMillis().also { now -> prefs.edit().putLong("last_contact", now).apply() } else it }
        set(v) = prefs.edit().putLong("last_contact", v).apply()

    fun touch() {
        lastContact = System.currentTimeMillis()
    }

    fun absence(now: Long = System.currentTimeMillis()): com.zongkong.core.hq.Hq.Absence =
        com.zongkong.core.hq.Hq.absence(lastContact, now, _config.value.hq)

    /** 叫醒记录：总控时间提醒发没发、为什么没进去（设置页里能看到，排查“到点没反应”）。 */
    val wakeLog: List<String>
        get() = (prefs.getString("wake_log", "") ?: "").split('\n').filter { it.isNotBlank() }

    fun logWake(text: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val t = java.time.Instant.ofEpochMilli(now).atZone(zone).let { "%d/%d %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) }
        val next = (wakeLog + "$t  $text").takeLast(40)
        prefs.edit().putString("wake_log", next.joinToString("\n")).apply()
        Log.i(TAG, "叫醒：$text")
    }

    /** 连续震动到什么时候（0 = 不震）；打开总控就停。 */
    var buzzUntil: Long
        get() = prefs.getLong("buzz_until", 0L)
        set(v) = prefs.edit().putLong("buzz_until", v).apply()

    var buzzReason: String
        get() = prefs.getString("buzz_reason", "") ?: ""
        set(v) = prefs.edit().putString("buzz_reason", v).apply()

    /** 今天主线的行动键（没定为空）。 */
    fun mainKey(now: Long = System.currentTimeMillis()): String =
        com.zongkong.core.plan.Plans.day(_plans.value, DayClock.dateKey(now, zone))?.mainActionKey.orEmpty()

    /** 当前的专注锁（正在做的一步，锁还没到期）。 */
    fun focusLock(now: Long = System.currentTimeMillis()): FocusLock? {
        val s = _work.value.session ?: return null
        if (s.lockUntil <= now) return null
        val title = _work.value.rec(s.actionKey)?.title ?: return null
        return FocusLock(title, s.lockUntil)
    }

    /** 换日、到点的放宽改动都在这里处理。每次算状态前调用。 */
    fun refresh(now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val key = DayClock.dateKey(now, zone)
            if (_today.value.date != key) _today.value = loadDay(key)
            val c = _config.value
            val applied = Policy.applyDue(c, now)
            if (applied != c) setConfigLocked(applied)
        }
    }

    fun status(now: Long = System.currentTimeMillis()): Status {
        refresh(now)
        val c = _config.value
        // 训练换来的分钟算进“今天挣到的”：同一本账、同一个每天总上限，用完照旧拦
        val bonus = com.zongkong.core.moling.Training.bonusMinutes(_training.value, now, zone)
        val earned = if (c.earn.enabled) com.zongkong.core.plan.Earn.earned(_work.value, now, zone, c.earn) + bonus else null
        val debt = if (c.earn.enabled) com.zongkong.core.plan.Earn.debt(_work.value, _plans.value, now, zone, c.earn)?.let { it.key to it.title } else null
        return Engine.evaluate(c, _today.value, now, zone, focusLock(now), _hq.value.active, absence(now), earned, debt, control = _control.value, holds = _moling.value.holds)
    }

    fun updateConfig(f: (Config) -> Config): Config = synchronized(lock) {
        val next = f(_config.value)
        if (next != _config.value) setConfigLocked(next)
        next
    }

    private fun setConfigLocked(c: Config) {
        _config.value = c
        val text = ZkJson.encodeToString(Config.serializer(), c)
        io.execute { writeAtomic(File(dir, "config.json"), text) }
    }

    /** 修改今天（逻辑日）的记录。 */
    fun updateToday(now: Long = System.currentTimeMillis(), f: (DayLog) -> DayLog): DayLog = synchronized(lock) {
        refresh(now)
        val next = f(_today.value)
        if (next != _today.value) {
            _today.value = next
            saveDay(next)
        }
        next
    }

    /** 修改某一天的记录（同步旧记录用）。 */
    fun updateDay(date: String, f: (DayLog) -> DayLog) = synchronized(lock) {
        if (date == _today.value.date) {
            updateToday(f = f)
        } else {
            val next = f(loadDay(date))
            saveDay(next)
        }
    }

    private fun saveDay(day: DayLog) {
        val text = ZkJson.encodeToString(DayLog.serializer(), day)
        io.execute { writeAtomic(File(daysDir, "${day.date}.json"), text) }
    }

    fun day(date: String): DayLog = synchronized(lock) {
        if (date == _today.value.date) _today.value else loadDay(date)
    }

    /** 最近 n 天（含今天）的记录，新的在前。 */
    fun history(n: Int): List<DayLog> {
        val today = LocalDate.parse(_today.value.date)
        return (0 until n).map { day(today.minusDays(it.toLong()).toString()) }
    }

    // ---------- 小状态：心跳、草稿、提醒去重 ----------

    var heartbeat: Long
        get() = prefs.getLong("heartbeat", 0L)
        set(v) = prefs.edit().putLong("heartbeat", v).apply()

    /** 去 ChatGPT 续接的是哪件事、什么时候去的（回来时提示导入交接块）。 */
    var awaitingGpt: String
        get() = prefs.getString("awaiting_gpt", "") ?: ""
        set(v) = prefs.edit().putString("awaiting_gpt", v).apply()

    /** “查看结果”上次看到第几条变化。 */
    var seenChanges: Long
        get() = prefs.getLong("seen_changes", 0L)
        set(v) = prefs.edit().putLong("seen_changes", v).apply()

    /** 一手的每晚总结记到哪一天了。 */
    var yishouSummaryDate: String
        get() = prefs.getString("yishou_summary_date", "") ?: ""
        set(v) = prefs.edit().putString("yishou_summary_date", v).apply()

    var syncNote: String
        get() = prefs.getString("sync_note", "") ?: ""
        set(v) = prefs.edit().putString("sync_note", v).apply()

    fun draft(gateId: String): String? = prefs.getString("draft_$gateId", null)
    fun saveDraft(gateId: String, text: String) = prefs.edit().putString("draft_$gateId", text).apply()
    fun clearDraft(gateId: String) = prefs.edit().remove("draft_$gateId").apply()

    /** 某条提醒今天发过没有。发过返回 false，没发过记下并返回 true。 */
    fun firstAlert(key: String): Boolean = synchronized(lock) {
        val date = _today.value.date
        val stored = prefs.getString("alerts", "") ?: ""
        val (d, keys) = stored.split('|', limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val set = if (d == date) keys.split(',').filter { it.isNotEmpty() }.toMutableSet() else mutableSetOf()
        if (!set.add(key)) return false
        prefs.edit().putString("alerts", date + "|" + set.joinToString(",")).apply()
        true
    }

    // ---------- 文件 ----------

    private fun loadConfig(): Config {
        val f = File(dir, "config.json")
        if (!f.exists()) return Config()
        return try {
            com.zongkong.core.Defaults.migrate(ZkJson.decodeFromString(Config.serializer(), f.readText()))
        } catch (e: Exception) {
            Log.e(TAG, "配置文件读不了，备份后用默认配置", e)
            f.copyTo(File(dir, "config.broken.${System.currentTimeMillis()}.json"), overwrite = true)
            Config()
        }
    }

    private fun loadDay(date: String): DayLog {
        val f = File(daysDir, "$date.json")
        if (!f.exists()) return DayLog(date)
        return try {
            ZkJson.decodeFromString(DayLog.serializer(), f.readText())
        } catch (e: Exception) {
            Log.e(TAG, "记录 $date 读不了", e)
            DayLog(date)
        }
    }

    private fun writeAtomic(target: File, text: String) {
        try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "写入 ${target.name} 失败", e)
        }
    }

    companion object {
        private const val TAG = "Store"
    }
}
