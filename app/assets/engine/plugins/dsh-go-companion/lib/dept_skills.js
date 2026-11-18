// dsh-go-companion · 四部实战认知 Skill 引擎 (Cognitive OS Skills for Yuwan & Jinglan)
// 解决用户深刻痛点：
// 1) 「信息收集部 · 红旗防骗核验 Skill」：交易/付费前硬核查信用红旗，阻止冲动被骗 80 元这类事件重演；
// 2) 「谋划思考部 · 反沉没成本与主航道对齐 Skill」：拦截沉迷造自律轮子、逃避就业/考公核心主线的行为；
// 3) 「统筹规划部 · 三段因果链知识压缩 Skill」：拒绝信息堆砌，把零散资料压成 [触发场景 → 底层因果 → 下次唯一动作]；
// 4) 「行为管理/督办部 · 契约执法与弹性奖惩 Skill」：按契约检查点与视频提炼问答客观裁决。

export const COGNITIVE_SKILLS = Object.freeze([
  {
    id: 'skill-anti-scam-redflag',
    dept: 'dept:collect',
    name: '红旗防骗与信源核验清单',
    triggerDesc: '涉及付款、转账、买课、闲鱼/私下交易、先款后货或商家催促下单时必触发',
    redFlags: [
      { id: 'rf_credit', pattern: /(差评|投诉|跑路|信用差|历史纠纷|小号|刚注册|三无)/i, label: '存在信用瑕疵或匿名小号' },
      { id: 'rf_urgency', pattern: /(限时|最后名额|马上涨价|先付定金|私聊微信|走线下|免手续费)/i, label: '催促绕开平台担保或制造虚假紧迫感' },
      { id: 'rf_norefund', pattern: /(不退不换|概不退款|收货确认后才发|口头承诺)/i, label: '拒绝售后保障或用口头承诺替代契约' },
      { id: 'rf_anomaly', pattern: /(低价|羊毛|包过|保过|内部渠道|破解版)/i, label: '异常超低价或违背常识的“包过/内幕”承诺' },
    ],
  },
  {
    id: 'skill-anti-sunk-cost',
    dept: 'dept:strategize',
    name: '反沉没成本与主航道对齐审计',
    triggerDesc: '用户准备花费 >1 小时折腾工具、重写软件架构或陷入情绪懊恼过去浪费时间时触发',
    questions: [
      '假设过往消耗的时间、金钱、Token 今天全部归零，重新站在门外我还会开这个新坑吗？',
      '这件事今天做成 100 分，能直接换来考公笔面分数或真实就业面试机会吗？',
      '如果现有功能已够凑合 80%，现在是该用工具去干正事，还是在借“完善工具”逃避困难任务？',
    ],
  },
  {
    id: 'skill-causal-chain',
    dept: 'dept:dispatch',
    name: '三段因果链知识体系提炼法',
    triggerDesc: '看视频学知识、复盘踩坑或阅读长文后，将信息堆砌压成可复用体系',
    template: '[触发问题/场景] → [底层运行机制（为什么）] → [下次遇到时的唯一刚性动作]',
  },
])

/**
 * 对交易/消费/决策输入做红旗一票否决扫描（治“明明看到不靠谱还付钱被骗 80 元”）。
 */
export function runRedFlagScamAudit(text = '', { amount = 0, platformGuarantee = true } = {}) {
  const s = String(text || '').trim()
  const skill = COGNITIVE_SKILLS[0]
  const hits = []
  for (const rf of skill.redFlags) {
    if (rf.pattern.test(s)) hits.push({ id: rf.id, label: rf.label })
  }
  if (platformGuarantee === false) {
    hits.push({ id: 'rf_no_escrow', label: '脱离第三方担保交易' })
  }
  const riskyAmount = Number(amount) > 0 && hits.length > 0
  const verdict = hits.length >= 2 || riskyAmount ? 'BLOCK_RED_FLAG' : (hits.length === 1 ? 'WARN_VERIFY' : 'CLEAR')
  return {
    ok: true,
    skill: skill.id,
    verdict,
    redFlags: hits,
    recommendation: verdict === 'CLEAR'
      ? '暂未扫到明显交易红旗，仍建议保留付款与聊天凭证，走担保交易。'
      : `⚠️ 触发防骗一票否决（命中 ${hits.length} 条红旗：${hits.map((h) => h.label).join('、')}）——哪怕只有 50~80 元也立刻停手不付款！先把可疑点核验清再说。`,
  }
}

