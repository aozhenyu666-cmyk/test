package com.zongkong.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.director.DirectorHost
import com.zongkong.app.director.Quiet
import com.zongkong.app.window.Relay
import com.zongkong.app.zk
import com.zongkong.core.director.Card
import com.zongkong.core.director.Reply

/**
 * 墨灵首页（v4 第 5 项：界面减肥）。
 *
 * 导演开着时，首页就换成这一页。只留三样：
 *   1. 今天一句话（她看到的数字）
 *   2. 手上这一张卡（只有一个问题 + 两条能念的话 + 两三个按钮）
 *   3. 她的判断和日记（nowText）
 *
 * 关卡、账本、低屏、强督导那些都还在，只是不再出现在你每天第一眼看到的地方。
 */
@Composable
fun MailuoScreen(nav: NavHostController) {
    val context = LocalContext.current
    val now = rememberNow(30_000)
    val card = remember(now) { DirectorHost.currentCard() }
    val text = remember(now) { DirectorHost.nowText(now) }
    val ctx = remember(now) { Quiet.status(context, now) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("墨灵", "她看到什么、现在想说什么、今天还剩多少。")

        Panel {
            Text("今天", style = MaterialTheme.typography.titleMedium)
            Text(ctx, style = MaterialTheme.typography.bodySmall, color = LocalSignals.current.muted)
            if (text.isNotBlank()) {
                Text(text, style = MaterialTheme.typography.bodySmall, color = LocalSignals.current.muted)
            }
        }

        if (card != null) {
            CardPanel(card)
        } else {
            Panel {
                Text("现在没有要说的事。", style = MaterialTheme.typography.bodyLarge)
                Text("你可以记一条、或者点小圆点自己看她在想什么。", style = MaterialTheme.typography.bodySmall, color = LocalSignals.current.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton("记一条", { nav.navigate("quick") }, Modifier.weight(1f))
                    BigButton("打开小窗", { nav.navigate("chat") }, Modifier.weight(1f))
                }
            }
        }

        Hint("这张卡点过之后就不会再重复弹；今天主动出声的上限是 6 次，会议/专注/深夜只排队。")
    }
}

@Composable
private fun CardPanel(card: Card) {
    val context = LocalContext.current
    Panel(accent = MaterialTheme.colorScheme.primary) {
        Text(card.title, style = MaterialTheme.typography.titleSmall, color = LocalSignals.current.muted)
        Text(card.ask, style = MaterialTheme.typography.titleMedium)
        if (card.confidence.isNotBlank()) {
            Text("我猜（把握：${card.confidence}）", style = MaterialTheme.typography.bodySmall, color = LocalSignals.current.muted)
        }
        card.sayLines.take(2).forEach { line ->
            Text("念这句：$line", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (card.choices.isNotEmpty()) {
            card.choices.take(4).forEach { c ->
                BigButton(
                    c.label,
                    {
                        if (c.key == "go") Relay.begin(context, card)
                        else DirectorHost.reply(context, Reply.Pick(card.id, System.currentTimeMillis(), c.key))
                    },
                    Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("卡住了", { DirectorHost.reply(context, Reply.Stuck(card.id, System.currentTimeMillis())) }, Modifier.weight(1f))
                BigButton("休息", { DirectorHost.reply(context, Reply.Rest(card.id, System.currentTimeMillis(), 0)) }, Modifier.weight(1f))
            }
        }
        if (card.actor != com.zongkong.core.director.Actor.NONE) {
            BigButton("去聊：${card.actor.label}", { Relay.begin(context, card) }, Modifier.fillMaxWidth())
        }
    }
}
