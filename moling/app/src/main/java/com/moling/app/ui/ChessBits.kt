package com.moling.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * 三手棋的共用小零件（E1）：标签 + 三颗进度点、细墨线框里的“暴论”、收官分数、
 * “我自己说一句”的单行输入。底部卡片、桌宠气泡、对话页都用这一套，样子才一致。
 */

/** 顶上小标签「三手棋 · 第 N 手 / 收官」+ 右边三颗进度点（下过的实心）。 */
@Composable
fun ChessHeader(turn: Int, done: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .background(Seal.copy(alpha = 0.10f), RoundedCornerShape(50))
                .border(0.6.dp, Seal.copy(alpha = 0.5f), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                if (done) "三手棋 · 收官" else "三手棋 · 第 " + turn + " 手",
                color = Seal,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { i ->
                val filled = done || i < (turn - 1).coerceAtLeast(0)
                Box(
                    Modifier
                        .size(6.dp)
                        .background(if (filled) Ink else Ink.copy(alpha = 0.16f), CircleShape),
                )
            }
        }
    }
}

/** 她的“暴论”：细墨线框起来，像棋盘上的一枚子。 */
@Composable
fun ChessBait(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .border(0.9.dp, Ink.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink)
    }
}

/** 收官：0–3 颗墨点 + “新结论挂进体系图了”。onOpenAtlas 不为空时给一个“去看看”。 */
@Composable
fun ChessScore(score: Int, conclusion: String, onOpenAtlas: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("这局 ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            repeat(3) { i ->
                Box(
                    Modifier
                        .padding(horizontal = 1.dp)
                        .size(7.dp)
                        .background(if (i < score) Ink else Ink.copy(alpha = 0.15f), CircleShape),
                )
            }
            Text(" 分", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onOpenAtlas != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onOpenAtlas) { Text("去体系图看看", style = MaterialTheme.typography.labelMedium) }
            }
        }
        if (conclusion.isNotBlank()) {
            Text(
                "新结论挂进体系图了：" + conclusion,
                style = MaterialTheme.typography.bodySmall,
                color = Seal,
            )
        }
    }
}

/** “我自己说一句”：单行输入 + 发。发出去调用方拿这句话去调 BrainHost.tap。 */
@Composable
fun ChessFreeInput(onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(120) },
            modifier = Modifier.weight(1f),
            placeholder = { Text("你自己说一句") },
            singleLine = true,
        )
        TextButton(enabled = text.isNotBlank(), onClick = { onSend(text.trim()); text = "" }) { Text("发") }
    }
}