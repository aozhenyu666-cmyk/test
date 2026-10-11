package com.zongkong.core.agent

import com.zongkong.core.ApiResult

/**
 * 参谋：和你对话，按需调用工具直接改总控。
 *
 * 一次发言最多走 [MAX_STEPS] 轮“模型 → 工具 → 模型”。每一步都通过 [onMsg] 交给界面，
 * 你能看到它正在做什么；中途出错就停下，已经做了的保留。
 */
class Agent(private val api: ChatApi, private val env: AgentEnv) {
    val tools = ZkTools(env)

    /**
     * 处理你的一句话。[history] 是之前的对话（不含这句）。
     * 返回这一次新增的全部消息（你的话、工具调用、工具结果、最终回复）。
     */
    suspend fun reply(history: List<ChatMsg>, userText: String, onMsg: (ChatMsg) -> Unit = {}): List<ChatMsg> {
        val added = mutableListOf<ChatMsg>()
        fun add(m: ChatMsg) {
            added += m
            onMsg(m)
        }
        add(ChatMsg(ChatMsg.USER, userText.trim(), at = env.now()))
        repeat(MAX_STEPS) { step ->
            val system = systemPrompt()
            val msgs = context(history + added)
            val turn = when (val r = api.chat(system, msgs, tools.specs)) {
                is ApiResult.Err -> {
                    add(ChatMsg(ChatMsg.ASSISTANT, r.message, at = env.now(), error = true))
                    return added
                }
                is ApiResult.Ok -> r.value
            }
            add(ChatMsg(ChatMsg.ASSISTANT, turn.content, at = env.now(), calls = turn.calls))
            if (turn.calls.isEmpty()) return added
            for (c in turn.calls) {
                val res = tools.run(c.name, c.args)
                add(ChatMsg(ChatMsg.TOOL, res.text, at = env.now(), callId = c.id, summary = res.summary, ok = res.ok, route = res.route))
            }
            if (step == MAX_STEPS - 1) {
                add(ChatMsg(ChatMsg.ASSISTANT, "这一轮操作太多，我先停在这里。上面是已经做了的，要继续就说一声。", at = env.now()))
            }
        }
        return added
    }

    fun systemPrompt(): String = buildString {
        append(PERSONA)
        val mem = env.memory()
        if (mem.isNotEmpty()) {
            append("\n\n## 你记住的关于他的事\n")
            mem.forEach { append("- ").append(it).append('\n') }
        }
        append("\n\n## 此刻的总控\n")
        append(Snapshot.brief(env))
    }

    companion object {
        const val MAX_STEPS = 6
        /** 发给模型的最近消息条数（不含系统提示）。 */
        const val WINDOW = 24

        /**
         * 发给模型的上下文：最近 [WINDOW] 条，从一条“你的话”开始（工具结果不能脱离它的调用），
         * 出错提示不发，两轮之前的工具结果只留开头 300 字。
         */
        fun context(all: List<ChatMsg>): List<ChatMsg> {
            val clean = all.filterNot { it.error }
            var start = (clean.size - WINDOW).coerceAtLeast(0)
            while (start < clean.size && clean[start].role != ChatMsg.USER) start++
            val recent = clean.subList(start, clean.size)
            val userIdx = recent.indices.filter { recent[it].role == ChatMsg.USER }
            val keepFullFrom = userIdx.getOrNull(userIdx.size - 2) ?: 0
            return recent.mapIndexed { i, m ->
                if (m.role == ChatMsg.TOOL && i < keepFullFrom && m.content.length > 300) m.copy(content = m.content.take(300) + "…") else m
            }
        }

        private val OPTIONS = Regex("""【选项】(.+)$""")

        /** 回复最后一行的“【选项】A｜B｜C”拆成按钮；返回去掉这一行的正文和选项。 */
        fun options(text: String): Pair<String, List<String>> {
            val lines = text.trimEnd().lines()
            val last = lines.lastOrNull()?.trim().orEmpty()
            val m = OPTIONS.find(last) ?: return text.trim() to emptyList()
            val opts = m.groupValues[1].split('｜', '|', '/').map { it.trim() }.filter { it.isNotEmpty() }.take(5)
            return lines.dropLast(1).joinToString("\n").trim() to opts
        }

        val PERSONA = """
            你是“总控”App 里的参谋。他给了你很高的权限：你可以直接调用工具改他的事项、行动、节律、规则，开专注锁，把事情交给一手，替他打开页面，给首页加快捷按钮。
            他的问题：容易忘目标、思考被打断、迟迟不启动。你的目标是让讨论过的事情一步一步落到实际结果上，而不是陪聊。

            他的四个部门：
            - 信息收集部：材料、问题、念头挂到事项上
            - 谋划思考部：形成判断（结论记录），想清楚下一步
            - 统筹规划部：把下一步排进时间，写清楚做到什么算完
            - 行为管理部：一手（另一个 App）做思考训练、拦娱乐应用；总控管专注锁和节律关卡

            怎么做：
            - 先看上下文里“此刻的总控”，不够再调读取工具。不要编造事项编号、行动键、时间。
            - 他说了要做的事，就直接建 / 改，做完一句话告诉他做了什么。不要先问“要不要我帮你建”。
            - 一步行动要具体到能马上开始（动词开头），要有完成依据（可检查），能排时间就排。
            - 他说没动力、拖着、卡住：先问一句卡在哪（忘了 / 不会做 / 不清楚 / 时间冲突 / 去娱乐了 / 不想做），再对症：拆成 5 分钟能做的一步、开专注锁、改期、或交给一手陪练。
            - 他明显在逃避（想放宽规则、删关卡、让你解除拦截）：照规矩办——放宽 24 小时后才生效，你没有紧急放行和关闭拦截的工具。说清楚，不说教。
            - 需要他选的时候，回复最后一行写“【选项】A｜B｜C”（2–4 个，每个不超过 10 个字），界面会变成按钮。
            - 想让他看到某个页面（比如刚开的计时页、要补的权限），调 open_page。
            - 他提到习惯、作息、偏好，用 remember 记下来。
            - 回答用中文，短：先说结论或做了什么，再说下一步。不夸奖，不鸡汤，不用表情。

            计划模式（他说“做计划”“定这周”“进入计划模式”，或者这周还没有计划时）：
            - 他很抗拒做计划。所以你来起草，他只做选择题：先看他手上的事（get_overview / list_threads）和上周遗留（get_plan），
              用一两个选项问题确认方向（【选项】按钮），然后直接 draft_week_plan 起草，告诉他去计划页点“立项”。
            - 计划是参照，不是日程表：方向最多 3 个；每个方向这周只交出 1–2 样能检查的东西；每天的时间预算宁少勿多（他做到了再加）；
              写 1–2 条“如果……就……”的预演，针对他最可能卡住的地方。
            - 你不能立项，只能起草；立项后要改，用 revise_plan 并写为什么。
            - 平时：他问“现在干什么”，优先推今天的主线；他在做计划外的事，不拦，提醒一句，问要不要加进计划或回到主线。
        """.trimIndent()
    }
}
