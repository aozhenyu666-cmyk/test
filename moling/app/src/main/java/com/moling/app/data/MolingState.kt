package com.moling.app.data

import com.moling.core.atlas.Atlas
import com.moling.core.flow.FlowPlan
import com.moling.core.predict.DayStat
import com.moling.core.predict.Prediction
import com.moling.core.talk.Turn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * 墨灵 App 自己的一份状态（M2）。
 *
 * 核心库不碰 Android、也不写文件；界面要用的东西攒成这一份，整份写成 mind/moling.json。
 * 字段顺序照约定；以后加字段必须给默认值，旧文件才读得回来。
 */

@Serializable
data class MolingState(
    val flow: FlowPlan? = null,
    val atlas: Atlas = Atlas(),
    val topicId: String = "",
    val predictions: List<Prediction> = emptyList(),
    val days: List<DayStat> = emptyList(),
    val talk: List<Turn> = emptyList(),
    val pending: List<PendingAction> = emptyList(),
)

/** 这一批不执行的动作（总控指令 / 去聊），先记账，下一批再接。 */
@Serializable
data class PendingAction(val id: String, val kind: String, val text: String, val at: Long)

object MolingCodec {
    // 旧版本多出来的字段直接忽略；默认值不写，文件才小。
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(s: MolingState): String = json.encodeToString(MolingState.serializer(), s)

    /** 读坏了不抛异常：给一份空状态，界面照常起（FileMindStore 还留着 .bak）。 */
    fun decode(text: String?): MolingState =
        if (text.isNullOrBlank()) MolingState()
        else runCatching { json.decodeFromString(MolingState.serializer(), text) }.getOrDefault(MolingState())
}
