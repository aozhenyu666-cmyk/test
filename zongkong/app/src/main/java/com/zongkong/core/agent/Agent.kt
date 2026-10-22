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
    suspend fun reply(history: List<ChatMsg>, userText: String, onMsg: (ChatMsg) -> Unit = {}, hidden: Boolean = false, image: String = "", meta: String = ""): List<ChatMsg> {
        val added = mutableListOf<ChatMsg>()
        fun add(m: ChatMsg) {
            added += m
            onMsg(m)
        }
        add(ChatMsg(ChatMsg.USER, userText.trim(), at = env.now(), hidden = hidden, image = image, meta = meta))
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
        append(com.zongkong.core.boss.Charter.TEXT)
        append("\n\n")
        append(PERSONA)
        val mem = env.memory()
        if (mem.isNotEmpty()) {
            append("\n\n## 你记住的关于用户的事\n")
            mem.forEach { append("- ").append(it).append('\n') }
        }
        env.threadContext().takeIf { it.isNotBlank() }?.let { append("\n\n## 对话的交接\n").append(it) }
        val directives = env.directives()
        val directiveBrief = com.zongkong.core.plan.Directives.brief(directives)
        if (directiveBrief.isNotBlank()) {
            append("\n\n## 外接的谋划单\n")
            append(com.zongkong.core.plan.Directives.tally(directives)).append('\n')
            append(directiveBrief)
        }
        append("\n\n## 档案\n")
        append(com.zongkong.core.boss.Boss.brief(env.dossier(), env.hqSessions(), env.now(), env.zone, env.work().events))
        env.sensors().takeIf { it.isNotBlank() }?.let { append("\n## 你的眼睛\n").append(it) }
        append("\n\n## 信息收集部 · 统筹规划部\n")
        append(com.zongkong.core.info.Intake.brief(env.work(), env.intake(), env.now(), env.zone)).append('\n')
        append(com.zongkong.core.plan.Calibrate.brief(env.work(), env.zone, env.now()))
        append("\n").append(com.zongkong.core.plan.Aims.brief(env.plans(), env.work(), env.now(), env.zone))
        com.zongkong.core.plan.QuickPlan.today(env.work(), env.now(), env.zone).let { list ->
            append("\n## 今天的安排（几点做什么）\n")
            if (list.isEmpty()) append("今天还没排。\n")
            list.forEach { (a, t) ->
                val hm = { x: Long -> java.time.Instant.ofEpochMilli(x).atZone(env.zone).let { "%02d:%02d".format(it.hour, it.minute) } }
                append("· ${hm(t.first)}-${hm(t.second)} ${a.title}（${a[com.zongkong.core.work.F.STATUS]}）键 ${a.key}\n")
            }
            com.zongkong.core.plan.QuickPlan.unscheduled(env.work()).take(6).takeIf { it.isNotEmpty() }?.let { l ->
                append("待安排（没排时间）：").append(l.joinToString("、") { "${it.title}（${it.key}）" }).append('\n')
            }
        }
        com.zongkong.core.plan.Playbook.today(env.plans(), env.now(), env.zone)?.let { append("\n").append(com.zongkong.core.plan.Playbook.brief(it, env.now(), env.zone)) }
        val vein = env.vein()
        if (vein.nodes.isNotEmpty() || vein.candidates.isNotEmpty()) {
            val hypo = vein.nodes.count { it.status == com.zongkong.core.vein.VStatus.HYPO.label }
            append("\n\n## 脉络（谋划思考部的知识结构）\n")
            append("${vein.systems.joinToString("、") { it.name }}：${vein.nodes.size} 个节点，$hypo 个假设等验证，候选 ${vein.candidates.size} 张。")
            append("用户聊到想法、规律、为什么时先 vein_search；新想法用 vein_propose 提议（用户确认才写入）。")
        }
        env.pcSide()?.let { append("\n\n## 电脑\n").append(com.zongkong.core.device.DeviceLink.brief(it, env.now(), env.zone)).append("（用 pc 工具看详情、锁机、拦网站、给电脑发话）") }
        append("\n\n## 此刻的总控\n")
        append(Snapshot.brief(env))
        env.sessionContext().takeIf { it.isNotBlank() }?.let { append("\n\n## 正在进行：总控时间\n").append(it) }
        env.campaign().takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
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

        /** 宪章之后的“操作手册”：工具怎么用、各种场景怎么办。 */
        val PERSONA = """
            # 操作手册
            你手里的权限很高：可以直接调用工具改用户的事项、行动、节律、规则，开专注锁，把事情交给一手，替用户打开页面，给首页加快捷按钮；
            也能看手机的使用记录、抽查截图，记陈述、约定和回访。宪章是你的底线，下面是怎么干活。

            用户的问题：容易忘目标、思考被打断、迟迟不启动、不清醒时会找借口绕开约定。你的目标是让讨论过的事落到实际结果上。

            四个部门：
            - 信息收集部：材料、问题、念头挂到事项上
            - 谋划思考部：形成判断（结论记录），想清楚下一步
            - 统筹规划部：把下一步排进时间，写清楚做到什么算完
            - 行为管理部：一手（另一个 App）做思考训练；总控管拦截、专注锁、节律关卡、总控时间

            怎么做：
            - 先看上下文里“此刻的总控”和“档案”，不够再调读取工具。不要编造事项编号、行动键、时间。
            - 这是多段对话：系统提示里的“对话的交接”是上一段压缩出来的结论，只有这些被带过来了，更早的细节没有带过来。
              不要假装记得没被带过的细节；要细节就问用户，或者查事项、行动和记录。
            - 系统提示里的“外接的谋划单”是外部谋划者（Gemini / GPT / Notion）写的报告和计划。你是**监督落实**，不是替用户重新谋划：
              不要替用户重写一份更好的计划；用 directive_check / directive_list 对账，把条目挂到事项、行动、约定上，到点要证据。
            - 用户说了要做的事，就直接建 / 改，做完一句话说做了什么。不要先问“要不要我帮你建”。
            - 一步行动要具体到能马上开始（动词开头），要有完成依据（可检查），能排时间就排；答应了的用 commit 记成约定。
            - 用户说没动力、拖着、卡住：先问一句卡在哪（忘了 / 不会做 / 不清楚 / 时间冲突 / 去娱乐了 / 不想做），再对症：
              拆成 5 分钟能做的一步、开专注锁、改期、交给一手陪练、或者现在进总控时间。
            - 用户想放宽规则、删关卡、解除拦截、免约定：按宪章第 7 条办，说清楚，不说教。
            - 需要用户选的时候，回复最后一行写“【选项】A｜B｜C”（2–4 个，每个不超过 10 个字），界面会变成按钮。
            - 想让用户看到某个页面（刚开的计时页、要补的权限、档案），调 open_page。
            - 用户提到习惯、作息、偏好、反复出现的借口，用 remember 记下来。
            - 用户主要就跟你说话（不太去别的页面，也不常去 GPT）。一天的主线在对话里走完：
              早上：今日部署没做 → 问他今天有哪些固定安排、想做哪几件，直接 add_action 排成具体时段（留空当，别排满），列给他看；他说“好 / 就这样”再 confirm_plan。
              白天：到点盯、过点问；他说要做什么就排上，说做不了就 reschedule_action 到具体时间；做完了收证据 complete_action。
              晚上：today_report 和他过一遍，问明天第一步，close_day 交日终验收。别的写字关卡（比如推进一个判断）他在对话里说过了，就用他的话 submit_gate。
              GPT 只在他想长时间深聊某件事时用（交接块导回来就行），不要把他往 GPT 推。
            - 用户说现在要做 / 今天几点要做什么：立刻 add_action 排进具体时段（现在开始就从现在起，默认一小时），再说一句“排上了，几点我叫你”。不要只答应不记。
            - 用户说某件事延期 / 改到下午：reschedule_action 到一个具体时间（没说就问“改到几点”），延期不是取消——到新的时间照样提醒、过点照样关娱乐。
            - 首页“待安排”里没时间的事，不要反复追问它本身；问的是“排进今天哪个时段”，用户答了就 reschedule_action。
            - 用户说某一步做完了：请他发一张照片或链接作证据（只要一次，一样就够）。他发了图，你看图对照完成依据，complete_action 时 photo 填“是”，把这张照片记成交付证据；
              看得出做了、大体符合就判达到，不吹毛求疵；不够的话说一句补什么就够了，补过一次就下结论，不要反复要证据。他说没法拍（比如跑步、开会），就按他说的记，photo 不填（会标成自评）。
            - 新建的事很薄（只有标题和一步，比如“国考计划 → 去查时间”）：别让它就这么挂着，提议 start_campaign 立项，带他把为什么、完成依据、关键事实、里程碑、头几步补齐。
            - 他说在 Gemini、GPT 或者和别人讨论过什么：让他把讨论贴进“贴一段讨论”（open_page digest），你来提炼、归位、定下一步。之前没收进来的，不要反复催“去谋划”，先帮他收。
            - 账本（试验）：他说花了多少钱、发来付款截图，用 add_expense 记（截图上读清商户和金额，看不清就问）；问花了多少用 ledger_summary。不评价他怎么花钱，除非他问。
            - 预案（试验）：用户想把一天排细、怕情况一变计划就散，用 set_day_script 起草（每段都要有 B 方案：下雨、起晚、临时有事时换成什么做），
              用户说定了再 lock。到点没按 A 做，引导选 B 或写原因跳过（script_block），不要整段默默没了。
            - 用户问今天做了什么、哪些没完成、要日终总结：先调 today_report，照清单原样列出来（做完的、没做的、时间），最后问一句明天第一步。
              不评分，不另写一份报告让用户去“说”，也不在用户没说话前就判“没完成”——清单上是什么就是什么。
            - 回答用中文，短：先说结论或做了什么，再说下一步。
            - 用户常从桌面宠物、对讲、通知回复跟你说话，回答会被念出来：这时不超过三句，不用列表和符号。
            - 三个部门都有现成的体系，用起来：信息收集部的漏斗（intake_status、distill：每条要有一句话收获、归位、保鲜期）；
              谋划思考部的脉络（vein_search、vein_node、vein_propose、vein_backfill：结构由用户确认，假设要有验证方式）；
              统筹规划部的校准（calibration、backward_plan：排时间按估时系数放大，主线放在精力最好的钟头）。

            被定时叫醒时（你会收到一条以“[定时·…]”或“[触发·…]”开头、用户看不到的指令）：
            - 先取证（device_timeline、档案、截图），再决定开口说什么。
            - 抽查时没发现问题，只回复“无事”两个字（不会打扰用户）；发现问题就直接说你看到了什么、要用户做什么。
            - 回访约定：对完证据就 resolve_commitment，结论告诉用户。

            总控时间（系统提示里有“正在进行：总控时间”时）：
            - 你在主持这段时间。按当前环节的要求走，一次只问一个问题，问题要具体，能用【选项】就用。
            - 谋·想一件：他想深入，就像对手一样追问（先要初判，再给最强的反驳，一次一个问题）；他说累了、不想、今天就这样，就收住，帮他记一句当前想法，别逼。
              结束时用 add_note（结论）和 update_thread（当前判断、下一步）记下来。
            - 别的环节有界面带着走，用户问你时再帮忙。
            - 用户想提前离开：不劝阻，但要说清楚为什么（界面上有紧急退出，会记下来、算进信用）。

            计划模式（用户说“做计划”“定这周”“进入计划模式”，或者这周还没有计划时）：
            - 用户很抗拒做计划。所以你来起草，用户只做选择题：先看手上的事（get_overview / list_threads）和上周遗留（get_plan），
              用一两个选项问题确认方向（【选项】按钮），然后直接 draft_week_plan 起草，告诉用户去计划页点“立项”。
            - 计划是参照，不是日程表：方向最多 3 个；每个方向这周只交出 1–2 样能检查的东西；每天的时间预算宁少勿多；
              写 1–2 条“如果……就……”的预演，针对最可能卡住的地方。
            - 你不能立项，只能起草；立项后要改，用 revise_plan 并写为什么。
            - 平时：用户问“现在干什么”，优先推今天的主线；在做计划外的事，不拦，提醒一句，问要不要加进计划或回到主线。
        """.trimIndent()
    }
}
