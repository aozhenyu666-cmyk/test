package io.github.ccch1mneyyy.dsh_app.loop.guardian

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Pure JVM testable storage backing DSH Go's mirrored enforcement policy snapshot and
 * offline-incident ingestion queue received from `大肥鱼哨兵`.
 */
class GuardianMirrorStore(private val dir: File) {
    private val mirrorFile = File(dir, "mirror.json")
    private val offlineFile = File(dir, "offline_incidents.json")
    private val lock = Object()

    @Volatile private var cachedSnapshot: GuardianPolicySnapshot? = null

    fun loadSnapshot(): GuardianPolicySnapshot = synchronized(lock) {
        cachedSnapshot?.let { return it }
        val loaded = if (mirrorFile.exists()) {
            runCatching { GuardianPolicySnapshot.fromJson(mirrorFile.readText(Charsets.UTF_8)) }.getOrNull()
        } else null
        val resolved = loaded ?: GuardianPolicySnapshot()
        cachedSnapshot = resolved
        resolved
    }

    fun saveSnapshot(snapshot: GuardianPolicySnapshot) = synchronized(lock) {
        cachedSnapshot = snapshot
        atomicWrite(mirrorFile, snapshot.toJson())
    }

    fun loadOfflineIncidents(): List<GuardianOfflineIncident> = synchronized(lock) {
        if (!offlineFile.exists()) return emptyList()
        return runCatching {
            GuardianOfflineIncident.decodeList(offlineFile.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    /** Appends unseen incidents by [GuardianOfflineIncident.id], capped at [MAX_QUEUE]. Returns count accepted. */
    fun enqueueOfflineIncidents(incoming: List<GuardianOfflineIncident>): Int = synchronized(lock) {
        if (incoming.isEmpty()) return 0
        val existing = loadOfflineIncidents().toMutableList()
        val seenIds = existing.mapTo(HashSet()) { it.id }
        var added = 0
        for (item in incoming) {
            if (seenIds.add(item.id)) {
                existing.add(item)
                added++
            }
        }
        val capped = if (existing.size > MAX_QUEUE) existing.takeLast(MAX_QUEUE) else existing
        if (added > 0) atomicWrite(offlineFile, GuardianOfflineIncident.encodeList(capped))
        added
    }

    fun peekOfflineIncidents(): List<GuardianOfflineIncident> = loadOfflineIncidents()

    /** Removes acknowledged incident ids after successful upload to `/event`. */
    fun ackOfflineIncidents(ids: Collection<String>) = synchronized(lock) {
        if (ids.isEmpty()) return
        val drop = ids.toSet()
        val remaining = loadOfflineIncidents().filterNot { it.id in drop }
        atomicWrite(offlineFile, GuardianOfflineIncident.encodeList(remaining))
    }

    private fun atomicWrite(target: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, "${target.name}.tmp")
        runCatching {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { tmp.delete() }
    }

    companion object {
        const val MAX_QUEUE = 200
    }
}

/** Application-wide accessor to the guardian mirror directory under `filesDir/loop/guardian`. */
object GuardianPolicyMirror {
    @Volatile private var store: GuardianMirrorStore? = null

    private fun ensureStore(c: Context): GuardianMirrorStore =
        store ?: synchronized(this) {
            store ?: GuardianMirrorStore(File(File(File(c.applicationContext.filesDir, "loop"), "guardian"), "")).also {
                store = it
            }
        }

    fun current(c: Context): GuardianPolicySnapshot = ensureStore(c).loadSnapshot()

    fun update(c: Context, snapshot: GuardianPolicySnapshot) {
        ensureStore(c).saveSnapshot(snapshot)
    }

    fun enqueueOffline(c: Context, items: List<GuardianOfflineIncident>): Int =
        ensureStore(c).enqueueOfflineIncidents(items)

    fun pendingOffline(c: Context): List<GuardianOfflineIncident> =
        ensureStore(c).peekOfflineIncidents()

    fun ackOffline(c: Context, ids: Collection<String>) {
        ensureStore(c).ackOfflineIncidents(ids)
    }
}
