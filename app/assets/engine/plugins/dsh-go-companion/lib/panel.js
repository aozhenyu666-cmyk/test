// dsh-go-companion · 「鱼丸 · 鲸澜」面板（原生插件视图）
//
// 两姐妹和他的总看板 + 家庭群：
//   顶部：信用分、今日总评、现在的管法（档位 / 任务 / 拒绝 / 放风 / 连麦）。
//   今日评分：回应、守约、专注、思考四项，各 0–100。
//   信用分怎么来的：最近几件加减分的事。
//   家庭群：两人主动找他、他说的话、她们的回话、姐妹悄悄话、立任务、违抗、连麦复盘，按时间混在一条线上。
//           底下可以直接发：给鱼丸、给鲸澜、或者一起（鱼丸先回，鲸澜接着她的话说）。
//   她们记得的你：记忆、约定、最近一次 Gemini 复盘。
// 视图只认 data.* 绑定；数据由 index.js 推。没有任何"放松管控"的按钮——面板只能收紧（开忙时 / 拒绝模式）。

import { trustLabel } from './trust.js'
import { cardsOf } from './sisters.js'
import { hm as zoneHm } from './tz.js'

const b = (key) => ({ $: `data.${key}` })

export function sistersView(_scope, caps = {}) {
  const forms = Array.isArray(caps.components) && caps.components.includes('form')
  const children = [
    { type: 'text', text: b('summary') },
    { type: 'text', text: '**两位姐妹**', tone: 'muted' },
    { type: 'list', items: b('sisterCards') },
    { type: 'text', text: b('sisterPersonaText'), tone: 'muted' },
    {
      type: 'row',
      children: [
        { type: 'metric', label: '信用分', value: b('trust.score'), tone: b('trust.tone'), caption: b('trust.caption') },
        { type: 'metric', label: '今日总评', value: b('grade.letter'), tone: b('grade.tone'), caption: b('grade.caption') },
      ],
    },
    { type: 'callout', text: b('state.text'), tone: b('state.tone') },
    { type: 'text', text: '**墙（规则执行层）**', tone: 'muted' },
    { type: 'callout', text: b('wall.text'), tone: b('wall.tone') },
    { type: 'row', children: [
      { type: 'metric', label: '本周放松额度', value: b('wall.quota'), tone: b('wall.quotaTone'), caption: b('wall.quotaCaption') },
      { type: 'metric', label: '今天有没有修改', value: b('wall.today'), tone: b('wall.todayTone'), caption: b('wall.todayCaption') },
    ] },
    { type: 'text', text: '**今日评分**' },
    { type: 'progress', label: '回应', value: b('scores.reply.value'), caption: b('scores.reply.caption'), tone: b('scores.reply.tone') },
    { type: 'progress', label: '守约', value: b('scores.keep.value'), caption: b('scores.keep.caption'), tone: b('scores.keep.tone') },
    { type: 'progress', label: '专注', value: b('scores.focus.value'), caption: b('scores.focus.caption'), tone: b('scores.focus.tone') },
    { type: 'progress', label: '思考', value: b('scores.think.value'), caption: b('scores.think.caption'), tone: b('scores.think.tone') },
    { type: 'text', text: '**信用分怎么来的**', tone: 'muted' },
    { type: 'list', items: b('trustRows') },
    { type: 'divider' },
    { type: 'text', text: '**家庭群**（新的在上面）' },
  ]
  if (forms) {
    children.push({
      type: 'form',
      label: '在群里说一句',
      submit: 'group.say',
      submitLabel: '发送',
      children: [
        { type: 'textField', label: '说点什么', bind: 'data.form.text', multiline: true, maxLines: 4, maxLength: 1000, required: true },
        {
          type: 'segmented', label: '发给', bind: 'data.form.to',
          options: [
            { label: b('names.companion'), value: 'companion' },
            { label: b('names.strategist'), value: 'strategist' },
            { label: '一起', value: 'both' },
          ],
        },
      ],
    })
  }
  children.push({ type: 'text', text: b('chatNote'), tone: 'muted' })
  children.push({ type: 'list', items: b('timeline') })
  children.push({ type: 'divider' })
  children.push({ type: 'text', text: '**她们记得的你**' })
  children.push({ type: 'list', items: b('memory') })
  children.push({
    type: 'actions',
    items: [
      { label: '刷新', action: 'sisters.refresh', icon: 'refresh', style: 'primary' },
      { label: '开忙时 45 分钟', action: 'sisters.busy', icon: 'clock' },
      { label: '拒绝模式 30 分钟', action: 'sisters.refuse', icon: 'bell' },
    ],
  })
  return { type: 'stack', title: '鱼丸 · 鲸澜', children }
}

