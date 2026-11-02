package com.yishou.app.focus

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 文件和时钟都能替换，过期恢复、写入中断不用启动手机也能测。 */
class FocusStore(private val directory: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val activeFile = File(directory, "focus_active.json")
    private val historyFile = File(directory, "focus_history.json")
    private val _state = MutableStateFlow<FocusSession?>(null)
    val state: StateFlow<FocusSession?> = _state.asStateFlow()
    private val _history = MutableStateFlow<List<FocusRun>>(emptyList())
    val history: StateFlow<List<FocusRun>> = _history.asStateFlow()
    private var current: FocusRun? = null

    init {
        directory.mkdirs()
        if (historyFile.exists()) {
            val a = JSONArray(historyFile.readText())
            _history.value = (0 until a.length()).map { FocusJson.decode(a.getJSONObject(it)) }.takeLast(200)
        }
        if (activeFile.exists()) {
            val saved = FocusJson.decode(JSONObject(activeFile.readText()))
            // 归档后进程可能恰好被杀，旧的 active 文件不能让这一场复活。
            if (_history.value.none { it.session.startedAt == saved.session.startedAt }) {
                current = saved.copy(session = FocusEngine.cancelQuit(saved.session))
                _state.value = current!!.session
                active(clock())
                current?.let { writeActive(it) }
            } else activeFile.delete()
        }
    }

    @Synchronized fun active(now: Long = clock()): FocusRun? {
        val r = current ?: return null
        if (!r.session.active(now)) {
            archive(r.copy(session = r.session.copy(endedAt = r.session.endedAt ?: r.session.endsAt)))
            return null
        }
        return r
    }

    @Synchronized fun start(plan: FocusPlan, allowedPackages: Set<String>, messaging: Set<String>, now: Long = clock()): Boolean {
        if (active(now) != null) return false
        val s = FocusEngine.start(plan, now) ?: return false
        publish(FocusRun(s, allowedPackages.toSet(), messaging.toSet()))
        return true
    }

    @Synchronized fun update(change: (FocusSession) -> FocusSession?): Boolean {
        val r = active() ?: return false
        val next = change(r.session) ?: return false
        val changed = r.copy(session = next)
        if (next.endedAt != null) archive(changed) else publish(changed)
        return true
    }

    @Synchronized fun mark(startedAt: Long, round: Int, done: Boolean, note: String) {
        val r = active()?.takeIf { it.session.startedAt == startedAt }
            ?: _history.value.lastOrNull { it.session.startedAt == startedAt } ?: return
        if (r.session.startedAt != startedAt || round !in FocusRuntime.completedRounds(r.session, clock())) return
        val next = r.copy(session = FocusEngine.mark(r.session, round, done, note, clock()))
        if (current != null && current!!.session.startedAt == startedAt) publish(next) else replaceHistory(next)
    }

    @Synchronized fun claimRounds(): Pair<FocusRun, List<Int>>? {
        val r = current ?: _history.value.lastOrNull { !it.endNotified } ?: return null
        val rounds = FocusRuntime.completedRounds(r.session, clock()).filter { it !in r.alertedRounds }
        if (rounds.isEmpty()) return null
        val next = r.copy(alertedRounds = r.alertedRounds + rounds)
        if (current?.session?.startedAt == r.session.startedAt) publish(next) else replaceHistory(next)
        return next to rounds
    }

    @Synchronized fun claimEnd(): FocusRun? {
        val r = _history.value.lastOrNull { !it.endNotified } ?: return null
        replaceHistory(r.copy(endNotified = true))
        return r
    }

    private fun publish(r: FocusRun) {
        writeActive(r)
        current = r
        _state.value = r.session
    }
    private fun archive(r: FocusRun) {
        replaceHistory(r)
        // 先落账再删 active：中断重启时按 startedAt 去重。
        activeFile.delete()
        current = null
        _state.value = null
    }
    private fun replaceHistory(r: FocusRun) {
        val list = (_history.value.filter { it.session.startedAt != r.session.startedAt } + r).sortedBy { it.session.startedAt }.takeLast(200)
        write(historyFile, JSONArray(list.map(FocusJson::encode)).toString())
        _history.value = list
    }
    private fun writeActive(r: FocusRun) = write(activeFile, FocusJson.encode(r).toString())
    private fun write(file: File, text: String) {
        val tmp = File(directory, file.name + ".tmp")
        java.io.FileOutputStream(tmp).use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
