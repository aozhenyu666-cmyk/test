package com.yishou.app.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.yishou.app.MainActivity
import com.yishou.app.YishouApp
import com.yishou.app.data.MaterialSource
import com.yishou.app.llm.LlmResult
import com.yishou.app.ui.VortexLoader
import com.yishou.app.ui.theme.YishouTheme
import kotlinx.coroutines.launch

/**
 * 从别的应用“分享 → 一手”进来的地方：文章、帖子、一段话，或者一张截图。
 * 只接收你主动分享的这一条；文字存进素材库，图片只拿去转写或认商品，不保存。
 */
class ShareActivity : ComponentActivity() {

    private var busy by mutableStateOf<String?>(null)
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as YishouApp
        val text = sharedText()
        val image = sharedImage()
        if (text == null && image == null) {
            finish()
            return
        }
        setContent {
            YishouTheme {
                Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(24.dp)) {
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("收进一手", style = MaterialTheme.typography.titleLarge)
                        if (text != null) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                                Text(text.take(400), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
                            }
                        } else {
                            Text("一张图。可以读出里面的正文当素材，或者认出商品来比一比价格。图片本身不保存。", style = MaterialTheme.typography.bodyMedium)
                        }
                        val b = busy
                        if (b != null) {
                            VortexLoader(b)
                        } else {
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            val pack = remember(text) { text?.let { com.yishou.app.bank.BankParser.parse(it) }.orEmpty() }
                            if (pack.isNotEmpty()) {
                                Text("看起来是一份题包：认出 ${pack.size} 道题。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                                Button(onClick = { importPack(app, text!!) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("收进题库") }
                            }
                            if (text != null) {
                                Button(onClick = { saveText(app, text, practice = true) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (pack.isEmpty()) "存下，马上练" else "当素材存下，马上练") }
                                OutlinedButton(onClick = { saveText(app, text, practice = false) }, modifier = Modifier.fillMaxWidth()) { Text("只存进素材库") }
                            } else if (image != null) {
                                Button(onClick = { saveImage(app, image) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("读出正文，当素材") }
                                OutlinedButton(onClick = { compare(app, image) }, modifier = Modifier.fillMaxWidth()) { Text("比一比价格") }
                            }
                            TextButton(onClick = { finish() }) { Text("算了") }
                        }
                    }
                }
            }
        }
    }

    private fun sharedText(): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val t = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        return if (!subject.isNullOrBlank() && !t.startsWith(subject)) "$subject\n$t" else t
    }

    private fun sharedImage(): Uri? {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun saveText(app: YishouApp, text: String, practice: Boolean) {
        busy = "存进素材库……"
        lifecycleScope.launch {
            when (val r = app.materials.addText(text, MaterialSource.SHARE)) {
                is LlmResult.Err -> {
                    busy = null
                    error = r.error.message
                }
                is LlmResult.Ok -> if (practice) open(app, "think?m=${r.value.id}") else done("存好了：${r.value.title}")
            }
        }
    }

    private fun importPack(app: YishouApp, text: String) {
        busy = "在本机拆题……"
        lifecycleScope.launch {
            val r = app.bankImport.import(text)
            if (r.total > 0) {
                Toast.makeText(this@ShareActivity, r.line(), Toast.LENGTH_LONG).show()
                open(app, "bank")
            } else {
                busy = null
                error = r.line()
            }
        }
    }

    private fun saveImage(app: YishouApp, uri: Uri) {
        busy = "正在读图里的正文……"
        lifecycleScope.launch {
            when (val r = app.materials.addImage(uri, MaterialSource.SCREEN)) {
                is LlmResult.Err -> {
                    busy = null
                    error = r.error.message
                }
                is LlmResult.Ok -> open(app, "think?m=${r.value.id}")
            }
        }
    }

    private fun compare(app: YishouApp, uri: Uri) {
        busy = "正在认商品和规格……"
        lifecycleScope.launch {
            when (val r = app.materials.goods(uri)) {
                is LlmResult.Err -> {
                    busy = null
                    error = r.error.message
                }
                is LlmResult.Ok -> {
                    if (r.value.size < 2) {
                        busy = null
                        error = "只认出 ${r.value.size} 件商品，比不起来。换一张能看到两件以上的图。"
                    } else {
                        app.pendingGoods.value = r.value
                        open(app, "deeds")
                    }
                }
            }
        }
    }

    private fun open(app: YishouApp, route: String) {
        app.pendingRoute.value = route
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    private fun done(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}
