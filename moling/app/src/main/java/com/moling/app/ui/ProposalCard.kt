package com.moling.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moling.core.Proposal
import com.moling.core.ProposalState

/**
 * 一条「提议」的卡片：对话页和今天页共用。
 *
 * 只显示和回调，不碰 BrainHost——真正执行在用户点“好”之后由大脑做。
 * 所以这里出现的任何字都只是“墨灵想做什么”，不是“已经做了什么”。
 */
@Composable
fun ProposalCard(
    proposal: Proposal,
    onAccept: (Proposal) -> Unit,
    onDecline: (Proposal) -> Unit,
    onRetry: (Proposal) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 4.dp)) {
            if (proposal.auto) Text(
                "墨灵直接交的", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(proposal.text, style = MaterialTheme.typography.bodyLarge)
            if (proposal.why.isNotBlank()) {
                Text(
                    proposal.why,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            when (proposal.state) {
                ProposalState.PENDING -> Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onDecline(proposal) }) { Text("不用") }
                    TextButton(onClick = { onAccept(proposal) }) {
                        Text("好", color = MaterialTheme.colorScheme.primary)
                    }
                }

                ProposalState.FAILED -> Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 把总控给的原因也带上，用户才知道是没装、没开还是版本旧。
                    Text(
                        if (proposal.receipt.isBlank()) "没送到总控"
                        else "没送到总控：${proposal.receipt}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onRetry(proposal) }) { Text("重试") }
                }

                ProposalState.SENT -> Column(Modifier.padding(top = 2.dp)) {
                    Text(
                        proposal.state.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 结局优先；授权倒计时和普通待确认分别说明。
                    Text(
                        proposal.zkOutcome.ifBlank {
                            if (proposal.receipt == "授权内：总控倒计时执行") "60 秒后执行，可以在总控里取消"
                            else "等你在总控里确认"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> Text(                    proposal.state.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
            }
        }
    }
}