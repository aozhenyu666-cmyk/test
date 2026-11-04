package com.dshdriver.app

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import com.dshdriver.core.AppTimeline
import com.dshdriver.core.Checkins
import com.dshdriver.core.Supervision
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Uses Engine's existing ticker; network and bitmap work never block that ticker. */
object AwarenessRuntime {
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newFixedThreadPool(2)
    private val syncing=AtomicBoolean(false)
    private var lastSync=0L
    private var lastPrune=0L
    @Volatile var revision=0L; private set
    private var voice: CheckinVoice?=null
    @Volatile var state: Supervision?=null; private set
    private val store get()=Engine.store.observations
    val active get()=state?.active==true && System.currentTimeMillis()< (state?.endsAt?:0)
    fun init() {
        val now=System.currentTimeMillis()
        store.prune(now)
        store.foreground(now,null) // A saved foreground package is never proof of activity during a dead process.
        state=store.state()
        if(state?.active==true && now>state!!.endsAt) {state=state!!.copy(active=false);store.save(state!!)}
    }
    fun start(minutes: Int) = Engine.background {
        finishPending("superseded")
        state=Supervision.start(System.currentTimeMillis(),minutes)
        store.save(state!!)
        main.post {LockService.start(Engine.app,"监督模式 · 每5分钟察觉，每10分钟复盘");GuardService.sync(Engine.app);changed()}
        Engine.ensureTicker()
    }
    fun stop() = Engine.background {
        state=state?.copy(active=false);state?.let(store::save)
        finishPending("stopped")
        sync()
        main.post {Overlay.hideCheckin();ObservationActivity.withdraw();voice?.close();voice=null
            if(!Engine.locked) LockService.stop(Engine.app);GuardService.sync(Engine.app);changed()}
    }
    private fun finishPending(reason: String) {
        store.pending()?.let { row -> val answers=row.optJSONObject("answers")?:JSONObject()
            val hasAnswer=Checkins.KEYS.any { answers.optString(it).isNotBlank() }
            store.complete(row.getString("id"),answers,!hasAnswer,reason)
        }
    }
    fun tick(now: Long) {
        if(now-lastPrune>60*60_000L) {store.prune(now);lastPrune=now}
        val previous=state
        val due=previous?.poll(now)
        if(due!=null) {
            state=due.state;store.save(due.state)
            finishPending("unanswered")
            observe(due)
        } else if(previous?.active==true && now>previous.endsAt) {
            state=previous.copy(active=false);store.save(state!!);main.post {changed()}
        }
        if(now-lastSync>=30_000 && (active || due!=null)) {lastSync=now;sync()}
        if(previous?.active==true && state?.active==false) main.post {if(!Engine.locked) LockService.stop(Engine.app);changed()}
    }
    private fun observe(due: Supervision.Due) {
        val id=UUID.randomUUID().toString()
        val summary=AppTimeline.summarize(store.timeline(),due.from,due.to)
        val row=JSONObject().put("id",id).put("at",due.to).put("from",due.from).put("to",due.to)
            .put("kind",due.kind).put("mode","supervise").put("questionSource","fixed")
            .put("questions",JSONArray(Checkins.fixed(due.kind))).put("question",Checkins.fixed(due.kind).joinToString("\n"))
            .put("answers",JSONObject()).put("pending",true).put("skipped",false).put("synced",false)
            .put("unknownSec",summary.unknownMs/1000).put("screenshot",JSONObject.NULL).put("shotStatus",if(store.screenshots) "pending" else "disabled")
            .put("apps",JSONArray((summary.apps.take(39) + if(summary.apps.size>39) listOf(AppTimeline.Usage("other-apps",summary.apps.drop(39).sumOf {it.ms})) else emptyList()).map { u -> JSONObject().put("pkg",u.pkg).put("label",if(u.pkg=="other-apps") "其他应用" else label(u.pkg)).put("sec",u.ms/1000) }))
        store.put(row)
        val capture=CountDownLatch(1)
        val deadline=SystemClock.elapsedRealtime()+4000
        val presented=AtomicBoolean(false)
        fun show(questions: List<String>?) {
            if(!presented.compareAndSet(false,true)) return
            if(questions!=null) store.edit(id) {if(it.optBoolean("pending")) it.put("questions",JSONArray(questions)).put("question",questions.joinToString("\n")).put("questionSource","dsh")}
            val current=store.get(id)
            if(current?.optBoolean("pending")==true) present(id,current)
            changed()
        }
        main.postDelayed({show(null)},4000)
        main.post {
            if(store.screenshots && screenAvailable()) ObservationShots.capture(Engine.app,id) {path,status->
                store.edit(id) {it.put("screenshot",path?:JSONObject.NULL).put("shotStatus",status)};capture.countDown()
            } else {store.edit(id) {it.put("shotStatus",if(store.screenshots) "screen-unavailable" else "disabled")};capture.countDown()}
        }
        io.execute {
            val questions=Engine.store.pairing()?.let { DshLink.checkin(it,row) }
            capture.await((deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0),TimeUnit.MILLISECONDS)
            main.post {show(questions)}
        }
    }
    private fun present(id: String, row: JSONObject) {
        if(!screenAvailable()) {ObservationActivity.notifyPending(Engine.app,id);return}
        // 答题页在前台时不要把全屏察觉页盖上去：那会让答题页 pause、状态被系统重建，
        // 用户正打的字和正在答的题会一起丢掉。先留通知/浮条，等门关了再答。
        if(GateActivity.visible) {
            ObservationActivity.notifyPending(Engine.app,id)
            if(row.optString("kind")=="micro" && Overlay.canShow(Engine.app)) Overlay.showCheckin(Engine.app,row)
            return
        }
        if(row.optString("kind")=="micro" && Overlay.canShow(Engine.app)) Overlay.showCheckin(Engine.app,row)
        else {
            // Android may silently reject a background start; retain a clickable route until the page resumes.
            ObservationActivity.notifyPending(Engine.app,id)
            runCatching {ObservationActivity.start(Engine.app,id)}
        }
        if(store.voice) {
            if(voice==null) voice=CheckinVoice(Engine.app)
            voice?.speak(if(row.optString("kind")=="deep") "到了十分钟复盘。${row.optJSONArray("questions")?.optString(0).orEmpty()}" else row.optJSONArray("questions")?.optString(0).orEmpty())
        }
    }
    fun complete(id: String, answers: JSONObject, skipped: Boolean) {
        store.complete(id,answers,skipped,if(skipped) "skipped" else "answered")
        main.post {Overlay.hideIf("observation:$id");ObservationActivity.clearNotice(Engine.app);changed()}
        sync()
    }
    fun sync(alreadyPinged: Boolean=false) {
        val pairing=Engine.store.pairing()?:return
        if(!syncing.compareAndSet(false,true)) return
        io.execute {
            try {
                if(!alreadyPinged && !DshLink.ping(pairing).ok) return@execute
                while(true) {
                    val batch=store.outbox();if(batch.isEmpty()) return@execute
                    val ids=DshLink.observe(pairing,batch)?:return@execute
                    val allowed=batch.map {it.getString("id")}.toSet()
                    val ack=ids.intersect(allowed);if(ack.isEmpty()) return@execute
                    store.acknowledge(ack)
                }
            } finally {syncing.set(false);main.post {changed()} }
        }
    }
    fun screenAvailable(): Boolean = Engine.app.getSystemService(PowerManager::class.java).isInteractive &&
        !Engine.app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun label(pkg: String)=runCatching {Engine.app.packageManager.getApplicationLabel(Engine.app.packageManager.getApplicationInfo(pkg,0)).toString().take(80)}.getOrDefault(pkg)
    private fun changed() {revision++;Engine.onChange?.invoke()}
}
