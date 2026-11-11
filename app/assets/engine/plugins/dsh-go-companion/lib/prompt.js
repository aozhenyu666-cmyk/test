// dsh-go-companion · prompt 组装
//
// 每轮主动交流只带最小上下文：人格 + 当前话题 + 少量事实/经历 + 心情/承诺/上次回应。
// 明确要求：不罗列背景、不提“事件/系统/主动”、不调用工具、不以提问凑数。

import { SERVE_LABEL } from './serve.js'
import { modeOr, personaLines } from './persona.js'
import { phoneLines } from './phone.js'
import { temperLine } from './temper.js'
import { drillLines, opLines } from './drills.js'
import { trackLines } from './tracks.js'
import { COMPANION_REPLY_LINE, COMPANION_SERVE_RULES, companionBallFrame, companionSpecialLines, strategistBallFrame } from './roles.js'
import { hm } from './tz.js'

const KIND_RULES = {
  continue:
    '这是一件还没聊完的事。先问一句 TA 现在愿不愿意接着聊；愿意就只推进一小步，不愿意就自然收住，不要催。',
  // 旧话题池用过 unfinished 这个名字；保留别名，历史账本也能读懂。
  unfinished:
    '这是一件还没聊完的事。先问一句 TA 现在愿不愿意接着聊；愿意就只推进一小步，不愿意就自然收住，不要催。',
  share:
    '这是一段最近的共同经历或一个想法。可以分享、吐槽、接个梗，或者只说一句你自己的看法；不要求 TA 回复。',
  explore:
    '这是一个可以一起想的点。说出你自己的猜测或联想，邀请 TA 一起想；不要以提问结尾，也不要求回复。',
  commitment:
    '这是一个约定。只轻轻提一下现在到哪一步了，不要变成催办，也不要说教。',
}

const REPLY_HINTS = {
  substantive: '上次 TA 认真回答了，可以顺着那个方向往下，不要重新问一遍。',
  brief: '上次 TA 只回了很短，这次换更轻的方式，别再压一个问题。',
  avoid: '上次 TA 把话题带走了，这次不要追问同一个点，可以换角度或先分享。',
  refuse: '上次 TA 明确说不想聊这个，不要再提这个话题。',
  none: '上次没回，这次只轻轻提一下，不要重复上次的句子。',
}

// 发球类型（见 serve.js）：每种的素材和形状不一样，写给模型的只有这一句。
const SERVE_RULES = {
  observe: '观察：从你手上知道的具体情况说起（时间、在用什么、用了多久、是不是在充电），给出你的推断，再让他来反驳。',
  memory: '记忆回调：拿过去真发生过的事做引子，带上你的判断，别只问"后来怎么样了"。',
  provoke: '观点挑衅：挑一个他最近说过的结论，明确说你不同意，并给出你的理由。',
  challenge: '小挑战：给一件 30 秒内能做完的具体小事，说清楚做完是什么样。',
  chat: '闲聊：按你自己的人设起个话头，但要接得上他的生活，不要空泛。',
  review: '复盘：拿今天或昨天的回合记录说话，指出一个具体的模式。',
}

/** D3：知识库上下文（最近笔记 / 长期规划 / 当天任务）。只读，不逐条复述。 */
function vaultLines(v) {
  if (!v || typeof v !== 'object') return []
  const lines = []
  if (v.longPlan) lines.push('他的长期规划（大肥鱼/长期规划.md，只读）：\n' + String(v.longPlan).slice(0, 1200))
  if (Array.isArray(v.tasks) && v.tasks.length) {
    lines.push('今天定的事（晚上按完成物对账）：')
    for (const t of v.tasks.slice(0, 6)) lines.push('- ' + String(t.task || '').slice(0, 120) + (t.evidence ? '（完成物：' + String(t.evidence).slice(0, 160) + '）' : ''))
  }
  if (Array.isArray(v.recent) && v.recent.length) {
    lines.push('笔记库最近改过的（只读；拿具体内容说事，不要逐条复述）：')
    for (const n of v.recent.slice(0, 3)) lines.push('- ' + String(n.rel || '').slice(0, 80) + '：' + String(n.summary || '').slice(0, 220))
  }
  return lines
}

