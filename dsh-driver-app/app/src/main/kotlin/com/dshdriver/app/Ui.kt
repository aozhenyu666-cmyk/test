package com.dshdriver.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 界面用的结果色：一处定义，圆点和文字都从这里取。
 * OK 只表示"这件事已经就绪"，不表示表扬。
 */
enum class Tone { OK, WARN, BAD, NEUTRAL }

/** 拦截门用：禁掉长按菜单里的粘贴，答案只能自己打出来。 */
private class NoPasteEditText(context: Context) : EditText(context) {
    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) return true
        return super.onTextContextMenuItem(id)
    }
}

/**
 * 一套颜色。浅色给首页，深色给盖在别的应用上的答题页和悬浮卡。
 *
 * 参照 Claude 的暖色调：米白底、暖灰字、陶土橙只用于主行动，其余靠 1px 边框和留白分层，
 * 不用阴影，也不用高饱和的色块。
 */
class Palette(
    val bg: Int,
    val surface: Int,
    val surfaceAlt: Int,
    val border: Int,
    val text: Int,
    val textSecondary: Int,
    val textTertiary: Int,
    val accent: Int,
    val onAccent: Int,
    val accentSoft: Int,
    val ok: Int,
    val okSoft: Int,
    val warn: Int,
    val warnSoft: Int,
    val danger: Int,
    val dangerSoft: Int,
    val dark: Boolean,
) {
    companion object {
        /** 首页：#FAF9F5 米白、#1F1E1D 墨字、#D97757 陶土橙。 */
        val light = Palette(
            bg = 0xFFFAF9F5.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            surfaceAlt = 0xFFF0EEE6.toInt(),
            border = 0xFFE6E2D9.toInt(),
            text = 0xFF1F1E1D.toInt(),
            textSecondary = 0xFF605D57.toInt(),
            textTertiary = 0xFF9A958C.toInt(),
            accent = 0xFFD97757.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            accentSoft = 0xFFFBEDE7.toInt(),
            ok = 0xFF2F6F54.toInt(),
            okSoft = 0xFFE9F1EA.toInt(),
            warn = 0xFF94651F.toInt(),
            warnSoft = 0xFFF8F0DF.toInt(),
            danger = 0xFFB4453A.toInt(),
            dangerSoft = 0xFFFAEAE7.toInt(),
            dark = false,
        )

        /** 答题页 / 悬浮卡：盖在别的应用上，用低亮度的暖灰，夜里不刺眼。 */
        val dark = Palette(
            bg = 0xFF262624.toInt(),
            surface = 0xFF30302E.toInt(),
            surfaceAlt = 0xFF3A3A37.toInt(),
            border = 0xFF45453F.toInt(),
            text = 0xFFF5F4EF.toInt(),
            textSecondary = 0xFFB5B2AA.toInt(),
            textTertiary = 0xFF8A867E.toInt(),
            accent = 0xFFE08A6B.toInt(),
            onAccent = 0xFFFFF9F5.toInt(),
            accentSoft = 0xFF3C2B24.toInt(),
            ok = 0xFF7BC0A0.toInt(),
            okSoft = 0xFF22322B.toInt(),
            warn = 0xFFD8A55F.toInt(),
            warnSoft = 0xFF352C1E.toInt(),
            danger = 0xFFE79A90.toInt(),
            dangerSoft = 0xFF3A2422.toInt(),
            dark = true,
        )
    }
}

/** 一小块状态瓦片：上面小字说明是什么，下面一句话说现在怎么样。 */
class Tile(val label: String, val value: String, val tone: Tone)

/**
 * 画界面的地方。全部用代码搭，不依赖 androidx。
 *
 * 用法：先 [reset] 给一个纵向容器，之后每次 add 都落在当前容器里；
 * [card] 会临时把当前容器换成卡片内容区，画完自动还回去，所以卡片可以嵌套。
 */
class Ui(private val ctx: Context, private val p: Palette) {

    lateinit var target: LinearLayout
        private set

