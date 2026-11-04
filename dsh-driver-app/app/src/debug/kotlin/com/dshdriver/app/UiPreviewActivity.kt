package com.dshdriver.app

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * 仅存在于 debug 构建：把两张暗色页单独画出来，方便在设备上直接看排版（不用等真的切屏或被拦截）。
 * 用 `adb shell am start -n com.dshdriver.app/.UiPreviewActivity --es screen card|gate` 打开。
 * release 构建不含这个类，见 app/src/debug/AndroidManifest.xml。
 */
class UiPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Palette.dark
        val ui = Ui(this, p)
        window.statusBarColor = p.bg

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 靠上一点：拦截 / 提醒页是要立刻读的，居中的话上半屏会空掉
            setPadding(ui.px(22), ui.px(88), ui.px(22), ui.px(28))
            setBackgroundColor(p.bg)
        }
        ui.reset(page)

        when (intent.getStringExtra("screen")) {
            "gate" -> {
                ui.kicker("拦截中")
                ui.headline("答一道题再进", 24f)
                ui.lead("说依据就行：因为什么、题干哪一句、什么规律。")
                ui.card(muted = true) {
                    ui.kicker("这一问")
                    ui.text("你上次说图形推理错在「对称轴方向」——这道题的第一条对称轴是横向还是纵向？", 17f, p.text, top = 2f, bottom = 0f)
                }
                ui.spacer(14f)
                ui.field("写下你的依据", multiline = true, lines = 3)
                ui.text("太短了，说清因为什么。", 13f, p.warn, top = 6f, bottom = 0f)
                ui.primary("提交") { }
                ui.quiet("不打开了") { }
            }
            else -> {
                ui.kicker("你切走了")
                ui.headline("你说过「递归先写边界」——那个函数的边界，在哪一行？", 24f)
                ui.option("A   在 n 等于 0 的地方") { }
                ui.option("B   在第一次调用之前") { }
                ui.spacer(12f)
                ui.field("或者打一句 / 两个词", multiline = true, lines = 2)
                ui.primary("发送") { }
                ui.divider()
                ui.row {
                    ui.micro("回到 Live") { }
                    ui.micro("休息…") { }
                    ui.micro("紧急结束", Tone.BAD) { }
                }
            }
        }

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(page) })
    }
}