const clamp = (n) => Math.max(0, Math.min(100, Math.round(n)))
const toneOf = (n) => (n >= 75 ? 'good' : n >= 50 ? 'info' : n >= 30 ? 'warn' : 'bad')
const hm = (at) => zoneHm(Number(at))

/**
 * 今日四项评分（0–100）。entries 是今天的账本，phone 是最近一次手机现状。
 *   回应：主动找他的球里，他接住（判球配上，或之后说过话）的比例。
 *   守约：从 80 起，违抗 -25、任务没通过 -15、没做完喊收工 -10、没回就关窗 -5，任务通过 +10、放风按时回来 +5。
 *   专注：今天娱乐 ≤30 分钟满分，到 180 分钟归零；忙时 / 任务每 15 分钟补 5 分。
 *   思考：认真回一次 +15、Gemini 复盘一次 +25、自己找她们说一句 +3。
 */
export function dayScores(entries, phone, now = Date.now()) {
  let balls = 0
  let caught = 0
  let human = 0
  const answered = new Set()
  for (const e of entries) {
    if (e?.state === 'replied') answered.add(e.requestId)
    if ((e?.state === 'human' || e?.state === 'said') && Number(e.at) > human) human = Number(e.at)
  }
  const c = (s) => entries.filter((e) => e?.state === s).length
  for (const e of entries) {
    if (e?.state !== 'generated') continue
    balls += 1
    if (answered.has(e.requestId) || human > Number(e.at)) caught += 1
  }
  const reply = balls ? clamp((caught / balls) * 100) : 100
  const keep = clamp(80 - 25 * c('defied') - 15 * entries.filter((e) => e?.state === 'task-check' && e.passed === false).length -
    10 * c('task-claim') - 5 * entries.filter((e) => e?.state === 'overlay-closed' && !e.chatted).length +
    10 * c('task-done') + 5 * entries.filter((e) => e?.state === 'break-end' && e.kept).length)
  let fun = 0
  for (const t of phone?.today || []) if (t.fun) fun += t.minutes
  let busyMin = 0
  for (const e of entries) if (e?.state === 'gear' && e.gear === 'busy' && Number(e.until) > Number(e.at)) busyMin += Math.min(Number(e.until), now) > Number(e.at) ? (Math.min(Number(e.until), now) - Number(e.at)) / 60_000 : 0
  const focus = clamp((fun <= 30 ? 100 : 100 - ((fun - 30) / 150) * 100) + Math.floor(busyMin / 15) * 5)
  const says = entries.filter((e) => e?.state === 'human').length
  const think = clamp(15 * c('substantive') + 25 * c('gemini-recap') + 3 * says)
  const avg = (reply + keep + focus + think) / 4
  const letter = avg >= 85 ? 'A' : avg >= 70 ? 'B' : avg >= 55 ? 'C' : 'D'
  return {
    reply: { value: reply, caption: balls ? caught + '/' + balls + ' 球接住' : '今天还没找过你', tone: toneOf(reply) },
    keep: { value: keep, caption: c('defied') ? '违抗 ' + c('defied') + ' 次' : c('task-done') ? '任务通过 ' + c('task-done') + ' 个' : '没出岔子', tone: toneOf(keep) },
    focus: { value: focus, caption: '娱乐 ' + fun + ' 分钟' + (busyMin >= 15 ? ' · 忙时 ' + Math.round(busyMin) + ' 分钟' : ''), tone: toneOf(focus) },
    think: { value: think, caption: '认真回 ' + c('substantive') + ' · 连麦复盘 ' + c('gemini-recap') + ' · 说话 ' + says, tone: toneOf(think) },
    grade: { letter, tone: toneOf(avg), caption: '四项平均 ' + Math.round(avg) },
  }
}

