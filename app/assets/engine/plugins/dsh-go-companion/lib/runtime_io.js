// dsh-go-companion · 运行时读写与配置（H9：从 index.js 原样搬出，行为不变）
//
// 配置读写、状态文件、HTTP 发送、会话辅助、mergeConfig。index.js 从这里导入。

import { mkdirSync, readFileSync, renameSync, writeFileSync, existsSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { SOURCE_KIND } from './core.js'
import { readLedger } from './ledger.js'
import { DEFAULT_TZ, startOfDay as zoneStartOfDay, zoneOr } from './tz.js'
import { COACH_LIMITS, cleanPersona, modeOr } from './persona.js'
import { roleNames } from './roles.js'
import { tidyNightly } from './nightly.js'
import { FOLLOWUP_PENALTY } from './followup.js'
import { SPRINT_LONG_MIN, SPRINT_MIN } from './machine.js'
import { DEFAULT_INTENSITY, applyIntensityChange, isValidIntensity } from './intensity.js'
import { DEFAULT_WALL } from './wall.js'
import { GEMINI_PACKAGES } from './gemini.js'

export const PREFIX = '/dsh-go-companion/v1'
export const MAX_BODY = 16 * 1024

export const DEFAULTS = Object.freeze({
  enabled: true,
  sessionId: undefined,
  // 安静时段 2–7 点（她们不说话）；0:30 以后娱乐照样锁（见 rhythm.js）。
  quietStart: 2,
  quietEnd: 7,
  // 整体高频自由档：最短 8 分钟一次、每小时与每日默认不设死限（设置页可调），骰子 88 基本随叫随开。
  dailyMax: 0,
  hourlyMax: 0,
  minGapMs: 8 * 60_000,
  diceThreshold: 88,
  // 2026-11：全状态全工具直挂，不再按主状态收起或屏蔽任何能力。
  fullToolAccess: true,
  cooldownMs: 24 * 60 * 60_000,
  allowColdSession: true,
  owner: '主人',
  self: '大肥鱼',
  timeZone: DEFAULT_TZ,
  // R7 P0-2：时间基准固定北京时间。为真时忽略手机请求里的 timeZone（他出国 / 模拟器设成纽约都不带偏），
  // 一律按 DEFAULT_TZ 算。关掉才会跟着手机时区走。
  lockTimeZone: true,
  // 人格卡（口吻、口头禅、兴趣、关系、能说多狠、不碰的话题）和两种模式。
  persona: {},
  mode: 'partner',
  // 教练模式自己的频率（乒乓式高频）：最小间隔 8 分钟、每小时 6、每天 40。
  ...COACH_LIMITS,
  // 控机权限：水位到了就锁娱乐 App / 退回桌面（手机执行）。关掉就最多到浮层。
  control: true,
  // 人格卡进每一轮聊天（不只主动发球）：没有它，平时聊天里的大肥鱼就是一个普通助手。
  personaInChat: true,
  // 姐妹双人格：陪伴（妹妹，sessionId）+ 谋士（姐姐，strategistSessionId）。关掉就回到单人格大肥鱼。
  dual: true,
  strategistSessionId: undefined,
  // [Boss-Wall Path B] 端内第 3 独立主管/老板审计会话（与两条鱼完全隔离）
  wallSessionId: undefined,
  companionName: '鱼丸',
  strategistName: '鲸澜',
  // 他给了高权限：主动找他的那一轮也能动手（设提醒、退出 App、调手机）。DSH 自己的审批照常生效。
  proactiveTools: true,
  // 念出来（B5）：默认关。关着浮层照常，只是不出声——不联网的系统 TTS 也得他自己点头。
  voice: false,
  arbiterEnforce: false,
  companionCard: '',
  strategistCard: '',
  // 做题模式：默认关，主线是聊天。说"开始做题 / 做题模式"打开，"别出题了"关掉。
  drills: false,
  // 支线（第六轮 A）：一页配置就是这四项。代码只认抽题/判断/漏项/标签四个槽位，
  // 具体是什么方向全写在这里。空数组 = 全部支线关掉，主干照跑。
  tracks: [],
  // 自建工作流（第六轮 D）：她提的、用户点「试」才跑的附加流程。
  // 护栏在 lib/workflows.js：每周最多 1 条、同时在跑 ≤3 条、7 天没可数效果自动停。
  workflows: [],
  // 档位：free（闲时，默认高频）/ busy（忙时，gearUntil 到点回闲时）。
  gear: 'free',
  // Gemini 的包名（Gemini App / Google App 里的 Gemini）。
  geminiPackages: GEMINI_PACKAGES,
  // 连麦舱里"其余第三方 App"的宽限：从白名单 App 跳出去、或者进了一个没见过的 App，
  // 先放这么多毫秒（只提示不锁），超了才升到 4 级锁。图库/文件/相机走系统应用，不受这个影响。
  sessionGraceMs: 45_000,
  // 判球可以单独配一个更快的模型；没配就沿用会话自己的 provider/model。
  judgeProvider: undefined,
  judgeModel: undefined,
  // 2026-11-19 H1：强度表（gentle / standard / strict）与待生效改松。
  intensity: DEFAULT_INTENSITY,
  pendingIntensity: null,
  // 2026-11-19 H2：破窗不再扣信用分（保留记账与日判）。
  breakglassPenalty: 0,
  // 那一天（当地零点，ms）已经破过窗：账本被压缩/清理也不会把同一天放开第二次。
  breakglassDay: 0,
  // 第二轮·墙：默认严格的娱乐锁时间表（吃饭开放、夜里深夜锁），由这张内部表驱动，改表不必动代码。
  wall: DEFAULT_WALL,
  // 今天生效的放松（对账后申请窗口里批的，只管一天）：{ for:<当地零点ms>, segments:[{from,to}], reason }。
  relax: null,
  // 今天的收紧（任何时候可提，立即生效，当天有效）：{ for:<当地零点ms>, at }。
  tighten: null,
  // 本周放松用量：{ week:<自然周号>, used }。额度见 wall.weeklyQuota。
  relaxWeek: null,
  // 申请窗口状态机：{ day, openAt, closeAt, notified, used }。对账完成后随机打开 15 分钟。
  applyWindow: null,
  // 第三轮·追问锁：{ reason, question, since, until }。同一时刻只有一把，最长 followupCapMin 分钟。
  followup: null,
  // 追问锁单次上限（分钟）与超时扣的信用分。
  followupCapMin: 30,
  followupPenalty: FOLLOWUP_PENALTY,
  // 待命多久没动就开追问锁（分钟）。
  standbyMin: 10,
  // 冲刺两档时长（分钟）：用户点「开始」时选。放风时长见 wall.relaxSegmentMin / breakMin。
  sprintMin: SPRINT_MIN,
  sprintLongMin: SPRINT_LONG_MIN,
  // 验收通过后放风多久（分钟）；连麦满多久才算完成（分钟）。
  breakMin: 20,
  brainMinMin: 3,
  // 待办义务：超时没过关的事挂在这里，只由用户的动作触发再问。
  obligations: [],
})

export function home() {
  return process.env.DSH_HOME || join(homedir(), '.dsh')
}

export function configPath() {
  return join(home(), 'companion', 'config.json')
}

export function readConfig() {
  let merged = { ...DEFAULTS }
  try {
    const raw = JSON.parse(readFileSync(configPath(), 'utf8'))
    if (raw && typeof raw === 'object') merged = { ...merged, ...raw }
  } catch {
    // defaults
  }
  // 上一版的默认名字（小肥鱼 / 大肥鱼）会随任何一次 /config 写进配置；那不是他起的，换成现在的默认名。
  if (merged.companionName === '小肥鱼' && merged.strategistName === '大肥鱼') {
    merged.companionName = DEFAULTS.companionName
    merged.strategistName = DEFAULTS.strategistName
  }
  // 上一版的默认安静时段 1–8 点：深夜 1 点正是该管的时候，没改过就换成 2–7。
  if (merged.quietStart === 1 && merged.quietEnd === 8) Object.assign(merged, { quietStart: 2, quietEnd: 7 })
  // 上一版的默认频率（6/3/45m/60 或 40/4/18m/85）也会随 /config 写进配置：没改过就升到现在的高频自由默认。
  if (
    (merged.dailyMax === 6 && merged.hourlyMax === 3 && merged.minGapMs === 45 * 60_000 && merged.diceThreshold === 60) ||
    (merged.dailyMax === 40 && merged.hourlyMax === 4 && merged.minGapMs === 18 * 60_000 && merged.diceThreshold === 85)
  ) {
    Object.assign(merged, { dailyMax: DEFAULTS.dailyMax, hourlyMax: DEFAULTS.hourlyMax, minGapMs: DEFAULTS.minGapMs, diceThreshold: DEFAULTS.diceThreshold })
  }
  // 2026-11：全权限直挂默认开启，没显式关闭的统一放开。
  if (merged.fullToolAccess !== false) merged.fullToolAccess = true
  // 旧 dsh-go-proactive 设置过目标会话：迁过来，避免升级后“开了但不说话”。
  if (!merged.sessionId) {
    try {
      const legacy = JSON.parse(readFileSync(join(home(), 'storages', 'dsh-go-proactive', 'config.json'), 'utf8'))
      if (legacy && typeof legacy.sessionId === 'string' && legacy.sessionId) merged.sessionId = legacy.sessionId
    } catch {
      // no legacy config
    }
  }
  // R7 P0-2：锁定时区就无视存下来的 timeZone——老配置里可能已经写进了手机时区（比如模拟器的 UTC）。
  // 这一行兜住所有 `zoneOr(config.timeZone)` 的调用点，不用逐个改。
  if (merged.lockTimeZone !== false) merged.timeZone = DEFAULT_TZ
  return merged
}

/**
 * 单点绑定联动：当主库绑好了陪伴/谋士会话而 feiyu-mood 还未绑定（或由联动接管）时，
 * 自动把姐妹会话与名字同步进 $DSH_HOME/feiyu-mood/config.json 的 slots，省去两边反复点选。
 */
export function syncMoodBridge(cfg) {
  try {
    const sisSid = String(cfg?.sessionId || '').trim()
    if (!sisSid) return false
    const moodDir = join(home(), 'feiyu-mood')
    const moodFile = join(moodDir, 'config.json')
    let raw = {}
    if (existsSync(moodFile)) {
      try {
        raw = JSON.parse(readFileSync(moodFile, 'utf8')) || {}
      } catch {
        raw = {}
      }
    }
    const emptySlots = !Array.isArray(raw.slots) || raw.slots.length === 0
    const unbound = !String(raw.targetSession || '').trim() && emptySlots
    if (existsSync(moodFile) && raw.autoLinked !== true && !unbound) return false
    const names = roleNames(cfg)
    const owner = String(cfg?.owner || '主人').trim() || '主人'
    const bigSid = cfg?.dual !== false && cfg?.strategistSessionId && cfg.strategistSessionId !== sisSid
      ? String(cfg.strategistSessionId).trim()
      : ''
    const slots = [
      { id: 'companion-sis', label: names.companion, selfName: names.companion, ownerName: owner, sessions: [sisSid] },
      ...(bigSid ? [{ id: 'companion-big', label: names.strategist, selfName: names.strategist, ownerName: owner, sessions: [bigSid] }] : []),
    ]
    const next = {
      ...raw,
      模式: raw.模式 || 'session',
      targetSession: sisSid,
      selfName: names.companion,
      ownerName: owner,
      slots,
      autoLinked: true,
    }
    mkdirSync(moodDir, { recursive: true, mode: 0o700 })
    const tmp = moodFile + '.' + String(process.pid) + '.tmp'
    writeFileSync(tmp, JSON.stringify(next, null, 2) + '\n', { encoding: 'utf8', mode: 0o600 })
    renameSync(tmp, moodFile)
    return true
  } catch {
    return false
  }
}

export function writeConfig(cfg) {
  const p = configPath()
  mkdirSync(join(p, '..'), { recursive: true, mode: 0o700 })
  const tmp = p + '.' + String(process.pid) + '.tmp'
  writeFileSync(tmp, JSON.stringify(cfg, null, 2) + '\n', { encoding: 'utf8', mode: 0o600 })
  renameSync(tmp, p)
  syncMoodBridge(cfg)
}

/**
 * 最近 10 次实测耗时的中位数。发球 / 判球 / 聊天分开算：模型慢的时候，设置页要能一眼看出
 * 慢在哪一段，而不是只看到一句"没回"。
 */
export function readTimings() {
  let rows = []
  try {
    rows = readLedger(home(), 400)
  } catch {
    rows = []
  }
  const median = (values) => {
    const a = values.filter((v) => Number.isFinite(v) && v >= 0).slice(0, 10).sort((x, y) => x - y)
    return a.length ? a[Math.floor(a.length / 2)] : null
  }
  // 账本本身就是时间线：generated.at - accepted.at 就是模型这一段。不依赖进程内 map，
  // 重启引擎后仍然能读出上一轮的真实耗时。
  const began = new Map()
  for (const e of rows) {
    if (!e || !e.requestId || !Number.isFinite(Number(e.at))) continue
    if (e.state === 'accepted' || e.state === 'said') began.set(String(e.requestId), Number(e.at))
  }
  const span = (state) => rows
    .filter((e) => e && e.state === state && e.requestId && began.has(String(e.requestId)))
    .map((e) => Number(e.at) - began.get(String(e.requestId)))
    .reverse() // oldest → newest; the median wants the latest ten
  const judge = rows
    .filter((e) => e && e.state === 'replied' && Number.isFinite(Number(e.judgeMs)))
    .map((e) => Number(e.judgeMs))
    .reverse()
  return {
    generate: median(span('generated')),
    judge: median(judge),
    chat: median(span('heard')),
  }
}

export function memoryPath() {
  return join(home(), 'fish-memory', 'fish-memory.sqlite')
}

export function gardenPath() {
  return join(home(), 'companion', 'topics.json')
}

export function stateFile(name) {
  return join(home(), 'companion', name)
}

export function readState(file, fallback) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'))
  } catch {
    return fallback
  }
}

