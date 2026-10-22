package com.zongkong.sentinel

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 极简的界面拼装：米色纸面、深色字，普通 View，不依赖任何库。 */
class Ui(private val a: Activity) {
    private val dp = a.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()
    val root = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(PAPER)
        setPadding(px(24), px(56), px(24), px(32))
    }

    fun text(t: String, size: Float, color: Int = INK, bold: Boolean = false): TextView = TextView(a).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, px(12)); setLineSpacing(0f, 1.2f)
        root.addView(this)
    }

    fun button(t: String, primary: Boolean, onClick: () -> Unit): Button = Button(a).apply {
        text = t; textSize = 16f; isAllCaps = false
        setTextColor(if (primary) Color.WHITE else INK)
        setBackgroundColor(if (primary) RED else BUTTON)
        setOnClickListener { try { onClick() } catch (_: Throwable) {} }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(52)).apply { topMargin = px(10) }
        root.addView(this)
    }

    fun scroll() = ScrollView(a).apply { setBackgroundColor(PAPER); addView(root) }

    companion object {
        val INK = Color.rgb(0x2A, 0x24, 0x1F)
        val PAPER = Color.rgb(0xF4, 0xF1, 0xEA)
        val RED = Color.rgb(0xB5, 0x3A, 0x2A)
        val GREEN = Color.rgb(0x3E, 0x7A, 0x4F)
        val MUTED = Color.rgb(0x5A, 0x53, 0x4B)
        val FAINT = Color.rgb(0x8A, 0x83, 0x7B)
        val BUTTON = Color.rgb(0xE6, 0xE1, 0xD6)
    }
}