/**
 * 对“准备开新坑/完善工具/纠结过往浪费”做反沉没成本与主航道（考公/就业）对齐审计。
 */
export function runMainlineAlignmentAudit(proposal = '', { longGoals = [], toolTinkerMinToday = 0 } = {}) {
  const s = String(proposal || '').trim()
  const tinkering = /(优化软件|改ui|新功能|写脚本|换工具|重做系统|提示词|配置)/i.test(s) || toolTinkerMinToday >= 45
  const alignedMainline = /(考公|行测|申论|面试|投递|简历|真题|刷题|算法|岗)/i.test(s) ||
    longGoals.some((g) => g?.title && s.includes(String(g.title).slice(0, 4)))

  let score = 70
  if (tinkering && !alignedMainline) score = 25
  else if (alignedMainline) score = 92

  return {
    ok: true,
    skill: 'skill-anti-sunk-cost',
    alignmentScore: score,
    isToolEscapeRisk: tinkering && !alignedMainline,
    advice: tinkering && !alignedMainline
      ? '⚠️ 警报：当前动议有「借完善工具/磨刀逃避考公就业主线」高风险！按反沉没成本法则：工具能用 70 分就立刻停工，把接下来的 90 分钟全部砸向最直接的真题或简历产出。'
      : '✓ 与你的长期主航道对齐，建议直接在计划模态设定 1 个明确验收点后开干。',
    questions: COGNITIVE_SKILLS[1].questions,
  }
}

/**
 * 将一段视频提炼 / 踩坑记录压成标准三段因果链卡片（可供存入 Obsidian）。
 */
export function compileCausalChainCard({ trigger = '', mechanism = '', rule = '', source = '视频提炼/复盘' } = {}) {
  const t = String(trigger || '').trim()
  const m = String(mechanism || '').trim()
  const r = String(rule || '').trim()
  if (!t || !m || !r) {
    return {
      ok: false,
      error: '三段因果链必须完整：触发场景 + 底层机制 + 下次唯一行动，缺一不可（防信息堆砌）。',
    }
  }
  const markdown = [
    `### ${t}`,
    `- **来源**：${source}`,
    `- **底层因果机制**：${m}`,
    `- **唯一行动红线**：${r}`,
  ].join('\n')
  return {
    ok: true,
    skill: 'skill-causal-chain',
    card: { trigger: t, mechanism: m, rule: r, source, markdown },
  }
}

/**
 * 生成可注入大模型 System / Turn Prompt 的精简 Skill 指令块（按当前部门自适应高亮）。
 */
export function buildSkillsPromptBlock(activeDept = 'dept:govern') {
  const mapHint = {
    'dept:collect': '当前主激活【信息收集部 · 红旗防骗核验】：任何消费/外部承诺先扫红旗（小号/催单/私聊/不退不换），命中即喊停。',
    'dept:strategize': '当前主激活【谋划思考部 · 反沉没成本与主线对齐】：先看过往清零测试与考公/就业主航道贡献，阻止无效造轮子。',
    'dept:dispatch': '当前主激活【统筹规划部 · 因果链压缩】：把杂乱笔记和视频心得压成 [触发 → 机制 → 唯一动作]，禁止罗列空话。',
    'dept:govern': '当前主激活【督办审计部 · 契约验收】：对照检查点与视频压缩提炼追问 2 句真实因果，达标解锁，逾期严格锁定。',
  }
  return [
    '【四部体系化实战 Skill（必须调用工具核验，不做泛泛说教）】',
    mapHint[activeDept] || mapHint['dept:govern'],
    '可用专属工具：plan_check_apps / plan_set_contract / plan_verify_checkpoint / audit_decision_redflag / bank_import_questions。',
  ].join('\n')
}