export function writeJson(file, value) {
  try {
    mkdirSync(join(file, '..'), { recursive: true, mode: 0o700 })
    const tmp = file + '.' + String(process.pid) + '.tmp'
    writeFileSync(tmp, JSON.stringify(value) + '\n', { encoding: 'utf8', mode: 0o600 })
    renameSync(tmp, file)
  } catch (error) {
    console.error('[companion] 写 ' + file + ' 失败：' + String((error && error.message) || error))
  }
}

// 引擎跑在 TZ=GMT，凡是「今天」「零点」都必须按手机时区算，所以这里显式要 tz。
// /plan 允许每次请求带上 timeZone（APP 那边知道手机时区），其余入口用配置里的。
// R7 P0-2：`lockTimeZone` 为真（默认）时请求体里的时区一概不认，固定北京时间。
export function requestZone(tzIn, config) {
  if (config.lockTimeZone !== false) return DEFAULT_TZ
  return zoneOr(tzIn || config.timeZone)
}

export function startOfDay(now, tz) {
  return zoneStartOfDay(now, zoneOr(tz))
}

export function send(res, status, body) {
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
  })
  res.end(JSON.stringify(body))
}

export async function readJson(req) {
  if (!String(req.headers['content-type'] ?? '').startsWith('application/json')) return { error: 415 }
  let size = 0
  const chunks = []
  for await (const c of req) {
    size += c.length
    if (size > MAX_BODY) return { error: 413 }
    chunks.push(c)
  }
  try {
    return { value: JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}') }
  } catch {
    return { error: 400 }
  }
}