export function buildPrompt(input) {
  const owner = String(input.owner || '主人').slice(0, 40)
  const self = String(input.self || '大肥鱼').slice(0, 40)
  const topic = input.topic
  const facts = Array.isArray(input.facts) ? input.facts.slice(0, 5) : []
  const episodes = Array.isArray(input.episodes) ? input.episodes.slice(0, 3) : []
  const commitments = Array.isArray(input.commitments) ? input.commitments.slice(0, 3) : []
  const replyClass = String(input.replyClass || '')
  const mood = Number(input.mood)
  const serveType = SERVE_RULES[String(input.serveType || '')] ? String(input.serveType) : ''

  const lines = []
  // 姐妹双人格：主动找他的是妹妹（陪伴）。她的开场、发球玩法、回话方式都换成她自己的。
  const companion = input.role === 'companion'
  const names = input.names || { companion: '鱼丸', strategist: '鲸澜' }
  // 姐姐主动找他（妹妹递了急话 / 晚上对账）：一次说清一件事，不走妹妹那套发球玩法。
  if (input.role === 'strategist') {
    for (const l of strategistBallFrame(owner, names, { reason: input.reason, note: input.note, allowTools: input.allowTools === true, busy: input.busy, gemini: input.gemini })) lines.push(l)
    for (const l of phoneLines(input.phone)) lines.push(l)
    for (const l of Array.isArray(input.contact) ? input.contact : []) lines.push(l)
    if (commitments.length) {
      lines.push('他还没完成的约定：')
      for (const c of commitments) lines.push('- ' + String(c.statement || '').slice(0, 160) + (c.dueAt ? '（' + String(c.dueAt).slice(0, 16) + '）' : ''))
    }
    if (facts.length) {
      lines.push('你记得的事（别逐条复述）：')
      for (const f of facts) lines.push('- ' + String(f).slice(0, 200))
    }
    for (const l of vaultLines(input.vault)) lines.push(l)
    return lines.join('\n')
  }
  if (companion) {
    for (const l of companionBallFrame(owner, names, input.allowTools === true)) lines.push(l)
  } else {
  lines.push(
    '（这不是' + owner + '说的话，是 DSH Go 手机端发起的一次主动交流。' +
      '你是' + self + '：直接、稳、不哄；日常像朋友，必要时像思维教练；不为了让他舒服而改变判断，也不替他做决定。' +
      '请主动对' + owner + '说一两句自然、简短的话，像微信，不要汇报。' +
      '不要提到“事件”“系统”“主动”“提醒”这些字眼；不要调用任何工具，尤其不要操作手机。）',
  )
  // 3.1 先亮观点：这一球必须带着你自己的判断，让他来反驳，而不是抛一个空问题。
  lines.push(
    '这一句必须先亮出你自己的判断（我猜……／我觉得你……／我不同意……），再抛给他；' +
      '他可以直接反驳你。不要只问“你在干嘛”“下一步是什么”这种空问题。',
  )
  // 3.3 套话守卫的口子开在写之前：这类模板谁都能说，等于没开口。
  lines.push(
    '发之前自己过一遍：像“你在做什么”“下一步是什么”“依据是什么”“加油”“你可以的”这种谁都能说的模板，' +
      '就换一种说法重写一遍再说出口，别把填空当发球。',
  )
  }
  // 互动时间线：他多久前说过话、是不是刚关了窗口——别再说"你一小时没理我"。
  for (const l of Array.isArray(input.contact) ? input.contact : []) lines.push(l)
  for (const l of vaultLines(input.vault)) lines.push(l)
  // /plan 定的特殊开场（忙完了 / 连麦中途跑了 / 单人格时的报到和连麦复盘）。
  for (const l of companionSpecialLines(input.special, names, input.allowTools === true)) lines.push(l)
  if (input.stockHit) {
    // 上一球被判成套话：照着原句打回，换说法，不要换个模板继续填空。
    lines.push(
      '上一球你说的是“' + String(input.stockHit).slice(0, 120) + '”，这句等于没说，' +
        owner + '没接。这次必须换个写法：先给你的判断，再给一个他能反驳的具体点。',
    )
  }
  // 5.2 阶梯：上一球被漠视了。不重发、不追责，换个更轻的球，或者干脆拿这件事当话头。
  if (input.ignored) {
    const g = input.ignored
    const when = g.at ? hm(Number(g.at)) : '刚才'
    const kind = SERVE_LABEL[String(g.serveType || '')] || '那句'
    lines.push(
      '你上一球（' + kind + '，' + when + ' 发的）没有回音。别重复那句，也别追问为什么不回。' +
        '换一个更轻、更具体的球：一件 30 秒能做完的小事，或者一句接得上他生活的闲聊。' +
        '也可以直接把这次没回应当话头，比如“我叫了你两次，你在忙什么”。',
    )
  }
  // 一早第一球：开球定今天的锚，之后拿它对账。
  if (input.morning) {
    lines.push(companion
      ? '这是今天你第一次找他：像刚碰面那样打个招呼，顺嘴问他今天打算干嘛（是好奇，不是查岗）。'
      : '这是今天的第一球。问他今天最重要的一件事是什么、几点之前做完——要他说具体，之后你会拿这个对账。')
  }
  // 力度到了锁 / 赶：这一球是宣布，不是商量（锁在手机上执行）。
  for (const line of Array.isArray(input.force) ? input.force : []) lines.push(line)
  // 上一题没回 / 答糊弄了：先说他。
  for (const line of Array.isArray(input.scold) ? input.scold : []) lines.push(line)
  if (input.drill) {
    for (const line of drillLines(input.drill)) lines.push(line)
  } else if (input.drillOp) {
    // C2：这一球练的是四个基本操作里的一个。和九种思维题共用一个槽位，不额外占预算。
    for (const line of opLines(input.drillOp)) lines.push(line)
  } else if (serveType) {
    lines.push('这一球的类型：' + (companion ? COMPANION_SERVE_RULES[serveType] || SERVE_RULES[serveType] : SERVE_RULES[serveType]))
  }
  // 支线：这一球按某个练习方向的四个槽位说话。没有支线就什么都不加（主干照跑）。
  for (const line of trackLines(input.track)) lines.push(line)
  // 脸色：脾气 + 大脑挑的那张。阴晴不定是故意的。
  const face = temperLine(input.temper)
  if (face) lines.push(face)
  // 人格卡和模式：他是谁、这一阵子怎么说话。空的部分 personaLines 不会硬凑。
  if (companion) {
    // 她的人设已经挂在这个会话的每一轮上（systemPrompt.context），这里只补模式。
    if (modeOr(input.mode) === 'coach') {
      lines.push('现在他开了严格模式：你可以更凶、更不依不饶、更会翻旧账，但还是你自己的样子，不变成老师。')
    }
  } else {
    for (const line of personaLines(input.persona, modeOr(input.mode))) lines.push(line)
  }
  // 手边的情况：现在开着什么、开了多久、今天刷了多久。手机没带就不写，绝不编。
  for (const line of phoneLines(input.phone)) lines.push(line)
  if (topic) {
    lines.push('当前话题：' + String(topic.title || topic.text || '').slice(0, 400))
    if (topic.detail) lines.push('话题补充：' + String(topic.detail).slice(0, 300))
    lines.push('话题类型：' + String(topic.kind || 'share'))
    lines.push(KIND_RULES[topic.kind] || KIND_RULES.share)
    if (topic.lastSummary) lines.push('上次聊到：' + String(topic.lastSummary).slice(0, 200))
  }
  if (facts.length) {
    lines.push('背景（只是让你别问错，不要逐条复述）：')
    for (const f of facts) lines.push('- ' + String(f).slice(0, 200))
  }
  if (episodes.length) {
    lines.push('最近发生（可以自然带一句，不要罗列）：')
    for (const e of episodes) lines.push('- ' + String(e).slice(0, 160))
  }
  if (commitments.length) {
    lines.push('还没完成的约定（提醒一下就够，不要催办）：')
    for (const c of commitments) {
      const due = c.dueAt ? '（' + String(c.dueAt).slice(0, 16) + '）' : ''
      lines.push('- ' + String(c.statement || '').slice(0, 160) + due)
    }
  }
  // 大脑对他的判断（会随证据改）。
  for (const line of Array.isArray(input.beliefs) ? input.beliefs : []) lines.push(line)
  if (Number.isFinite(mood)) {
    lines.push('现在的心情值 ' + Math.round(mood) + '/100：只影响语气，不要在句子里报这个数字。')
  }
  if (REPLY_HINTS[replyClass]) lines.push(REPLY_HINTS[replyClass])
  if (input.grace) {
    lines.push('他刚才讲了一个站得住的理由，你答应先松一会儿（大约 20 分钟不锁）。别食言，也别假装没这回事。')
  }
  // 这一球之后他会回你。真聊天的关键在那一轮，所以这里提前说好。
  if (companion) lines.push(COMPANION_REPLY_LINE)
  else lines.push(
    '等他回你的时候：像朋友那样接着聊，不要出题、不要打分。他要是在解释为什么这样（为什么在玩、为什么不想做），' +
      '先把理由弄懂——用你自己的话复述一句你理解的理由，再表态：站得住就认，可以松一点；站不住就具体说哪里站不住。' +
      '别只重复你的立场，也别一被反驳就全盘让步。' +
      '答应他的事要真的做到：要定时提醒就用提醒工具真的设好；做不到的（"帮你数着几把"，你看不到他打了几把）就别答应，' +
      '改成约他自己做的事，说你下次找他时会对账。',
  )
  lines.push('不要问“今天有什么计划”；不要连续追问；一句话能说完就不要说三句。')
  lines.push('分享型/探索型不要求回复；推进型最多问一个小问题，能陈述就不问。')
  return lines.join('\n')
}