/** 账本 → 家庭群的一条（不认识的返回 null）。 */
function timelineRow(e, names, speakerOf) {
  const who = (sid) => (speakerOf(sid) === 'strategist' ? names.strategist : names.companion)
  const cut = (t, n = 120) => String(t || '').replace(/^〔[^〕]{1,20}〕/, '').replace(/\s+/g, ' ').slice(0, n)
  const time = hm(e.at)
  switch (e.state) {
    case 'generated': return e.text ? { title: who(e.sessionId) + '：' + cut(e.text), subtitle: time + ' · 主动找你' + (e.reason ? '（' + e.reason + '）' : ''), icon: 'chat' } : null
    case 'say-text': return { title: '我' + (e.to === 'both' ? '（对两个人）' : e.to === 'strategist' ? '→' + names.strategist : '→' + names.companion) + '：' + cut(e.text), subtitle: time, icon: 'heart' }
    case 'heard': return e.text ? { title: who(e.sessionId) + '：' + cut(e.text), subtitle: time + ' · 回你', icon: 'chat' } : null
    case 'replied': return e.answer ? { title: '我：' + cut(e.answer), subtitle: time + ' · 回' + who(e.sessionId) + (e.replyClass === 'substantive' ? ' · 认真' : ''), icon: 'heart' } : null
    case 'sister-note': return { title: (e.from === 'strategist' ? names.strategist + ' → ' + names.companion : names.companion + ' → ' + names.strategist) + '（悄悄话）：' + cut(e.text, 80), subtitle: time + (e.urgent ? ' · 急' : ''), icon: 'bell', tone: 'info' }
    case 'task-start': return { title: '立任务：' + cut(e.goal || (e.mode === 'refuse' ? '拒绝模式' : ''), 60) + '（' + e.minutes + ' 分钟）', subtitle: time + ' · ' + (e.by === 'strategist' ? names.strategist : e.by === 'companion' ? names.companion : '') + (e.reason ? ' · ' + cut(e.reason, 40) : ''), icon: 'flag', tone: 'warn' }
    case 'task-done': return { title: '任务验收通过：' + cut(e.goal, 60), subtitle: time + (e.summary ? ' · ' + cut(e.summary, 50) : ''), icon: 'star', tone: 'good' }
    case 'task-check': return e.passed === false ? { title: '任务验收没通过', subtitle: time + (e.summary ? ' · ' + cut(e.summary, 50) : ''), icon: 'bookmark', tone: 'bad' } : null
    case 'task-claim': return { title: '我喊了收工（任务还没验收）', subtitle: time, icon: 'flag', tone: 'warn' }
    case 'defied': return { title: '关了提醒接着玩 ' + (e.label || e.app || '') + ' → 拒绝模式', subtitle: time, icon: 'bell', tone: 'bad' }
    case 'overlay-closed': return e.chatted ? null : { title: '没回就关了窗', subtitle: time, icon: 'bookmark', tone: 'warn' }
    case 'gear': return { title: e.gear === 'busy' ? '进入忙时：' + (e.what || '') : '回到闲时', subtitle: time + (e.by === 'phrase' ? ' · 我自己说的' : ''), icon: 'clock' }
    case 'gemini-start': return { title: '去 Gemini 连麦：' + cut(e.topic, 60) + '（' + e.minutes + ' 分钟）', subtitle: time, icon: 'mic' }
    case 'gemini-recap': return { title: 'Gemini 连麦复盘已存', subtitle: time + (e.file ? ' · ' + e.file.split('/').slice(-2).join('/') : ''), icon: 'book', tone: 'good' }
    case 'break-start': return { title: '放风 ' + e.minutes + ' 分钟', subtitle: time + (e.reason ? ' · ' + cut(e.reason, 40) : ''), icon: 'play', tone: 'good' }
    case 'break-end': return { title: e.kept ? '放风结束，按时回来了' : '放风结束，还在玩', subtitle: time, icon: 'clock', tone: e.kept ? 'good' : 'bad' }
    case 'tamper': return { title: '想去设置里拆管控（' + (e.what || '') + '）→ 信用分清零', subtitle: time, icon: 'bell', tone: 'bad' }
    case 'breakglass': return { title: '破窗 ' + e.minutes + ' 分钟' + (e.steps ? '（走了 ' + e.steps + ' 步）' : ''), subtitle: time + (e.note ? ' · ' + cut(e.note, 50) : ''), icon: 'bell', tone: 'warn' }
    case 'block': return { title: '封禁：' + (e.apps || []).join('、'), subtitle: time + ' · 到 ' + hm(e.until) + (e.reason ? ' · ' + cut(e.reason, 40) : ''), icon: 'flag', tone: 'warn' }
    case 'kb-write': return { title: e.file === 'self' ? '鲸澜起草了 我.md（等你改）' : '鲸澜更新了 判断.md', subtitle: time, icon: 'note' }
    case 'gate-closed': return { title: (e.gates || []).includes('morning') ? '早上的 3 件事说完了，解锁' : '晚上对完账了，解锁', subtitle: time, icon: 'star', tone: 'good' }
    case 'daily-plan': return { title: '早上定了 ' + (e.count || 0) + ' 件事（带完成物）', subtitle: time + (e.file ? ' · ' + String(e.file).split('/').slice(-2).join('/') : ''), icon: 'note' }
    case 'daily-review': return { title: '对账：做完 ' + (e.done || 0) + ' / 一半 ' + (e.partial || 0) + ' / 没做 ' + (e.miss || 0) + '（共 ' + (e.total || 0) + '）', subtitle: time + (e.quotaDelta ? ' · 明天额度 ' + (e.quotaDelta > 0 ? '+' + e.quotaDelta : e.quotaDelta) + ' 天' : ''), icon: 'star', tone: e.quotaDelta > 0 ? 'good' : e.quotaDelta < 0 ? 'bad' : 'info' }
    case 'relax-grant': return { title: '批了明天放松一段（' + (e.minutes || 0) + ' 分钟）', subtitle: time + (e.reason ? ' · ' + cut(e.reason, 50) : ''), icon: 'play', tone: 'good' }
    case 'tighten': return { title: '收紧了：今天娱乐锁到零点', subtitle: time + (e.reason ? ' · ' + cut(e.reason, 50) : ''), icon: 'flag', tone: 'warn' }
    case 'apply-window-open': return { title: '申请窗口开了（15 分钟）', subtitle: time + ' · 只能提明天的调整', icon: 'bell', tone: 'info' }
    default: return null
  }
}