export function sessionIds(id) {
  const s = String(id || '')
  return s.startsWith('session-') ? [s, s.slice(8)] : [s, 'session-' + s]
}

export function isArchived(ctx, id) {
  try {
    const archived = ctx.get?.('workspaceRegistry')?.archivedSessionIds
    if (!Array.isArray(archived)) return false
    return sessionIds(id).some((x) => archived.includes(x))
  } catch {
    return false
  }
}

export function liveAgent(ctx, id) {
  const agents = ctx.get?.('agents')
  if (!agents) return undefined
  for (const x of sessionIds(id)) {
    try {
      const a = agents.get(x)
      if (a !== undefined) return a
    } catch {
      // not live
    }
  }
  return undefined
}

/** 冷会话：只唤醒调用方指定/配置过的会话；失败不新建。 */
export async function resolveAgent(ctx, id, config) {
  const live = liveAgent(ctx, id)
  if (live !== undefined) return { agent: live, cold: false }
  if (config.allowColdSession !== true) return undefined
  const sc = ctx.get?.('sessionController')
  if (!sc || typeof sc.resolveAgent !== 'function') return undefined
  for (const x of sessionIds(id)) {
    try {
      const r = await sc.resolveAgent(x)
      if (r && r.agent) return { agent: r.agent, cold: true }
    } catch {
      // try the other id form
    }
  }
  return undefined
}