    private val sans: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val serif: Typeface = Typeface.create("serif", Typeface.NORMAL)

    val colors: Palette get() = p

    fun reset(container: LinearLayout) {
        target = container
    }

    fun px(v: Number): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics,
    ).toInt()

    // ───────────────────────────── 文字 ─────────────────────────────

    /** 小字标签：字号小、字距大，用来说明下面这块是什么。 */
    fun kicker(text: String, color: Int = p.textTertiary): TextView = make(text, 11f, color, medium, spacing = 0.13f)
        .also { target.addView(it, match(bottom = 8f)) }

    /** 页面主标题。衬线，和正文拉开层次。 */
    fun headline(text: String, size: Float = 28f): TextView = make(text, size, p.text, serif)
        .also {
            it.setLineSpacing(px(4).toFloat(), 1f)
            target.addView(it, match(bottom = 8f))
        }

    fun lead(text: String): TextView = make(text, 14.5f, p.textSecondary, sans)
        .also {
            it.setLineSpacing(px(5).toFloat(), 1f)
            target.addView(it, match(bottom = 16f))
        }

    fun cardTitle(text: String): TextView = make(text, 16f, p.text, medium)
        .also { target.addView(it, match(bottom = 8f)) }

    fun text(text: String, size: Float = 14.5f, color: Int = p.text, top: Float = 0f, bottom: Float = 4f): TextView =
        make(text, size, color, sans)
            .also {
                it.setLineSpacing(px(4).toFloat(), 1f)
                target.addView(it, match(top = top, bottom = bottom))
            }

    fun caption(text: String): TextView = make(text, 12.5f, p.textSecondary, sans)
        .also {
            it.setLineSpacing(px(4).toFloat(), 1f)
            target.addView(it, match(bottom = 6f))
        }

    fun footnote(text: String): TextView = make(text, 11.5f, p.textTertiary, sans)
        .also {
            it.setLineSpacing(px(3).toFloat(), 1f)
            target.addView(it, match(top = 6f, bottom = 4f))
        }

    fun spacer(h: Float) = target.addView(View(ctx), LinearLayout.LayoutParams(1, px(h)))

    fun divider() = target.addView(View(ctx).apply { setBackgroundColor(p.border) },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1)).also {
            it.topMargin = px(14)
            it.bottomMargin = px(4)
        })

    private fun make(text: String, size: Float, color: Int, tf: Typeface, spacing: Float = 0f): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = tf
            includeFontPadding = false
            if (spacing != 0f) letterSpacing = spacing
        }

    // ───────────────────────────── 卡片 ─────────────────────────────

    /** 白底、1px 暖灰边框、14dp 圆角；[muted] 时用米色底表示"次要 / 只读"。 */
    fun card(
        title: String? = null,
        chip: String? = null,
        tone: Tone = Tone.NEUTRAL,
        muted: Boolean = false,
        build: () -> Unit,
    ): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(18), px(18))
            background = round(if (muted) p.surfaceAlt else p.surface, 14f,
                if (muted) null else p.border, 1f)
        }
        target.addView(box, match(bottom = 12f))
        if (title != null) {
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(make(title, 16f, p.text, medium), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (chip != null) head.addView(pill(chip, tone), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            box.addView(head, match(bottom = 10f))
        }
        val outer = target
        target = box
        build()
        target = outer
        return box
    }

    /** 横排容器，等分宽度用 weight。 */
    fun row(build: () -> Unit): LinearLayout {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        target.addView(r, match(top = 2f))
        val outer = target
        target = r
        build()
        target = outer
        return r
    }

    /** 在横排里塞一个等分格子，然后往里画。 */
    fun cell(weight: Float = 1f, right: Float = 0f, build: () -> Unit) {
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        target.addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).also {
            it.rightMargin = px(right)
        })
        val outer = target
        target = c
        build()
        target = outer
    }

    // ───────────────────────────── 状态 ─────────────────────────────

    /** 药丸标签。改文字走 [styleChip]。 */
    fun chip(label: String, tone: Tone = Tone.NEUTRAL, align: Int = Gravity.START): TextView =
        pill(label, tone).also { v ->
            target.addView(
                v,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                    it.topMargin = px(6)
                    it.gravity = align
                },
            )
        }

    private fun pill(label: String, tone: Tone): TextView {
        val (fg, bg) = toneColors(tone)
        return make(label, 11.5f, fg, medium, spacing = 0.02f).apply {
            setPadding(px(10), px(5), px(10), px(5))
            background = round(bg, 99f)
        }
    }

    fun styleChip(v: TextView, label: String, tone: Tone) {
        val (fg, bg) = toneColors(tone)
        v.text = label
        v.setTextColor(fg)
        v.background = round(bg, 99f)
    }

    private fun toneColors(tone: Tone): Pair<Int, Int> = when (tone) {
        Tone.OK -> p.ok to p.okSoft
        Tone.WARN -> p.warn to p.warnSoft
        Tone.BAD -> p.danger to p.dangerSoft
        Tone.NEUTRAL -> p.textSecondary to p.surfaceAlt
    }

    /** 一排状态瓦片：拦截 / 大脑 / 无障碍，一眼看全部就绪与否。 */
    fun tiles(items: List<Tile>) {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        target.addView(r, match(bottom = 16f))
        items.forEachIndexed { i, t ->
            val (fg, _) = toneColors(t.tone)
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(12), px(11), px(12), px(12))
                background = round(p.surfaceAlt, 12f)
            }
            box.addView(make(t.label, 10.5f, p.textTertiary, medium, spacing = 0.06f))
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(View(ctx).apply { background = round(fg, 99f) },
                LinearLayout.LayoutParams(px(6), px(6)).also { it.rightMargin = px(6) })
            line.addView(make(t.value, 13f, fg, medium))
            box.addView(line, match(top = 5f))
            r.addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                if (i > 0) it.leftMargin = px(8)
            })
        }
    }

    /** 数字 + 说明，横排等分。返回数字视图，方便之后再改（只改字，不重建）。 */
    fun stats(items: List<Pair<String, String>>): List<TextView> {
        val values = mutableListOf<TextView>()
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        target.addView(r, match(top = 4f, bottom = 4f))
        items.forEachIndexed { i, (value, name) ->
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val num = make(value, 22f, p.text, serif)
            values += num
            box.addView(num)
            box.addView(make(name, 11f, p.textTertiary, sans).apply { setPadding(0, px(2), 0, 0) })
            // 紧凑并排，不平摊整行；数字之间留 26dp 呼吸
            r.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                if (i > 0) it.leftMargin = px(26)
            })
        }
        return values
    }

    /** 大号数字（倒计时）。衬线，和正文区分开。 */
    fun display(text: String, size: Float): TextView =
        make(text, size, p.text, serif).also { target.addView(it, match(top = 6f, bottom = 0f)) }

    /** 一条细进度，用来表示这一段过去了多少。 */
    fun meter(height: Float = 5f): Bar = Bar(ctx).also { b ->
        b.trackColor = p.surfaceAlt
        b.fillColor = p.accent
        target.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(height)).also {
            it.topMargin = px(12)
            it.bottomMargin = px(2)
        })
    }

    // ───────────────────────────── 按钮 ─────────────────────────────

    fun primary(label: String, onClick: () -> Unit): Button =
        button(label, p.accent, p.onAccent, p.accent, onClick)

    fun secondary(label: String, onClick: () -> Unit): Button =
        button(label, p.surfaceAlt, p.text, 0x00000000, onClick)

    fun quiet(label: String, color: Int = p.textSecondary, onClick: () -> Unit): Button =
        button(label, 0x00000000, color, 0x00000000, onClick, height = 46)

    fun dangerButton(label: String, onClick: () -> Unit): Button =
        button(label, p.dangerSoft, p.danger, 0x00000000, onClick)

    private fun button(
        label: String,
        bg: Int,
        fg: Int,
        stroke: Int,
        onClick: () -> Unit,
        height: Int = 46,
    ): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        typeface = medium
        setTextColor(fg)
        stateListAnimator = null
        minimumWidth = 0
        minWidth = 0
        minimumHeight = 0
        minHeight = 0
        setPadding(px(14), 0, px(14), 0)
        gravity = Gravity.CENTER
        background = press(round(bg, 10f, if (stroke == 0) null else stroke, 1f), bg)
        setOnClickListener { onClick() }
    }.also {
        target.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(height)).also { lp ->
            lp.topMargin = px(10)
        })
    }

    /** 大号选项按钮（答题卡的 A / B）：左对齐、够高，单手也好点。 */
    fun option(label: String, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        typeface = sans
        setTextColor(p.text)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        stateListAnimator = null
        minimumWidth = 0
        minWidth = 0
        minimumHeight = 0
        minHeight = 0
        setPadding(px(18), 0, px(18), 0)
        background = press(round(p.surfaceAlt, 12f, p.border, 1f), p.surfaceAlt)
        setOnClickListener { onClick() }
    }.also {
        target.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(54)).also { lp ->
            lp.topMargin = px(10)
        })
    }

    /** 小块按钮（悬浮卡上用）：低调、不占地方。 */
    fun micro(label: String, tone: Tone = Tone.NEUTRAL, onClick: () -> Unit): TextView {
        val (fg, bg) = toneColors(tone)
        val color = if (tone == Tone.NEUTRAL) p.textSecondary else fg
        return make(label, 12.5f, color, medium, spacing = 0.01f).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(px(10), px(8), px(10), px(8))
            background = press(round(bg, 9f), bg)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }.also {
            target.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { lp ->
                lp.leftMargin = px(3)
                lp.rightMargin = px(3)
            })
        }
    }

    /** 圆角 + 按压反馈；底色透明时只给水波纹，不画形状。 */
    private fun press(content: Drawable, bg: Int): Drawable {
        val ripple = if (bg == 0x00000000) {
            if (p.dark) 0x22FFFFFF else 0x14000000
        } else {
            if (p.dark) 0x33FFFFFF else 0x33FFFFFF
        }
        return RippleDrawable(ColorStateList.valueOf(ripple), content, null)
    }

    // ───────────────────────────── 输入 ─────────────────────────────

    fun field(
        hint: String,
        value: String = "",
        numeric: Boolean = false,
        multiline: Boolean = false,
        lines: Int = 1,
        noPaste: Boolean = false,
    ): EditText = (if (noPaste) NoPasteEditText(ctx) else EditText(ctx)).apply {
        this.hint = hint
        setText(value)
        textSize = 14.5f
        typeface = sans
        setTextColor(p.text)
        setHintTextColor(p.textTertiary)
        setPadding(px(14), px(12), px(14), px(12))
        background = round(fieldBg(), 10f, p.border, 1f)
        inputType = when {
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            numeric -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT
        }
        // 顺序要紧：先放开单行，再设最少行数，否则多行框会被压回一行高
        setSingleLine(!multiline)
        if (multiline) {
            minLines = lines.coerceAtLeast(2)
            gravity = Gravity.TOP or Gravity.START
        }
    }.also { v ->
        val lp = match(top = 4f, bottom = 2f)
        if (multiline) lp.height = px(24f * lines.coerceAtLeast(2) + 26f)
        target.addView(v, lp)
    }

    /**
     * 一排可点的选项（比如"多久"）。选中的那个用陶土橙细边。
     * [onPick] 只回调下标，选中样式由这里自己改。
     */
    fun choice(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        var picked = selected
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        target.addView(r, match(top = 6f, bottom = 4f))
        val pills = mutableListOf<TextView>()
        lateinit var repaint: () -> Unit
        repaint = {
            pills.forEachIndexed { i, v ->
                val on = i == picked
                v.setTextColor(if (on) p.accent else p.textSecondary)
                v.typeface = if (on) medium else sans
                v.background = round(if (on) p.accentSoft else fieldBg(), 9f, if (on) p.accent else p.border, 1f)
            }
        }
        options.forEachIndexed { i, label ->
            val v = TextView(ctx).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, px(10), 0, px(10))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    picked = i
                    repaint()
                    onPick(i)
                }
            }
            pills += v
            r.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                if (i > 0) it.leftMargin = px(8)
            })
        }
        repaint()
    }

    /**
     * 一行权限：左边名字 + 一行为什么要它，右边"已开"或者"去打开"。
     * 已开的不给按钮，避免满屏都是可点的东西。
     */
    fun permission(name: String, ok: Boolean, detail: String? = null, action: (() -> Unit)? = null) {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        target.addView(r, match(top = 8f, bottom = 8f))
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(make(name, 14.5f, p.text, medium))
        if (!ok && detail != null) {
            left.addView(make(detail, 12f, p.textSecondary, sans).apply {
                setLineSpacing(px(3).toFloat(), 1f)
                setPadding(0, px(3), 0, 0)
            })
        }
        r.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
            it.rightMargin = px(12)
        })
        if (ok) {
            r.addView(pill("已开", Tone.OK))
        } else if (action != null) {
            val b = TextView(ctx).apply {
                text = "去打开"
                textSize = 13f
                typeface = medium
                setTextColor(p.accent)
                setPadding(px(12), px(8), px(12), px(8))
                isClickable = true
                isFocusable = true
                background = press(round(p.accentSoft, 9f), p.accentSoft)
                setOnClickListener { action() }
            }
            r.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        } else {
            r.addView(pill("未开", Tone.WARN))
        }
    }

    /** 列表项：一句正文 + 可选的一句出处。 */
    fun item(title: String, detail: String? = null) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(View(ctx).apply { background = round(p.textTertiary, 99f) },
            LinearLayout.LayoutParams(px(4), px(4)).also {
                it.topMargin = px(7)
                it.rightMargin = px(10)
            })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(make(title, 14f, p.text, sans).apply { setLineSpacing(px(4).toFloat(), 1f) })
        if (detail != null) {
            col.addView(make(detail, 11.5f, p.textTertiary, sans).apply { setPadding(0, px(3), 0, 0) })
        }
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        target.addView(row, match(top = 5f, bottom = 5f))
    }

    /** 一条提示：左边竖线用状态色，正文用反色底卡片。 */
    fun callout(text: String, tone: Tone = Tone.NEUTRAL) {
        val (fg, bg) = toneColors(tone)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(12), px(11), px(12), px(11))
            background = round(bg, 10f)
        }
        box.addView(View(ctx).apply { setBackgroundColor(fg) },
            LinearLayout.LayoutParams(px(2), LinearLayout.LayoutParams.MATCH_PARENT).also { it.rightMargin = px(10) })
        box.addView(make(text, 13.5f, p.text, sans).apply {
            setLineSpacing(px(4).toFloat(), 1f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        target.addView(box, match(top = 8f, bottom = 4f))
    }

    private fun fieldBg(): Int = if (p.dark) p.surfaceAlt else p.bg

    // ───────────────────────────── 排版小工具 ─────────────────────────────

    private fun match(top: Float = 0f, bottom: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = px(top)
            it.bottomMargin = px(bottom)
        }

    fun round(color: Int, radius: Float, stroke: Int? = null, strokeWidth: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = px(radius).toFloat()
            if (stroke != null) setStroke(px(strokeWidth).coerceAtLeast(1), stroke)
        }
}

/** 一条细进度：剩余时间占整段的比例。只有几 px 高，不抢注意力。 */
class Bar(ctx: Context) : View(ctx) {
    var fraction: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var trackColor: Int = 0xFFE6E2D9.toInt()
    var fillColor: Int = 0xFFD97757.toInt()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        if (h <= 0f) return
        val r = h / 2f
        paint.color = trackColor
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, r, r, paint)
        val w = width * fraction
        if (w > 0f) {
            paint.color = fillColor
            rect.set(0f, 0f, w.coerceAtLeast(h), h)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}
