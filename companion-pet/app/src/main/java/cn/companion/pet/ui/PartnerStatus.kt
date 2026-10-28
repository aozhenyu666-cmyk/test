package cn.companion.pet.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.companion.pet.PetApp
import cn.companion.pet.service.PetService
import kotlinx.coroutines.delay

/** The same live state is shown at every entry, including failures before a model call. */
@Composable
internal fun PartnerStatus(context: Context) {
    val app = PetApp.instance
    val progress by app.partner.progress.collectAsState()
    val busy by app.partner.busy.collectAsState()
    val speech by app.speech.status.collectAsState()
    val settingsVersion by app.settings.changes.collectAsState()
    val enabled = remember(settingsVersion) { app.settings.presenceEnabled }
    val paused = remember(settingsVersion) { app.settings.petPaused }
    val voice = remember(settingsVersion) { app.settings.ttsEnabled }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { clock = System.currentTimeMillis(); delay(1000) } }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("主动交流 · " + progress.stage, style = MaterialTheme.typography.titleMedium)
        Text(progress.detail, style = MaterialTheme.typography.bodyMedium)
        if (progress.nextAt > clock) Text("约 ${(progress.nextAt - clock + 999) / 1000} 秒后再检查时机", style = MaterialTheme.typography.bodySmall)
        Text("本次运行尝试 ${progress.attempts} 次，确认显示或播出 ${progress.delivered} 次", style = MaterialTheme.typography.bodySmall)
        Text(if (voice) "语音：$speech" else "当前只显示气泡；开启朗读后才会有声音。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                if (!PetService.tryProactive(context)) Toast.makeText(context, "先授予悬浮窗权限，再启动桌宠。", Toast.LENGTH_LONG).show()
            }, enabled = !busy) { Text("现在聊一句") }
            TextButton(onClick = {
                app.settings.ttsEnabled = !voice
                if (voice) app.stopSpeech()
            }) { Text(if (voice) "关闭朗读" else "开启朗读") }
        }
        if (!enabled || paused || app.settings.partnerQuietUntil > clock) TextButton(onClick = {
            app.settings.presenceEnabled = true
            app.settings.petPaused = false
            app.settings.partnerQuietUntil = 0
            PetService.tryProactive(context)
        }) { Text("恢复主动陪伴并试聊") }
    }
}
