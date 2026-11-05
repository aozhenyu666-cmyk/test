package com.yishou.app.thread

import com.yishou.app.judge.LockedJson
import com.yishou.app.judge.LockedQ
import org.json.JSONArray
import org.json.JSONObject

/**
 * 思维线程：一个能判定的目标，加上几项要先定下来的事。
 * 每一问都挂在一个待定项上；连着两手没推进，就拉回来。
 * 中途走开了，下次过入口第一屏就是断点——思路靠入口续上，不靠人记得。
 */

enum class ItemState { OPEN, SETTLED }

/** 待定项：要定下目标，必须先回答的一个问题 */
data class Item(
    val id: String,
    val question: String,
    val state: ItemState = ItemState.OPEN,
    /** 定下来的那句（用户自己的话） */
    val conclusion: String = "",
    /** 这一项上已经接住了几手 */
    val passes: Int = 0,
)

enum class MoveKind {
    /** 推进一项 */
    STEP,
    /** 给这一项下结论 */
    CLOSE,
    /** 跑偏了，拉回来 */
    PULL_BACK,
    /** 从外部 AI 带回来的总结 */
    EXTERNAL,
    /** 所有项都定了：回答目标 */
    FINAL,
}

/** 下过的一手（记账、对比卡、导出用） */
data class Move(
    val at: Long,
    val itemId: String?,
    val kind: MoveKind,
    val ask: String,
    val answer: String,
    val passed: Boolean,
    val offTopic: Boolean,
)

/** 正在等回答的那一问：出题时就锁死存库 */
data class Pending(val kind: MoveKind, val itemId: String?, val q: LockedQ)

data class Strand(
    val id: Long = 0,
    /** 一句能判定的目标，比如“周六几点出发” */
    val goal: String,
    /** 用户自己写的：什么时候算定了 */
    val doneWhen: String,
    val items: List<Item>,
    val moves: List<Move> = emptyList(),
    /** 连着几手没推进 */
    val stall: Int = 0,
    val pending: Pending? = null,
    val done: Boolean = false,
    /** 目标的最终答案 */
    val answer: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /** 断点：第一个还没定的项 */
    val current: Item? get() = items.firstOrNull { it.state == ItemState.OPEN }
    val settled: List<Item> get() = items.filter { it.state == ItemState.SETTLED }

    fun toJson(): JSONObject = JSONObject()
        .put("goal", goal)
        .put("doneWhen", doneWhen)
        .put("items", JSONArray(items.map {
            JSONObject().put("id", it.id).put("question", it.question).put("state", it.state.name)
                .put("conclusion", it.conclusion).put("passes", it.passes)
        }))
        .put("moves", JSONArray(moves.map {
            JSONObject().put("at", it.at).put("itemId", it.itemId ?: "").put("kind", it.kind.name).put("ask", it.ask)
                .put("answer", it.answer).put("passed", it.passed).put("offTopic", it.offTopic)
        }))
        .put("stall", stall)
        .put("pending", pending?.let { JSONObject().put("kind", it.kind.name).put("itemId", it.itemId ?: "").put("q", LockedJson.toJson(it.q)) })
        .put("done", done)
        .put("answer", answer)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        /** id 由数据库给，不存在 JSON 里 */
        fun fromJson(id: Long, o: JSONObject): Strand {
            val items = o.getJSONArray("items")
            val moves = o.optJSONArray("moves") ?: JSONArray()
            val p = o.optJSONObject("pending")
            return Strand(
                id = id,
                goal = o.getString("goal"),
                doneWhen = o.getString("doneWhen"),
                items = (0 until items.length()).map { i ->
                    val it = items.getJSONObject(i)
                    Item(it.getString("id"), it.getString("question"), ItemState.valueOf(it.getString("state")), it.optString("conclusion"), it.optInt("passes"))
                },
                moves = (0 until moves.length()).map { i ->
                    val m = moves.getJSONObject(i)
                    Move(m.getLong("at"), m.optString("itemId").ifEmpty { null }, MoveKind.valueOf(m.getString("kind")), m.optString("ask"),
                        m.optString("answer"), m.optBoolean("passed"), m.optBoolean("offTopic"))
                },
                stall = o.optInt("stall"),
                pending = p?.let { Pending(MoveKind.valueOf(it.getString("kind")), it.optString("itemId").ifEmpty { null }, LockedJson.fromJson(it.getJSONObject("q"))) },
                done = o.optBoolean("done"),
                answer = o.optString("answer"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
            )
        }
    }
}
