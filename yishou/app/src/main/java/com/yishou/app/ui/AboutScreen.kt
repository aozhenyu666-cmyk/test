package com.yishou.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 透明原则，与 YishouApp.kt 顶部注释保持一致。 */
private val PRINCIPLES = listOf(
    "仅供用户本人在自己的设备上使用，所有权限由用户在系统设置中手动授予。",
    "应用图标、名称正常显示；运行时保持一条常驻通知，写明“一手正在运行”。",
    "用户随时可以在系统设置里关闭无障碍服务或卸载应用，本应用不做任何阻止。",
    "无障碍服务只读取当前前台应用的包名，不读取屏幕内容、输入内容或其他应用的数据。",
    "“让陪练看屏”需要你每次在系统弹窗里同意；只在你点“看一眼”（或你设置的自动间隔）时截一张屏，发给你自己配置的识图模型，图片不保存，随时可以在通知里停止。",
    "数据只保存在本机。唯一的网络请求是把任务文本、回答，以及你主动发出的照片或截屏，发送到你自己配置的大模型接口。",
    "每日使用总量的限制交给用户另装的「不做手机控」，本应用不重复实现。",
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar("关于「一手」", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "「一手」是给自己用的数字健康应用：打开娱乐应用前，先就当前学习任务走一步思考，" +
                    "走得有效就获得一段使用时间。名字取自下棋——对方落子，你应一手。",
                style = MaterialTheme.typography.bodyMedium,
            )
            SectionTitle("透明原则")
            PRINCIPLES.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
