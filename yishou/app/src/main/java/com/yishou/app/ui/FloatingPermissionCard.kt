package com.yishou.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.yishou.app.floating.CoachFloat

@Composable
fun FloatingPermissionCard() {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ready = Settings.canDrawOverlays(context); CoachFloat.refresh(context) }
    Column {
        Text(if (ready) "陪练小窗已允许：切到别的应用后点屏幕上的“一手 ●”" else "开启陪练小窗后，屏幕上能直接看问题、开口、回复和停止，不用找通知。")
        if (!ready) OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }) { Text("允许显示陪练小窗") }
    }
}
