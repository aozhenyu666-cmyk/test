package com.moling.app.live

import android.content.Context
import android.provider.Settings
import com.moling.core.LiveTurn
import com.moling.core.live.Can
import com.moling.core.live.Modality

/*
 * 她出现的方式（v9 反免疫）：大脑每次挑一种（[LiveTurn.modality]），这里照着弹。
 *
 * 现在接好的：GATE（拦人页）、PEEK（底部卡片）。
 * DeepSeek 第六批（moling/DEEPSEEK-6.md）要填的：PET、STARE、COUNCIL、WHISPER、QUIZ——
 * 每种做好后在 [show] 里把对应的分支换成真的；没做好之前一律退回底部卡片，功能不缺，只是样子单一。
 *
 * 他怎么回应，各个界面调 [com.moling.app.BrainHost.react] 报回来（反免疫账本靠它）。
 */
object Appear {
    /** 此刻能用哪些方式。 */
    fun can(context: Context): Can = Can(
        overlay = Settings.canDrawOverlays(context),
        whisper = canWhisper(context),
        quiz = true,
    )

    /** 耳语能不能用：戴着耳机、不在安静时段（DeepSeek Z3 实现；之前一律不能）。 */
    fun canWhisper(context: Context): Boolean = false

    fun show(context: Context, turn: LiveTurn, label: String) {
        when (turn.modality) {
            Modality.GATE -> LiveScreens.gate(context, turn.mood, turn.line, label)
            // ↓ DeepSeek 第六批：Z2 桌宠 / Z4 盯着你 / Z5 参谋弹幕 / Z3 耳语 / Z6 捉虫题
            else -> LiveScreens.peek(context, turn.mood, turn.line, turn.modality)
        }
    }
}