/** "开始做题""做题模式" → true；"别出题了""关掉做题" → false；别的 → null。 */
export function drillToggle(text) {
  const s = String(text == null ? '' : text).replace(/\s+/g, '')
  if (!s || s.length > 20) return null
  if (/(别|不要|不用|停止|关掉|关闭|停)(再)?(做题|出题)|(做题|出题)(模式)?(关掉|关了|先停)/.test(s)) return false
  if (/(开始|开启|打开|来|进入)(做题|出题)|做题模式|出(几|一)?道题/.test(s)) return true
  return null
}

export function userMessage(text) {
  return { id: randomUUID(), role: 'user', content: [{ type: 'text', text }], source: { kind: SOURCE_KIND } }
}

/**
 * 判球用哪个模型：跟当前会话自己的选择走（它自己的人格和历史也都在那个会话里）。
 * 拿不到就返回 undefined —— 判球会退回本地规则，不会因为没模型就卡住。
 */
export function judgeTargetOf(ctx, sessionId, config) {
  // 单独配的判球模型优先：判球只有 6 秒预算，不值得排在会话那个慢模型后面。
  const jp = config?.judgeProvider
  const jm = config?.judgeModel
  if (typeof jp === 'string' && jp && typeof jm === 'string' && jm) return { provider: jp, model: jm }
  try {
    const agent = liveAgent(ctx, sessionId)
    const cfg = agent?.config
    if (cfg && cfg.provider && cfg.model) return { provider: cfg.provider, model: cfg.model }
    const o = agent?.options
    if (o && o.provider && o.model) return { provider: o.provider, model: o.model }
  } catch {
    // fall through
  }
  return undefined
}

