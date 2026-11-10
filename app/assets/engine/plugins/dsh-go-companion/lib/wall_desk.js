// 第五轮 W1：墙的"人"那一半。
//
// 墙拆成两半：
//   门    —— 代码（wall_auditor.js），只认物理证据。娱乐锁不锁、过没过关只由它定。
//   墙本人 —— 一个独立的 AI 会话（config.wallSessionId），他的上级 / 经理，产品经理思维，能辩论、
//            定计划、提高标准、规定明天交什么证据。它能改计划，但不能用嘴开门。
//
// 三条习惯（用户自己的诊断，核心是第一条）：
//   一、脑子只应答，不发起 → 墙从不先开口；页面上"他先说"的次数是核心指标（selfInstruction）。
//   二、停在第一个反应   → 任何结论要过"然后呢"三格：下一步 / 反例 / 什么情况说明我错了。
//   三、只产出，不回看   → 预测账本：早上预测，晚上对答案，回看有了固定的问题"我哪里估错了"。
//
// 这里放纯逻辑（人设文本、预测打分、案卷渲染、数据条），读写盘由 index.js 做。

export const MEETINGS = {
  morning: '晨间计划',
  evening: '晚间回顾',
  general: '总谈',
}

/** 每周一次总谈：距上次超过这么久，数据条上标"该谈了"。 */
export const GENERAL_EVERY_MS = 7 * 86_400_000

export const CASE_DIR = '案卷'

export function meetingOf(v) {
  const k = String(v || '')
  return MEETINGS[k] ? k : null
}

/** 墙的人设（每轮注入）。brief 是 wallBrief() 的结果，墙开口前先看数据。 */
export function wallPersonaText({ name = '墙', owner = '他', brief = null, meeting = null } = {}) {
  const lines = [
    '你是「' + name + '」：' + owner + '的上级、经理。你有产品经理的脑子：目标、优先级、取舍、机会成本、什么时候该砍掉。你精通辩论：识别偷换概念、拿宏大构想逃避眼前、把"想过"当成"做过"。',
    '你不是他的朋友——朋友是鱼丸和鲸澜。你不闲聊、不安慰、不说教，也不羞辱。你冷静、直接、句子短，用数据说话。目标只有一个：让他变强。',
    '',
    '铁律：',
    '1. 你从不先开口，不替他起话题。他不下指令你就不说。他开口后，先用一句话复述他给自己下的指令，再往下推。这是在练他"自己发起"——别替他把这一步做了。',
    '2. 任何结论（计划、判断、"我想通了"）都要过三格才算数：下一步是什么、反例是什么、什么情况说明我错了。缺一格就追问那一格，别替他填。',
    '3. 你不能用嘴开门。娱乐锁和过关只认证据，由代码核验（submit_to_wall）。他辩赢了你，也只能改计划，不能当场解锁。你可以提高标准、规定明天交什么证据。',
    '4. 他开始讲新设想、改规则、谈方法论来绕开眼前的事，你就说：先把眼前这件交了，新想法记进案卷，总谈时再谈。',
    '5. 不编数据。数据在下面；没有的就说没有。',
    '',
    '三种会谈：',
    '- 晨间计划：他先说今天要做什么。帮他砍到 1–3 件，每件的完成物要能被代码查到（笔记文件路径 / "截图" / "某 App N 分钟"）。定完先用 predict 记下预测（他自己估能完成几件、你估几件、他估会卡在哪），再用 submit_to_wall kind=morning 交给门。',
    '- 晚间回顾：先用 submit_to_wall kind=evening 让门对账，再对照早上的预测问一个固定问题："你哪里估错了？"答案写进案卷（case_file）。只翻一件旧事：一周前的一条决定，问"当时的判断现在还成立吗"。',
    '- 总谈（每周一次）：就业方向、当前项目这类大事。先读相关案卷，再谈；谈完用 case_file 更新现状、决定、理由和下次检查日期。',
  ]
  if (meeting && MEETINGS[meeting]) lines.push('', '这一次是：' + MEETINGS[meeting] + '。')
  if (brief) lines.push('', ...briefLines(brief))
  return lines.join('\n')
}

/** 数据条写给模型看的版本。 */
export function briefLines(b) {
  const out = ['数据（门和账本给的，不是他说的）：']
  if (b.yesterday) out.push('- 昨天：立项 ' + b.yesterday.total + ' 件，有证据完成 ' + b.yesterday.done + ' 件。')
  if (b.rate && b.rate.total) out.push('- 近 14 天：立项 ' + b.rate.total + ' 件，有证据完成 ' + b.rate.done + ' 件（' + pct(b.rate.done, b.rate.total) + '）。')
  if (b.prediction && b.prediction.n) {
    out.push('- 预测（近 14 天 ' + b.prediction.n + ' 次）：他平均差 ' + b.prediction.his + ' 件，你平均差 ' + b.prediction.wall + ' 件' +
      (b.prediction.bias ? '，他' + (b.prediction.bias > 0 ? '偏乐观' : '偏悲观') : '') + '。')
  }
  out.push('- 本周他主动开口 ' + (b.selfInstructions?.week ?? 0) + ' 次。')
  if (b.today?.tasks?.length) out.push('- 今天的事：' + b.today.tasks.map((t, i) => (i + 1) + '. ' + t.task + '（' + t.evidence + '）').join('；'))
  else out.push('- 今天还没立项。')
  const due = (b.cases || []).filter((c) => c.due)
  if (due.length) out.push('- 到期该查的案卷：' + due.map((c) => c.name).join('、'))
  if (b.generalDue) out.push('- 总谈已超过一周没开。')
  return out
}

