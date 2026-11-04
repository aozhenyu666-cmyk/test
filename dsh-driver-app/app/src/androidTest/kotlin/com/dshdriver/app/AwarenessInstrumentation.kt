package com.dshdriver.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.*
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import com.dshdriver.core.Supervision
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Platform-only instrumentation, run on an empty emulator; no androidx dependency. */
class AwarenessInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {super.onCreate(arguments);start()}
    override fun onStart() {
        try {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk") || Build.HARDWARE in setOf("ranchu","goldfish")) {"Only run this test on an emulator"}
            waitForIdleSync()
            check(Engine.store.pairing()==null) {"Use an unpaired test install"}
            val main=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val testRoot=java.io.File(targetContext.cacheDir,"awareness-test-${UUID.randomUUID()}")
            val isolated=ObservationStore(targetContext,testRoot)
            val now=System.currentTimeMillis()
            isolated.save(Supervision.start(now,60))
            check(ObservationStore(targetContext,testRoot).state()==Supervision.start(now,60)) {"Session state did not survive a fresh store"}
            testRoot.deleteRecursively()
            val id="smoke-${UUID.randomUUID()}"
            val row=JSONObject().put("id",id).put("at",now).put("from",now-300000).put("to",now)
                .put("kind","deep").put("questionSource","fixed").put("questions",JSONArray(com.dshdriver.core.Checkins.fixed("deep")))
                .put("apps",JSONArray().put(JSONObject().put("pkg","test.learning").put("label","学习测试").put("sec",180)))
                .put("unknownSec",120).put("pending",true).put("synced",false).put("answers",JSONObject()).put("shotStatus","disabled")
            Engine.store.observations.put(row)
            val page=startActivitySync(Intent(targetContext,ObservationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("observation",id))
            runOnMainSync {
                page.findViewById<EditText>(1000).setText("做了第3题，画出了两个条件之间的关系。")
                button(page.window.decorView,"保存回答")!!.performClick()
            }
            waitForIdleSync()
            val saved=ObservationStore(targetContext).get(id)!!
            check(!saved.getBoolean("pending") && saved.getJSONObject("answers").getString("did").contains("第3题"))
            check(saved.getJSONObject("answers").optString("next").isEmpty()) {"Partial answers were not preserved"}
            check(Engine.store.observations.outbox().any {it.getString("id")==id})
            Engine.store.observations.acknowledge(setOf("not-an-id"))
            check(!ObservationStore(targetContext).get(id)!!.getBoolean("synced"))
            Engine.store.observations.acknowledge(setOf(id))
            check(ObservationStore(targetContext).get(id)!!.getBoolean("synced"))
            val handshake=java.io.File(targetContext.cacheDir,"awareness-a11y-ready")
            handshake.delete()
            sendStatus(0,Bundle().apply {putString("stream","AWARENESS_READY_FOR_ACCESSIBILITY\n")})
            val timeout=SystemClock.elapsedRealtime()+20000
            while((!handshake.exists() || !Engine.watchConnected) && SystemClock.elapsedRealtime()<timeout) SystemClock.sleep(100)
            check(handshake.exists() && Engine.watchConnected) {"Accessibility service did not bind"}
            runOnMainSync {main.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
            SystemClock.sleep(400)
            val screenshot=shot("smoke-${UUID.randomUUID()}")
            check(screenshot.first!=null) {"Screenshot failed: ${screenshot.second}"}
            val file=Engine.store.observations.shot(screenshot.first!!)!!
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeFile(file.absolutePath,bounds)
            check(bounds.outWidth in 1..720 && bounds.outHeight>0 && file.length()>0)
            file.delete()
            // Whole-display capture may return black redaction rather than a failure for FLAG_SECURE.
            val marker=android.graphics.Color.rgb(20,230,100)
            runOnMainSync {main.setContentView(View(main).apply {setBackgroundColor(marker)})}
            waitForIdleSync();SystemClock.sleep(600)
            val visible=shot("smoke-${UUID.randomUUID()}")
            check(visible.first!=null) {"Marker control capture failed: ${visible.second}"}
            val visibleFile=Engine.store.observations.shot(visible.first!!)!!
            val control=BitmapFactory.decodeFile(visibleFile.absolutePath)
            val pixel=control.getPixel(control.width/2,control.height/2)
            check(kotlin.math.abs(android.graphics.Color.green(pixel)-230)<25 && kotlin.math.abs(android.graphics.Color.red(pixel)-20)<25) {"Marker window was not actually visible"}
            control.recycle();visibleFile.delete()
            runOnMainSync {main.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}
            waitForIdleSync();SystemClock.sleep(600)
            val protected=shot("smoke-${UUID.randomUUID()}")
            if(protected.first==null) check(protected.second.startsWith("system-error-")) {"Unexplained secure capture failure: $protected"}
            else {
                val protectedFile=Engine.store.observations.shot(protected.first!!)!!
                val bitmap=BitmapFactory.decodeFile(protectedFile.absolutePath)
                val redacted=bitmap.getPixel(bitmap.width/2,bitmap.height/2)
                check(android.graphics.Color.red(redacted)<30 && android.graphics.Color.green(redacted)<30 && android.graphics.Color.blue(redacted)<30) {"Secure content was visible in screenshot"}
                bitmap.recycle();protectedFile.delete()
            }
            runOnMainSync {main.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);main.finish()}
            finish(Activity.RESULT_OK,Bundle().apply {putString("stream","\nAWARENESS_SMOKE=PASS\nPersistent session + partial answers + exact ACK + screenshot720 + secure-content redaction checks passed\n")})
        } catch(t:Throwable) {
            finish(Activity.RESULT_CANCELED,Bundle().apply {putString("stream","\nAWARENESS_SMOKE=FAIL\n${t.stackTraceToString()}\n")})
        }
    }
    private fun button(v: View,text: String): Button? {
        if(v is Button && v.text.toString()==text) return v
        if(v is ViewGroup) for(i in 0 until v.childCount) button(v.getChildAt(i),text)?.let {return it}
        return null
    }
    private fun shot(id: String): Pair<String?,String> {
        val latch=CountDownLatch(1);var result: Pair<String?,String> = null to "no-callback"
        runOnMainSync {ObservationShots.capture(targetContext,id) {path,status->result=path to status;latch.countDown()}}
        check(latch.await(4,TimeUnit.SECONDS)) {"Screenshot callback missing"}
        return result
    }
}