/**
 * B2 夜间整理用哪个模型：和判球同一套优先级（单独配的优先，否则跟会话自己的）。
 * 拿不到就返回空对象 —— tidyNightly 会直接返回失败，不会因为没模型卡住 /plan。
 */
export function nightlyTargetOf(ctx, config) {
  const np = config?.nightlyProvider
  const nm = config?.nightlyModel
  if (typeof np === 'string' && np && typeof nm === 'string' && nm) return { provider: np, model: nm }
  return judgeTargetOf(ctx, config?.sessionId, config) || {}
}

export function messageText(data) {
  const content = data?.content
  if (!Array.isArray(content)) return ''
  return content
    .filter((b) => b && b.type === 'text' && typeof b.text === 'string')
    .map((b) => b.text)
    .join('')
    .trim()
}


export function mergeConfig(current, body) {
  const next = { ...current }
  if (body && typeof body === 'object') {
    if (body.sessionId === null) next.sessionId = undefined
    else if (typeof body.sessionId === 'string' && body.sessionId.length <= 256) next.sessionId = body.sessionId
    // 换了对话就从这一刻起算轮数（会话换班用）。
    for (const [key, role] of [['sessionId', 'companion'], ['strategistSessionId', 'strategist']]) {
      if (typeof body[key] === 'string' && body[key] && body[key] !== current[key]) next.sessionBoundAt = { ...(next.sessionBoundAt || current.sessionBoundAt || {}), [role]: Date.now() }
    }
    if (typeof body.enabled === 'boolean') next.enabled = body.enabled
    for (const key of ['quietStart', 'quietEnd']) {
      if (Number.isFinite(Number(body[key]))) next[key] = Math.max(0, Math.min(23, Math.round(Number(body[key]))))
    }
    if (Number.isFinite(Number(body.dailyMax))) next.dailyMax = Math.max(0, Math.min(120, Math.round(Number(body.dailyMax))))
    if (Number.isFinite(Number(body.hourlyMax))) next.hourlyMax = Math.max(0, Math.min(60, Math.round(Number(body.hourlyMax))))
    // 一键安静：给 0 或过去的时间就是解除。
    if (Number.isFinite(Number(body.muteUntil))) next.muteUntil = Math.max(0, Number(body.muteUntil))
    if (body.persona && typeof body.persona === 'object') next.persona = cleanPersona(body.persona)
    if (typeof body.mode === 'string') next.mode = modeOr(body.mode)
    if (Number.isFinite(Number(body.modeUntil))) next.modeUntil = Math.max(0, Number(body.modeUntil))
    if (Number.isFinite(Number(body.refuseUntil))) next.refuseUntil = Math.max(0, Number(body.refuseUntil))
    if (typeof body.notesPath === 'string') next.notesPath = body.notesPath.trim().slice(0, 300)
    if (Number.isFinite(Number(body.coachMinGapMs))) next.coachMinGapMs = Math.max(60_000, Math.min(6 * 3_600_000, Number(body.coachMinGapMs)))
    if (Number.isFinite(Number(body.coachHourlyMax))) next.coachHourlyMax = Math.max(0, Math.min(60, Math.round(Number(body.coachHourlyMax))))
    if (Number.isFinite(Number(body.coachDailyMax))) next.coachDailyMax = Math.max(0, Math.min(120, Math.round(Number(body.coachDailyMax))))
    if (Number.isFinite(Number(body.minGapMs))) next.minGapMs = Math.max(60_000, Math.min(24 * 3_600_000, Number(body.minGapMs)))
    if (Number.isFinite(Number(body.diceThreshold))) next.diceThreshold = Math.max(0, Math.min(100, Number(body.diceThreshold)))
    // R4-6 走神阈值：同一个娱乐 App 坐够这么多分钟才算走神。
    if (Number.isFinite(Number(body.driftMin))) next.driftMin = Math.max(5, Math.min(60, Math.round(Number(body.driftMin))))
    if (typeof body.allowColdSession === 'boolean') next.allowColdSession = body.allowColdSession
    if (typeof body.control === 'boolean') next.control = body.control
    if (typeof body.drills === 'boolean') next.drills = body.drills
    if (typeof body.personaInChat === 'boolean') next.personaInChat = body.personaInChat
    if (typeof body.dual === 'boolean') next.dual = body.dual
    if (typeof body.proactiveTools === 'boolean') next.proactiveTools = body.proactiveTools
    // 测试模式（影子锁）：所有锁只提示「这里本来会锁」，不真锁。只认他手动开关（模型工具只能关）。
    if (typeof body.lockShadow === 'boolean') next.lockShadow = body.lockShadow
    if (typeof body.arbiterEnforce === 'boolean') next.arbiterEnforce = body.arbiterEnforce
    if (typeof body.fullToolAccess === 'boolean') next.fullToolAccess = body.fullToolAccess
    // B5：念出来。默认关，只认布尔。
    if (typeof body.voice === 'boolean') next.voice = body.voice
    if (typeof body.driveVoice === 'boolean') next.driveVoice = body.driveVoice
    if (body.strategistSessionId === null) next.strategistSessionId = undefined
    else if (typeof body.strategistSessionId === 'string' && body.strategistSessionId.length <= 256) next.strategistSessionId = body.strategistSessionId || undefined
    // [Boss-Wall Path B] 端内独立主管墙会话：空值清零；禁止与陪伴/谋士会话重合
    if (body.wallSessionId === null || body.wallSessionId === '') next.wallSessionId = undefined
    else if (typeof body.wallSessionId === 'string' && body.wallSessionId.length <= 256) {
      const wSid = body.wallSessionId.trim()
      if (!wSid) next.wallSessionId = undefined
      else if (wSid !== String(next.sessionId || '') && wSid !== String(next.strategistSessionId || '')) {
        next.wallSessionId = wSid
      }
    }
    for (const k of ['companionName', 'strategistName']) if (typeof body[k] === 'string' && body[k].trim()) next[k] = body[k].trim().slice(0, 20)
    for (const k of ['companionCard', 'strategistCard']) if (typeof body[k] === 'string') next[k] = body[k].slice(0, 4000)
    if (typeof body.owner === 'string' && body.owner.trim()) next.owner = body.owner.trim().slice(0, 40)
    if (typeof body.self === 'string' && body.self.trim()) next.self = body.self.trim().slice(0, 40)
    if (typeof body.lockTimeZone === 'boolean') next.lockTimeZone = body.lockTimeZone
    if (typeof body.timeZone === 'string' && body.timeZone.trim() && next.lockTimeZone === false) next.timeZone = zoneOr(body.timeZone)
    // 判球模型：空字符串 = 清掉，回到沿用会话。
    if (typeof body.judgeProvider === 'string') next.judgeProvider = body.judgeProvider.trim().slice(0, 80) || undefined
    if (typeof body.judgeModel === 'string') next.judgeModel = body.judgeModel.trim().slice(0, 120) || undefined
    if (Number.isFinite(Number(body.breakglassPenalty))) next.breakglassPenalty = Math.max(0, Math.min(100, Math.round(Number(body.breakglassPenalty))))
    if (isValidIntensity(body.intensity)) {
      const r = applyIntensityChange(next, body.intensity, { now: Date.now(), tz: zoneOr(next.timeZone), allowRelax: true })
      if (r.ok) {
        next.intensity = r.intensity
        next.pendingIntensity = r.pendingIntensity
        next.intensityExplicit = true
      }
    }
    if (Array.isArray(body.defaultAllow)) {
      next.defaultAllow = body.defaultAllow.filter((s) => typeof s === 'string' && s.trim()).map((s) => s.trim().slice(0, 120)).slice(0, 30)
    }
  }
  return next
}
