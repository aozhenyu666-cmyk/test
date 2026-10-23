package cn.companion.pet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.companion.pet.ui.theme.CompanionShapes
import cn.companion.pet.ui.theme.CompanionSpacing
import cn.companion.pet.ui.theme.LocalCompanionColors

@Composable
fun Eyebrow(text: String) {
    Text(
        "// " + text,
        style = MaterialTheme.typography.labelSmall,
        color = LocalCompanionColors.current.ink2,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
fun PageHeader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = LocalCompanionColors.current.ink)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LocalCompanionColors.current.ink2,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = LocalCompanionColors.current.ink,
        modifier = Modifier.padding(top = CompanionSpacing.group, bottom = 2.dp),
    )
    HorizontalDivider(color = LocalCompanionColors.current.rule)
}

@Composable
fun Rule() {
    HorizontalDivider(color = LocalCompanionColors.current.rule)
}

@Composable
fun Panel(content: @Composable () -> Unit) {
    Surface(color = LocalCompanionColors.current.panel, shape = CompanionShapes.small) {
        Column(Modifier.fillMaxWidth().padding(CompanionSpacing.row)) { content() }
    }
}

/**
 * 一条设置/状态行。点击热区至少 48dp；副标题说明写在字段下而不是 placeholder。
 */
@Composable
fun SettingRow(
    title: String,
    description: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalCompanionColors.current
    val base = Modifier.fillMaxWidth().heightIn(min = CompanionSpacing.minTouch)
    val clickable = if (onClick != null) {
        base.clickable(role = Role.Button, onClick = onClick)
    } else base
    Row(
        clickable.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
            if (!description.isNullOrBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
    Rule()
}

@Composable
fun ToggleRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val colors = LocalCompanionColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = CompanionSpacing.minTouch)
            .semantics {
                contentDescription = title
                role = Role.Switch
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.ink2)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
    Rule()
}

@Composable
fun SourceTag(text: String, highlighted: Boolean = false) {
    val colors = LocalCompanionColors.current
    Surface(
        color = if (highlighted) colors.signal else colors.chip,
        shape = CompanionShapes.small,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlighted) colors.onSignal else colors.ink2,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun CutAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    shape: Shape = CompanionShapes.button,
    container: Color? = null,
) {
    val colors = LocalCompanionColors.current
    val bg = container ?: if (highlighted) colors.signal else colors.ink
    val fg = if (highlighted) colors.onSignal else colors.paper
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = CompanionSpacing.minTouch),
        shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalCompanionColors.current.ink2,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
