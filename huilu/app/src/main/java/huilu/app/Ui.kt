package huilu.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 不依赖任何 UI 库的小工具：用代码搭简单、稳定的界面。 */
object C {
    const val INK = 0xFF1D1D1B.toInt()
    const val MUTED = 0xFF6B6B66.toInt()
    const val ACCENT = 0xFF1F5C4A.toInt()
    const val WARN = 0xFFB5542B.toInt()
    const val CARD = 0xFFF5F1E8.toInt()
    const val LINE = 0xFFE2DCCF.toInt()
    const val BG = 0xFFFFFFFF.toInt()
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Activity.page(build: LinearLayout.() -> Unit): LinearLayout {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(32))
        build()
    }
    setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); isFillViewport = true; addView(box) })
    return box
}

fun LinearLayout.text(s: CharSequence, size: Float = 15f, color: Int = C.INK, bold: Boolean = false, top: Int = 4): TextView =
    TextView(context).also {
        it.text = s
        it.textSize = size
        it.setTextColor(color)
        if (bold) it.typeface = Typeface.DEFAULT_BOLD
        it.setLineSpacing(0f, 1.15f)
        addView(it, lp(top = top))
    }

fun LinearLayout.title(s: String) = text(s, 13f, C.MUTED, bold = true, top = 18)

fun LinearLayout.button(label: String, primary: Boolean = false, weight: Float = 0f, onClick: () -> Unit): Button =
    Button(context).also {
        it.text = label
        it.isAllCaps = false
        it.textSize = 15f
        it.setTextColor(if (primary) C.BG else C.INK)
        it.background = round(if (primary) C.ACCENT else C.CARD, if (primary) C.ACCENT else C.LINE)
        it.setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
        it.setOnClickListener { onClick() }
        val p = if (weight > 0) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.topMargin = context.dp(8)
        if (weight > 0) { p.marginEnd = context.dp(4); p.marginStart = context.dp(4) }
        addView(it, p)
    }

fun LinearLayout.edit(hint: String, value: String = "", lines: Int = 1, number: Boolean = false): EditText =
    EditText(context).also {
        it.hint = hint
        it.setText(value)
        it.textSize = 16f
        if (number) it.inputType = InputType.TYPE_CLASS_NUMBER
        else if (lines > 1) { it.minLines = lines; it.gravity = Gravity.TOP; it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        else it.inputType = InputType.TYPE_CLASS_TEXT
        addView(it, lp(top = 4))
    }

fun LinearLayout.row(build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(context).also {
    it.orientation = LinearLayout.HORIZONTAL
    it.build()
    addView(it, lp(top = 0))
}

fun LinearLayout.card(color: Int = C.CARD, build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(context).also {
    it.orientation = LinearLayout.VERTICAL
    it.background = round(color, C.LINE)
    it.setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(14))
    it.build()
    addView(it, lp(top = 12))
}

/** 每行 n 个按钮。 */
fun LinearLayout.grid(items: List<Pair<String, () -> Unit>>, n: Int = 2, primaryFirst: Boolean = false) {
    items.chunked(n).forEachIndexed { r, chunk ->
        row {
            chunk.forEachIndexed { i, (label, f) -> button(label, primary = primaryFirst && r == 0 && i == 0, weight = 1f, onClick = f) }
            repeat(n - chunk.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
        }
    }
}

fun View.round(color: Int, stroke: Int) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = context.dp(12).toFloat()
    setStroke(context.dp(1), stroke)
}

private fun LinearLayout.lp(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    .apply { topMargin = context.dp(top) }

/** 从已安装的 App 里多选。 */
fun Activity.pickApps(title: String, selected: Set<String>, onDone: (Set<String>) -> Unit) {
    val apps = App.of(this).platform.launchableApps()
    val extra = selected.filter { s -> apps.none { it.first == s } } // 曾经选过但已卸载的保留
    val all = apps + extra.map { it to "$it（未安装）" }
    val checked = BooleanArray(all.size) { all[it].first in selected }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setMultiChoiceItems(all.map { it.second }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
        .setPositiveButton("确定") { _, _ -> onDone(all.indices.filter { checked[it] }.map { all[it].first }.toSet()) }
        .setNegativeButton("取消", null)
        .show()
}