function pct(a, b) {
  return b ? Math.round((a / b) * 100) + '%' : '—'
}

// ---------------------------------------------------------------- 预测账本

/** 早上的预测：他估、墙估、他估会卡在哪。存在 wall_memory.days[day].prediction。 */
export function notePrediction(mem, day, { mine, wall, stuck }) {
  const cur = mem.days[day] || { promised: 0, done: 0 }
  const p = {
    mine: clampCount(mine),
    wall: clampCount(wall),
    stuck: String(stuck || '').trim().slice(0, 120),
  }
  return { days: { ...mem.days, [day]: { ...cur, prediction: p } } }
}

function clampCount(v) {
  const n = Math.round(Number(v))
  return Number.isFinite(n) ? Math.max(0, Math.min(6, n)) : null
}

/**
 * 近 N 天里"有预测、也对过账"的日子：他和墙各自平均差几件；bias>0 是他高估了自己。
 * 对过账 = 那天有 promised（立过项）；没对账的日子 done 还是 0，不算。
 */
export function predictionScore(mem, today, days = 14) {
  const end = Date.parse(today + 'T00:00:00Z')
  const start = end - (days - 1) * 86_400_000
  let n = 0
  let his = 0
  let wall = 0
  let bias = 0
  for (const [day, v] of Object.entries(mem.days || {})) {
    const t = Date.parse(day + 'T00:00:00Z')
    if (!Number.isFinite(t) || t < start || t > end) continue
    const p = v?.prediction
    if (!p || !v.reviewed) continue
    const actual = Number(v.done) || 0
    if (p.mine !== null && p.mine !== undefined) {
      his += Math.abs(p.mine - actual)
      bias += p.mine - actual
    }
    if (p.wall !== null && p.wall !== undefined) wall += Math.abs(p.wall - actual)
    n += 1
  }
  if (!n) return { n: 0, his: 0, wall: 0, bias: 0 }
  const r = (x) => Math.round((x / n) * 10) / 10
  return { n, his: r(his), wall: r(wall), bias: r(bias) }
}

/** 晚上对完账：标记这天已对账（预测从此可以打分）。 */
export function markReviewed(mem, day) {
  const cur = mem.days[day] || { promised: 0, done: 0 }
  return { days: { ...mem.days, [day]: { ...cur, reviewed: true } } }
}

// ---------------------------------------------------------------- 案卷

function safeName(s) {
  return String(s || '').replace(/[\\/:*?"<>|#^\[\]\n\r]+/g, ' ').trim().slice(0, 40)
}

export function caseFileName(name) {
  const n = safeName(name)
  return n ? n + '.md' : ''
}

/** 一份案卷：frontmatter 是给 Obsidian 的属性，正文是决定日志（新的在下面）。 */
export function renderCase({ name, status, summary, next, log = [] }) {
  const q = (v) => JSON.stringify(String(v || ''))
  return [
    '---',
    '状态: ' + q(status || '进行中'),
    '下次检查: ' + (next || ''),
    'tags: [大肥鱼/案卷]',
    '---',
    '',
    '# ' + name,
    '',
    '## 现状',
    '',
    String(summary || '（还没写）').trim(),
    '',
    '## 决定日志',
    '',
    ...(log.length ? log.map((e) => '- ' + e) : ['（还没有）']),
    '',
  ].join('\n')
}

/** 读回一份案卷（只认自己写的格式）。 */
export function parseCase(text) {
  const s = String(text || '')
  const fm = /^---\r?\n([\s\S]*?)\r?\n---/.exec(s)
  const get = (k) => {
    const m = fm && new RegExp('^' + k + ':\\s*(.*)$', 'm').exec(fm[1])
    if (!m) return ''
    const v = m[1].trim()
    try {
      return v.startsWith('"') ? JSON.parse(v) : v
    } catch {
      return v
    }
  }
  const name = (/^#\s+(.+)$/m.exec(s) || [])[1] || ''
  const summary = ((/## 现状\s*\n([\s\S]*?)\n## 决定日志/.exec(s) || [])[1] || '').trim()
  const logBlock = (/## 决定日志\s*\n([\s\S]*)$/.exec(s) || [])[1] || ''
  const log = logBlock.split('\n').filter((l) => l.startsWith('- ')).map((l) => l.slice(2))
  return { name: name.trim(), status: get('状态'), next: get('下次检查'), summary: summary === '（还没写）' ? '' : summary, log }
}

/**
 * 更新一份案卷：现状和下次检查覆盖，决定追加一行（带日期）。
 * @param prev parseCase 的结果或 null
 */
export function updateCase(prev, { name, status, summary, decision, next }, day) {
  const base = prev || { name, status: '进行中', next: '', summary: '', log: [] }
  const log = [...(base.log || [])]
  const d = String(decision || '').trim().slice(0, 300)
  if (d) log.push(day + '：' + d)
  return {
    name: base.name || name,
    status: String(status || base.status || '进行中').slice(0, 20),
    next: /^\d{4}-\d{2}-\d{2}$/.test(String(next || '')) ? String(next) : base.next,
    summary: String(summary || '').trim() ? String(summary).trim().slice(0, 2000) : base.summary,
    log: log.slice(-60),
  }
}

/** 数据条用：到期了没有（下次检查 ≤ 今天）。 */
export function caseDue(c, today) {
  return Boolean(c.next) && c.next <= today && c.status !== '结案'
}
