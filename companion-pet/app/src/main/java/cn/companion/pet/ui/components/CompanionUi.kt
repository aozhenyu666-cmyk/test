package cn.companion.pet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = LocalCompanionColors.current.accent,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
fun PageHeader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = LocalCompanionColors.current.ink)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LocalCompanionColors.current.ink2,
                modifier = Modifier.padding(top = 6.dp),
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
        modifier = Modifier.padding(top = CompanionSpacing.group, bottom = 8.dp),
    )
}

@Composable
fun Rule() {
    HorizontalDivider(color = LocalCompanionColors.current.rule)
}

@Composable
fun Panel(content: @Composable () -> Unit) {
    Surface(
        color = LocalCompanionColors.current.panel,
        shape = CompanionShapes.small,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, LocalCompanionColors.current.rule),
    ) {
        Column(Modifier.fillMaxWidth().padding(CompanionSpacing.card)) { content() }
    }
}

@Composable
fun HeroPanel(content: @Composable () -> Unit) {
    Surface(
        color = LocalCompanionColors.current.ink,
        shape = CompanionShapes.hero,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) { content() }
    }
}

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
        clickable.padding(vertical = 12.dp),
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
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.labelMedium, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            .padding(vertical = 10.dp),
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
        color = if (highlighted) colors.accent else colors.chip,
        shape = CompanionShapes.pill,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlighted) colors.onAccent else colors.ink2,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
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
    val bg = container ?: if (highlighted) colors.accent else colors.ink
    val fg = if (highlighted) colors.onAccent else colors.paper
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
fun QuietAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = CompanionShapes.button,
        modifier = Modifier.heightIn(min = CompanionSpacing.minTouch),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalCompanionColors.current.ink2,
        modifier = Modifier.padding(vertical = 10.dp),
    )
}

/**
 * 空状态：说明 + 一个明确的下一步。
 * 只显示一句灰字会让用户不知道能做什么，这是审计里的 P2。
 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalCompanionColors.current
    Panel {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = colors.ink2,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (actionLabel != null && onAction != null) {
            CutAction(actionLabel, onAction, modifier = Modifier.padding(top = 12.dp), highlighted = true)
        }
    }
}

/**
 * API Key 这类敏感输入框。
 *
 * 不用 PasswordVisualTransformation：它会让系统把输入框判定为密码框，
 * vivo/iQOO 会切到“安全键盘”，而安全键盘默认禁止粘贴，用户无法粘 Key。
 * 这里改为自己打码，并提供显式“粘贴”按钮（直接读剪贴板，不经过输入法）。
 */
private class MaskTransformation : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val masked = "\u25CF".repeat(text.text.length)
        val map = object : androidx.compose.ui.text.input.OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offset.coerceIn(0, masked.length)
            override fun transformedToOriginal(offset: Int): Int = offset.coerceIn(0, masked.length)
        }
        return androidx.compose.ui.text.input.TransformedText(androidx.compose.ui.text.AnnotatedString(masked), map)
    }
}

@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val visibleState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val visible = visibleState.value
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier.fillMaxWidth()) {
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else MaskTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
                autoCorrect = false,
            ),
            trailingIcon = {
                androidx.compose.material3.TextButton(onClick = { visibleState.value = !visibleState.value }) {
                    Text(if (visible) "隐藏" else "显示", style = MaterialTheme.typography.labelMedium)
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            androidx.compose.material3.TextButton(onClick = {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                val clip = cm?.primaryClip
                val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0)?.text?.toString() else null
                if (text.isNullOrBlank()) {
                    android.widget.Toast.makeText(context, "剪贴板里没有文字", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    onValueChange(text.trim())
                    android.widget.Toast.makeText(context, "已粘贴", android.widget.Toast.LENGTH_SHORT).show()
                }
            }) { Text("粘贴", style = MaterialTheme.typography.labelMedium) }
            androidx.compose.material3.TextButton(onClick = { onValueChange("") }) {
                Text("清空", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
