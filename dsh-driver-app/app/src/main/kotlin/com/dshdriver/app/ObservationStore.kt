package com.dshdriver.app

import android.content.Context
import android.util.AtomicFile
import com.dshdriver.core.AppTimeline
import com.dshdriver.core.Supervision
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One lock guards read/modify/write, including sync acknowledgements arriving beside user answers. */
class ObservationStore(context: Context, directory: File = File(context.filesDir,"driver")) {
    private val dir = directory.apply { mkdirs() }
    private val prefs = context.getSharedPreferences("awareness", Context.MODE_PRIVATE)
    var screenshots: Boolean
        get() = prefs.getBoolean("screenshots", true)
        set(v) { prefs.edit().putBoolean("screenshots", v).apply() }
    var voice: Boolean
        get() = prefs.getBoolean("voice", true)
        set(v) { prefs.edit().putBoolean("voice", v).apply() }
    private fun read(name: String): String? = runCatching { AtomicFile(File(dir,name)).openRead().bufferedReader().use { it.readText() } }.getOrNull()
    private fun write(name: String, value: String) {
        val file = AtomicFile(File(dir,name)); val stream = file.startWrite()
        try { stream.write(value.toByteArray()); file.finishWrite(stream) } catch (e: Throwable) { file.failWrite(stream); throw e }
    }
    @Synchronized fun state(): Supervision? = read("supervision.json")?.let { runCatching {
        val o = JSONObject(it)
        Supervision(o.getLong("startedAt"),o.getLong("endsAt"),o.getLong("nextMicro"),o.getLong("nextDeep"),o.getLong("windowFrom"),o.getBoolean("active"))
    }.getOrNull() }
    @Synchronized fun save(s: Supervision) = write("supervision.json", JSONObject().put("startedAt",s.startedAt).put("endsAt",s.endsAt)
        .put("nextMicro",s.nextMicro).put("nextDeep",s.nextDeep).put("windowFrom",s.windowFrom).put("active",s.active).toString())
    @Synchronized fun rows(): List<JSONObject> = read("observations.jsonl").orEmpty().lineSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.toList()
    private fun saveRows(rows: List<JSONObject>) = write("observations.jsonl",rows.joinToString("\n",postfix = if(rows.isEmpty()) "" else "\n") { it.toString() })
    @Synchronized fun put(row: JSONObject) { val rows=rows().filter { it.optString("id") != row.getString("id") }; saveRows(rows+row) }
    @Synchronized fun get(id: String) = rows().firstOrNull { it.optString("id")==id }
    @Synchronized fun edit(id: String, update: (JSONObject)->Unit) { val rows=rows(); rows.firstOrNull { it.optString("id")==id }?.let(update); saveRows(rows) }
    @Synchronized fun pending() = rows().lastOrNull { it.optBoolean("pending") }
    @Synchronized fun complete(id: String, answers: JSONObject, skipped: Boolean, reason: String) {
        edit(id) { row ->
            if (row.optBoolean("pending")) row.put("answers",answers).put("skipped",skipped).put("reason",reason)
                .put("pending",false).put("synced",false).put("answeredAt",System.currentTimeMillis())
        }
    }
    @Synchronized fun outbox(): List<JSONObject> {
        var size=0
        return rows().filter { !it.optBoolean("pending") && !it.optBoolean("synced") }.take(20).takeWhile {
            size+=it.toString().toByteArray().size
            size<200_000
        }
    }
    @Synchronized fun acknowledge(ids: Set<String>) {
        val rows=rows(); rows.filter { it.optString("id") in ids && !it.optBoolean("pending") }.forEach { it.put("synced",true) }; saveRows(rows)
    }
    @Synchronized fun timeline(): List<AppTimeline.Event> = read("timeline.json")?.let { raw -> runCatching {
        val a=JSONArray(raw); (0 until a.length()).map { i -> val o=a.getJSONObject(i); AppTimeline.Event(o.getLong("at"),if(o.isNull("pkg")) null else o.getString("pkg")) }
    }.getOrNull() }.orEmpty()
    @Synchronized fun foreground(at: Long, pkg: String?) {
        val old=timeline()
        if (old.lastOrNull()?.pkg == pkg && old.isNotEmpty() && at >= old.last().at) return
        // Clock rollback closes the old chronology; it must not create negative or overlapping intervals.
        val base=if(old.lastOrNull()?.at?.let { it > at }==true) emptyList() else old
        val next=AppTimeline.retain(base+AppTimeline.Event(at,pkg),at-2*60*60_000L)
        write("timeline.json",JSONArray(next.map { JSONObject().put("at",it.at).put("pkg",it.pkg ?: JSONObject.NULL) }).toString())
    }
    @Synchronized fun prune(now: Long) {
        saveRows(rows().filter { it.optLong("at") >= now-14*24*60*60_000L })
        File(dir,"shots").listFiles()?.filter { it.lastModified()<now-7*24*60*60_000L }?.forEach { it.delete() }
    }
    fun shot(path: String): File? = path.takeIf { it.matches(Regex("shots/[a-zA-Z0-9-]+\\.jpg")) }?.let { File(dir,it) }?.takeIf { it.isFile }
}
