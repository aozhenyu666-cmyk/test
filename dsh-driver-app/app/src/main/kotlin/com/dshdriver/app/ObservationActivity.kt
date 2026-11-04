package com.dshdriver.app

import android.app.*
import android.content.*
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.*
import com.dshdriver.core.Checkins
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.*

/** The same Ui and input-method voice entry as existing cards; drafts survive rotation and process death. */
class ObservationActivity : Activity() {
    private var observationId: String?=null
    private val fields=linkedMapOf<String,EditText>()
    private var completed=false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current=WeakReference(this)
        observationId=intent.getStringExtra("observation")
        val row=observationId?.let {Engine.store.observations.get(it)}
        val p=Palette.dark
        window.statusBarColor=p.bg
        val ui=Ui(this,p)
        val page=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(ui.px(22),ui.px(48),ui.px(22),ui.px(28));setBackgroundColor(p.bg)
        }
        ui.reset(page)
        if(row==null) history(ui) else render(ui,row)
        setContentView(ScrollView(this).apply {isFillViewport=true;addView(page)})
    }
    override fun onResume() {super.onResume();if(observationId!=null) clearNotice(this)}
    private fun render(ui: Ui,row: JSONObject) {
        val deep=row.optString("kind")=="deep"
        ui.kicker(if(deep) "10分钟 · 深度复盘" else "5分钟 · 快速察觉")
        ui.headline(if(deep) "停一停，看看刚才" else "刚才在做什么？",24f)
        ui.caption("${time(row.optLong("from"))} — ${time(row.optLong("to"))} · ${if(row.optString("questionSource")=="dsh") "大肥鱼提问" else "离线固定问题"}")
        ui.card(title="这一段的应用记录",muted=true) {ui.text(appsText(row))}
        val shot=if(row.isNull("screenshot")) null else Engine.store.observations.shot(row.optString("screenshot"))
        ui.caption(if(shot!=null) "已留一张截图，仅存在本机" else "截图：${row.optString("shotStatus","无记录")}")
        if(shot!=null) ui.quiet("查看本地截图") {
            val view=ImageView(this).apply {setImageBitmap(BitmapFactory.decodeFile(shot.absolutePath));adjustViewBounds=true}
            AlertDialog.Builder(this).setView(view).setPositiveButton("关闭",null).show()
        }
        val questions=row.optJSONArray("questions")?:JSONArray(Checkins.fixed(row.optString("kind")))
        val answers=row.optJSONObject("answers")?:JSONObject()
        for(i in 0 until questions.length()) {
            val key=Checkins.KEYS[i]
            ui.text(questions.optString(i),16f,top=12f)
            fields[key]=ui.field("打字或用输入法语音回答",answers.optString(key),multiline=true,lines=2).apply {id=1000+i;filters=arrayOf(android.text.InputFilter.LengthFilter(500));isEnabled=row.optBoolean("pending")}
        }
        if(row.optBoolean("pending")) {
            ui.caption("可以只答一部分；返回会保存已有回答，暂时切走会保留草稿。")
            ui.primary("保存回答") {finishAnswer(false)}
            ui.quiet("跳过这次") {finishAnswer(true)}
        } else ui.caption(if(row.optBoolean("synced")) "已同步给大肥鱼" else "保存在本机，等待同步")
    }
    private fun history(ui: Ui) {
        ui.kicker("本地记录 · 保留14天")
        ui.headline("察觉记录",24f)
        val rows=Engine.store.observations.rows()
        ui.caption("共 ${rows.size} 条；${rows.count {!it.optBoolean("synced")&&!it.optBoolean("pending")}} 条待同步。截图7天清理，默认不上传。")
        ui.primary("导出观察 JSON（不含图片）") {startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE);type="application/json";putExtra(Intent.EXTRA_TITLE,"observations.json")
        },20)}
        for(row in rows.asReversed().take(30)) ui.card(title="${time(row.optLong("at"))} · ${if(row.optString("kind")=="deep") "复盘" else "小问"}") {
            ui.text(appsText(row))
            val answers=row.optJSONObject("answers")?:JSONObject()
            ui.caption(if(row.optBoolean("skipped")) "已跳过（${row.optString("reason")}）" else Checkins.KEYS.map {answers.optString(it)}.filter {it.isNotBlank()}.joinToString("\n").ifBlank {"待回答"})
            ui.quiet(if(row.optBoolean("pending")) "接着回答" else "查看记录") {start(this,row.getString("id"))}
        }
    }
    @Deprecated("Activity callback")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==20 && resultCode==RESULT_OK) data?.data?.let {uri->
            val text=JSONArray(Engine.store.observations.rows()).toString(2)
            runCatching {contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {it.write(text)}}
                .onFailure {Toast.makeText(this,"导出失败",Toast.LENGTH_SHORT).show()}
        }
    }
    private fun answers()=JSONObject().apply {fields.forEach {(k,v)->put(k,v.text.toString().trim().take(500))}}
    private fun finishAnswer(skip: Boolean) {
        val id=observationId?:return
        AwarenessRuntime.complete(id,answers(),skip);completed=true;finishAndRemoveTask()
    }
    override fun onPause() {
        if(!completed) observationId?.let {id->val answer=answers();Engine.store.observations.edit(id) {if(it.optBoolean("pending")) it.put("answers",answer)}}
        super.onPause()
    }
    @Deprecated("Activity back") override fun onBackPressed() {
        if(observationId!=null && Engine.store.observations.get(observationId!!)?.optBoolean("pending")==true) finishAnswer(fields.values.all {it.text.isNullOrBlank()})
        else super.onBackPressed()
    }
    companion object {
        private var current=WeakReference<ObservationActivity>(null)
        fun withdraw() {current.get()?.finish()}
        fun start(c: Context,id: String?=null) {c.startActivity(Intent(c,ObservationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK);putExtra("observation",id)
        })}
        fun time(at: Long)=SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(Date(at))
        fun appsText(row: JSONObject): String {
            val a=row.optJSONArray("apps")?:JSONArray()
            val parts=(0 until a.length()).map {a.getJSONObject(it)}.filter {it.optLong("sec")>0}.take(12).map {
                val sec=it.optLong("sec");"${it.optString("label",it.optString("pkg"))} ${sec/60}分${sec%60}秒"
            }
            val unknown=row.optLong("unknownSec")
            return (parts + if(unknown>0) listOf("未观察到/锁屏 ${unknown/60}分${unknown%60}秒") else emptyList()).joinToString("\n").ifBlank {"没有可用应用时间线；仍可回答。"}
        }
        fun clearNotice(c: Context) {c.getSystemService(NotificationManager::class.java).cancel(12)}
        fun notifyPending(c: Context,id: String) {
            val nm=c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("awareness","自我察觉",NotificationManager.IMPORTANCE_HIGH))
            val intent=Intent(c,ObservationActivity::class.java).putExtra("observation",id)
            val pi=PendingIntent.getActivity(c,id.hashCode(),intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n=Notification.Builder(c,"awareness").setSmallIcon(R.drawable.ic_driver).setContentTitle("有一条察觉等你回答")
                .setContentText("点击打开，可跳过，也可只答一部分。").setContentIntent(pi).setAutoCancel(true).build()
            runCatching {nm.notify(12,n)}
        }
    }
}