/** 面板数据（纯函数，index.js 把读好的东西交进来）。 */
export function buildSnapshot({ entries = [], todayEntries = [], trust, phone, names, speakerOf, config = {}, facts = [], commitments = [], gemini = {}, notesDir = '', wall = null, now = Date.now() }) {
  const scores = dayScores(todayEntries, phone, now)
  const state = []
  let tone = 'info'
  if (config.gear === 'busy' && Number(config.gearUntil) > now) {
    state.push((config.taskBy ? '任务模式：' + (config.taskGoal || config.gearWhat) : '忙时：' + (config.gearWhat || '正事')) + '，到 ' + hm(config.gearUntil))
    tone = 'warn'
  } else state.push('闲时：高频陪聊')
  if (Number(config.refuseUntil) > now) {
    state.push('拒绝模式到 ' + hm(config.refuseUntil))
    tone = 'warn'
  }
  if (Number(config.breakUntil) > now) {
    state.push('放风到 ' + hm(config.breakUntil))
    tone = 'good'
  }
  if (gemini.session && Number(gemini.session.until) > now) state.push('Gemini 连麦中：' + (gemini.session.topic || ''))
  const timeline = []
  // 浮层 / 面板里打的字已经有一条 say-text，判球那条 replied 就不再重复显示。
  const typed = new Set(entries.filter((e) => e?.state === 'say-text').map((e) => String(e.text || '').slice(0, 60)))
  for (let i = entries.length - 1; i >= 0 && timeline.length < 40; i -= 1) {
    const e = entries[i]
    if (e?.state === 'replied' && typed.has(String(e.answer || '').slice(0, 60))) continue
    const row = e && timelineRow(e, names, speakerOf)
    if (row) timeline.push(row)
  }
  const memory = []
  for (const f of facts.slice(0, 6)) memory.push({ title: String(f).slice(0, 80), subtitle: '记忆', icon: 'book' })
  for (const c of commitments.slice(0, 3)) memory.push({ title: String(c.statement || '').slice(0, 80), subtitle: '答应过' + (c.dueAt ? ' · ' + String(c.dueAt).slice(0, 16) : ''), icon: 'flag', tone: 'warn' })
  const recaps = Array.isArray(gemini.recaps) ? gemini.recaps : []
  const r = recaps[recaps.length - 1]
  if (r) memory.push({ title: 'Gemini：' + String(r.summary).slice(0, 70), subtitle: (r.next ? '下一步：' + String(r.next).slice(0, 40) + ' · ' : '') + hm(r.at), icon: 'mic' })
  memory.push({ title: notesDir ? '笔记库：' + notesDir : '笔记库：还没找到（跟她们说一声在哪）', subtitle: notesDir ? 'Gemini 复盘写在 Gemini/ 下' : '', icon: 'folder' })
  if (!memory.length) memory.push({ title: '她们还没记住关于你的事', subtitle: '多聊几句', icon: 'book' })
  // 墙（第二轮）：此刻锁/开、本周剩余放松额度、今天有没有修改（放松 / 收紧）。
  const quota = Math.max(0, Math.round(Number(wall?.relaxRemaining) || 0))
  const wallToday = wall?.tightenToday ? '收紧了' : wall?.relaxToday ? '放松中' : '没有'
  const wallLine = !wall ? '墙还没上报（等下一次 /plan）'
    : wall.locked
      ? '现在锁着：' + (wall.kind === 'night' ? '深夜锁' : '默认严格') + (wall.applyWindowOpen ? ' · 申请窗口开着' : '')
      : '现在开放' + (wall.openUntil ? '到 ' + hm(wall.openUntil) : '') + (wall.applyWindowOpen ? ' · 申请窗口开着' : '')
  const cards = cardsOf(names)
  const sisterCards = cards.map((c) => ({
    title: `${c.name} · ${c.role === 'companion' ? '陪伴妹妹' : '谋士姐姐'}`,
    subtitle: c.line,
    trailing: c.role === 'companion' ? '陪伴' : '谋划',
    icon: c.role === 'companion' ? 'chat' : 'book',
    tone: c.role === 'companion' ? 'warn' : 'info',
  }))
  const sisterPersonaText = cards
    .map((c) => (c.persona ? `**${c.name}**：${c.persona}` : ''))
    .filter(Boolean)
    .join('\n')
  return {
    sisterCards,
    sisterPersonaText,
    wall: {
      text: wallLine,
      tone: !wall ? 'info' : wall.locked ? 'warn' : 'good',
      quota: String(quota),
      quotaTone: quota > 0 ? 'good' : 'warn',
      quotaCaption: '本周最多 ' + (Number(wall?.weeklyQuota) || 3) + ' 天',
      today: wallToday,
      todayTone: wallToday === '没有' ? 'info' : 'warn',
      todayCaption: wall?.depositMissing
        ? '额度先记 0：今天四段还没交齐（' + String(wall.depositMissing) + '）'
        : wall?.applyWindowOpen ? '窗口开着：只能提明天' : '放松要等申请窗口',
    },
    summary: names.companion + '陪你聊、跟你闹；' + names.strategist + '早上定事、晚上对账。锁和放由墙按规则执行，两人共用一个信用分。',
    names,
    trust: { score: trust ? String(trust.score) : '—', tone: trust ? toneOf(trust.score) : 'info', caption: trust ? (trust.band === 'low' ? '低：她们不给豁免' : trust.band === 'high' ? '高：可以放风' : '中') : '' },
    grade: scores.grade,
    state: { text: state.join(' · '), tone },
    scores,
    trustRows: (trust?.recent || []).map((x) => ({ title: trustLabel(x.kind), trailing: (x.delta > 0 ? '+' : '') + (Math.round(x.delta * 10) / 10), subtitle: hm(x.at), tone: x.delta > 0 ? 'good' : 'bad' })),
    timeline: timeline.length ? timeline : [{ title: '群里还没有消息', subtitle: '她们主动找你、你回她们，都会出现在这里', icon: 'chat' }],
    chatNote: '「一起」：' + names.companion + '先回，' + names.strategist + '看到她的回答再接着说。回复要等几秒，点刷新看。',
    memory,
    form: { text: '', to: 'both' },
  }
}
