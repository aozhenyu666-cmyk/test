// dsh-go-companion · 宿主半边
//
// 手机端主动事件的唯一决策与回合服务：
//   POST /request   一次主动交流（原生闹钟已经判定“到点”）
//   GET  /result    长轮询 accepted / claimed / generated / failed
//   POST /cancel
//   POST /plan      本地决策：开没开、静不静、额度、掷骰、话题（零模型）
//   POST /outcome   送达/展示/回应回执（写账本）
//   GET  /status
//   POST /config
//
// 复用：fish-memory.sqlite（只读话题/事实/经历）、relation 存在性、原生展示层。
// 不新建记忆库；不读屏幕内容；所有 SQL 参数绑定。

import { appendFileSync, mkdirSync, readFileSync, renameSync, writeFileSync, existsSync, statSync } from 'node:fs'
import { homedir } from 'node:os'
import { isAbsolute, join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { DEFAULT_LIMITS, Desk, SOURCE_KIND } from './core.js'
import { BACKGROUND_LIMIT, backgroundFacts, loadCandidates, openMemoryDb } from './topics.js'
import { buildPrompt } from './prompt.js'
import { appendLedger, readLedger, summarize } from './ledger.js'
import { plan as decide } from './plan.js'
import { DEFAULT_TZ, minuteOfDay as zoneMinuteOfDay, startOfDay as zoneStartOfDay, weekdayOf, zoneOr } from './tz.js'
import { classifyReply } from './classify.js'
import { gardenClass, judgeReply } from './judge.js'
import { isStock } from './stock.js'
import { capCount, nextShot, rallyStats } from './rally.js'
import { pickServeType } from './serve.js'
import { LIGHTER_TYPES, mutedUntil, serveWeights, silenceState } from './silence.js'
import { COACH_LIMITS, cleanPersona, effectiveMode, mergePersona, modeLimits, modeOr, modeServeOrder, modeSwitch, personaLines } from './persona.js'
import {
  MAX_ATTEMPTS,
  addTopic,
  applyNoReply,
  applyReply,
  closeTopic,
  discoverFromMessage,
  gardenStats,
  loadGarden,
  markGenerated,
  markUsed,
  nextTopic,
  nextTopicAt,
  publicTopic,
  saveGarden,
  seedFromCommitments,
  seedFromMemory,
} from './garden.js'
import { lastReplyClass, exportImportantMemories, readCommitments, readCorePersona, readMood } from './context.js'
import { cleanPhone, funToday, worthObserving } from './phone.js'
import { NOTES_CANDIDATES, agendaLines, capabilityLines, companionChatText, roleNames, strategistChatText } from './roles.js'
import { tide as tideOf, tideGap, tideHourly, vagueStreak } from './tide.js'
import { FACES, stepTemper, temperLine } from './temper.js'
import { drillLines, drillOf, pickDrill, scoldLines } from './drills.js'
import { normalizeTracks, pickTrack, recentTracks, trackStatus } from './tracks.js'
// 第六轮 C：暗思考（后台自己整理，不开口）。
import { museSweep, museStatus } from './muse.js'
// 第六轮 D：姐妹自建工作流的护栏（配额 + 试启用 + 到期自动停 + 能力层拦墙）。
import { workflowAct, workflowPropose, workflowStatus, workflowSweep } from './workflows.js'
import { REQUIRED_FIELDS, WORKFLOW_ALLOW } from './workflows.js'
import { brainLines, chooseFace, cleanBrain, drillScorer, observe, onReply, onServe, onSilent, revise, updateArm } from './brain.js'
import { appendPrediction, checkClaim, cleanLedger, dueCount, emptyLedger, predictionLines, settle } from './predict.js'
import { buildVibe, pickedArms, renderVibe } from './vibe.js'
import { decideForce, forceLines, refuseSwitch } from './force.js'
import { BUSY_CHECK_MS, BUSY_DEFAULT_MIN, BUSY_MAX_MIN, aiTask, applyGear, effectiveGear, gearLines, gearSwitch } from './gear.js'
import { trustGap, trustLines, trustScore } from './trust.js'
import { buildSnapshot, sistersView } from './panel.js'
import { kbDir, kbWrite, knowledgeLines, longPlanDigest, scanUnfinished, syncVerdicts, vaultRecentNotes, vaultSearch } from './notes.js'
import { MORNING_CAP_MS, NIGHT_LOCK_TO, REVIEW_CAP_MS, ROLL_MS, absentAnchors, closeGate, localMinute, rhythmLines, trackRhythm } from './rhythm.js'
import {
  FOLLOWUP_CAP_MS, FOLLOWUP_PENALTY, STANDBY_LOCK_MS, addObligation, checkVerdict, clearObligation, closeLock, followupView,
  funBlockedBy, lockActive, lockExpired, obligationOf, obligationsView, openLock, standbyDue, takeReask,
} from './followup.js'
import { BRAIN_MIN_MS, SPRINT_LONG_MIN, SPRINT_MIN, resolveState } from './machine.js'
import {
  DEFAULT_WALL, wallConfig, wallDecision, mealNow, activeRelax, tightenActive, relaxRemaining, relaxUsedThisWeek, relaxBonus,
  trackApplyWindow, applyWindowOpen, dayKey, weekKey, WALL_KIND_DEFAULT, wallDefianceText,
} from './wall.js'
import { dailyPath, dayString, keywordsOf, loadDaily, normalizeTasks, reconcile, reviewText, writeDaily } from './daily.js'
import { DEPARTMENTS, completeOf, depositLines, depositOf, missingText, normalizeEntry, statusOf, withEntry } from './deposit.js'
import {
  SCREENSHOT_DIRS, auditTask, baselineFor, checkMorningTasks, evidenceType, listScreenshots, loadWallMemory,
  noteDone, notePromised, pruneMemory, recentRate, statOf, wallText,
} from './wall_auditor.js'
import {
  AUDIT_DIR, PENDING_FILE, RULES_COOLDOWN_DAYS, RULES_FILE, WALL_DIR, appendAudit, classifyRules, parseRules, readText as readVaultText,
  findObsidianVault, renderPending, renderRules, renderTaskNote, taskNotePath, updatePending, writeText as writeVaultText,
} from './vault_wall.js'
import { GEMINI_PACKAGES, driftPull, geminiBrief, geminiLines, geminiMode, geminiSteps, recapMarkdown, trackGemini } from './gemini.js'
import { auditOf, cardsOf, groupPrompts } from './sisters.js'

export const name = 'dsh-go-companion'
// Without this, `ctx.tools` below throws "cannot get property tools without inject"
// and the whole entry fails to activate (routes, sweeper and companion_topic never load).
export const inject = ['tools']
const PREFIX = '/dsh-go-companion/v1'
const MAX_BODY = 16 * 1024

const DEFAULTS = Object.freeze({
  enabled: true,
  sessionId: undefined,
  // 安静时段 2–7 点（她们不说话）；0:30 以后娱乐照样锁（见 rhythm.js）。
  quietStart: 2,
  quietEnd: 7,
  // 整体高频（闲时档）：最短 18 分钟一次、每小时 4、每天 40，骰子 85 基本都开口。设置页可改。
  dailyMax: 40,
  // 每小时上限：阶梯换法子时 minGap 会被压过，靠它兜住"别连着砸"。
  hourlyMax: 4,
  // 温和模式的最短间隔（设置页可调）。原来是 30 分钟再乘 1.5，现在直接写 45。
  minGapMs: 18 * 60_000,
  diceThreshold: 85,
  cooldownMs: 24 * 60 * 60_000,
  allowColdSession: true,
  owner: '主人',
  self: '大肥鱼',
  timeZone: DEFAULT_TZ,
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
  companionName: '鱼丸',
  strategistName: '鲸澜',
  // 他给了高权限：主动找他的那一轮也能动手（设提醒、退出 App、调手机）。DSH 自己的审批照常生效。
  proactiveTools: true,
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
  // D5：破窗一次扣多少信用分（默认 15）；每天最多破窗 1 次是硬规则，不做配置。
  breakglassPenalty: 15,
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

function home() {
  return process.env.DSH_HOME || join(homedir(), '.dsh')
}

function configPath() {
  return join(home(), 'companion', 'config.json')
}

function readConfig() {
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
  // 上一版的默认频率（每天 6、每小时 3、45 分钟、骰子 60）也会随 /config 写进配置：没改过就升到现在的高频默认。
  if (merged.dailyMax === 6 && merged.hourlyMax === 3 && merged.minGapMs === 45 * 60_000 && merged.diceThreshold === 60) {
    Object.assign(merged, { dailyMax: DEFAULTS.dailyMax, hourlyMax: DEFAULTS.hourlyMax, minGapMs: DEFAULTS.minGapMs, diceThreshold: DEFAULTS.diceThreshold })
  }
  // 旧 dsh-go-proactive 设置过目标会话：迁过来，避免升级后“开了但不说话”。
  if (!merged.sessionId) {
    try {
      const legacy = JSON.parse(readFileSync(join(home(), 'storages', 'dsh-go-proactive', 'config.json'), 'utf8'))
      if (legacy && typeof legacy.sessionId === 'string' && legacy.sessionId) merged.sessionId = legacy.sessionId
    } catch {
      // no legacy config
    }
  }
  return merged
}

function writeConfig(cfg) {
  const p = configPath()
  mkdirSync(join(p, '..'), { recursive: true, mode: 0o700 })
  const tmp = p + '.' + String(process.pid) + '.tmp'
  writeFileSync(tmp, JSON.stringify(cfg, null, 2) + '\n', { encoding: 'utf8', mode: 0o600 })
  renameSync(tmp, p)
}

/**
 * 最近 10 次实测耗时的中位数。发球 / 判球 / 聊天分开算：模型慢的时候，设置页要能一眼看出
 * 慢在哪一段，而不是只看到一句"没回"。
 */
function readTimings() {
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

function memoryPath() {
  return join(home(), 'fish-memory', 'fish-memory.sqlite')
}

function gardenPath() {
  return join(home(), 'companion', 'topics.json')
}

function stateFile(name) {
  return join(home(), 'companion', name)
}

function readState(file, fallback) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'))
  } catch {
    return fallback
  }
}

function writeJson(file, value) {
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
function startOfDay(now, tz) {
  return zoneStartOfDay(now, zoneOr(tz))
}

function send(res, status, body) {
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
  })
  res.end(JSON.stringify(body))
}

async function readJson(req) {
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

function sessionIds(id) {
  const s = String(id || '')
  return s.startsWith('session-') ? [s, s.slice(8)] : [s, 'session-' + s]
}

function isArchived(ctx, id) {
  try {
    const archived = ctx.get?.('workspaceRegistry')?.archivedSessionIds
    if (!Array.isArray(archived)) return false
    return sessionIds(id).some((x) => archived.includes(x))
  } catch {
    return false
  }
}

function liveAgent(ctx, id) {
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
async function resolveAgent(ctx, id, config) {
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

function userMessage(text) {
  return { id: randomUUID(), role: 'user', content: [{ type: 'text', text }], source: { kind: SOURCE_KIND } }
}

/**
 * 判球用哪个模型：跟当前会话自己的选择走（它自己的人格和历史也都在那个会话里）。
 * 拿不到就返回 undefined —— 判球会退回本地规则，不会因为没模型就卡住。
 */
function judgeTargetOf(ctx, sessionId, config) {
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

function messageText(data) {
  const content = data?.content
  if (!Array.isArray(content)) return ''
  return content
    .filter((b) => b && b.type === 'text' && typeof b.text === 'string')
    .map((b) => b.text)
    .join('')
    .trim()
}

export function apply(ctx) {
  let config = readConfig()
  let garden = loadGarden(gardenPath())
  // 判球要模型。llm 不在就一直是 undefined，判球自动退回本地规则——不能因为缺服务就不加载插件。
  let llmStream
  if (typeof ctx.inject === 'function') {
    try {
      ctx.inject(['llm'], (c) => {
        llmStream = c?.llm?.stream ? c.llm.stream.bind(c.llm) : undefined
      })
    } catch {
      // 没有 llm 服务也没关系
    }
  }
  const streamOf = () => llmStream
  console.error('[companion] loaded; session=' + String(config.sessionId || 'none') + ' memory=' + memoryPath())

  function persistGarden() {
    try {
      saveGarden(gardenPath(), garden)
    } catch (error) {
      console.error('[companion] 保存话题花园失败：' + String((error && error.message) || error))
    }
  }

  /** sessionId -> {requestId, topicId, at}: a generated message the person has not answered yet. */
  const pendingReply = new Map()
  const REPLY_WINDOW_MS = 2 * 3_600_000

  function setPending(sessionId, value) {
    if (!sessionId) return
    for (const id of sessionIds(sessionId)) pendingReply.set(id, value)
  }

  function takePending(sessionId) {
    if (!sessionId) return undefined
    for (const id of sessionIds(sessionId)) {
      const v = pendingReply.get(id)
      if (v) {
        for (const x of sessionIds(sessionId)) pendingReply.delete(x)
        return v
      }
    }
    return undefined
  }

  function record(entry) {
    appendLedger(home(), { v: 1, ...entry })
  }

  // ---------------------------------------------------------------- 大脑
  // 大脑和脾气各存一个小文件；读坏了就从空的开始，绝不让一轮卡住。
  /** sessionId -> 他最后一次亲口说话的时间：正在聊就不用主动发球。 */
  const lastHumanAt = new Map()
  /** 他说完这么久之内算"正在聊"。 */
  const IN_CONVERSATION_MS = 3 * 60_000

  /** 他正在跟大肥鱼说话（刚说过，或者大肥鱼正在回他）。 */
  function inConversation(sessionId, now) {
    for (const id of sessionIds(sessionId)) {
      if (now - (lastHumanAt.get(id) || 0) < IN_CONVERSATION_MS) return true
    }
    // 浮层里那句话还在等回复，也算正在聊。
    chat.sweep()
    if (chat.inFlight()) return true
    return liveAgent(ctx, sessionId)?.status === 'running'
  }

  /** 讲通了理由之后松多久。 */
  const GRACE_MS = 20 * 60_000
  const loadBrain = () => cleanBrain(readState(stateFile('brain.json'), null))
  const saveBrain = (b) => writeJson(stateFile('brain.json'), b)
  // 预判账本：模型只写 claim，verdict 由墙的遥测填（predict.js 里 settle 是唯一出口）。
  const loadPredict = () => cleanLedger(readState(stateFile('predict.json'), null))
  const savePredict = (p) => writeJson(stateFile('predict.json'), p)
  // 上轮注入了哪几个 arm：这一球被拉回来时按它们各自记账（§13.1）。进程重启就丢，可接受。
  let lastVibePicks = []
  let lastVibe = { face: '', turn: 0 }
  /** requestId -> 发球时的现状（generated 时交给大脑开始计"有没有拉回来"）。 */
  const servedContext = new Map()
  /** requestId -> first seen moment, for the median latencies in /status (P6). */
  const startedAt = new Map()

  function hourOf(phone, now) {
    if (phone?.local) return Number(phone.local.slice(0, 2))
    return Math.floor(zoneMinuteOfDay(now, zoneOr(config.timeZone)) / 60)
  }

  /** 这一刻他陷得多深：手机现状 + 账本（连着没接、连着糊弄）。顺带让大脑看一眼上一球有没有把他拉回来。 */
  function sense(phone, entries, sessionId, now) {
    const brain = loadBrain()
    const pulled = observe(brain, { phone, now })
    if (pulled) {
      // §13.1 multiple-play bandit：只给上一轮真被抽中的 arm 各记一次，没抽中的不动。
      // 按组合记账会摊薄到永远攒不够，所以这里绝不能写成记一个组合 key。
      for (const arm of lastVibePicks) updateArm(brain, arm, pulled.pulled)
      revise(brain, now)
      record({ state: 'pulled', at: now, pulled: pulled.pulled, face: pulled.face, band: pulled.band, arms: lastVibePicks })
    }
    const silence = silenceState(entries, { sessionId, now })
    const vague = vagueStreak(entries)
    const water = tideOf({ phone, noTake: silence.noTake, vague, hour: hourOf(phone, now) })
    return { brain, silence, vague, water }
  }

  /** 上一道思维题怎么样了：没回 / 答糊弄了，这一球就要说他。 */
  function lastDrillOutcome(entries, now) {
    for (let i = entries.length - 1; i >= 0; i -= 1) {
      const e = entries[i]
      if (e?.state !== 'generated') continue
      if (!e.drill) return null
      const reply = entries.find((x) => x?.requestId === e.requestId && x.state === 'replied')
      if (reply) return { drill: e.drill, requestId: e.requestId, verdict: reply.verdict, answer: reply.answer }
      if (now - Number(e.at) >= 5 * 60_000) return { drill: e.drill, requestId: e.requestId, silent: true }
      return null
    }
    return null
  }

  /**
   * 最近几拍，给判球当上下文（任务书：发球原文 + 回球 + 最近 3 拍）。
   * 只取最近一个回合里的：跨回合的内容对判断没帮助，还会带偏。
   */
  function recentShots() {
    const entries = readLedger(home(), 40)
    let rallyId = ''
    for (let i = entries.length - 1; i >= 0; i -= 1) {
      if (entries[i]?.rallyId) {
        rallyId = String(entries[i].rallyId)
        break
      }
    }
    if (!rallyId) return []
    const out = []
    for (const e of entries) {
      if (e?.rallyId !== rallyId) continue
      if (e.state === 'generated' && e.text) out.push({ from: 'me', text: String(e.text) })
      else if (e.state === 'replied' && e.verdict) out.push({ from: 'user', text: String(e.answer || e.verdict) })
    }
    return out.slice(-3)
  }

  /** 最近一小时发出去几条：阶梯能压过 minGap，靠这个兜住频率。回合里接着打的不算（见 capCount）。 */
  function sentLastHour(entries, now) {
    return capCount(entries, Number(now) - 3_600_000)
  }

  /** 上一次被漠视的那一球：阶梯换法子时拿它当素材（"我叫了你两次，你在刷什么"）。 */
  function lastIgnored(entries) {
    for (let i = entries.length - 1; i >= 0; i -= 1) {
      const e = entries[i]
      if (e?.state !== 'generated' || !e.requestId) continue
      let answered = false
      for (const x of entries) if (x?.requestId === e.requestId && x.state === 'replied') answered = true
      if (!answered) return { at: Number(e.at) || 0, serveType: String(e.serveType || ''), text: String(e.text || '') }
      return null
    }
    return null
  }

  /** 上一球是什么类型（观察球不连着打）。 */
  function lastServeType(entries) {
    for (let i = entries.length - 1; i >= 0; i -= 1) {
      const e = entries[i]
      if (e?.state === 'generated') return String(e.serveType || '')
    }
    return ''
  }

  // 上一球如果被判成套话（3.3），把原句取出来，下一球照着打回。
  function lastStockBall(entries) {
    for (let i = entries.length - 1; i >= 0; i -= 1) {
      const e = entries[i]
      if (e?.state !== 'generated' || !e.text) continue
      return e.stock === true ? String(e.text) : null
    }
    return null
  }

  async function compose(req, sessionId) {
    const db = openMemoryDb(memoryPath())
    try {
      const now = Date.now()
      if (speakerOf(sessionId) === 'strategist') {
        // 姐姐主动找他：一次说清一件事（妹妹递的急话 / 晚上对账）。
        const why = strategistBallReason.get(String(req?.requestId || '')) || { reason: pendingHandoff(now) ? 'handoff' : 'review' }
        const note = why.reason === 'handoff' ? why.note || pendingHandoff(now) : null
        const candidates = loadCandidates(db, { sessionId, now })
        const vault = vaultContext(now)
        const prompt = buildPrompt({
          role: 'strategist',
          reason: why.reason,
          note: why.reason === 'handoff' ? note?.text : why.note,
          busy: why.digest === true ? { ...(why.busy || {}), digest: why.digestText || (why.digestText = takeDigest()) } : why.busy,
          contact: contactLines(readLedger(home(), 400), now),
          gemini: why.gemini,
          phone: cleanPhone(req?.phone),
          commitments: readCommitments(home(), now, 3),
          facts: backgroundFacts(candidates, BACKGROUND_LIMIT),
          vault,
          names: roleNames(config),
          owner: config.owner,
          allowTools: config.proactiveTools !== false,
        })
        if (note) markDelivered(note)
        markBall(why, now)
        const entries = readLedger(home(), 400)
        const rally = nextShot(entries, { sessionId, now })
        return {
          text: prompt, prompt, topic: null, serveType: 'strategist', rallyId: rally.rallyId, shot: rally.shot,
          silence: { stage: 'none' }, extra: { speaker: 'strategist', reason: why.reason },
        }
      }
      // /plan 定的特殊开场（忙完迎接 / 连麦中途跑了 / 单人格的报到和连麦复盘）。
      const special = strategistBallReason.get(String(req?.requestId || '')) || null
      if (special) markBall(special, now)
      const candidates = loadCandidates(db, { sessionId, now })
      const entries = readLedger(home(), 400)
      const recent = summarize(entries, startOfDay(now, config.timeZone))
      const commitments = readCommitments(home(), now, 3)

      // 记忆是话题的种子来源，花园自己管生命周期；每次合成前同步一次，只增不删。
      seedFromMemory(garden, candidates, now)
      seedFromCommitments(garden, commitments, now)

      const topic = nextTopic(garden, {
        now,
        lastTopicId: recent.lastTopicId,
        lastAt: recent.lastAt,
        cooldownMs: config.cooldownMs,
        maxAttempts: MAX_ATTEMPTS,
      })
      // 没有话题照样开口：用手边的情况、人设起话头。话题只是素材，不是开口的前提。
      if (topic) markUsed(garden, topic.id, now)
      persistGarden()

      const mood = readMood(home())
      // 没接球时不砸同一类：走阶梯就只挑更轻的球，平时按权重挑（被漠视的降、接住的升）。
      const silence = silenceState(entries, { sessionId, now })
      const lighter = silence.stage === 'lighter' || silence.stage === 'channel'
      // 模式决定从哪几种球里挑：伙伴偏闲聊/记忆，教练偏观察/挑衅。前三种里挑。
      const mode = effectiveMode(config, now)
      // 手边的情况由手机在 /request 里带来；没带就是不知道，观察球就不打（没东西可观察只能编）。
      const phone = cleanPhone(req?.phone)
      const order = modeServeOrder(mode).filter((t) => t !== 'observe' || phone)
      const picked = pickServeType(entries, Math.floor(Math.random() * 100), {
        only: lighter ? LIGHTER_TYPES : order.slice(0, 3),
        weights: serveWeights(entries),
      })
      // 正开着娱乐 App 一阵子了：这一球就打观察（"你在 B站 20 分钟了"），上一球已经是观察就不连着打。
      let serveType = !lighter && worthObserving(phone) && lastServeType(entries) !== 'observe' ? 'observe' : picked

      // 大脑：水位 → 脾气 → 脸色（Thompson 挑招）→ 力度 → 要不要出思维题。
      const { brain, water } = sense(phone, entries, sessionId, now)
      const temper = stepTemper(readState(stateFile('temper.json'), null), {
        mode, tide: water.level, noTake: silence.noTake, mood: mood ? mood.mood : undefined, now,
      })
      // 整份存：face/label/rule/surprise 原先被丢掉，导致 temperLine 永远拼不出内容（这条脸色从没进过 prompt）。
      writeJson(stateFile('temper.json'), temper)
      const faceId = chooseFace(brain, { band: water.band, face: temper.face, surprise: temper.surprise })
      const face = { ...temper, ...(FACES.find((f) => f.face === faceId) || {}), face: faceId }
      const force = decideForce({
        phone, tide: water, mode, noTake: silence.noTake, control: config.control !== false, funToday: funToday(phone),
        grace: Number(brain.graceUntil) > now && trustNow(now).band !== 'low',
        refuse: refuseUntilAt(now) > now,
      })
      // 上一题没回 / 答糊弄了：这一球必须是说他 + 同一类题重问。
      const drillsOn = config.drills === true
      const owed = drillsOn ? lastDrillOutcome(entries, now) : null
      const scold = owed ? scoldLines(drillOf(owed.drill), owed) : []
      if (owed?.silent) onSilent(brain, { requestId: owed.requestId })
      // 在娱乐 App 里越陷越可能出题；要锁的那一球一定出题（答过线才放行）。
      // 阶梯在"换更轻的球"时不随机出题（欠着的题除外）；温和模式又不知道他在干嘛时，也不凭空出题。
      const drillChance = !drillsOn ? 0
        : force.level >= 3 || scold.length ? 1
        : lighter ? 0
        : phone?.fun ? 0.45 + water.level / 200
        : mode === 'coach' ? 0.3 : 0
      const drill = Math.random() < drillChance
        ? (scold.length ? drillOf(owed.drill) : pickDrill(entries, drillScorer(brain)))
        : null
      if (drill) serveType = 'drill'
      // 支线（第六轮 A）：配置里有几条就按"最近三条避开"挑一条。一条都没有 → null，主干不受影响。
      const track = pickTrack(normalizeTracks(config.tracks).tracks, recentTracks(entries))
      const firstToday = recent.counts.generated === 0
      saveBrain(brain)
      // 回合与拍数都从账本重建：进程重启不会丢，也不多存一份状态。
      const rally = nextShot(entries, { sessionId, now })
      const vault = vaultContext(now)
      const prompt = buildPrompt({
        topic,
        facts: backgroundFacts(candidates, BACKGROUND_LIMIT),
        episodes: (candidates.episodes || []).map((e) => e.text),
        commitments,
        mood: mood ? mood.mood : undefined,
        replyClass: lastReplyClass(entries),
        serveType,
        stockHit: lastStockBall(entries),
        ignored: lighter ? lastIgnored(entries) : undefined,
        // 人格卡：关系库人物卡打底，配置里的覆盖（两张卡合一）。
        persona: mergePersona(readCorePersona(home()), config.persona),
        mode,
        phone,
        vault,
        role: config.dual === false ? undefined : 'companion',
        names: roleNames(config),
        allowTools: config.proactiveTools !== false,
        temper: face,
        drill,
        track,
        scold,
        beliefs: brainLines(brain),
        force: forceLines(force, phone, { drill: Boolean(drill), gemini: config.proactiveTools !== false }),
        special,
        contact: contactLines(entries, now),
        grace: Number(brain.graceUntil) > now,
        morning: firstToday,
        owner: config.owner,
        self: config.dual === false ? config.self : roleNames(config).companion,
      })
      servedContext.set(String(req?.requestId || ''), { phone, band: water.band, face: faceId, drill: drill?.id })
      const extra = {
        tide: water.level, band: water.band, face: faceId, surprise: temper.surprise || undefined,
        drill: drill?.id, force: force.level || undefined, track: track?.id,
      }
      return { text: prompt, prompt, topic, serveType, rallyId: rally.rallyId, shot: rally.shot, silence, extra }
    } finally {
      try {
        db?.close?.()
      } catch {
        // ignore
      }
    }
  }

  /** 最近一次 /say 带来的手机时区（没带就用配置里的）。 */
  let sayZone = ''

  // ---------------------------------------------------------------- 直接聊（/say）
  // 浮层里打的字原样作为他的话进大肥鱼的会话（不带插件来源，就是他本人说的），大肥鱼照常回，
  // 工具也不拦——跟在 App 里聊一模一样。回复从 /result 取。判球、安静、换模式这些照常认这句话。
  const chat = new Desk({
    resolveAgent: (id) => resolveAgent(ctx, id, config),
    compose: async (req) => ({ text: req.payload.text, prompt: req.payload.text }),
    // 来源必须带：引擎的会话控制器默认每条消息都有 source，没有就抛异常、这一轮根本不开（真机测出来的）。
    // 他本人打的字，就照 App 里打字的格式标成 user；rpcId 加前缀，不会和 App 的提交撞上。
    // 带上手机时区：引擎只从 user 来源的 clientTimeZone 读当地时间；不带就按引擎的 GMT，
    // 大肥鱼会在中午说"四点了快去睡"（人格测试测出来的）。
    makeMessage: (text) => ({
      id: randomUUID(), role: 'user', content: [{ type: 'text', text }],
      source: { kind: 'user', rpcId: 'dsh-go-companion-say-' + randomUUID(), clientTimeZone: zoneOr(sayZone || config.timeZone) },
    }),
    isArchived: (id) => isArchived(ctx, id),
    defaultSession: () => config.sessionId,
    limits: { ...DEFAULT_LIMITS, maxPayloadValue: 2_000 },
    allowBusy: true,
    onChange: (r) => {
      const state = { accepted: 'said', generated: 'heard', failed: 'chat-failed' }[r.state]
      const now = Date.now()
      const key = String(r.requestId)
      const began = startedAt.get(key)
      if (r.state === 'accepted') startedAt.set(key, now)
      if (state) record({ requestId: r.requestId, sessionId: r.sessionId, state, at: now, ms: began ? now - began : undefined, failure: r.failure ?? undefined,
        // 家庭群要看得到她回了什么（只留一段）。
        text: r.state === 'generated' && r.text ? String(r.text).slice(0, 400) : undefined })
      if (r.state === 'generated' || r.state === 'failed') refreshPanels()
      if (r.state !== 'accepted') startedAt.delete(key)
    },
  })

  const desk = new Desk({
    resolveAgent: (id) => resolveAgent(ctx, id, config),
    compose,
    allowTools: () => config.proactiveTools !== false,
    makeMessage: userMessage,
    isArchived: (id) => isArchived(ctx, id),
    defaultSession: () => config.sessionId,
    onChange: (r) => {
      const now = Date.now()
      const key = String(r.requestId)
      const began = startedAt.get(key)
      if (r.state === 'accepted') startedAt.set(key, now)
      record({
        requestId: r.requestId,
        eventId: r.eventId,
        sessionId: r.sessionId,
        topicId: r.topicId,
        topicKind: r.topicKind,
        serveType: r.serveType ?? undefined,
        rallyId: r.rallyId ?? undefined,
        shot: r.shot ?? undefined,
        silence: r.silence ?? undefined,
        ...(r.extra && (r.state === 'accepted' || r.state === 'generated') ? r.extra : {}),
        state: r.state,
        at: now,
        ms: began ? now - began : undefined,
        text: r.text ?? undefined,
        // 套话只在生成时判：谁都能说的模板不算开口。判出来就记进账本，
        // 下一球把它打回去，P5 的评测也靠这个数。
        stock: isStock(r.text) || undefined,
        failure: r.failure ?? undefined,
        cold: r.cold === true,
      })
      if (r.state !== 'accepted') startedAt.delete(key)
      // 生成成功后等一次人的回应；2 小时内没有就记 none（有限跟进/冷却的依据）。
      if (r.state === 'generated') {
        lastBall = { role: speakerOf(r.sessionId), at: Date.now() }
        // 大脑从这一刻开始计：10 分钟内他有没有离开那个娱乐 App。
        const served = servedContext.get(String(r.requestId))
        servedContext.delete(String(r.requestId))
        if (served) {
          const b = loadBrain()
          onServe(b, { requestId: r.requestId, phone: served.phone, band: served.band, face: served.face, drill: served.drill, at: Date.now() })
          saveBrain(b)
        }
        // 发球原文、回合、拍数都留给判球用：回球回来时要能把它和这一球对上。
        setPending(r.sessionId, {
          requestId: r.requestId,
          topicId: r.topicId,
          rallyId: r.rallyId,
          shot: r.shot,
          serveType: r.serveType,
          drill: r.extra?.drill,
          serve: r.text || '',
          at: Date.now(),
        })
        if (r.topicId) {
          markGenerated(garden, r.topicId, r.text, Date.now())
          persistGarden()
        }
      }
    },
  })

  ctx.on('session/event', async (session, event) => {
    try {
      desk.onSessionEvent(session?.id, event)
      chat.onSessionEvent(session?.id, event)
    } catch {
      // never disturb the session
    }
    try {
      if (event?.type === 'user/message') {
        const text = messageText(event.data)
        // 插件注入的消息都走 user/message（自己、speak-up、思维驾驶员……），
        // 也都不是人打的字。当成回复会凭空记一条 replied，还会把话题判成 done；
        // 只有来源为空或明确标成 user 的才算人。
        const kind = event.data?.source?.kind
        if (kind && kind !== 'user') return
        lastHumanAt.set(String(session?.id || ''), Date.now())
        // 落进账本：引擎重启也知道他刚说过话（阶梯不会把聊过的球当成没接，发球也知道他多久前说的话）。
        record({ state: 'human', at: Date.now(), sessionId: String(session?.id || '') })
        // 第三轮：早上/晚上的门不再靠"他认真回了一句"关——过关要交墙按证据判（submit_to_wall），
        // 敷衍的话不会让窗口消失。这里只记"他在聊"。
        // 他说"安静一小时""今天别找我"就照做：这是一句话就说清的事，不必占一次模型调用。
        const muteUntilAt = mutedUntil(text, Date.now(), { tz: config.timeZone })
        if (muteUntilAt) {
          config = { ...config, muteUntil: muteUntilAt }
          try {
            writeConfig(config)
          } catch {
            // 写不进去也不影响这一轮；下次他还说就再设一次
          }
          record({ state: 'muted', at: Date.now(), until: muteUntilAt, answer: String(text).slice(0, 100) })
        }
        // 他说"进入教练模式 2 小时"就切过去；给没给时长都认。
        const sw = modeSwitch(text)
        if (sw) {
          config = { ...config, mode: sw.mode, modeUntil: sw.hours ? Date.now() + sw.hours * 3_600_000 : 0 }
          try {
            writeConfig(config)
          } catch {
            // 写不进去也不影响这一轮
          }
          record({ state: 'mode', at: Date.now(), mode: sw.mode, until: config.modeUntil || undefined })
        }
        // "拒绝模式 / 拒绝模式 3 小时"：娱乐 App 一打开就赶回桌面锁住；"关掉拒绝模式"解除。
        const refuse = refuseSwitch(text, Date.now())
        if (refuse) {
          config = { ...config, refuseUntil: refuse.off ? 0 : refuse.until }
          try {
            writeConfig(config)
          } catch {
            // 写不进去也不影响这一轮
          }
          record({ state: 'refuse', at: Date.now(), until: config.refuseUntil || undefined, on: !refuse.off })
        }
        // "忙时 90 分钟""我去做饭了""忙完了"：切档（忙时顺带开拒绝模式）。
        const gearSw = gearSwitch(text, Date.now())
        if (gearSw && gearSw.gear === 'free' && aiTask(config, Date.now())) {
          // 她们立的任务：他自己喊收工不算，要验收（人设里告诉她们去验）。
          record({ state: 'task-claim', at: Date.now(), text: String(text).slice(0, 100) })
        } else if (gearSw) setGear(gearSw, 'phrase')
        // "开始做题 / 做题模式"打开做题，"别出题了"关掉。
        const drillSwitch = drillToggle(text)
        if (drillSwitch !== null) {
          config = { ...config, drills: drillSwitch }
          try {
            writeConfig(config)
          } catch {
            // 写不进去也不影响这一轮
          }
          record({ state: 'drills', at: Date.now(), on: drillSwitch })
        }
        const pending = takePending(session?.id)
        if (pending) {
          // 这一球由大肥鱼自己判（6 秒超时或连不上就退回规则），不再按字数。
          const target = judgeTargetOf(ctx, session?.id, config)
          const judgeBegan = Date.now()
          const judged = await judgeReply({
            stream: streamOf(),
            provider: target?.provider,
            model: target?.model,
            sessionId: session?.id,
            serve: pending.serve,
            reply: text,
            recent: recentShots(),
            criteria: drillOf(pending.drill)?.pass,
            mode: effectiveMode(config, Date.now()),
          })
          const judgeMs = Date.now() - judgeBegan
          const replyClass = gardenClass(judged.verdict)
          const now = Date.now()
          const shot = (Number(pending.shot) || 0) + 1
          record({
            requestId: pending.requestId,
            sessionId: session?.id,
            topicId: pending.topicId,
            rallyId: pending.rallyId || undefined,
            shot: pending.rallyId ? shot : undefined,
            state: 'replied',
            at: now,
            judgeMs,
            replyClass,
            verdict: judged.verdict,
            why: judged.why || undefined,
            next: judged.next,
            judged: judged.judged === true,
            // 判下一球时要看得到上一球回了什么；只留一小段，账本本来就在手机上、权限 0600。
            answer: String(text || '').slice(0, 200) || undefined,
          })
          if (replyClass === 'substantive') {
            record({
              requestId: pending.requestId,
              sessionId: session?.id,
              topicId: pending.topicId,
              rallyId: pending.rallyId || undefined,
              shot: pending.rallyId ? shot : undefined,
              state: 'substantive',
              at: now,
              replyClass,
              verdict: judged.verdict,
            })
          }
          // 大脑记账：思维题过没过线、这张脸有没有把他拉回来；证据变了就改判断。
          {
            const b = loadBrain()
            onReply(b, { requestId: pending.requestId, verdict: judged.verdict })
            // 他讲了站得住的理由（真反驳）：先松一会儿，这段时间不锁。讲得通就该被听进去。
            if (judged.verdict === 'pushback') {
              b.graceUntil = now + GRACE_MS
              record({ state: 'grace', at: now, until: b.graceUntil, why: judged.why || undefined })
            }
            revise(b, now)
            saveBrain(b)
          }
          // 话题花园跟着人的反应走：认真答就继续，敷衍就换轻的，拒绝就停。
          if (pending.topicId) {
            applyReply(garden, pending.topicId, replyClass, now)
            persistGarden()
          }
        } else if (text) {
          // 正常聊天里冒出的新话题：每天最多 3 条，宁缺毋滥。
          if (discoverFromMessage(garden, text, Date.now())) persistGarden()
        }
      }
    } catch {
      // classification must never break a turn
    }
  })
  ctx.on(
    'tools/pre-execute',
    (exec, next) => {
      let denied
      try {
        denied = desk.guardTool(exec?.agent?.id ?? exec?.agent?.session?.id)
      } catch {
        denied = undefined
      }
      return denied ?? next()
    },
    { global: true, prepend: true },
  )
  ctx.on('agent/disposed', (agent) => {
    try {
      if (agent?.id) {
        desk.onAgentGone(agent.id)
        chat.onAgentGone(agent.id)
      }
    } catch {
      // ignore
    }
  })

  // 正常聊天时，模型可以把“以后还想聊的事”放进话题花园；主动回合里工具会被硬拦。
  const tools = ctx.get?.('tools') ?? ctx.tools
  if (tools && typeof tools.register === 'function') {
    try {
      const off = tools.register({
        name: 'companion_topic',
        description:
          'Record a topic worth returning to in the companion topic garden (not long-term facts). ' +
          'Use add when the person mentions something they want to do/watch/play/discuss later, ' +
          'or when you have an interesting share/idea worth bringing up later. ' +
          'list shows the garden; done/pause closes a topic. Keep one topic per entry.',
        parameters: {
          type: 'object',
          properties: {
            action: { type: 'string', enum: ['add', 'list', 'done', 'pause'] },
            title: { type: 'string', description: 'One line: what to talk about later.' },
            detail: { type: 'string', description: 'Optional context worth keeping.' },
            kind: { type: 'string', enum: ['continue', 'share', 'explore'], description: 'continue/unfinished, share, or an open question to think about.' },
            interest: { type: 'integer', description: '0..2, how much the person seems to care.' },
            id: { type: 'string', description: 'Topic id from list (for done/pause).' },
          },
          additionalProperties: false,
        },
        output: {
          schema: {
            type: 'object',
            additionalProperties: true,
            properties: { ok: { type: 'boolean' }, text: { type: 'string' } },
          },
          render: (_args, value) => [{ type: 'text', text: String((value && value.text) || '') }],
        },
        execute: (args = {}) => {
          const now = Date.now()
          const action = String(args.action || 'list')
          if (action === 'add') {
            const topic = addTopic(garden, {
              title: args.title,
              detail: args.detail,
              kind: args.kind,
              interest: args.interest,
              source: 'agent',
            }, now)
            if (!topic) return { ok: false, text: '标题不能为空。' }
            persistGarden()
            return { ok: true, text: '记进话题花园了：' + topic.title, topic: publicTopic(topic) }
          }
          if (action === 'list') {
            const rows = garden.topics.filter((t) => t.status !== 'done').slice(0, 10).map(publicTopic)
            return {
              ok: true,
              text: rows.length ? rows.map((t) => `${t.id} · ${t.kind} · ${t.status} · ${t.title}`).join('\n') : '花园里还没有话题。',
              topics: rows,
            }
          }
          let target = String(args.id || '')
          if (!target && args.title) {
            const needle = String(args.title).trim()
            const hit = garden.topics.find((t) => t.status !== 'done' && t.title.includes(needle))
            if (hit) target = hit.id
          }
          if (!target) return { ok: false, text: '没找到要关闭的话题（用 list 拿 id）。' }
          const closed = closeTopic(garden, target, action === 'done' ? 'done' : 'pause', now)
          if (!closed) return { ok: false, text: '没找到这个话题。' }
          persistGarden()
          return { ok: true, text: action === 'done' ? '话题结束了。' : '话题先搁着。', topic: publicTopic(closed) }
        },
      })
      if (typeof off === 'function') ctx.effect?.(() => off)
      console.error('[companion] registered tool companion_topic')
    } catch (error) {
      console.error('[companion] companion_topic 注册失败：' + String((error && error.message) || error))
    }
  }

  // ---------------------------------------------------------------- 人格进每一轮聊天
  // 人格卡以前只在主动发球那一球里带给模型；他在 App 里或浮层里正常聊，模型拿不到，
  // 人格就只剩会话自带的设定（新装就是通用助手）。这里给目标会话挂一段固定的系统上下文，
  // 和记忆插件同一个机制（systemPrompt.context），每轮都带，不改历史、不破坏前缀缓存之外的东西。
  // ---------------------------------------------------------------- 姐妹传话
  // 两人可以互相递话（sister_note 工具，按会话注册，所以知道是谁递给谁）。递给对方的话 24 小时内
  // 出现在对方每一轮的人设里；妹妹标了 urgent 的，姐姐会主动去找他（见 /plan）。
  const notesFile = () => stateFile('notes.json')
  function loadNotes() {
    const raw = readState(notesFile(), null)
    const list = Array.isArray(raw?.notes) ? raw.notes : []
    const cut = Date.now() - 3 * 86_400_000
    return list.filter((n) => n && Number(n.at) > cut).slice(-40)
  }
  function saveNotes(list) {
    writeJson(notesFile(), { notes: list.slice(-40) })
  }
  function notesFor(role, now = Date.now()) {
    return loadNotes().filter((n) => n.to === role && now - Number(n.at) < 86_400_000).slice(-5)
  }
  function sisterTool(role) {
    const names = () => roleNames(config)
    const other = role === 'strategist' ? 'companion' : 'strategist'
    return {
      name: 'sister_note',
      description:
        role === 'strategist'
          ? '给妹妹递一句话（她会在接下来和他的聊天里知道）。比如他被你说重了、太紧绷，让她去陪陪他、逗逗他。'
          : '给姐姐递一句话（她会在接下来和他的聊天里知道）。他说了正事、或者该被姐姐管一管了，就递过去；急的标 urgent，姐姐会主动去找他。',
      parameters: {
        type: 'object',
        properties: {
          text: { type: 'string', description: '一两句话：他怎么了、你想让对方做什么。' },
          urgent: { type: 'boolean', description: '急：对方会尽快主动去找他。' },
        },
        required: ['text'],
        additionalProperties: false,
      },
      output: {
        schema: { type: 'object', additionalProperties: true, properties: { ok: { type: 'boolean' }, text: { type: 'string' } } },
        render: (_a, v) => [{ type: 'text', text: String((v && v.text) || '') }],
      },
      execute: (args = {}) => {
        const text = String(args.text || '').trim().slice(0, 300)
        if (!text) return { ok: false, text: '话是空的。' }
        if (config.dual === false || !config.strategistSessionId) return { ok: false, text: '现在只有一个人格，没有人可以递话。' }
        const list = loadNotes()
        list.push({ from: role, to: other, text, urgent: args.urgent === true, at: Date.now() })
        saveNotes(list)
        record({ state: 'sister-note', at: Date.now(), from: role, to: other, urgent: args.urgent === true || undefined, text })
        const to = other === 'strategist' ? names().strategist : names().companion
        return { ok: true, text: '递给' + to + '了' + (args.urgent === true ? '（急）' : '') + '。' }
      },
    }
  }
  /** 双人格才挂传话工具（单人格时没人可递）。 */
  const sisterToolIfDual = (role) => (config.dual !== false ? sisterTool(role) : null)
  /** 他的笔记库：设置里填的优先，否则找常见的 Obsidian / 笔记目录（10 分钟查一次）。 */
  let notesCache = { at: 0, dir: '' }
  const isDir = (p) => {
    try {
      return statSync(p).isDirectory()
    } catch {
      return false
    }
  }
  function notesDir() {
    const now = Date.now()
    if (now - notesCache.at < 10 * 60_000) return notesCache.dir
    const set = typeof config.notesPath === 'string' ? config.notesPath.trim() : ''
    // 第四轮：先找带 .obsidian 的真库，再退回几个常见文件夹名。
    const dir = set && isDir(set) ? set : findObsidianVault() || NOTES_CANDIDATES.find(isDir) || ''
    notesCache = { at: now, dir }
    return dir
  }
  /** 进 prompt 的知识库摘要：长期规划、今天的事、最近改过的笔记（摘要就够了，别把整库灌进去）。 */
  function vaultContext(now) {
    const dir = notesDir()
    if (!dir) return null
    const kb = kbDir(dir, home())
    const tz = zoneOr(config.timeZone)
    const daily = loadDaily(loadDailyState(), now, tz)
    return {
      longPlan: longPlanDigest(kb, 1200) || undefined,
      tasks: daily.tasks.length ? daily.tasks : undefined,
      recent: vaultRecentNotes(dir, { limit: 3, now }) || undefined,
    }
  }
  /** 他说"我的笔记在 xxx"：她们自己记下笔记库在哪，不用他去翻设置。 */
  function notesTool() {
    return {
      name: 'notes_vault',
      description: '记下他的笔记库（Obsidian / Markdown 笔记文件夹）在哪，之后两姐妹都能去读。他告诉你路径时用；路径是手机里的绝对路径，比如 /sdcard/Documents/Obsidian/我的库。',
      parameters: {
        type: 'object',
        properties: { path: { type: 'string', description: '笔记库文件夹的绝对路径' } },
        required: ['path'],
        additionalProperties: false,
      },
      output: {
        schema: { type: 'object', additionalProperties: true, properties: { ok: { type: 'boolean' }, text: { type: 'string' } } },
        render: (_a, v) => [{ type: 'text', text: String((v && v.text) || '') }],
      },
      execute: (args = {}) => {
        const path = String(args.path || '').trim().replace(/\/+$/, '')
        if (!isAbsolute(path)) return { ok: false, text: '要手机里的绝对路径，比如 /sdcard/Documents/Obsidian/我的库。' }
        if (!isDir(path)) return { ok: false, text: '这个文件夹我看不到：要么路径不对，要么没在 DSH Go 里打开"访问手机文件"。' }
        config = { ...config, notesPath: path.slice(0, 300) }
        try {
          writeConfig(config)
        } catch {
          // 这一轮照样能用
        }
        notesCache = { at: 0, dir: '' }
        return { ok: true, text: '记住了，笔记库在 ' + path + '。' }
      },
    }
  }
  // ---------------------------------------------------------------- 档位 / Gemini / 控制
  const loadRhythm = () => readState(stateFile('rhythm.json'), {}) || {}
  const saveRhythm = (r) => writeJson(stateFile('rhythm.json'), r)
  /** 最近一次 /plan 算出的作息（早上门 / 晚上门 / 深夜阶段 / 深夜锁到几点）。 */
  let lastRhythm = null
  let lastApplyWindow = null
  const loadNotifs = () => readState(stateFile('notifs.json'), {}) || {}
  const saveNotifs = (n) => writeJson(stateFile('notifs.json'), n)
  /** 永远不封的：电话、短信、地图、支付、DSH 自己。 */
  const NEVER_BLOCK = ['com.android.dialer', 'com.google.android.dialer', 'com.android.phone', 'com.android.server.telecom', 'com.android.mms',
    'com.google.android.apps.messaging', 'com.android.contacts', 'com.autonavi.minimap', 'com.baidu.BaiduMap', 'com.google.android.apps.maps',
    'com.tencent.map', 'com.eg.android.AlipayGphone', 'com.unionpay', 'io.github.ccch1mneyyy.dsh_app', 'io.github.ccch1mneyyy.dsh_app.fish']
  /** 临时封禁名单（block_apps 写的）：还没到期的包名。 */
  const blockedNow = (now) => (Array.isArray(config.blockExtra) ? config.blockExtra : [])
    .filter((b) => b && Number(b.until) > now && !NEVER_BLOCK.includes(b.app)).map((b) => b.app)
  const loadGemini = () => readState(stateFile('gemini.json'), {}) || {}
  const saveGemini = (g) => writeJson(stateFile('gemini.json'), g)
  // D4：预约的下一场 Gemini 连麦（一次性，发过就 fired）。
  const loadGeminiSchedule = () => readState(stateFile('gemini-schedule.json'), {}) || {}
  const saveGeminiSchedule = (s) => writeJson(stateFile('gemini-schedule.json'), s)
  /**
   * "HH:MM" 解析成当地时间的下一个时刻：今天还没到就是今天，过了就是明天。
   * 认不出来返回 0，调用方再试 ISO。
   */
  function nextLocalAt(text, now, tz) {
    const m = /^(\d{1,2}):(\d{2})$/.exec(String(text || '').trim())
    if (!m) return 0
    const hour = Number(m[1])
    const minute = Number(m[2])
    if (!(hour >= 0 && hour < 24 && minute >= 0 && minute < 60)) return 0
    const day = startOfDay(now, tz)
    // 当地零点 + 时分就是当地那一刻。
    const target = day + (hour * 60 + minute) * 60_000
    return target > now ? target : target + 86_400_000
  }
  /** 到点了就把预约换成一次 launch（背景简报 + 进共舱），只发一次。 */
  function dueGeminiLaunch(now = Date.now()) {
    const s = loadGeminiSchedule()
    const at = Number(s.at) || 0
    if (!at || s.fired === true || at > now) return null
    const topic = String(s.topic || '')
    const minutes = Math.max(5, Math.min(180, Math.round(Number(s.minutes) || 30)))
    const mode = geminiMode(s.mode)
    const brief = geminiBrief({
      owner: String(config.owner || '他'), topic, minutes, mode,
      about: personaLines(mergePersona(readCorePersona(home()), config.persona), effectiveMode(config, now)).slice(0, 5),
      commitments: readCommitments(home(), now, 3).map((c) => c.statement),
    })
    saveGeminiSchedule({ ...s, fired: true, firedAt: now })
    saveGemini({ ...loadGemini(), session: { topic, mode, minutes, startedAt: now, until: now + minutes * 60_000, by: s.by || 'schedule' } })
    record({ state: 'gemini-schedule-fired', at: now, topic, minutes, mode })
    return { topic, minutes, mode, brief }
  }
  /**
   * R4-6 走神强拉：忙时他在娱乐 App 里坐着，就开一场审讯式连麦。
   * 判据在 gemini.js 的 driftPull（白天 / 忙时 / 坐够分钟数 / 深夜吃饭放风不拉 / 一天两次）。
   * 拉起来的办法不另造一条通知：写进 R4-4 的预约文件，手机到点自己调 /gemini/due 取简报、
   * 复制进剪贴板、打开 Gemini。`at` 放半秒后是为了让这次 /plan 就把它当"下一场"下发。
   */
  function driftPullNow(now, phone, tz) {
    const sch = loadGeminiSchedule()
    if (!sch.fired && Number(sch.at) > now) return null
    const r = driftPull({
      phone, now, day: startOfDay(now, tz), gear: effectiveGear(config, now), busyWhat: config.gearWhat,
      night: lastRhythm?.night, meal: mealNow(wallConfig(config).meals, localMinute(phone, zoneMinuteOfDay(now, tz))),
      onBreak: onBreak(now), session: geminiSessionView(now),
      pulls: loadGemini().pulls, dwellMin: config.driftMin,
    })
    if (!r) return null
    const at = now + 500
    saveGeminiSchedule({ at, topic: r.topic, minutes: r.minutes, mode: r.mode, by: 'drift' })
    const g = loadGemini()
    saveGemini({ ...g, pulls: [...(Array.isArray(g.pulls) ? g.pulls : []), { at: now, app: r.app, minutes: r.dwell }].slice(-30) })
    record({ state: 'gemini-drift', at: now, app: r.app, minutes: r.dwell })
    return r
  }
  const loadGearState = () => readState(stateFile('gear.json'), {}) || {}
  const saveGearState = (g) => writeJson(stateFile('gear.json'), g)
  // 第二轮·每日文件：今天的事 + 完成物 + 晚上对账的结论（渲染成 大肥鱼/每日/日期.md）。
  const loadDailyState = () => readState(stateFile('daily.json'), {}) || {}
  const saveDailyState = (s) => writeJson(stateFile('daily.json'), s)
  // 第五轮·每日四部门提交：信息收集 / 谋划思考 / 行为管理 / 统筹规划，一天一份，零点复归。
  const loadDepositState = () => readState(stateFile('deposit.json'), {}) || {}
  const saveDepositState = (s) => writeJson(stateFile('deposit.json'), s)
  /** 今天这份提交。没交过就是空的四项。 */
  const depositToday = (now) => depositOf(loadDepositState(), dayString(now, zoneOr(config.timeZone)))
  const geminiPackages = () => (Array.isArray(config.geminiPackages) && config.geminiPackages.length ? config.geminiPackages.map(String) : GEMINI_PACKAGES)
  /** 正在进行的 Gemini 互动舱：手机据此只许 Gemini（急事底线 App 除外）。 */
  const geminiSessionView = (now = Date.now()) => {
    const s = loadGemini().session
    if (!s || !(Number(s.until) > now)) return null
    return {
      app: geminiPackages()[0],
      allow: geminiPackages(),
      until: Number(s.until),
      // R7 P0-1：舱内第三方 App 的宽限。手机侧不再"非白名单一律锁"，按这个秒数先放行再升锁。
      graceMs: Math.max(0, Number(config.sessionGraceMs) || 45_000),
      topic: String(s.topic || ''),
      mode: String(s.mode || 'think'),
      by: s.by || undefined,
    }
  }
  function setGear(sw, by) {
    config = applyGear(config, sw, Date.now())
    try {
      writeConfig(config)
    } catch {
      // 这一轮照样生效
    }
    record({ state: 'gear', at: Date.now(), gear: sw.gear, until: sw.until || undefined, what: sw.what || undefined, by })
  }
  /** 这一球真的要发了：把"报到过 / 迎接过 / 叫回去过 / 读回过"记下，免得下一问又来一遍。 */
  function markBall(why, now) {
    if (!why) return
    if (why.reason === 'checkin') saveGearState({ ...loadGearState(), lastCheckAt: now })
    if (why.reason === 'busy-done' || why.reason === 'task-due') saveGearState({ ...loadGearState(), doneFor: Number(config.gearUntil) })
    if (why.reason === 'defied') pendingDefiance = null
    if (why.reason === 'restless') saveGearState({ ...loadGearState(), restlessAt: now })
    // 任务 / 忙时结束：拦下的通知一次性交代。
    if (why.digest === true && why.digestText === undefined) why.digestText = takeDigest()
    if (why.reason === 'morning-gate') saveRhythm({ ...loadRhythm(), morningBall: true })
    if (why.reason === 'review') saveRhythm({ ...loadRhythm(), reviewBall: true })
    if (why.reason === 'night') saveRhythm({ ...loadRhythm(), nightBallAt: now })
    if (why.reason === 'notes-chase' && why.note?.path) {
      const gs = loadGearState()
      saveGearState({ ...gs, chaseAt: now, chased: [...(Array.isArray(gs.chased) ? gs.chased : []), { path: why.note.path, at: now }].slice(-30) })
    }
    if (why.reason === 'patrol') saveGearState({ ...loadGearState(), patrolAt: now, ...(why.note?.slot ? { patrolSlot: why.note.slot } : {}) })
    if (why.mark) saveGemini({ ...loadGemini(), ...why.mark })
  }
  /** 两姐妹共用的信用分（从账本算，见 trust.js）。 */
  const trustWeights = () => ({
    breakglass: -(Number(config.breakglassPenalty) || 15),
    'followup-timeout': -(Number(config.followupPenalty) || 5),
  })
  const trustNow = (now = Date.now()) => trustScore(readLedger(home(), 800), now, { weights: trustWeights() })
  /** 放风中：娱乐临时解锁（盖过拒绝模式和任务模式），到点自动恢复。 */
  const onBreak = (now) => Number(config.breakUntil) > now
  /** 拒绝到什么时候：拒绝模式（含忙时）和正在进行的 Gemini 连麦取更晚的。 */
  function refuseUntilAt(now) {
    const g = loadGemini()
    const session = g.session && Number(g.session.until) > now ? Number(g.session.until) : 0
    const r = Number(config.refuseUntil) > now ? Number(config.refuseUntil) : 0
    // 任务到点还没验收：继续锁（每次往后顺 10 分钟），直到交墙验收通过（submit_to_wall review）或者兜底两小时。
    const pending = aiTask(config, now) ? Math.max(Number(config.gearUntil) || 0, now + 10 * 60_000) : 0
    if (onBreak(now)) return 0
    // 作息：早上没说今天的 3 件事、晚上没对完账——滚动锁；深夜锁到早上 7 点。
    const rh = lastRhythm
    const gate = rh && (rh.morningGate || rh.reviewGate) ? now + ROLL_MS : 0
    const night = rh && rh.night === 'lock' && Number(rh.nightUntil) > now ? Number(rh.nightUntil) : 0
    return Math.max(r, session, pending, gate, night)
  }
  /**
   * 本地规则快照（P0 C2）：现在生效的每一条锁，各带自己的到期时间，一起下发给手机。
   * 手机把它存在本地；引擎死了就只按这份执行，到点按手机本地时间解除，绝不自己加新规则。
   * 和 refuseUntilAt 是同一套规则，只是不合成一个数：早晚两道门这里给的是门本身的封顶时间
   * （而不是 3 分钟滚动），这样引擎挂了门也按时关、不会锁死。
   * kind 的先后由手机端 LockPriority 仲裁（晚间对账 > 任务 > 早上 > 深夜 > 拒绝）。
   */
  /** 晚上对账通过：她们立的任务（任务模式、她们开的拒绝模式）记一笔"对账时清算"，一起解锁。 */
  function settleTasksAtReview(now) {
    const task = aiTask(config, now)
    // 第三轮：他点「开始」的冲刺也算，验收/对账过了就一起收。
    const sprint = Number(config.sprintUntil) > 0
    const aiRefuse = config.refuseBy === 'ai' && Number(config.refuseUntil) > now
    if (!task && !sprint && !aiRefuse) return false
    const goal = config.taskGoal
    if (task || sprint) {
      setGear({ gear: 'free' }, 'review')
      saveGearState({ ...loadGearState(), doneFor: Number(config.gearUntil) })
    }
    if (config.refuseBy === 'ai' || (config.refuseBy === 'gear' && (task || sprint))) {
      config = { ...config, refuseUntil: 0, refuseBy: undefined }
      try {
        writeConfig(config)
      } catch {
        // 这一轮照样生效
      }
    }
    record({ state: 'task-settled', at: now, by: 'review', goal })
    return true
  }
  // ---- 第三轮：追问锁 / 裁决 / 全天状态 ---------------------------------------------------------
  //
  // 门不再按字数关：节奏只负责"该不该开门"，关不关由姐妹的裁决（满意）或超时决定。
  // 墙按状态机执行；过关由墙按证据判（submit_to_wall，见 wall_auditor.js）。

  /** 改配置并落盘；落盘失败也照样在内存里生效（这一轮不能因为写失败就反悔）。 */
  function commit(patch) {
    config = { ...config, ...patch }
    try {
      writeConfig(config)
    } catch {
      // 这一轮照样生效
    }
    return config
  }

  /** 开追问锁。同一时刻只有一把；同原因重复开不续命（30 分钟上限绕不过去）。 */
  function openFollowup(reason, question, now = Date.now()) {
    const lock = openLock(config.followup, {
      reason,
      question,
      now,
      capMs: Math.max(60_000, Math.round(Number(config.followupCapMin) || 30) * 60_000),
    })
    commit({ followup: lock })
    record({ state: 'followup-open', at: now, reason: lock.reason, question: lock.question, until: lock.until })
    return lock
  }

  /**
   * 追问锁到点还没裁决：结束拦截、记账扣分、把没过关的事挂成义务（义务只由用户的动作触发再问）。
   * 手机报 followup-timeout 和每次 /plan 都会兜一次，谁先到都算；第二次是空的。
   */
  function expireFollowup(now) {
    const lock = config.followup
    if (!lockExpired(lock, now)) return false
    const ob = obligationOf(lock.reason, now)
    commit({
      followup: closeLock(lock, { now, by: 'timeout' }),
      obligations: addObligation(config.obligations, ob),
      // 晚间对账没过：今晚的申请窗口直接作废（任务书那张表）。
      ...(ob.kind === 'evening' ? { applyWindow: { ...(config.applyWindow || {}), used: true, closeAt: now } } : {}),
    })
    record({ state: 'followup-timeout', at: now, reason: lock.reason, question: lock.question, obligation: ob.kind })
    return true
  }

  /** 这一刻的全天状态（C2）：主状态只有一项，追问锁是叠在上面的临时层。 */
  function stateView(now, tz, phone) {
    return resolveState({
      now,
      tz,
      min: localMinute(phone, zoneMinuteOfDay(now, tz)),
      config,
      rhythm: lastRhythm || {},
      followup: followupView(config.followup, now),
      session: geminiSessionView(now),
    })
  }

  /**
   * 一次裁决落地（C3）。满意才收；不满意只记账、锁继续挂着，到点按超时处理。
   * 记账形状对齐 DeepSeek 的 D3：{state:'verdict', kind, satisfied, summary, nextStep, tasks, longTerm}。
   */
  function applyVerdict(v, now, by) {
    record({
      state: 'verdict', at: now, by, kind: v.kind, satisfied: v.satisfied,
      summary: v.summary || undefined, nextStep: v.nextStep || undefined,
      tasks: v.tasks, longTerm: v.longTerm,
    })
    // 过关了这条义务就消掉；没过得留着（超时会重新挂）。
    if (v.satisfied) commit({ obligations: clearObligation(config.obligations, v.kind) })
    if (!v.satisfied) return { ok: true, text: '记下了：这轮不算过。锁还在，说清它什么时候解决。' }
    const patch = { followup: closeLock(config.followup, { now, by: 'verdict' }) }
    if (v.kind === 'morning' && v.tasks) {
      // 晨间立项的任务进当日文件（晚上对账按完成物逐项找证据）。
      const tz = zoneOr(config.timeZone)
      const state = loadDaily(loadDailyState(), now, tz)
      const next = { ...state, tasks: v.tasks, review: state.review }
      const wrote = writeDaily(kbDir(notesDir(), home()), next)
      if (wrote.ok) patch.daily = { day: state.day, tasks: v.tasks.length, at: now }
      saveDailyState(next)
    }
    if (v.kind === 'review' || v.kind === 'morning') {
      // 验收/立项过了：放风（复用第二轮那段 breakUntil 语义，放风期间不下发任何锁）。
      const mins = Math.max(5, Math.round(Number(config.breakMin) || 20))
      Object.assign(patch, { breakUntil: now + mins * 60_000, breakSince: now, breakChecked: false })
      settleTasksAtReview(now)
    }
    if (v.kind === 'evening') {
      // 晚间对账过了：按当日文件对账，产出明天的放风额度；任务和冲刺一并清算（C5：对完账一起解除）。
      settleTasksAtReview(now)
      if (Number(config.sprintUntil) > 0) Object.assign(patch, { sprintUntil: 0 })
      const tz = zoneOr(config.timeZone)
      const state = loadDaily(loadDailyState(), now, tz)
      if (state.tasks.length) {
        const out = reconcile(state, {
          now, tz, notesDir: notesDir(), ledger: readLedger(home(), 800),
          usage: Array.isArray(lastPhone?.today) ? lastPhone.today : [],
          check: wallCheck(now),
        })
        const next = { ...state, review: { at: now, ...out } }
        writeDaily(kbDir(notesDir(), home()), next)
        saveDailyState(next)
        Object.assign(patch, {
          daily: { day: state.day, done: out.done, partial: out.partial, miss: out.miss, total: out.total, at: now },
          dailyQuota: { for: startOfDay(now, tz) + 86_400_000, delta: out.quotaDelta, done: out.done, total: out.total, at: now },
        })
      }
    }
    if (v.kind === 'review' || v.kind === 'morning') {
      if (Number(config.sprintUntil) > 0) Object.assign(patch, { sprintUntil: 0 })
    }
    commit(patch)
    // 门由裁决关：writeConfig 之后写节奏状态（closeGate 回的是 {state, closed}）。
    const rh = lastRhythm
    if (rh?.morningGate && v.kind === 'morning') saveRhythm(closeGate(rh.state, 'morning').state)
    if (rh?.reviewGate && (v.kind === 'review' || v.kind === 'evening')) saveRhythm(closeGate(rh.state, 'review').state)
    return { ok: true, text: '过了。' + (patch.breakUntil ? '放风到 ' + new Date(patch.breakUntil).toTimeString().slice(0, 5) + '，到点回来。' : '') }
  }

  function rulesSnapshot(now, tz = zoneOr(config.timeZone)) {
    const rules = []
    const block = {}
    if (onBreak(now)) return { v: 1, at: now, rules, block }
    const push = (kind, until, why) => {
      const u = Math.round(Number(until) || 0)
      if (u > now) rules.push({ kind, until: u, why })
    }
    // 墙：默认严格的时间表锁（白天非吃饭时段 default，夜里 night）。深夜锁这里不重复推（下面 rhythm 的 night 管），
    // 只推白天的默认锁；手机端 LockPriority 里 default 排在普通之上、拒绝之下。
    const wd = wallDecision(config, now, tz)
    if (wd.locked && wd.kind === WALL_KIND_DEFAULT) push(WALL_KIND_DEFAULT, wd.until, wd.why)
    // 追问锁：门开着就不放——回复敷衍时窗口不消失，到点由插件自己结束。
    if (lockActive(config.followup, now)) push('followup', Number(config.followup.until), '追问锁：把这一个问题说清楚')
    const rh = lastRhythm
    const st = rh?.state || {}
    if (rh?.reviewGate) push('review', Number(st.reviewSince) + REVIEW_CAP_MS, '晚上对账：和鲸澜对完今天的账才解锁')
    // 冲刺（用户点「开始」）和她们立的任务都按 task 下发：到点就是结束。
    const sprinting = Number(config.sprintUntil) > now
    const task = aiTask(config, now) || sprinting
    if (task) {
      const goal = config.taskGoal ? '（' + String(config.taskGoal).slice(0, 40) + '）' : config.gearWhat ? '（' + String(config.gearWhat).slice(0, 40) + '）' : ''
      // 没到点：锁到任务结束；到点还没验收：往后顺 10 分钟（引擎在就一直顺，引擎死了 10 分钟后自己解）。
      push('task', Number(config.gearUntil) > now ? Number(config.gearUntil) : now + 10 * 60_000, '任务模式' + goal + '：验收合格才解锁')
    }
    if (rh?.morningGate) push('morning', Number(st.morningSince) + MORNING_CAP_MS, '早上对线：先说出今天最重要的 3 件事')
    if (rh?.night === 'lock') push('night', rh.nightUntil, '深夜锁：娱乐锁到早上 7 点')
    if (Number(config.refuseUntil) > now) {
      const by = config.refuseBy
      if (by === 'ai' || (by === 'gear' && task)) push('task', config.refuseUntil, '她们立的任务：验收合格才解锁')
      else push('refuse', config.refuseUntil, by === 'gear' ? '忙时：到点自动解锁' : '拒绝模式：到点自动解锁')
    }
    for (const b of Array.isArray(config.blockExtra) ? config.blockExtra : []) {
      if (b && typeof b.app === 'string' && Number(b.until) > now && !NEVER_BLOCK.includes(b.app)) block[b.app] = Math.round(Number(b.until))
    }
    return { v: 1, at: now, rules, block }
  }

  // ---- 第二轮·墙：放松申请 / 收紧 / 本周额度 ---------------------------------------------------
  //
  // 底线 1：任何对话都不能当场放松规则。放松只能在对账后的申请窗口里提，而且只管明天一天。
  // 收紧任何时候都能提，立即生效、当天有效。两者都记进账本；额度按自然周算。

  /** 本周放松是否还有额度（按目标日所在的自然周；含昨晚对账挣到 / 扣掉的）。 */
  function relaxQuotaLeft(targetDay, now, tz) {
    const week = weekKey(targetDay, tz)
    const used = config.relaxWeek && Number(config.relaxWeek.week) === week ? Math.max(0, Math.round(Number(config.relaxWeek.used) || 0)) : 0
    // 对账的额度增减只作用于被裁定的那一天，所以按目标日算，而不是按 now。
    const bonus = relaxBonus(config, targetDay, tz)
    const left = Math.max(0, wallConfig(config).weeklyQuota + bonus - used)
    return { left, week, used }
  }

  /**
   * 申请放松：只在申请窗口开着时能提，只能给明天开一段（默认 wall.relaxSegmentMin 分钟），每周额度内。
   * 这是替代第一轮 grant_break 的唯一放松通道——聊天里没有别的按钮能解锁（底线 1）。
   */
  function requestRelax(now, tz, { atHour, minutes, reason, by } = {}) {
    if (!applyWindowOpen(config.applyWindow, now)) {
      return { ok: false, code: 'no-window', text: '现在不是申请窗口。放松只能在今晚对账后、窗口开着的 15 分钟里提，而且只管明天。' }
    }
    if (config.applyWindow && config.applyWindow.used) {
      return { ok: false, code: 'window-used', text: '这个窗口已经提过一次申请了，明天再说。' }
    }
    // 第五轮·硬约束：四部门当天没交齐就没有放风额度。这里是唯一的批放松口子，
    // 所以挡在这里，而不是只把界面上的数字改成 0（那只是好看）。
    if (!completeOf(depositToday(now))) {
      return { ok: false, code: 'deposit', text: '今天四段部门汇报还没交齐（信息收集、谋划思考、行为管理、统筹规划），交齐之前没有放风额度。' }
    }
    if (funBlockedBy(config.obligations)) {
      return { ok: false, code: 'obligation', text: '今天还压着一件没补完的立项：先把完成物拿出来，再说放松。' }
    }
    const targetDay = dayKey(now, tz) + 86_400_000 // 明天
    const q = relaxQuotaLeft(targetDay, now, tz)
    if (q.left <= 0) {
      return { ok: false, code: 'quota', text: '这周的放松额度用完了（每周最多 ' + wallConfig(config).weeklyQuota + ' 天）。窗口还能用来收紧，但不能再放松。' }
    }
    const text = String(reason || '').trim().slice(0, 200)
    if (!text) return { ok: false, code: 'no-reason', text: '放松要写清理由（为什么明天想多玩一段）。' }
    const w = wallConfig(config)
    const span = Math.max(15, Math.min(240, Math.round(Number(minutes) || w.relaxSegmentMin)))
    const startHour = Math.max(0, Math.min(23, Math.round(Number(atHour))))
    const from = Number.isFinite(startHour) ? startHour * 60 : 20 * 60 // 默认晚上 20:00 那一段
    const to = Math.min(1440, from + span)
    config = {
      ...config,
      relax: { for: targetDay, segments: [{ from, to }], reason: text },
      relaxWeek: { week: q.week, used: q.used + 1 },
      applyWindow: { ...(config.applyWindow || {}), used: true },
    }
    try {
      writeConfig(config)
    } catch {
      // 这一轮照样生效
    }
    const hm = (m) => String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0')
    record({ state: 'relax-grant', at: now, for: targetDay, from, to, minutes: span, reason: text, by: String(by || 'ai').slice(0, 20) })
    return { ok: true, text: '批了：明天 ' + hm(from) + '–' + hm(to) + ' 开放娱乐一段。本周还剩 ' + (q.left - 1) + ' 天额度。今天的规则照旧。' }
  }

  /** 收紧：任何时候可提，立即生效、当天有效，次日复归。 */
  function tightenNow(now, tz, { reason, by } = {}) {
    const today = dayKey(now, tz)
    config = { ...config, tighten: { for: today, at: now }, relax: config.relax && Number(config.relax.for) === today ? null : config.relax }
    try {
      writeConfig(config)
    } catch {
      // 这一轮照样生效
    }
    record({ state: 'tighten', at: now, for: today, reason: String(reason || '').slice(0, 200), by: String(by || 'ai').slice(0, 20) })
    return { ok: true, text: '收紧了：今天娱乐锁到零点，明天恢复默认。' }
  }
  const toolOutput = {
    schema: { type: 'object', additionalProperties: true, properties: { ok: { type: 'boolean' }, text: { type: 'string' } } },
    render: (_a, v) => [{ type: 'text', text: String((v && v.text) || '') }],
  }
  /**
   * 管他（第二轮：只留收紧类）。在聊天里只能收紧，不能放松——放松只走申请窗口（底线 1）。
   *   busy / refuse / mute / strict 保留；free、refuse_off、gentle 已撤（那是聊天里切回宽松状态的口子）。
   */
  function controlTool() {
    return {
      name: 'dafeiyu_control',
      description: '切换对他的管法（只能收紧，不能放松）：busy=忙时（不闲聊、每 25 分钟报到、娱乐 App 一开就锁；时长默认 40 分钟、最长 60，到点只让他报到，不评分不扣分）；' +
        'refuse=拒绝模式 minutes 分钟（娱乐 App 一开就锁）；mute=安静 minutes 分钟；strict=严格。他亲口要求或明确同意时才用。' +
        '放松（回到闲时、关拒绝、切回温和）这里不做：他想放松只能等对账后的申请窗口。',
      parameters: {
        type: 'object',
        properties: {
          action: { type: 'string', enum: ['busy', 'refuse', 'mute', 'strict'] },
          minutes: { type: 'integer', minimum: 5, maximum: 480, description: 'busy / refuse / mute 的时长（分钟）' },
          what: { type: 'string', description: 'busy：他在忙什么（学习、做饭、写报告…）' },
        },
        required: ['action'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const mins = Math.max(5, Math.min(480, Math.round(Number(args.minutes) || 0)))
        const hm = (at) => new Date(at).toISOString().slice(11, 16) + ' UTC'
        let text = ''
        if (args.action === 'busy') {
          // 进入忙时必须有时长：默认 40 分钟，最长 60 分钟（10 分钟起步）。
          const busyMinutes = Math.max(10, Math.min(BUSY_MAX_MIN, Math.round(Number(args.minutes) || BUSY_DEFAULT_MIN)))
          const until = now + busyMinutes * 60_000
          setGear({ gear: 'busy', until, what: String(args.what || '正事').slice(0, 20) }, 'tool')
          text = '已切到忙时（' + (args.what || '正事') + '）' + busyMinutes + ' 分钟，到 ' + hm(until) + '：不闲聊、定时报到、娱乐 App 一开就锁。'
        } else if (args.action === 'refuse') {
          const until = now + (Number(args.minutes) ? mins : 120) * 60_000
          config = { ...config, refuseUntil: until, refuseBy: 'tool' }
          text = '拒绝模式开到 ' + hm(until) + '。'
        } else if (args.action === 'mute') {
          const until = now + (Number(args.minutes) ? mins : 60) * 60_000
          config = { ...config, muteUntil: until }
          text = '安静到 ' + hm(until) + '。'
        } else if (args.action === 'strict') {
          config = { ...config, mode: 'coach', modeUntil: 0 }
          text = '切到严格了。'
        } else {
          return { ok: false, text: '不认识的动作（这里只能收紧）。' }
        }
        if (args.action !== 'busy' && args.action !== 'free') {
          try {
            writeConfig(config)
          } catch {
            // 这一轮照样生效
          }
          record({ state: 'control', at: now, action: args.action, minutes: args.minutes || undefined })
        }
        return { ok: true, text }
      },
    }
  }
  /**
   * 立任务（第二轮：只在用户主动要求时）。不再"不经同意就立"——那等于聊天里当场加锁，违反底线。
   * 娱乐照样按规则锁，任务模式只在他说"帮我立个任务/盯我 45 分钟"时开。
   */
  function startTaskTool(role) {
    return {
      name: 'start_task_mode',
      description: '给他立一个任务（**只在他主动要求时**：他说"给我立个任务""盯我 45 分钟""这一个小时别让我玩"）。' +
        'mode=task 任务模式（有目标，定时报到，娱乐按规则锁，验收合格才结束）；mode=refuse 只锁娱乐不立目标。' +
        '他没要求时不要调用——规矩由墙执行，你只在他点名要的时候帮他立。立完告诉他做了什么、为什么、第一步是什么。',
      parameters: {
        type: 'object',
        properties: {
          mode: { type: 'string', enum: ['task', 'refuse'] },
          minutes: { type: 'integer', minimum: 10, maximum: 180, description: '时长，默认 45' },
          goal: { type: 'string', description: 'task：具体做什么、做到什么程度（例：周报写完第一部分 500 字）' },
          reason: { type: 'string', description: '为什么现在立（看到了什么）' },
        },
        required: ['mode', 'reason'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const minutes = Math.max(10, Math.min(180, Math.round(Number(args.minutes) || 45)))
        const until = now + minutes * 60_000
        const reason = String(args.reason || '').trim().slice(0, 160)
        const hm = new Date(until).toISOString().slice(11, 16) + ' UTC'
        if (args.mode === 'refuse') {
          config = { ...config, refuseUntil: Math.max(until, Number(config.refuseUntil) || 0), refuseBy: 'ai' }
          try {
            writeConfig(config)
          } catch {
            // 这一轮照样生效
          }
          record({ state: 'task-start', at: now, mode: 'refuse', minutes, by: role, reason })
          return { ok: true, text: '拒绝模式开到 ' + hm + '，娱乐 App 一开就锁。' }
        }
        const goal = String(args.goal || '').trim().slice(0, 120)
        if (!goal) return { ok: false, text: '任务要有具体目标（做什么、做到什么程度）。' }
        setGear({ gear: 'busy', until, what: goal.slice(0, 20), task: { goal, by: role, reason } }, 'ai-' + role)
        record({ state: 'task-start', at: now, mode: 'task', minutes, by: role, goal, reason })
        wallCloseOnStart(now)
        return { ok: true, text: '任务模式立好了：' + goal + '，到 ' + hm + '。娱乐全锁，到点交墙验收（submit_to_wall kind=review）。' }
      },
    }
  }
  /** 知识库：判断.md（她的观察和证据），我.md 只能在不存在时起草。 */
  function kbTool(role) {
    return {
      name: 'kb_write',
      description: '写笔记库 大肥鱼/ 下的文件：file=judge 整份更新 判断.md（你对他的判断和证据，保留他改过的，每次最多改 5 条）；' +
        'file=self 起草 我.md（他的目标、硬安排、红线、希望你们怎么对他）——只在他还没写时能起草，起草完跟他说让他改。',
      parameters: {
        type: 'object',
        properties: {
          file: { type: 'string', enum: ['judge', 'self'] },
          markdown: { type: 'string', description: '整份文件的 Markdown 内容' },
        },
        required: ['file', 'markdown'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const r = kbWrite(kbDir(notesDir(), home()), args.file, args.markdown, Date.now())
        if (r.ok) record({ state: 'kb-write', at: Date.now(), by: role, file: args.file })
        return { ok: r.ok, text: r.text }
      },
    }
  }
  // ---------------------------------------------------------------- 第四轮 R4-1：墙的审查
  //
  // 墙只看物理证据，判定全在 wall_auditor.js 的代码里，不读任何人的说法。姐妹是他这边的人：
  // 帮他把任务和证据整理好，用 submit_to_wall 交上来；过不过由这里决定。
  const loadWallMem = () => loadWallMemory(readState(stateFile('wall_memory.json'), {}))
  const saveWallMem = (m) => writeJson(stateFile('wall_memory.json'), m)
  const loadBaseline = () => readState(stateFile('wall-baseline.json'), {}) || {}
  const saveBaseline = (b) => writeJson(stateFile('wall-baseline.json'), b)
  const WALL_KIND_LABEL = { morning: '晨间立项', review: '验收', evening: '晚间对账', week: '周度审查', standby: '待命追问', defiance: '违抗追问', gemini: '连麦回流' }

  function wallRate(now) {
    return recentRate(loadWallMem(), dayString(now, zoneOr(config.timeZone)))
  }
  /** 审查要用的现场：立项时间、各文件立项时的大小、立项后的截图、今天的使用记录。 */
  function wallCtx(now) {
    const tz = zoneOr(config.timeZone)
    const day = dayString(now, tz)
    const base = loadBaseline()
    const today = base.day === day
    const since = today && Number(base.at) > 0 ? Number(base.at) : startOfDay(now, tz)
    return {
      now, since, notesDir: notesDir(), stat: statOf,
      screenshots: listScreenshots(SCREENSHOT_DIRS, since),
      usage: Array.isArray(lastPhone?.today) ? lastPhone.today : [],
      files: today && base.files && typeof base.files === 'object' ? base.files : {},
    }
  }
  function auditOne(task, ctx) {
    return auditTask(task, { ...ctx, baseline: ctx.files[task.task] })
  }
  /** 晚间对账也用墙的口径（原来的关键词匹配能被"在笔记里写上那几个词"糊弄过去）。 */
  function wallCheck(now) {
    const ctx = wallCtx(now)
    return (task) => {
      const r = auditOne(task, ctx)
      return { verdict: r.verdict, why: r.data + (r.missing ? '；还缺：' + r.missing : '') }
    }
  }

  // ---------------------------------------------------------------- 第六轮 B：三个审查锚点
  //
  // 早 / 晚 / 周末各一次，全部由墙主持。审不审由时刻决定，不看他说了什么；
  // 他没来就不审内容，只记一条缺席——"没发生"本身必须留痕，否则静默失效查不出来。
  /** 这一周的客观数字：墙审了几次、过了几次、缺席几回。只读账本。 */
  function weekSummary(now) {
    const since = startOfDay(now, zoneOr(config.timeZone)) - 6 * 86_400_000
    const rows = readLedger(home(), 800).filter((e) => e?.state === 'wall-audit' && Number(e.at) >= since)
    const line = (kind, name) => {
      const g = rows.filter((r) => r.kind === kind)
      if (!g.length) return name + '：0 次'
      const pass = g.filter((r) => r.pass).length
      const absent = g.filter((r) => r.absent).length
      return name + '：' + g.length + ' 次（过 ' + pass + (absent ? '，缺席 ' + absent : '') + '）'
    }
    return [line('morning', '早上立标准'), line('evening', '晚上撞证据'), line('week', '周末看一周')]
  }
  /**
   * 到点还没有结论，就由墙按账本记一条缺席。
   * 只 record()，绝不 applyVerdict——不加锁、不罚、不扣额度。
   */
  function anchorSweep(now, tzIn) {
    const tz = zoneOr(tzIn || config.timeZone)
    const day = startOfDay(now, tz)
    const seen = readLedger(home(), 400).filter((e) => e?.state === 'wall-audit' && Number(e.at) >= day).map((e) => e.kind)
    for (const a of absentAnchors(zoneMinuteOfDay(now, tz), weekdayOf(now, tz), seen)) {
      record({ state: 'wall-audit', at: now, kind: a.kind, pass: false, absent: true, by: 'wall', missing: [a.why] })
      try {
        vaultAudit(now, '〔墙〕' + WALL_KIND_LABEL[a.kind] + '：缺席——' + a.why)
      } catch {
        // 没有笔记库就不写盘外的副本，账本那条已经落了
      }
    }
  }
  /** 今天三次审查各自的最后一条结论（面板据此看出哪一次漏了）。 */
  function anchorStatus(now, entries) {
    const day = startOfDay(now, zoneOr(config.timeZone))
    const last = (kind) => {
      let hit = null
      for (const e of entries) {
        if (e?.state !== 'wall-audit' || e.kind !== kind || Number(e.at) < day) continue
        if (!hit || Number(e.at) >= Number(hit.at)) hit = e
      }
      return hit ? { at: Number(hit.at), pass: Boolean(hit.pass), absent: Boolean(hit.absent) } : null
    }
    return { morning: last('morning'), evening: last('evening'), week: last('week') }
  }

  // ---------------------------------------------------------------- 预判闭环（B 层 T1）
  /** 今天任务完成的比例。没有任务就是 null——不拿 0 顶，免得把"没定事"判成"没做"。 */
  function tasksRatio(now) {
    const tz = zoneOr(config.timeZone)
    const state = loadDaily(loadDailyState(), now, tz)
    if (!state.tasks?.length) return null
    const out = reconcile(state, { now, tz, notesDir: notesDir(), stat: statOf, check: wallCheck(now) })
    return out.total ? out.done / out.total : null
  }
  /** 结算要用的实测值。量不到就留空，settle 会记 unknown，不冤枉他。 */
  function predictReadings(now, cur) {
    const ctx = wallCtx(now)
    const fileSize = {}
    for (const r of cur.list || []) {
      if (r.verdict) continue
      for (const m of r.metrics || []) {
        if (m.metric !== 'file_growth' || !m.path) continue
        const st = statOf(m.path)
        if (st) fileSize[m.path] = Number(st.size) || 0
      }
    }
    const appMinutes = {}
    for (const u of ctx.usage || []) {
      const label = String(u?.label || u?.app || '')
      if (label) appMinutes[label] = Math.round(Number(u.minutes) || 0)
    }
    return {
      fileSize,
      screenshots: (ctx.screenshots || []).length,
      appMinutes,
      funMinutes: funToday(lastPhone),
      tasksRatio: tasksRatio(now),
    }
  }
  /**
   * 把到期的预判交给墙的遥测撞一遍。verdict / verdictSource 只有 predict.js 的 settle 能写，
   * 模型写不进这个字段——这就是 §13.3 那条后门的堵法。
   */
  function settlePredictions(now) {
    const cur = loadPredict()
    if (!dueCount(cur, now)) return cur
    const out = settle(cur, predictReadings(now, cur), now)
    if (out.settled.length) {
      savePredict(out.ledger)
      record({ state: 'predict', at: now, settled: out.settled.map((r) => ({ id: r.id, verdict: r.verdict })) })
      // 押错的那条写进墙的审计，他自己的账上留痕。
      for (const r of out.settled) {
        if (r.verdict !== 'miss') continue
        vaultAudit(now, '预判没说中：' + r.claim + '（判据 ' + r.verdictSource + '，实测 ' +
          (r.readings || []).map((x) => x.metric + '=' + (x.value === null ? '量不到' : x.value)).join('、') + '）')
      }
    }
    return out.ledger
  }
  function sprintOrTask(now) {
    return Number(config.sprintUntil) > now || Boolean(aiTask(config, now))
  }
  /** 他点「开始」或立了任务：挂着的待命 / 违抗追问由墙当场关掉——动手了就是交代。 */
  function wallCloseOnStart(now) {
    const l = config.followup
    if (!lockActive(l, now) || !['standby', 'defiance'].includes(l.reason)) return null
    record({ state: 'wall-audit', at: now, kind: l.reason, pass: true, by: 'start' })
    return applyVerdict({ ok: true, kind: l.reason, satisfied: true, summary: '开始干活了', nextStep: String(config.taskGoal || '冲刺') }, now, 'wall')
  }

  // ---------------------------------------------------------------- 第四轮 R4-3：墙在 Obsidian 里的那一半
  const localHm = (now) => {
    const m = zoneMinuteOfDay(now, zoneOr(config.timeZone))
    return String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0')
  }
  const vaultKb = () => {
    const dir = notesDir()
    return dir ? kbDir(dir, home()) : ''
  }
  /** 墙的原话写进 大肥鱼/墙/审计/日期.md（没有笔记库就不写）。 */
  function vaultAudit(now, text) {
    const kb = vaultKb()
    if (!kb) return
    try {
      appendAudit(kb, dayString(now, zoneOr(config.timeZone)), localHm(now), text)
    } catch {
      // 写不进笔记库不影响审查本身
    }
  }
  /** 大肥鱼/任务/日期-序号-名称.md：一件事一个文件，frontmatter 给 Obsidian Bases 用。 */
  function vaultTask(now, index, task, status, proof = '') {
    const kb = vaultKb()
    if (!kb) return
    const day = dayString(now, zoneOr(config.timeZone))
    try {
      writeVaultText(taskNotePath(kb, day, index, task.task), renderTaskNote({ day, task: task.task, evidence: task.evidence, status, proof }))
    } catch {
      // 同上
    }
  }
  const loadRulesPending = () => readState(stateFile('wall-rules-pending.json'), {}) || {}
  const saveRulesPending = (p) => writeJson(stateFile('wall-rules-pending.json'), p)
  let rulesSeen = { key: '', at: 0 }
  /**
   * 读 大肥鱼/墙/规则.md：改严的立即生效，改松的进待生效表，冷却期（默认 7 天）过了才生效。
   * 文件不存在就按当前规则写一份模板。文件一变就读；没变就每分钟兜底一次（待生效的到点要生效）。
   */
  function syncVaultRules(now) {
    const kb = vaultKb()
    if (!kb) return
    const base = join(kb, WALL_DIR)
    const st = statOf(join(base, RULES_FILE))
    const key = st ? st.mtimeMs + ':' + st.size : 'none'
    if (key === rulesSeen.key && now - rulesSeen.at < 60_000) return
    rulesSeen = { key, at: now }
    const cooldown = Math.max(0, Number(config.rulesCooldownDays ?? RULES_COOLDOWN_DAYS)) * 86_400_000
    const fmtDay = (at) => dayString(at, zoneOr(config.timeZone))
    const cur = wallConfig(config)
    const text = readVaultText(join(base, RULES_FILE))
    if (text === null) {
      writeVaultText(join(base, RULES_FILE), renderRules(cur))
      writeVaultText(join(base, PENDING_FILE), renderPending({}, cooldown, fmtDay))
      return
    }
    const parsed = parseRules(text)
    if (!parsed.ok) return
    const { tighten, loosen } = classifyRules(cur, parsed.wall)
    const prev = loadRulesPending()
    const { pending, due } = updatePending(prev, loosen, now, cooldown)
    const patch = { ...tighten, ...due }
    if (Object.keys(patch).length) {
      commit({ wall: { ...(config.wall || {}), ...patch } })
      record({ state: 'wall-rules', at: now, tighten: Object.keys(tighten), loosen: Object.keys(due) })
    }
    if (JSON.stringify(prev) !== JSON.stringify(pending) || text === null) {
      saveRulesPending(pending)
      writeVaultText(join(base, PENDING_FILE), renderPending(pending, cooldown, fmtDay))
    }
  }

  /**
   * 交给墙：先看现在有没有这一种可以交（挂着的锁 / 门对得上），再按证据判。
   * @returns {{ok:boolean, pass:boolean, text:string}}
   */
  function wallAudit(kind, args, now, by) {
    const label = WALL_KIND_LABEL[kind]
    if (!label) return { ok: false, pass: false, text: 'kind 只能是 ' + Object.keys(WALL_KIND_LABEL).join(' / ') + '。' }
    const l = config.followup
    const held = lockActive(l, now) ? l.reason : ''
    const rh = lastRhythm
    const open = {
      morning: held === 'morning' || Boolean(rh?.morningGate),
      evening: held === 'evening' || Boolean(rh?.reviewGate),
      week: Boolean(rh?.weekReviewGate),
      review: held === 'review' || sprintOrTask(now),
      standby: held === 'standby',
      defiance: held === 'defiance',
      gemini: held === 'gemini',
    }[kind]
    const rate = wallRate(now)
    const done = (pass, data, missing = []) => {
      record({ state: 'wall-audit', at: now, kind, pass, by })
      const text = wallText({ title: label, data, pass, missing, rate })
      vaultAudit(now, text)
      return { ok: true, pass, text }
    }
    if (!open) return done(false, ['现在没有挂着的「' + label + '」'], ['等到该交的时候再交'])

    if (kind === 'morning') {
      const c = checkMorningTasks(args.tasks)
      if (!c.ok) return done(false, [], c.problems)
      const tasks = c.tasks.map(({ task, evidence }) => ({ task, evidence }))
      const tz = zoneOr(config.timeZone)
      const day = dayString(now, tz)
      saveBaseline({ day, at: now, files: baselineFor(tasks, { notesDir: notesDir(), stat: statOf }) })
      saveWallMem(pruneMemory(notePromised(loadWallMem(), day, tasks.length), day))
      applyVerdict({ ok: true, kind, satisfied: true, summary: '立项 ' + tasks.length + ' 件', nextStep: tasks[0].task, tasks }, now, 'wall')
      tasks.forEach((t, i) => vaultTask(now, i, t, '进行中'))
      return done(true, tasks.map((t, i) => (i + 1) + '. ' + t.task + ' → ' + t.evidence))
    }

    if (kind === 'review') {
      const ctx = wallCtx(now)
      const state = loadDaily(loadDailyState(), now, zoneOr(config.timeZone))
      const pick = String(args.task ?? '').trim()
      const idx = /^\d+$/.test(pick) ? Number(pick) - 1 : state.tasks.findIndex((t) => pick && (t.task.includes(pick) || pick.includes(t.task)))
      let task = idx >= 0 ? state.tasks[idx] : state.tasks.length === 1 ? state.tasks[0] : null
      const extra = String(args.evidence ?? '').trim()
      if (!task && extra) {
        // 临时交的完成物：文件类没有立项时的底数，量不出长了多少，所以只收截图和 App 时长。
        const type = evidenceType(extra)
        if (type === 'file' || !type) return done(false, ['临时完成物「' + extra + '」'], ['临时交的只收"截图"或"某 App N 分钟"；文件类要在早上立项时登记'])
        task = { task: String(config.taskGoal || '这一轮'), evidence: extra }
      }
      if (!task) return done(false, ['今天立项的事：' + (state.tasks.map((t, i) => (i + 1) + '. ' + t.task).join('；') || '无')], ['说清交的是第几件（task），或者交截图 / App 时长（evidence）'])
      const r = auditOne(task, ctx)
      if (!r.pass) {
        applyVerdict({ ok: true, kind, satisfied: false }, now, 'wall')
        return done(false, [task.task + '：' + r.data], [r.missing])
      }
      applyVerdict({ ok: true, kind, satisfied: true, summary: task.task + '：' + r.data, nextStep: '放风，回来接着下一件' }, now, 'wall')
      if (idx >= 0 || state.tasks.length === 1) vaultTask(now, Math.max(0, idx), task, '完成', r.data)
      return done(true, [task.task + '：' + r.data])
    }

    if (kind === 'evening') {
      const tz = zoneOr(config.timeZone)
      const state = loadDaily(loadDailyState(), now, tz)
      const check = wallCheck(now)
      const rows = state.tasks.map((t) => ({ t, r: check(t) }))
      const ok = rows.filter((x) => x.r.verdict === 'done').length
      saveWallMem(pruneMemory(noteDone(loadWallMem(), state.day, ok), state.day))
      applyVerdict({ ok: true, kind, satisfied: true, summary: '对账：' + ok + '/' + rows.length, nextStep: '明天按对账结果' }, now, 'wall')
      const data = rows.length
        ? rows.map((x, i) => (i + 1) + '. ' + x.t.task + '：' + (x.r.verdict === 'done' ? '有证据' : x.r.verdict === 'partial' ? '一半' : '没证据') + '（' + x.r.why + '）')
        : ['今天没有立项的事']
      rows.forEach((x, i) => vaultTask(now, i, x.t, x.r.verdict === 'done' ? '完成' : x.r.verdict === 'partial' ? '一半' : '未完成', x.r.why))
      const text = wallText({ title: label + '：有证据完成 ' + ok + '/' + rows.length + '，结果作用于明天', data, pass: true, rate: wallRate(now) })
      record({ state: 'wall-audit', at: now, kind, pass: true, by })
      vaultAudit(now, text)
      return { ok: true, pass: true, text }
    }

    // 周末审这一周：只看账本里的客观数字，不问人，也不下发锁（§3.3）。
    if (kind === 'week') {
      const data = weekSummary(now)
      // 当周只开一次：交过就把门关上，同一晚不会重复交。
      saveRhythm(closeGate(loadRhythm(), 'week').state)
      return done(true, data)
    }

    if (kind === 'standby' || kind === 'defiance') {
      if (!sprintOrTask(now)) return done(false, ['现在没有在跑的冲刺或任务'], ['点「开始」，或者让姐妹帮他立一个任务（start_task_mode）'])
      wallCloseOnStart(now)
      return done(true, ['在做：' + String(config.taskGoal || '冲刺')])
    }

    // gemini：连麦回流只要一条能马上做的下一步。
    const next = String(args.next_step ?? args.nextStep ?? '').trim().slice(0, 200)
    if (next.length < 6) return done(false, ['下一步：' + (next || '空')], ['写一条能马上做的下一步（至少 6 个字）'])
    applyVerdict({ ok: true, kind, satisfied: true, summary: '连麦回流', nextStep: next }, now, 'wall')
    return done(true, ['下一步：' + next])
  }

  function wallTool(role) {
    return {
      name: 'submit_to_wall',
      description: '把他的东西交给墙审查。墙只看物理证据——文件立项后长了多少、立项后有没有新截图、App 今天用了多久——不读你们怎么说。' +
        'kind=morning：交今天 1–3 件事（tasks），每件的完成物写成墙查得到的样子：笔记文件路径（大肥鱼/周报.md）、"截图"、或"某 App N 分钟"。' +
        'kind=review：交验收，task 写第几件（从 1 数）或名字；临时的事可以用 evidence 交"截图"或"某 App N 分钟"。' +
        'kind=evening：交晚间对账（墙逐件核，结果作用于明天）。kind=week：周末交这一周的审查，只摆事实和下周重心两条让他拍板，不下发锁。' +
        'kind=standby / defiance：他已经点了「开始」或立了任务时交，关掉追问。' +
        'kind=gemini：交连麦得出的下一步（next_step）。墙的回复以〔墙〕开头，原样转给他，别改，也别替墙求情。',
      parameters: {
        type: 'object',
        properties: {
          kind: { type: 'string', enum: Object.keys(WALL_KIND_LABEL) },
          tasks: {
            type: 'array',
            maxItems: 3,
            items: {
              type: 'object',
              properties: {
                task: { type: 'string', description: '今天要做的事' },
                evidence: { type: 'string', description: '完成物：笔记文件路径 / "截图" / "某 App N 分钟"' },
              },
              required: ['task', 'evidence'],
              additionalProperties: false,
            },
          },
          task: { type: 'string', description: 'review：第几件（从 1 数）或任务名' },
          evidence: { type: 'string', description: 'review：临时的事交"截图"或"某 App N 分钟"' },
          next_step: { type: 'string', description: 'gemini：连麦得出的、能马上做的下一步' },
        },
        required: ['kind'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => wallAudit(String(args.kind || ''), args, Date.now(), role),
    }
  }
  /** 知识库只读检索：找证据、翻旧结论。姐妹共用（C4：两人工具一致）。 */
  function vaultSearchTool() {
    return {
      name: 'vault_search',
      description: '在笔记库里只读搜索（文件名和正文都搜），用来找完成物、翻以前定过的事。日记和私密目录不读。',
      parameters: {
        type: 'object',
        properties: {
          query: { type: 'string', description: '要找的词（一两个关键词最准）' },
          limit: { type: 'integer', minimum: 1, maximum: 20 },
        },
        required: ['query'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const hits = vaultSearch(notesDir(), args.query, { limit: Math.max(1, Math.min(20, Number(args.limit) || 8)) })
        if (!hits.length) return { ok: true, text: '笔记库里没有搜到「' + String(args.query || '').slice(0, 40) + '」。' }
        return { ok: true, text: hits.map((h) => h.rel + '：' + h.snippet).join('\n') }
      },
    }
  }
  /** 最近动过的笔记（摘要只读）：接着他昨天写到的地方说。 */
  function vaultRecentTool() {
    return {
      name: 'vault_recent_notes',
      description: '看他最近改过的笔记（只读摘要，不含日记和私密目录）。想接着昨天写到的地方说的时候用。',
      parameters: {
        type: 'object',
        properties: {
          limit: { type: 'integer', minimum: 1, maximum: 8 },
        },
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const list = vaultRecentNotes(notesDir(), { limit: Math.max(1, Math.min(8, Number(args.limit) || 3)) })
        if (!list.length) return { ok: true, text: '笔记库里最近没有改动。' }
        return { ok: true, text: list.map((n) => n.rel + '：' + n.summary).join('\n') }
      },
    }
  }
  /** 临时封禁名单：把 App 算进娱乐清单，到期自动失效。 */
  function blockTool(role) {
    return {
      name: 'block_apps',
      description: '把一些 App 临时算进娱乐清单（打开就照拒绝/任务/力度的规矩管）：比如晚上对账定下"明天封什么"，或者他最近沉迷某个不在清单里的 App。' +
        '填包名（不知道就先 mcp__phone__apps 查）。电话、短信、地图、支付永远不封。',
      parameters: {
        type: 'object',
        properties: {
          apps: { type: 'array', items: { type: 'string' }, description: '包名列表，最多 10 个' },
          hours: { type: 'integer', minimum: 1, maximum: 48, description: '封多久，默认 24 小时' },
          reason: { type: 'string' },
        },
        required: ['apps'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const until = now + Math.max(1, Math.min(48, Math.round(Number(args.hours) || 24))) * 3_600_000
        const want = (Array.isArray(args.apps) ? args.apps : []).map((a) => String(a).trim()).filter((a) => /^[A-Za-z0-9_.]{3,120}$/.test(a)).slice(0, 10)
        const skipped = want.filter((a) => NEVER_BLOCK.includes(a))
        const apps = want.filter((a) => !NEVER_BLOCK.includes(a))
        if (!apps.length) return { ok: false, text: skipped.length ? '这些永远不封：' + skipped.join('、') : '没有有效的包名。' }
        const keep = (Array.isArray(config.blockExtra) ? config.blockExtra : []).filter((b) => b && Number(b.until) > now && !apps.includes(b.app))
        config = { ...config, blockExtra: [...keep, ...apps.map((app) => ({ app, until, by: role }))].slice(-30) }
        try {
          writeConfig(config)
        } catch {
          // 这一轮照样生效
        }
        record({ state: 'block', at: now, apps, until, by: role, reason: String(args.reason || '').slice(0, 120) })
        return { ok: true, text: '封了 ' + apps.join('、') + '，到 ' + new Date(until).toISOString().slice(5, 16).replace('T', ' ') + ' UTC。' + (skipped.length ? '（' + skipped.join('、') + ' 永远不封）' : '') }
      },
    }
  }
  /**
   * 第二轮·放松申请（替代 grant_break）：只能在对账后的申请窗口里提，只给明天开一段，每周额度内。
   * 底线 1——聊天里没有别的通道能当场放松；窗口外调用直接拒。
   */
  function relaxTool(role) {
    return {
      name: 'request_relax',
      description: '替他向墙申请明天放松一段娱乐时间。只有对账后的申请窗口开着时才有用（窗口外会被拒）；只管明天一天，每周最多几天由额度定。' +
        '他清醒地想放松、给了理由时才用；atHour 是明天开始的整点（默认 20），minutes 默认 60。',
      parameters: {
        type: 'object',
        properties: {
          atHour: { type: 'integer', minimum: 0, maximum: 23, description: '明天从几点开始放松（整点）' },
          minutes: { type: 'integer', minimum: 15, maximum: 240, description: '放松多长，默认 60' },
          reason: { type: 'string', description: '他为什么想明天放松（必填）' },
        },
        required: ['reason'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const r = requestRelax(now, zoneOr(config.timeZone), { atHour: args.atHour, minutes: args.minutes, reason: args.reason, by: role })
        return { ok: r.ok, text: r.text }
      },
    }
  }
  /** 第二轮·收紧：任何时候可提，立即生效、当天有效。收紧不受窗口限制（底线只拦放松）。 */
  function tightenTool(role) {
    return {
      name: 'tighten_today',
      description: '他主动要求今天更严（或你判断该收紧）：立即把今天的娱乐锁到零点，明天恢复默认。任何时候都能用，不需要窗口。',
      parameters: {
        type: 'object',
        properties: { reason: { type: 'string', description: '为什么收紧' } },
        required: [],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const r = tightenNow(now, zoneOr(config.timeZone), { reason: args.reason, by: role })
        return { ok: r.ok, text: r.text }
      },
    }
  }
  /**
   * 第二轮·每日文件与按完成物对账（只有鲸澜有）。
   *   daily_plan  ：晨间对线时把今天的事和完成物写进 大肥鱼/每日/日期.md。
   *   daily_review：晚上对账，按完成物去知识库 / 账本 / 使用记录里找证据，结论作用于明天。
   */
  function dailyPlanTool(role) {
    return {
      name: 'daily_plan',
      description: '早上定事：把今天的事和每件事的完成物写进知识库的每日文件（大肥鱼/每日/日期.md）。' +
        '每件事必须带一个看得见的完成物（笔记路径、Gemini 记录、某个学习 App 今天要用够多少分钟），光写"做完 XX"不算。最多 6 件。',
      parameters: {
        type: 'object',
        properties: {
          tasks: {
            type: 'array',
            maxItems: 6,
            items: {
              type: 'object',
              properties: {
                task: { type: 'string', description: '今天要做的事（一句话）' },
                evidence: { type: 'string', description: '完成物：晚上能去查的东西（笔记路径 / Gemini 记录 / App 用够多少分钟）' },
              },
              required: ['task', 'evidence'],
              additionalProperties: false,
            },
          },
        },
        required: ['tasks'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const tz = zoneOr(config.timeZone)
        const norm = normalizeTasks(args.tasks)
        if (!norm.ok) return { ok: false, text: norm.text }
        const dir = kbDir(notesDir(), home())
        const state = loadDaily(loadDailyState(), now, tz)
        const next = { ...state, tasks: norm.tasks, review: state.review }
        const wrote = writeDaily(dir, next)
        saveDailyState(next)
        record({ state: 'daily-plan', at: now, by: role, count: norm.tasks.length, file: wrote.file || undefined })
        return { ok: true, text: norm.text + ' ' + wrote.text }
      },
    }
  }
  function dailyReviewTool(role) {
    return {
      name: 'daily_review',
      description: '晚上对账：按早上写下的完成物逐项去知识库、账本、使用记录里找证据——找到算完成，找不到算没做完，找到一半记一半。' +
        '不看他说了什么。结论写进每日文件和账本，只影响明天的放风额度和信用分，不当场锁人。',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      output: toolOutput,
      execute: () => {
        const now = Date.now()
        const tz = zoneOr(config.timeZone)
        const dir = kbDir(notesDir(), home())
        const state = loadDaily(loadDailyState(), now, tz)
        if (!state.tasks.length) return { ok: false, text: '今天早上没定事，没有可对的账。先跟他说清今天该做什么、各自的完成物是什么。' }
        const out = reconcile(state, {
          now, tz, notesDir: notesDir(), ledger: readLedger(home(), 800),
          usage: Array.isArray(lastPhone?.today) ? lastPhone.today : [],
          check: wallCheck(now),
        })
        const next = { ...state, review: { at: now, ...out } }
        const wrote = writeDaily(dir, next)
        saveDailyState(next)
        config = {
          ...config,
          daily: { day: state.day, done: out.done, partial: out.partial, miss: out.miss, total: out.total, at: now },
          // 作用于明天：明天那天的额度增减，过了那天自动失效。
          dailyQuota: { for: startOfDay(now, tz) + 86_400_000, delta: out.quotaDelta, done: out.done, total: out.total, at: now },
        }
        try {
          writeConfig(config)
        } catch {
          // 这一轮照样生效
        }
        record({ state: 'daily-review', at: now, by: role, done: out.done, partial: out.partial, miss: out.miss, total: out.total, quotaDelta: out.quotaDelta })
        const detail = out.results.map((r) => (r.verdict === 'done' ? '✅ ' : r.verdict === 'partial' ? '◐ ' : '✗ ') + r.task + '——' + r.why).join('\n')
        return { ok: true, text: reviewText(out) + '\n' + detail + '\n' + wrote.text }
      },
    }
  }
  function endTaskTool(role) {
    return {
      name: 'end_task_mode',
      description: '验收任务模式并结束（解锁娱乐）。只有确认他真的做到了才 passed=true：他讲清了做到哪、给你看了截图，或者你读屏核实过。没做到就别调用，或者用 start_task_mode 续。',
      parameters: {
        type: 'object',
        properties: {
          passed: { type: 'boolean' },
          summary: { type: 'string', description: '验收结论：做到了什么 / 依据是什么' },
        },
        required: ['passed', 'summary'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const summary = String(args.summary || '').trim().slice(0, 300)
        if (!aiTask(config, now) && !(config.refuseBy === 'ai' && Number(config.refuseUntil) > now)) return { ok: false, text: '现在没有你们立的任务。' }
        if (args.passed !== true) {
          record({ state: 'task-check', at: now, by: role, passed: false, summary })
          return { ok: true, text: '记下了：没通过，任务继续。' }
        }
        const goal = config.taskGoal
        setGear({ gear: 'free' }, 'ai-' + role)
        if (config.refuseBy === 'ai') {
          config = { ...config, refuseUntil: 0, refuseBy: undefined }
          try {
            writeConfig(config)
          } catch {
            // 这一轮照样生效
          }
        }
        // 提前验收通过：到点那一下"任务到点"的验收就不用再来了。
        saveGearState({ ...loadGearState(), doneFor: Number(config.gearUntil) })
        record({ state: 'task-done', at: now, by: role, goal, summary })
        return { ok: true, text: '验收通过，解锁了。' }
      },
    }
  }
  /**
   * 第四轮补·裁决入口：墙开的追问锁，由你自己回答算不算过关。
   * 格式校验在 followup.js 的 checkVerdict，落地在 applyVerdict——和墙走同一条路，只是 by 记你们两个。
   */
  function verdictTool(role) {
    return {
      name: 'submit_verdict',
      description: '回答墙开的追问锁（全屏拦着的那一问）：这次算不算过关，由你定。' +
        'kind 必须和锁的 reason 对上：morning 晨间立项 / review 验收 / evening 晚间对账 / defiance 违抗 / standby 待命超时 / gemini 连麦回流。' +
        'satisfied=false 要写清为什么不算过；satisfied=true 必须给 summary（为什么算过）和 nextStep（下一步做什么）。' +
        '晨间立项过关要带 1–3 项 tasks，每项有 task 和 evidence。过了就解锁（晨间/验收还会放一段风），不过锁继续挂着。',
      parameters: {
        type: 'object',
        properties: {
          kind: { type: 'string', description: 'morning / review / evening / defiance / standby / gemini' },
          satisfied: { type: 'boolean', description: '这次过不过' },
          summary: { type: 'string', description: '为什么算过（satisfied=true 时必填）' },
          nextStep: { type: 'string', description: '下一步做什么（satisfied=true 时必填）' },
          tasks: {
            type: 'array',
            maxItems: 3,
            description: '晨间立项过关时必带：今天 1–3 件事，每件带完成物',
            items: {
              type: 'object',
              properties: {
                task: { type: 'string', description: '要做什么（一句话）' },
                evidence: { type: 'string', description: '完成物：晚上能去查的东西' },
              },
              required: ['task', 'evidence'],
              additionalProperties: false,
            },
          },
          longTerm: { type: 'boolean', description: '这件事是不是长线的（进长期清单）' },
        },
        required: ['kind', 'satisfied'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const v = checkVerdict(args)
        if (!v.ok) return { ok: false, text: v.text }
        return applyVerdict(v, now, role)
      },
    }
  }
  /**
   * 第四轮补·当场放风：不解锁就玩不成的时候，你替他放一段。
   * 放风期间所有锁都不下发（手机按 breakUntil 执行），到点自动恢复。
   */
  function breakTool(role) {
    return {
      name: 'grant_break',
      description: '当场放他一段风：这段时间娱乐 App 全部解锁，到点自动恢复原样。' +
        '他清醒地提出、理由站得住才用；说清放多久、到点提醒他回来。账本会记下是谁放的、多久、为什么。',
      parameters: {
        type: 'object',
        properties: {
          minutes: { type: 'integer', description: '放多久（5–30 分钟，默认 10）' },
          reason: { type: 'string', description: '他给的理由（一句话）' },
        },
        required: ['reason'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const minutes = Math.max(5, Math.min(30, Math.round(Number(args.minutes) || 10)))
        const until = now + minutes * 60_000
        const reason = String(args.reason || '').slice(0, 200)
        config = { ...config, breakUntil: until, breakSince: now, breakChecked: false }
        try {
          writeConfig(config)
        } catch {
          // 这一轮照样生效
        }
        record({ state: 'grant-break', at: now, by: role, minutes, reason })
        return { ok: true, text: '放风 ' + minutes + ' 分钟，到 ' + new Date(until).toTimeString().slice(0, 5) + '；到点我提醒你回来。' }
      },
    }
  }
  /**
   * B 层 T1：押一条预判。
   * 你能写 claim、能写拿什么撞（evidenceRefs）、能定怎么算说中（metrics）；
   * **不能写 verdict**——那两个字由墙的遥测在到期时填，你说了不算（predict.js settle）。
   */
  function workflowTool(role) {
    return {
      name: 'workflow_propose',
      description: '提一条你自己想加的工作流。这是提议，不是启用——他不点「试」，它不会跑。' +
        '六项必须写全，缺一项交不上去：problem（解决什么问题）/ evidence（引账本哪条证据、哪个数）/ ' +
        'trigger（什么时候触发）/ ask（对他的要求，必须是他不用动手的那种）/ metric（可数的成功指标）/ ' +
        'judge（7 天后怎么判）。uses 是这条流程要用到的能力，只能用：' + WORKFLOW_ALLOW.join('、') + '。' +
        '每周最多提 1 条，同时在跑最多 3 条，满了先停再加。7 天没有可数效果会自动停，转正后每 30 天复核。',
      parameters: {
        type: 'object',
        properties: {
          problem: { type: 'string', description: '解决什么问题' },
          evidence: { type: 'string', description: '引哪条账本证据、哪个数' },
          trigger: { type: 'string', description: '什么时候触发' },
          ask: { type: 'string', description: '对用户的要求（不许是要他手动做的事）' },
          metric: { type: 'string', description: '可数的成功指标' },
          judge: { type: 'string', description: '7 天后怎么判' },
          uses: { type: 'array', maxItems: 8, description: '要用到的能力名', items: { type: 'string' } },
        },
        required: REQUIRED_FIELDS,
        additionalProperties: false,
      },
      output: {
        schema: { type: 'object', additionalProperties: true, properties: { ok: { type: 'boolean' }, text: { type: 'string' } } },
        render: (_a, v) => [{ type: 'text', text: String((v && v.text) || '') }],
      },
      execute: (args = {}) => {
        const now = Date.now()
        const entries = readLedger(home(), 300)
        const r = workflowPropose(config.workflows, entries, args, now, config.timeZone)
        if (!r.ok) {
          // 被拒的当场留痕：验收要查得到"墙的能力被拒"这件事发生过。
          record({ state: 'workflow-refused', at: now, by: role, code: r.code, missing: r.missing, rejected: r.rejected })
          const why =
            r.code === 'capability-refused'
              ? '这几种能力你碰不到：' + (r.rejected || []).map((x) => x.capability + '（' + x.code + '）').join('、')
              : r.code === 'field-missing'
                ? '缺这几项：' + (r.missing || []).join('、')
                : r.code === 'manual-action'
                  ? '这条要他自己动手，不算。'
                  : r.code === 'week-quota'
                    ? '这周已经提过一条了，下周再说。'
                    : r.code === 'running-quota'
                      ? '同时在跑的有 3 条了，先停一条再加。'
                      : r.code === 'duplicate'
                        ? '这个问题已经在跑了，不重复提。'
                        : '交不上去：' + r.code
          return { ok: false, text: why }
        }
        config = { ...config, workflows: [...(config.workflows || []), r.workflow] }
        try {
          writeConfig(config)
        } catch {
          return { ok: false, text: '存不下来，这条先不算。' }
        }
        record({ state: 'workflow-proposed', at: now, by: role, workflowId: r.workflow.id, problem: r.workflow.problem, metric: r.workflow.metric })
        return { ok: true, text: '提上去了。他不点「试」它不会跑；7 天没有可数效果自动停。' }
      },
    }
  }

  function predictTool(role) {
    return {
      name: 'submit_prediction',
      description: '押一条明天（或几小时后）会被证伪的预判，到期由墙的遥测判对错，不是你自己说了算。' +
        'claim 写一句具体的、能被打脸的话。' +
        'metrics 是判据，只能用这五个：file_growth（要给 path，量立项后字节增量）/ screenshot_count（新截图张数）/ ' +
        'app_minutes（要给 app 名，今天的分钟数）/ fun_minutes（娱乐合计分钟数）/ tasks_ratio（今天任务完成比例 0–1）。' +
        '每条 metric 给 op（> >= < <=）和 threshold；给几条就是几条全部成立才算说中。' +
        'evidenceRefs 写到时候拿什么撞它（例如 审计/2026-11-10.md、app:刷题、file:大肥鱼/周报.md），没有就交不上去。' +
        'dueInHours 默认 20 小时。说错了会被记进墙的审计，别急着找理由。',
      parameters: {
        type: 'object',
        properties: {
          claim: { type: 'string', description: '一句能被证伪的话' },
          dueInHours: { type: 'integer', description: '几小时后判（1–168，默认 20）' },
          evidenceRefs: {
            type: 'array',
            maxItems: 5,
            description: '到时候拿什么撞这条预判',
            items: { type: 'string' },
          },
          metrics: {
            type: 'array',
            maxItems: 3,
            description: '怎么算说中；给几条就是全部要成立',
            items: {
              type: 'object',
              properties: {
                metric: { type: 'string', description: 'file_growth / screenshot_count / app_minutes / fun_minutes / tasks_ratio' },
                op: { type: 'string', description: '> >= < <=' },
                threshold: { type: 'number', description: '门槛' },
                path: { type: 'string', description: 'file_growth 必填：文件路径' },
                app: { type: 'string', description: 'app_minutes 必填：App 名字' },
              },
              required: ['metric', 'op', 'threshold'],
              additionalProperties: false,
            },
          },
        },
        required: ['claim', 'evidenceRefs', 'metrics'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const cur = loadPredict()
        const v = checkClaim(args, { now, day: dayString(now, zoneOr(config.timeZone)), notesDir: notesDir() })
        if (!v.ok) return { ok: false, text: '这条押不上去：\n- ' + v.problems.join('\n- ') }
        const { ledger, record: rec } = appendPrediction(cur, v.value)
        savePredict(ledger)
        record({ state: 'predict-new', at: now, by: role, id: rec.id, claim: rec.claim })
        const when = new Date(rec.dueAt).toISOString().slice(5, 16).replace('T', ' ') + ' UTC'
        return {
          ok: true,
          text: '押上了（' + rec.id + '），' + when + ' 由墙的遥测判。判据：' +
            rec.metrics.map((m) => m.metric + (m.path ? '(' + m.path + ')' : m.app ? '(' + m.app + ')' : '') +
              m.op + m.threshold).join('，且') + '。到点不由你说了算。',
        }
      },
    }
  }
  /**
   * 手机报来的事：overlay-closed（他关掉了聊天窗，聊没聊过）、defied（关窗 30 秒后还在娱乐 App 里）。
   * 违抗按规矩当场升级（不等模型）：开拒绝模式，并让姐姐马上来收尾。
   */
  let pendingDefiance = null
  /**
   * Gemini 互动舱（应用管理页 / 两个人格都能开）：一场连麦期间手机只许 Gemini。
   *   start: { action:'start', topic, minutes, mode, by } → 写进 gemini.json 的 session
   *   end:   { action:'end' } → 提前收工，锁恢复成平时的拒绝模式 / 档位
   */
  function sessionRoute(body) {
    const now = Date.now()
    const action = String(body?.action || '')
    const g = loadGemini()
    if (action === 'end') {
      saveGemini({ ...g, session: null })
      record({ state: 'gemini-end', at: now, by: String(body?.by || 'ui').slice(0, 20) })
      return { v: 1, ok: true, session: null }
    }
    if (action !== 'start') return { v: 1, ok: false, error: { code: 'bad-action', message: 'start|end' } }
    const minutes = Math.max(5, Math.min(180, Math.round(Number(body?.minutes) || 30)))
    const mode = geminiMode(body?.mode)
    const topic = String(body?.topic || '').trim().slice(0, 120)
    const session = {
      topic, mode, minutes, startedAt: now, until: now + minutes * 60_000,
      by: String(body?.by || 'ui').slice(0, 20),
    }
    saveGemini({ ...g, session })
    record({ state: 'gemini-start', at: now, topic, mode, minutes, by: session.by })
    return { v: 1, ok: true, session: geminiSessionView(now) }
  }
  /**
   * 第二轮·墙的手机入口（应用管理页 / 面板）：
   *   status  → 读墙的现状（锁/开、窗口、本周剩余额度、今天有没有修改）。
   *   relax   → 申请明天放松一段（只在窗口+额度内；替代聊天里的放风）。
   *   tighten → 立即收紧到今天零点（任何时候）。
   */
  function wallRoute(body) {
    const now = Date.now()
    const tz = zoneOr(body?.timeZone || config.timeZone)
    const action = String(body?.action || 'status')
    const status = () => {
      const wd = wallDecision(config, now, tz)
      // 额度为什么可能是 0：四段没交齐。界面得能把这句话说出来，不然只看到 0 不知道原因。
      const dep = statusOf(depositToday(now))
      return {
        v: 1, ok: true, wall: {
          locked: wd.locked, kind: wd.kind || null, openUntil: wd.openUntil || 0, why: wd.why || null,
          applyWindowOpen: applyWindowOpen(config.applyWindow, now),
          applyWindowCloseAt: Number(config.applyWindow?.closeAt) || 0,
          relaxRemaining: relaxRemainingNow(now, tz),
          depositDone: dep.done,
          depositTotal: dep.total,
          depositMissing: dep.missing.join('、'),
          relaxToday: Boolean(activeRelax(config, now, tz)),
          tightenToday: tightenActive(config, now, tz),
          meals: wallConfig(config).meals,
        },
      }
    }
    if (action === 'relax') {
      const r = requestRelax(now, tz, { atHour: body?.atHour, minutes: body?.minutes, reason: body?.reason, by: 'ui' })
      return { ...status(), ok: r.ok, error: r.ok ? undefined : { code: r.code, message: r.text }, text: r.text }
    }
    if (action === 'tighten') {
      const r = tightenNow(now, tz, { reason: body?.reason, by: 'ui' })
      return { ...status(), ok: r.ok, text: r.text }
    }
    return status()
  }
  /**
   * 本周还剩几天放风额度——四部门没交齐的当天归零。
   *
   * 这是「每天必须交」的硬约束落点：不看聊天、不看模型，只看 deposit.json 里今天齐不齐。
   * 所有报额度的地方都走这一个，免得界面说还剩 2 天、实际却放不出来。
   */
  function relaxRemainingNow(now, tz) {
    if (!completeOf(depositToday(now))) return 0
    return relaxRemaining(config, now, tz)
  }

  /**
   * 每日四部门提交（第五轮 `POST /deposit`）。
   *
   * action=status：今天交了几段、还缺哪几段（界面和墙都用这一个）。
   * action=submit：交一段，body 里带 department + report + thought。字数不到当场退回。
   *
   * 只收自己的内容，只回自己的状态：这里不放宽任何规则，也不影响墙的锁。
   */
  function depositRoute(body) {
    const now = Date.now()
    const tz = zoneOr(body?.timeZone || config.timeZone)
    const action = String(body?.action || 'status')
    const view = () => {
      const s = statusOf(depositToday(now))
      return { v: 1, ok: true, deposit: { ...s, missingText: missingText(depositToday(now)), departments: DEPARTMENTS } }
    }
    if (action !== 'submit') return view()
    const key = String(body?.department || '').trim()
    if (!DEPARTMENTS.some((d) => d.key === key)) {
      return { ...view(), ok: false, error: { code: 'bad-department', message: '没有这个部门：' + key } }
    }
    const norm = normalizeEntry({ report: body?.report, thought: body?.thought }, now)
    if (!norm.ok) return { ...view(), ok: false, error: { code: 'too-thin', message: norm.text } }
    const next = withEntry(depositToday(now), key, norm.entry)
    saveDepositState(next)
    return { ...view(), ok: true, text: '交了「' + (DEPARTMENTS.find((d) => d.key === key) || {}).label + '」。' }
  }

  function eventRoute(body) {
    const now = Date.now()
    const type = String(body?.type || '')
    if (type === 'overlay-closed') {
      // busy = 点了「在忙，晚点回」（C4）：不算回过话、不开安静期，只记一笔。
      record({ state: 'overlay-closed', at: now, chatted: body?.chatted === true, muted: body?.muted === true, busy: body?.busy === true || undefined, app: typeof body?.app === 'string' ? body.app.slice(0, 120) : undefined })
      return { v: 1, ok: true }
    }
    if (type === 'defied') {
      const app = typeof body?.app === 'string' ? body.app.slice(0, 120) : ''
      const label = typeof body?.label === 'string' ? body.label.slice(0, 40) : app
      const mins = effectiveMode(config, now) === 'coach' ? 60 : 30
      const until = Math.max(now + mins * 60_000, Number(config.refuseUntil) || 0)
      config = { ...config, refuseUntil: until, refuseBy: config.refuseBy || 'defied', muteUntil: 0 }
      try {
        writeConfig(config)
      } catch {
        // 这一轮照样生效
      }
      pendingDefiance = { app, label, at: now, until, source: String(body?.source || 'overlay').slice(0, 20) }
      record({ state: 'defied', at: now, app, label, until, source: pendingDefiance.source })
      // 违抗打开追问锁（原因：违抗）。挂着没问过的义务趁这次一并问掉（下次违抗时再问）。
      const re = takeReask(config.obligations, 'defiance', now)
      if (re.ask) commit({ obligations: re.obligations })
      openFollowup(re.ask ? re.ask.kind : 'defiance', re.ask ? re.ask.text : '刚关掉提醒又打开了' + label + '，为什么？接下来怎么办？', now)
      // 收尾的话由墙说（代码模板），不经模型、不由姐姐出面。
      return { v: 1, ok: true, refuse: { until }, force: { level: 4, app, why: wallDefianceText(label, until) } }
    }
    // 锁屏页 / 无障碍报来的（DeepSeek 那边接）：
    //   tamper     —— 管控期间他去设置里找"强行停止 / 卸载 / 关无障碍"：信用分清零，拒绝模式 60 分钟，鲸澜收尾。
    //   breakglass —— 走够步数 + 手打违约说明，破窗 10 分钟：照放，扣信用分，记进账本晚上要问。
    //   notif      —— 任务期间拦下的一条通知（只要 App 和标题，不要正文）：攒着，任务结束一次性简报。
    if (type === 'tamper') {
      const until = Math.max(now + 60 * 60_000, Number(config.refuseUntil) || 0)
      config = { ...config, refuseUntil: until, refuseBy: config.refuseBy || 'tamper', muteUntil: 0 }
      try {
        writeConfig(config)
      } catch {
        // 这一轮照样生效
      }
      const what = typeof body?.what === 'string' ? body.what.slice(0, 60) : '设置'
      pendingDefiance = { app: 'settings', label: '系统设置（' + what + '）', at: now, until, source: 'tamper' }
      record({ state: 'tamper', at: now, what, until })
      return { v: 1, ok: true, refuse: { until }, force: { level: 4, app: String(body?.app || ''), why: '想去设置里拆掉管控' } }
    }
    if (type === 'breakglass') {
      // D5：每天最多 1 次。按手机时区算“今天”，跨零点就是新的一天。
      const today = startOfDay(now, config.timeZone)
      const usedToday = Number(config.breakglassDay) === today ||
        readLedger(home(), 800).some((e) => e?.state === 'breakglass' && Number(e.at) >= today)
      if (usedToday) return { v: 1, ok: false, error: { code: 'breakglass-used', message: '今天已经破窗过一次' } }
      const minutes = Math.max(5, Math.min(15, Math.round(Number(body?.minutes) || 10)))
      const penalty = Math.max(0, Math.min(100, Math.round(Number(config.breakglassPenalty) || 15)))
      const until = now + minutes * 60_000
      config = { ...config, breakUntil: until, breakSince: now, breakChecked: false, breakglassDay: today }
      try {
        writeConfig(config)
      } catch {
        // 这一轮照样生效
      }
      // penalty 一起记账：晚上对账和信用分都看得到这次扣了多少。
      record({ state: 'breakglass', at: now, minutes, penalty, steps: Number(body?.steps) || undefined, note: typeof body?.note === 'string' ? body.note.slice(0, 300) : undefined })
      return { v: 1, ok: true, breakUntil: until, penalty }
    }
    if (type === 'notif') {
      const n = loadNotifs()
      const label = String(body?.label || body?.app || '?').slice(0, 30)
      const apps = { ...(n.apps || {}) }
      const cur = apps[label] || { count: 0, titles: [], group: 0 }
      cur.count += 1
      if (body?.group === true) cur.group += 1
      const title = typeof body?.title === 'string' ? body.title.slice(0, 40) : ''
      if (title && cur.titles.length < 5 && !cur.titles.includes(title)) cur.titles.push(title)
      apps[label] = cur
      saveNotifs({ since: n.since || now, apps })
      return { v: 1, ok: true }
    }
    if (type === 'start') {
      // 用户点「开始」（带任务和时长）：进冲刺。娱乐锁上，通知收进防波堤。
      const long = body?.long === true
      const dflt = long ? Number(config.sprintLongMin) || SPRINT_LONG_MIN : Number(config.sprintMin) || SPRINT_MIN
      const minutes = Math.max(5, Math.min(120, Math.round(Number(body?.minutes) || dflt)))
      const task = String(body?.task || '').trim().slice(0, 120)
      const until = now + minutes * 60_000
      setGear({ gear: 'busy', until, what: task || '冲刺', task: task ? { goal: task, by: 'user' } : undefined }, 'sprint')
      commit({ sprintUntil: until })
      record({ state: 'sprint-start', at: now, minutes, task: task || undefined, until })
      wallCloseOnStart(now)
      return { v: 1, ok: true, sprint: { until, minutes, task: task || null } }
    }
    if (type === 'request-review') {
      // 用户点「申请验收」：进验收（追问锁，原因验收）。
      const lock = openFollowup('review', '这一轮做完了吗？拿完成物说话。', now)
      return { v: 1, ok: true, followup: followupView(lock, now) }
    }
    if (type === 'screen-on') {
      // 亮屏：只记账。晨间门的开关仍由 /plan 的作息判定，不在这儿抢。
      record({ state: 'screen-on', at: now })
      return { v: 1, ok: true }
    }
    if (type === 'gemini-end') {
      // 连麦结束（带时长）：满 3 分钟才算完成，接着出追问（连麦回流）；不足回待命。
      const minutes = Math.max(0, Math.round(Number(body?.minutes) || 0))
      const min = Math.max(1, Math.round(Number(config.brainMinMin) || 3))
      saveGemini({ ...loadGemini(), session: null })
      record({ state: 'gemini-end', at: now, minutes, by: String(body?.by || 'phone').slice(0, 20) })
      if (minutes >= min) {
        const lock = openFollowup('gemini', '刚才连麦说到哪、结论是什么？', now)
        return { v: 1, ok: true, counted: true, followup: followupView(lock, now) }
      }
      return { v: 1, ok: true, counted: false, followup: followupView(config.followup, now) }
    }
    if (type === 'followup-timeout') {
      // 手机本地到点，告诉插件一声（插件每次 /plan 也会兜一次，谁先到都算）。
      const l = config.followup
      if (!l || !lockExpired(l, now)) return { v: 1, ok: true, expired: false }
      expireFollowup(now)
      return { v: 1, ok: true, expired: true, followup: followupView(config.followup, now), obligations: obligationsView(config.obligations, now) }
    }
    if (type === 'wechat-hit') {
      // 微信里误入视频号：手机连续停留 3 秒、离开那一页时（去哪都算，灭屏也算）上报。插件只记账，不升级成违抗。
      record({ state: 'wechat-hit', at: now, page: String(body?.page || '').slice(0, 60), dwellMs: Math.max(0, Math.round(Number(body?.dwellMs) || 0)) })
      return { v: 1, ok: true }
    }
    if (type === 'blocked-open') {
      // 他打开被拦的 App：挂着的义务按用户的动作再问一次（不定时自动弹）。
      const r = takeReask(config.obligations, 'locked-app', now)
      if (!r.ask) return { v: 1, ok: true, ask: false }
      commit({ obligations: r.obligations })
      const lock = openFollowup(r.ask.kind, r.ask.text, now)
      record({ state: 'obligation-reask', at: now, kind: r.ask.kind, app: String(body?.app || '').slice(0, 120) })
      return { v: 1, ok: true, ask: true, followup: followupView(lock, now) }
    }
    return { v: 1, ok: false, error: { code: 'unknown-event' } }
  }
  /** 任务期间拦下的通知，整理成一句简报（取完就清）。 */
  function takeDigest() {
    const n = loadNotifs()
    const apps = Object.entries(n.apps || {})
    if (!apps.length) return ''
    saveNotifs({})
    return '刚才拦下的通知：' + apps.sort((a, b) => b[1].count - a[1].count).slice(0, 6)
      .map(([label, v]) => label + ' ' + v.count + ' 条' + (v.group ? '（群聊 ' + v.group + '）' : '') + (v.titles.length ? '：' + v.titles.slice(0, 3).join('、') : '')).join('；') + '。'
  }
  /**
   * 第二轮撤掉了「没人接就每 10 分钟追到回话」的追人阶梯：压力归墙，姐妹不催。
   * 她们照常关心（注意到他刷太久会说一句），只是不会为了等他回话而反复来。
   */
  /** 未完成笔记追杀：他有空（闲时、不在玩、亮屏、9–22 点），三小时最多一次，挑一篇没想完的。 */
  function chaseSignal(phone, now, tz) {
    const dir = notesDir()
    if (!dir || !phone?.screenOn || phone.fun) return null
    const min = localMinute(phone, zoneMinuteOfDay(now, tz))
    if (min < 9 * 60 || min >= 22 * 60) return null
    const gs = loadGearState()
    if (now - (Number(gs.chaseAt) || 0) < 3 * 3_600_000) return null
    const asked = (Array.isArray(gs.chased) ? gs.chased : []).filter((c) => now - Number(c.at) < 7 * 86_400_000).map((c) => c.path)
    const found = scanUnfinished(dir, { now, asked })
    return found[0] || null
  }
  /** 第二轮撤掉了追人阶梯（没人接不再每 10 分钟来一次）；姐妹正常的关心走 restless / patrol / busy-done。 */
  /**
   * 心猿意马：90 秒里切了 5 次以上、至少 4 个不同的 App，人还不在娱乐 App 里（在里面的话力度会管）。
   * 鱼丸在他点开游戏之前截胡。20 分钟最多一次。
   */
  function restlessSignal(phone, now) {
    const sw = phone?.switches
    if (!sw || sw.in90s < 5 || sw.apps90s.length < 4 || phone.fun) return null
    if (now - (Number(loadGearState().restlessAt) || 0) < 20 * 60_000) return null
    return { seconds: 90, switches: sw.in90s, apps: sw.apps90s.slice(0, 5).join('、') }
  }
  /** 最近的前台切换（巡逻用：来回刷手机）。 */
  const fronts = []
  function noteFront(phone, now) {
    if (!phone?.app) return
    if (!fronts.length || fronts[fronts.length - 1].app !== phone.app) fronts.push({ app: phone.app, fun: phone.fun === true, at: now })
    while (fronts.length && now - fronts[0].at > 30 * 60_000) fronts.shift()
  }
  /** 该不该巡逻一次：来回刷（15 分钟里切了 8 次以上）、今天娱乐超过 90 分钟还在玩、或者到了巡逻时段。 */
  function patrolSignal(phone, now, tz) {
    const gs = loadGearState()
    if (now - (Number(gs.patrolAt) || 0) < 90 * 60_000) return null
    const recent = fronts.filter((f) => now - f.at <= 15 * 60_000)
    // 手机自己数的切换次数更准（/plan 有节流，插件这边会漏）。
    const sw = phone?.switches
    const switches = sw ? sw.in15m : recent.length
    const distinct = sw ? sw.distinct15m : new Set(recent.map((f) => f.app)).size
    if (switches >= 8) return { why: '他 15 分钟里在 ' + distinct + ' 个 App 之间来回切了 ' + switches + ' 次，像是在无目的地刷' }
    const fun = funToday(phone)
    if (phone?.fun && fun >= 90) return { why: '他今天娱乐已经 ' + fun + ' 分钟了，现在还在' + (phone.label || phone.app) }
    const hour = phone?.local ? Number(phone.local.slice(0, 2)) : Math.floor(zoneMinuteOfDay(now, tz) / 60)
    const slots = Array.isArray(config.patrolHours) ? config.patrolHours.map(Number) : [10, 15, 20]
    const day = new Date(now).toISOString().slice(0, 10)
    const slotKey = day + ':' + hour
    if (slots.includes(hour) && gs.patrolSlot !== slotKey && phone?.screenOn) return { why: '到了 ' + hour + ' 点的巡逻时段，看看他该干的事动了没有', slot: slotKey }
    return null
  }
  /** 发球前的"互动时间线"：她们别再说"你一小时没理我"。 */
  function contactLines(entries, now) {
    let human = 0
    let closed = null
    let ball = 0
    for (const e of entries) {
      if (!e) continue
      if ((e.state === 'human' || e.state === 'said') && Number(e.at) > human) human = Number(e.at)
      if (e.state === 'overlay-closed' && (!closed || Number(e.at) > Number(closed.at))) closed = e
      if (e.state === 'generated' && Number(e.at) > ball) ball = Number(e.at)
    }
    const ago = (at) => {
      const m = Math.round((now - at) / 60_000)
      return m < 1 ? '不到 1 分钟前' : m < 120 ? m + ' 分钟前' : Math.round(m / 60) + ' 小时前'
    }
    const lines = []
    if (human) lines.push('他最后一次跟你们说话：' + ago(human) + '。')
    if (closed && Number(closed.at) > ball - 60_000) lines.push('他' + ago(Number(closed.at)) + '自己关掉了聊天窗' + (closed.chatted ? '（聊了几句之后）' : '（没回就关了）') + (closed.muted ? '，还点了"别烦我"' : '') + '。')
    if (ball) lines.push('上一次你们主动找他：' + ago(ball) + '。')
    if (lines.length) lines.push('按这条时间线说话：他刚聊过就别说"好久没理我"；他刚关窗就别装没发生。')
    return lines
  }
  /** 去：给 Gemini 的背景 + 怎么送过去。 */
  function geminiLiveTool(role) {
    return {
      name: 'gemini_live',
      description: '把他送去跟 Gemini 连麦（他有 Gemini App，Live 能实时语音、看屏幕）。生成一段给 Gemini 的背景（他是谁、这次干嘛、怎么盯他、结束怎么复盘），' +
        '返回背景全文和送过去的步骤（写剪贴板、打开 Gemini）。mode：study 学习陪练 / think 共脑想事 / interrogate 审讯摸鱼。连麦期间他跑去娱乐 App 会被锁。',
      parameters: {
        type: 'object',
        properties: {
          topic: { type: 'string', description: '这次要做什么（越具体越好）' },
          minutes: { type: 'integer', minimum: 5, maximum: 180, description: '连多久，默认 30' },
          mode: { type: 'string', enum: ['study', 'think', 'interrogate'] },
        },
        required: ['topic'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const minutes = Math.max(5, Math.min(180, Math.round(Number(args.minutes) || 30)))
        const mode = geminiMode(args.mode)
        const topic = String(args.topic || '').trim().slice(0, 120)
        const effMode = effectiveMode(config, now)
        let about = personaLines(mergePersona(readCorePersona(home()), config.persona), effMode).filter((l) => !/^现在是(伙伴|教练)模式/.test(l))
        const db = openMemoryDb(memoryPath())
        try {
          about = [...about, ...backgroundFacts(loadCandidates(db, { sessionId: config.sessionId, now }), 5)]
        } catch {
          // 记忆读不到就少几条背景
        } finally {
          try {
            db?.close?.()
          } catch {
            // ignore
          }
        }
        const g = loadGemini()
        const recaps = Array.isArray(g.recaps) ? g.recaps : []
        const brief = geminiBrief({
          owner: String(config.owner || '他'), topic, minutes, mode, about,
          commitments: readCommitments(home(), now, 3).map((c) => c.statement),
          recap: recaps[recaps.length - 1] || null,
          gear: effectiveGear(config, now) === 'busy' ? config.gearWhat : null,
        })
        saveGemini({ ...g, session: { topic, mode, minutes, startedAt: now, until: now + minutes * 60_000, by: role } })
        record({ state: 'gemini-start', at: now, topic, mode, minutes, by: role })
        return { ok: true, text: '背景：\n' + brief + '\n\n下一步：\n' + geminiSteps(), brief }
      },
    }
  }
  /** D4：预约一次 Gemini Live。到点由手机取 /gemini/due 的 launch（背景简报），打开 Gemini 进共舱。 */
  function scheduleGeminiLiveTool(role) {
    return {
      name: 'schedule_gemini_live',
      description: '预约一次 Gemini Live 连麦：到点自动生成背景简报、打开 Gemini、进入共舱。' +
        '用 delayMinutes（几分钟后）或 at（"HH:MM" 当地时间 / ISO 时间）指定时间；topic 写清这次要做什么。',
      parameters: {
        type: 'object',
        properties: {
          delayMinutes: { type: 'integer', minimum: 1, maximum: 1440, description: '多少分钟后开始' },
          at: { type: 'string', description: '开始时间：HH:MM（当地时间，下一次）或 ISO 时间' },
          topic: { type: 'string', description: '这次连麦要做什么（越具体越好）' },
          minutes: { type: 'integer', minimum: 5, maximum: 180, description: '连多久，默认 30' },
          mode: { type: 'string', enum: ['study', 'think', 'interrogate'] },
        },
        required: ['topic'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const tz = zoneOr(config.timeZone)
        const topic = String(args.topic || '').trim().slice(0, 120)
        if (!topic) return { ok: false, text: '要说清这次连麦做什么。' }
        let at = 0
        if (Number.isFinite(Number(args.delayMinutes)) && Number(args.delayMinutes) > 0) {
          at = now + Math.max(1, Math.min(1440, Math.round(Number(args.delayMinutes)))) * 60_000
        } else if (typeof args.at === 'string' && args.at.trim()) {
          at = nextLocalAt(args.at, now, tz) || (Number.isFinite(Date.parse(args.at)) ? Date.parse(args.at) : 0)
        }
        if (!at || at <= now) return { ok: false, text: '时间要往以后：delayMinutes 或 at="HH:MM"。' }
        const minutes = Math.max(5, Math.min(180, Math.round(Number(args.minutes) || 30)))
        const mode = geminiMode(args.mode)
        saveGeminiSchedule({ at, topic, minutes, mode, by: role, createdAt: now, fired: false })
        record({ state: 'gemini-schedule', at: now, fireAt: at, topic, minutes, mode, by: role })
        const hm = new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(at))
        return { ok: true, text: '约好了：' + hm + ' 开始，' + minutes + ' 分钟，主题「' + topic + '」。到点手机会自动把背景放进剪贴板并打开 Gemini。' }
      },
    }
  }
  /** 回：连麦读回来之后存进笔记库，两姐妹都看得到。 */
  function geminiLogTool(role) {
    return {
      name: 'gemini_log',
      description: '存下一场 Gemini 连麦的复盘（读回对话之后调用）：做了什么、卡在哪、下一步、他的一两句关键原话。写进笔记库 Gemini/日期.md，两姐妹之后每一轮都看得到。只写真读到的，读不到别编。',
      parameters: {
        type: 'object',
        properties: {
          summary: { type: 'string', description: '做了什么（一两句）' },
          stuck: { type: 'string', description: '卡在哪' },
          next: { type: 'string', description: '下一步' },
          quotes: { type: 'array', items: { type: 'string' }, description: '他说过的关键原话，最多 3 句' },
          topic: { type: 'string' },
        },
        required: ['summary'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const summary = String(args.summary || '').trim().slice(0, 400)
        if (!summary) return { ok: false, text: '复盘是空的。' }
        const g = loadGemini()
        const minutes = g.session?.startedAt ? Math.max(0, Math.round((now - Number(g.session.startedAt)) / 60_000)) : undefined
        const r = {
          at: now, by: role, summary, minutes,
          stuck: String(args.stuck || '').trim().slice(0, 200) || undefined,
          next: String(args.next || '').trim().slice(0, 200) || undefined,
          quotes: (Array.isArray(args.quotes) ? args.quotes : []).map((q) => String(q).slice(0, 200)).slice(0, 3),
          topic: String(args.topic || g.session?.topic || '').slice(0, 120) || undefined,
        }
        const day = new Date(now).toISOString().slice(0, 10)
        const dir = notesDir() ? join(notesDir(), 'Gemini') : join(home(), 'companion', 'gemini')
        const file = join(dir, day + '.md')
        let wrote = ''
        try {
          mkdirSync(dir, { recursive: true })
          if (!existsSync(file)) writeFileSync(file, '# ' + day + ' · Gemini 连麦\n', 'utf8')
          appendFileSync(file, recapMarkdown(r, now), 'utf8')
          wrote = file
        } catch (error) {
          console.error('[companion] Gemini 复盘写不进 ' + file + '：' + String((error && error.message) || error))
        }
        const recaps = [...(Array.isArray(g.recaps) ? g.recaps : []), { ...r, file: wrote || undefined }].slice(-5)
        saveGemini({ ...g, recaps, session: null, recappedFor: Number(g.inSince) || g.recappedFor })
        record({ state: 'gemini-recap', at: now, by: role, file: wrote || undefined, minutes })
        return { ok: true, text: wrote ? '存好了：' + wrote : '记下了（笔记文件没写进去，复盘还在，两姐妹都看得到）。' }
      },
    }
  }
  /** 提前收工：结束 Gemini 互动舱，手机恢复平时的拒绝模式 / 档位。两个人都能用。 */
  function geminiEndTool(role) {
    return {
      name: 'gemini_end',
      description: '结束正在进行的 Gemini 连麦（提前收工）。手机随即解除"只许 Gemini"的锁，恢复平时的档位和拒绝模式；连麦中的复盘照常用 gemini_log 存。',
      parameters: {
        type: 'object',
        properties: { reason: { type: 'string', description: '为什么提前结束（一句话）' } },
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const g = loadGemini()
        if (!g.session) return { ok: false, text: '现在没有进行中的 Gemini 连麦。' }
        const topic = String(g.session.topic || '')
        const minutes = g.session.startedAt ? Math.max(0, Math.round((Date.now() - Number(g.session.startedAt)) / 60_000)) : undefined
        saveGemini({ ...g, session: null })
        record({ state: 'gemini-end', at: Date.now(), by: role, minutes, reason: String(args.reason || '').slice(0, 80) || undefined })
        return { ok: true, text: '连麦结束了' + (topic ? '（' + topic + '）' : '') + (minutes ? '，约 ' + minutes + ' 分钟' : '') + '，手机不再只许 Gemini。' }
      },
    }
  }
  /** 姐姐还没去找他的那条急话（2 小时内）。 */
  function pendingHandoff(now) {
    return loadNotes().filter((n) => n.to === 'strategist' && n.urgent && !n.delivered && now - Number(n.at) < 2 * 3_600_000).pop() || null
  }
  function markDelivered(note) {
    if (!note) return
    const list = loadNotes()
    for (const n of list) if (n.at === note.at && n.text === note.text) n.delivered = Date.now()
    saveNotes(list)
  }
  /** /plan 决定了"下一球是姐姐"：/request 来的时候照这个把球送进姐姐的会话。 */
  let nextSpeaker = null
  /** requestId -> 姐姐这一球为什么开口（handoff / review）。 */
  const strategistBallReason = new Map()
  /** 最近一球是谁说的：浮层里回话默认回给她（30 分钟内）。 */
  let lastBall = { role: 'companion', at: 0 }
  const speakerOf = (sid) => (config.dual !== false && sid && sid === config.strategistSessionId ? 'strategist' : 'companion')

  /** 单人格时代的说法（dual 关掉时用）。 */
  function singlePersonaText(mode) {
    const lines = ['你是' + String(config.self || '大肥鱼') + '，' + String(config.owner || '他') + '身边的伙伴，不是客服也不是工具。']
    for (const l of personaLines(mergePersona(readCorePersona(home()), config.persona), mode)) lines.push(l)
    lines.push(
      '聊天时：像朋友那样说话，短、直接、有自己的判断；他在解释为什么这样，先用自己的话复述你理解的理由，再表态——' +
        '站得住就认，站不住就具体说哪里站不住；别只重复立场，也别一被反驳就全盘让步。',
    )
    lines.push('答应他的事要真的做到：要定时提醒就用提醒工具真的设好，并说清楚到点是怎么提醒的；做不到的（比如替他数着打了几把）就别答应。不要假装记得没发生过的事；不知道就说不知道。')
    return lines.join('\n')
  }
  // ---------------------------------------------------------------- B 层 T3：内容层随机
  const loadVibeState = () => readState(stateFile('vibe.json'), {}) || {}
  const saveVibeState = (s) => writeJson(stateFile('vibe.json'), s)
  // 同一次组装里 chatPersonaText 可能被调不止一次：这段窗口内复用同一批素材，
  // 免得同一轮里两句人设互相打架（ST 的 Sticky 就是干这个的）。
  const VIBE_HOLD_MS = 20_000
  let vibeCache = { at: 0, role: '', lines: [] }

  /**
   * 抽这一轮给哪几张素材。常驻层（铁律/人设卡/议程/判断）在上面已经全量进了 stateLines，
   * 这里只往上加，永远删不掉任何一条；被抽中的 arm 记进 lastVibePicks，下一球按结果各自记账。
   */
  function takeVibe(role, stateLines, now) {
    if (vibeCache.role === role && now - vibeCache.at < VIBE_HOLD_MS) return vibeCache.lines
    const st = loadVibeState()
    const turn = Number(st.turn) || 0
    const tz = zoneOr(config.timeZone)
    const temper = readState(stateFile('temper.json'), null)
    const daily = loadDaily(loadDailyState(), now, tz)
    const built = buildVibe({
      date: dayString(now, tz),
      turn,
      arms: loadBrain().arms,
      state: { last: st.last || {}, streak: st.streak || {} },
    })
    const lines = renderVibe(built.picks, {
      temperLine: temperLine(temper),
      predictions: predictionLines(loadPredict(), now),
      keywords: (daily.tasks || []).map((t) => (keywordsOf(t.evidence) || [])[0]).filter(Boolean),
      stateLines,
      face: temper?.face || '',
      roll: built.rng(),
    })
    saveVibeState({ v: 1, turn: turn + 1, last: built.state.last, streak: built.state.streak })
    lastVibePicks = pickedArms(built.picks)
    lastVibe = { face: temper?.face || '', turn: built.snapshot.turn }
    // §13.2：每轮落注入快照，真机"前后不一致"时翻快照定位，不靠猜。
    record({ state: 'vibe', at: now, role, ...built.snapshot })
    vibeCache = { at: now, role, lines }
    return lines
  }
  function chatPersonaText(role) {
    if (config.personaInChat === false) return ''
    const mode = effectiveMode(config, Date.now())
    if (config.dual === false) return singlePersonaText(mode)
    const names = roleNames(config)
    const refuseAt = Number(config.refuseUntil) || 0
    const refuseLines = refuseAt > Date.now()
      ? ['拒绝模式开着（到 ' + new Date(refuseAt).toISOString().slice(11, 16) + ' UTC）：他一打开娱乐 App 手机就会把他赶回桌面锁住。他求你放行，不松；要关只能他自己说"关掉拒绝模式"。']
      : []
    // D6：议程跟当前主状态走（状态机在 machine.js，追问锁是叠在上面的临时层）。
    // 锁的对外形状统一走 followupView（{active,reason,currentQuestion,since,until}），别再自己拼。
    const mainState = resolveState({
      now: Date.now(),
      tz: zoneOr(config.timeZone),
      config,
      rhythm: lastRhythm || {},
      followup: followupView(config.followup, Date.now()),
      session: loadGemini().session,
    }).state
    const stateLines = [...trustLines(trustNow()), ...rhythmLines(lastRhythm), ...gearLines(config, Date.now()), ...geminiLines(loadGemini(), Date.now()),
      ...depositLines(depositToday(Date.now()), Boolean(lastRhythm?.reviewGate)),
      ...agendaLines(mainState), ...knowledgeLines(kbDir(notesDir(), home()), role)]
    const notes = notesFor(role).map((n) => (n.from === 'companion' ? names.companion : names.strategist) + '刚跟你递话（' +
      new Date(Number(n.at)).toISOString().slice(11, 16) + ' UTC）："' + n.text + '"')
    const noteLines = notes.length ? ['姐妹之间的悄悄话（他看不到，自然地用上就行）：', ...notes.map((t) => '- ' + t)] : []
    // 内容层随机：只加不减，常驻层已经全在 stateLines 里。
    const vibeLines = (() => {
      try {
        return takeVibe(role, stateLines, Date.now())
      } catch {
        return [] // 随机器坏了不能拖垮人设
      }
    })()
    if (role === 'strategist') {
      // 关系库人物卡（价值观/底线/边界）是姐姐的底子。
      const core = personaLines(mergePersona(readCorePersona(home()), {}), mode).filter((l) => !/^现在是(伙伴|教练)模式/.test(l))
      return strategistChatText(config, core, [...refuseLines, ...stateLines, ...vibeLines, capabilityLines('strategist', { notesDir: notesDir() }), ...noteLines])
    }
    const extra = mode === 'coach' ? ['他现在开了严格模式：你可以更凶、更不依不饶，但还是你自己的样子。'] : []
    return companionChatText(config, [...extra, ...refuseLines, ...stateLines, ...vibeLines, capabilityLines('companion', { notesDir: notesDir() }), ...noteLines])
  }
  /** role -> {sid, agent, off}：两个会话各挂各的人设。 */
  const personaMounts = new Map()
  function mountOne(role, sid) {
    const cur = personaMounts.get(role) || { sid: '', agent: null, off: null }
    const agent = sid ? liveAgent(ctx, sid) : null
    if (cur.agent === (agent || null) && cur.sid === sid) return
    try {
      cur.off?.()
    } catch {
      // ignore
    }
    const next = { sid, agent: agent || null, off: null, toolOff: null }
    try {
      cur.toolOff?.()
    } catch {
      // ignore
    }
    personaMounts.set(role, next)
    // 传话工具按会话注册：这样工具知道是谁递给谁。
    if (agent?.ctx?.tools && typeof agent.ctx.tools.register === 'function') {
      const offs = []
      // 第四轮（R4-2）：两姐妹工具完全一致。她们是他这边的人：帮忙和收紧的工具全给（立任务、拒绝模式、
      // 封 App、今天收紧、连麦），放松只有 request_relax——那条由代码卡死在晚间申请窗口、只管明天、按周限额。
      // 过不过关不归她们：整理好交给墙（submit_to_wall），墙只看证据。
      // 第四轮补：按用户决定，把原来"谁都没有"的五个全给她们——裁决（submit_verdict）、
      // 当场放风（grant_break）、验收任务（end_task_mode）、定事（daily_plan）、对账（daily_review）。
      // 墙的权限不低于这些：submit_to_wall 仍然只认物理证据，比她们自己说了算更严。
      const list = [
        notesTool(), sisterToolIfDual(role), vaultSearchTool(), vaultRecentTool(), kbTool(role),
        geminiLiveTool(role), geminiEndTool(role), geminiLogTool(role), scheduleGeminiLiveTool(role),
        controlTool(), startTaskTool(role), blockTool(role), tightenTool(role), relaxTool(role),
        wallTool(role), verdictTool(role), breakTool(role), endTaskTool(role),
        dailyPlanTool(role), dailyReviewTool(role), predictTool(role), workflowTool(role),
      ].filter(Boolean)
      for (const tool of list) {
        try {
          const off = agent.ctx.tools.register(tool)
          if (typeof off === 'function') offs.push(off)
        } catch (error) {
          console.error('[companion] 工具 ' + tool.name + ' 挂不上（' + role + '）：' + String((error && error.message) || error))
        }
      }
      if (offs.length) next.toolOff = () => offs.forEach((f) => f())
    }
    if (!agent?.ctx?.systemPrompt?.context) return
    try {
      const off = agent.ctx.systemPrompt.context({
        name: role === 'strategist' ? 'dafeiyu-strategist' : 'dafeiyu-persona',
        order: 850,
        interpolate: false,
        text: () => chatPersonaText(role),
      })
      if (typeof off === 'function') next.off = off
    } catch (error) {
      console.error('[companion] 人格挂不上（' + role + '）：' + String((error && error.message) || error))
    }
  }
  function mountPersona() {
    mountOne('companion', config.sessionId || '')
    const strat = config.dual === false ? '' : config.strategistSessionId || ''
    // 两个角色指向同一个会话时，只挂陪伴那一份，别让模型同时拿到两套人设。
    mountOne('strategist', strat && strat !== config.sessionId ? strat : '')
  }
  try {
    mountPersona()
  } catch {
    // agents may not be up yet; the timer retries
  }
  const personaTimer = setInterval(() => {
    try {
      mountPersona()
    } catch {
      // never break the engine
    }
  }, 2_000)
  personaTimer.unref?.()
  ctx.effect?.(() => () => clearInterval(personaTimer))

  const sweeper = setInterval(() => {
    desk.sweep()
    const now = Date.now()
    for (const [sid, pending] of [...pendingReply]) {
      if (now - pending.at < REPLY_WINDOW_MS) continue
      record({ requestId: pending.requestId, sessionId: sid, topicId: pending.topicId, state: 'none', at: now })
      if (pending.drill) {
        const b = loadBrain()
        onSilent(b, { requestId: pending.requestId })
        revise(b, now)
        saveBrain(b)
      }
      if (pending.topicId) {
        applyNoReply(garden, pending.topicId, now)
        persistGarden()
      }
      for (const x of sessionIds(sid)) pendingReply.delete(x)
    }
  }, 5_000)
  sweeper.unref?.()
  ctx.effect?.(() => () => clearInterval(sweeper))

  const rejection = (req) => {
    const connection = ctx.get?.('connection')
    return connection === undefined ? 503 : connection.requestRejection(req)
  }

  const handler = async (req, res) => {
    const denied = rejection(req)
    if (denied !== undefined) {
      res.writeHead(denied, { 'cache-control': 'no-store' })
      res.end()
      return
    }
    const url = new URL(req.url, 'http://local')
    const path = url.pathname.slice(PREFIX.length)
    try {
      if (req.method === 'POST' && (path === '/request' || path === '/cancel' || path === '/config' || path === '/plan' || path === '/outcome' || path === '/say' || path === '/event' || path === '/session' || path === '/wall' || path === '/sisters' || path === '/deposit' || path === '/workflows' || path === '/gemini/due')) {
        const body = await readJson(req)
        if (body.error) {
          return send(res, body.error, {
            v: 1,
            error: {
              code: body.error === 415 ? 'unsupported-media-type' : body.error === 413 ? 'too-large' : 'bad-request',
              message: 'body',
            },
          })
        }
        if (path === '/request' && body.value && typeof body.value === 'object') {
          // /plan 刚决定"下一球是姐姐"：把这一球送进姐姐的会话。
          if (nextSpeaker && Date.now() - nextSpeaker.at < 5 * 60_000 && !body.value.sessionId) {
            if (nextSpeaker.role === 'strategist' && config.strategistSessionId) body.value.sessionId = config.strategistSessionId
            strategistBallReason.set(String(body.value.requestId || ''), nextSpeaker)
            nextSpeaker = null
          }
        }
        if (path === '/request') {
          // Start the clock here, not in the Desk callback: the accepted callback is not the only
          // entry point and this is the one moment that is certainly before the model runs.
          const rid = String(body.value?.requestId ?? '')
          if (rid) startedAt.set(rid, Date.now())
          const r = await desk.request(body.value)
          if (r.status >= 400) {
            console.error('[companion] request refused ' + r.status + ' ' + String(r.body?.error?.code || ''))
          }
          return send(res, r.status, r.body)
        }
        if (path === '/cancel') {
          const r = desk.cancel(String(body.value?.requestId ?? ''))
          return send(res, r.status, r.body)
        }
        if (path === '/plan') return send(res, 200, planRoute(body.value))
        if (path === '/say') {
          const said = String(body.value?.text ?? '').trim()
          if (typeof body.value?.timeZone === 'string' && body.value.timeZone) sayZone = body.value.timeZone
          if (!said) return send(res, 400, { v: 1, error: { code: 'empty', message: 'text' } })
          const id = typeof body.value?.requestId === 'string' && body.value.requestId ? body.value.requestId : 'say-' + randomUUID()
          startedAt.set(id, Date.now())
          // role=strategist：这句话给姐姐（谋士会话）；默认给妹妹（陪伴会话）。
          // 没指定就回给刚才主动找他的那位（30 分钟内）：姐姐来找他，他在浮层里回的就是姐姐。
          const wanted = body.value?.role || (lastBall.role === 'strategist' && Date.now() - lastBall.at < 30 * 60_000 ? 'strategist' : 'companion')
          const toStrategist = wanted === 'strategist' && config.dual !== false && config.strategistSessionId
          // 先记"他在聊"，再进会话：手机在他发第一句时会马上问一次 /plan，那一问可能比这句话的
          // user/message 事件先到；不先记上就会再发一球，把浮层里正在聊的窗口顶掉。
          const target = toStrategist ? config.strategistSessionId : config.sessionId
          if (target) lastHumanAt.set(String(target), Date.now())
          record({ state: 'say-text', at: Date.now(), to: toStrategist ? 'strategist' : 'companion', text: said.slice(0, 300), via: 'overlay' })
          const r = await chat.request({
            v: 1, requestId: id, eventId: id, kind: 'say', payload: { text: said.slice(0, 2_000) }, deadlineMs: 120_000,
            ...(toStrategist ? { sessionId: config.strategistSessionId } : {}),
          })
          return send(res, r.status, r.body)
        }
        if (path === '/outcome') return send(res, 200, outcomeRoute(body.value))
        if (path === '/gemini/due') return send(res, 200, { v: 1, launch: dueGeminiLaunch(Date.now()) })
        if (path === '/event') return send(res, 200, eventRoute(body.value))
        if (path === '/session') return send(res, 200, sessionRoute(body.value))
        if (path === '/wall') return send(res, 200, wallRoute(body.value))
        if (path === '/sisters') return send(res, 200, sistersRoute(body.value))
        if (path === '/tracks') return send(res, 200, tracksRoute(body.value))
      if (path === '/workflows') return send(res, 200, workflowsRoute(body.value))
        if (path === '/deposit') return send(res, 200, depositRoute(body.value))
        config = mergeConfig(config, body.value)
        writeConfig(config)
        // 换了会话 / 名字 / 人设：马上重挂，不等下一次定时检查。
        try {
          mountPersona()
        } catch {
          // ignore
        }
        return send(res, 200, { v: 1, sessionId: config.sessionId ?? null, config: publicConfig() })
      }
      if (req.method === 'GET' && path === '/result') {
        const waitMs = Math.min(Math.max(Number(url.searchParams.get('waitMs') ?? 0) || 0, 0), 25_000)
        const id = String(url.searchParams.get('requestId') ?? '')
        const r = await (chat.records.has(id) ? chat : desk).wait(id, waitMs)
        // 双人格：话前面标上是谁说的（〔鱼丸〕/〔鲸澜〕），浮层据此显示名字。
        if (config.dual !== false && r?.body?.state === 'generated' && typeof r.body.text === 'string') {
          const n = roleNames(config)
          r.body = { ...r.body, speaker: speakerOf(r.body.sessionId), text: '〔' + (speakerOf(r.body.sessionId) === 'strategist' ? n.strategist : n.companion) + '〕' + r.body.text }
        }
        return send(res, r.status, r.body)
      }
      if (req.method === 'GET' && path === '/status') {
        return send(res, 200, statusBody())
      }
      return send(res, 404, { v: 1, error: { code: 'not-found', message: path } })
    } catch (e) {
      return send(res, 500, { v: 1, error: { code: 'plugin-error', message: String(e?.message ?? e) } })
    }
  }

  function publicConfig() {
    return {
      enabled: config.enabled,
      refuseUntil: Number(config.refuseUntil) > Date.now() ? Number(config.refuseUntil) : 0,
      sessionId: config.sessionId ?? null,
      quietStart: config.quietStart,
      quietEnd: config.quietEnd,
      dailyMax: config.dailyMax,
      hourlyMax: config.hourlyMax,
      minGapMs: config.minGapMs,
      diceThreshold: config.diceThreshold,
      coachMinGapMs: config.coachMinGapMs,
      coachHourlyMax: config.coachHourlyMax,
      coachDailyMax: config.coachDailyMax,
      allowColdSession: config.allowColdSession,
      owner: config.owner,
      self: config.self,
      timeZone: config.timeZone,
      // 首页的一键安静：App 直接把要静音到的时间写进来。
      muteUntil: Number(config.muteUntil) || 0,
      // 人格卡与模式：首页可以编辑，引擎只管用。
      persona: cleanPersona(config.persona),
      // 笔记库位置：设置页那一栏显示的就是它（留空 = 自动找带 .obsidian 的库）。
      notesPath: typeof config.notesPath === 'string' ? config.notesPath : '',
      // 实际在用的目录（可能来自自动查找）：设置页靠它说清"现在用的是哪个"。
      notesDir: notesDir(),
      // 实际进 prompt 的那张：关系库人物卡 + 上面的覆盖。
      personaEffective: mergePersona(readCorePersona(home()), config.persona),
      // 判球模型（没配就是 null，表示沿用会话）。
      judgeProvider: config.judgeProvider || null,
      judgeModel: config.judgeModel || null,
      // D5：破窗扣多少分（默认 15）。
      breakglassPenalty: Number(config.breakglassPenalty) || 15,
      // 最近 10 次的实测耗时：发球 / 判球 / 聊天。设置页显示中位数，一眼看出是不是模型慢。
      timings: readTimings(),
      control: config.control !== false,
      drills: config.drills === true,
      personaInChat: config.personaInChat !== false,
      dual: config.dual !== false,
      proactiveTools: config.proactiveTools !== false,
      strategistSessionId: config.strategistSessionId ?? null,
      companionName: roleNames(config).companion,
      strategistName: roleNames(config).strategist,
      companionCard: config.companionCard || '',
      strategistCard: config.strategistCard || '',
      // 大脑现在对他的判断（会随证据改）和脾气的底色。
      beliefs: Object.values(loadBrain().beliefs || {}).map((b) => b.text),
      temper: readState(stateFile('temper.json'), null),
      mode: modeOr(config.mode),
      modeUntil: Number(config.modeUntil) || 0,
    }
  }

  function planRoute(body) {
    const now = Date.now()
    const tz = zoneOr(body?.timeZone || config.timeZone)
    try {
      syncVaultRules(now)
    } catch (error) {
      // 规则.md 读坏了不影响执法：照当前规则走
      console.error('[companion] 规则.md 同步失败：' + String((error && error.message) || error))
    }
    // B 层 T1：到期的预判在这里被墙的遥测撞一遍（verdict 只有这一步能写）。
    try {
      settlePredictions(now)
    } catch (error) {
      console.error('[companion] 预判结算失败：' + String((error && error.message) || error))
    }
    // 第六轮 B：早/晚/周末到点没结论，由墙补一条缺席记录（只记账，不加锁）。
    try {
      anchorSweep(now, tz)
    } catch (error) {
      console.error('[companion] 审查锚点补记失败：' + String((error && error.message) || error))
    }
    const sessionId = typeof body?.sessionId === 'string' && body.sessionId ? body.sessionId : config.sessionId
    if (!sessionId) return { v: 1, speak: false, reason: 'no-session', topic: null, nextCheckAt: now + 6 * 3_600_000 }
    const db = openMemoryDb(memoryPath())
    let candidates = { topics: [], facts: [], episodes: [] }
    try {
      candidates = loadCandidates(db, { sessionId, now })
    } finally {
      try {
        db?.close?.()
      } catch {
        // ignore
      }
    }
    const ledger = readLedger(home(), 400)
    const recent = summarize(ledger, startOfDay(now, tz))
    const silence = silenceState(ledger, { sessionId, now })
    // 手机每次问 /plan 都带上现状：水位决定节奏和力度，大脑顺带判上一球有没有把他拉回来。
    const phone = cleanPhone(body?.phone)
    // 临时封禁名单里的 App 也算娱乐。
    if (phone?.app && blockedNow(now).includes(phone.app)) phone.fun = true
    // 第六轮 C：暗思考。他不在、没发消息也照样按客观变化整理一次；只写账本，不产生任何要发出去的话。
    // 挂在这里是因为手机闹钟问 /plan 是唯一的“后台”触发面（见 features_api.dart）：不问就不跑，和 anchorSweep 同一限制。
    try {
      const mused = museSweep(ledger, phone, now, tz)
      if (mused) record(mused)
    } catch (error) {
      console.error('[companion] 暗思考失败：' + String((error && error.message) || error))
    }
    // 第六轮 D：自建工作流到期处理。试启用 7 天没可数效果就停；转正的 30 天复核对不上同样停。
    // 和暗思考一样挂在 /plan 上——引擎里没有第二个"后台"面，不自建调度器。
    try {
      const wf = workflowSweep(config.workflows, ledger, now)
      if (wf.events.length) {
        config = { ...config, workflows: wf.next }
        try {
          writeConfig(config)
        } catch {
          // 下一轮再存：停用这件事比存盘重要，先按新状态跑
        }
        for (const ev of wf.events) {
          record({ state: 'workflow-' + ev.kind, at: ev.at, workflowId: ev.id, reason: ev.reason })
        }
      }
    } catch (error) {
      console.error('[companion] 自建工作流到期处理失败：' + String((error && error.message) || error))
    }
    const mind = sense(phone, ledger, sessionId, now)
    if (phone) lastPhone = phone
    // 作息：早上门、晚上门、深夜阶段（先算，力度和拒绝要用）。
    {
      const min = localMinute(phone, zoneMinuteOfDay(now, tz))
      const dayKey = startOfDay(now, tz)
      const reviewedToday = ledger.some((e) => e?.state === 'generated' && e.sessionId === config.strategistSessionId && e.reason === 'review' && Number(e.at) >= dayKey)
      const rh = trackRhythm(loadRhythm(), { min, day: dayKey, now, screenOn: phone?.screenOn !== false && Boolean(phone), reviewedToday, weekday: weekdayOf(now, tz) })
      saveRhythm(rh.state)
      lastRhythm = { ...rh, nightUntil: rh.night === 'lock' ? now + Math.max(1, NIGHT_LOCK_TO - min) * 60_000 : 0 }
      if (rh.openMorning) record({ state: 'gate-open', at: now, gate: 'morning' })
      if (rh.openReview) record({ state: 'gate-open', at: now, gate: 'review' })
      if (rh.openWeek) record({ state: 'gate-open', at: now, gate: 'week' })
      // 申请窗口：对账完成后随机开 15 分钟。先找今天对账是什么时候关门的（gate-closed 含 review）。
      let reviewDoneAt = 0
      for (const e of ledger) {
        if (e?.state === 'gate-closed' && Array.isArray(e.gates) && e.gates.includes('review') && Number(e.at) >= dayKey) {
          reviewDoneAt = Math.max(reviewDoneAt, Number(e.at))
        }
      }
      const aw = trackApplyWindow(config.applyWindow, { reviewDoneAt, now, tz })
      if (JSON.stringify(aw.state) !== JSON.stringify(config.applyWindow)) {
        config = { ...config, applyWindow: aw.state }
        try {
          writeConfig(config)
        } catch {
          // 下一轮再存
        }
      }
      if (aw.justOpened) record({ state: 'apply-window-open', at: now, closeAt: Number(aw.state.closeAt) })
      lastApplyWindow = aw
    }
    // 追问锁到点没裁决：结束拦截、记账扣分、挂义务（手机也会报 followup-timeout，谁先到都算）。
    expireFollowup(now)
    // 待命 10 分钟计时器：只有换出待命才重置——聊天不算动。已经欠着一笔就不再追问，只留着账。
    if (stateView(now, tz, phone).state !== 'standby') {
      if (Number(config.standbySince)) commit({ standbySince: 0 })
    } else if (standbyDue(config.standbySince, now)) {
      const owed = (config.obligations || []).some((o) => o.kind === 'standby')
      commit({ standbySince: 0 })
      if (!owed) openFollowup('standby', '这半天什么也没干。现在做哪一件？说一件，动手。', now)
    } else if (!Number(config.standbySince)) {
      commit({ standbySince: now })
    }
    // 冲刺到点 → 验收（追问锁，原因验收）。用户点的「开始」，到点由墙推进，不靠模型。
    if (Number(config.sprintUntil) > 0 && Number(config.sprintUntil) <= now) {
      commit({ sprintUntil: 0 })
      if (!config.followup || Number(config.followup.until) <= now) openFollowup('review', '这一轮到点了：做完了吗？拿完成物说话。', now)
    }
    const trust = trustScore(readLedger(home(), 800), now, { weights: trustWeights() })
    // 放风结束：看他回没回来（不在娱乐 App 里就算守约），记进信用分。
    if (Number(config.breakUntil) > 0 && Number(config.breakUntil) <= now && config.breakChecked === false) {
      config = { ...config, breakChecked: true }
      try {
        writeConfig(config)
      } catch {
        // ignore
      }
      record({ state: 'break-end', at: now, kept: !phone?.fun })
    }
    saveBrain(mind.brain)
    const water = mind.water
    const mode = effectiveMode(config, now)
    const limits = modeLimits(config, mode)
    const force = decideForce({
      phone, tide: water, mode, noTake: silence.noTake, control: config.control !== false, funToday: funToday(phone),
      // 信用分低：讲理由也不松（今天糊弄过她们）。
      grace: Number(mind.brain.graceUntil) > now && trust.band !== 'low',
      refuse: refuseUntilAt(now) > now,
    })
    seedFromMemory(garden, candidates, now)
    seedFromCommitments(garden, readCommitments(home(), now, 3), now)
    const topic = nextTopic(garden, {
      now,
      lastTopicId: recent.lastTopicId,
      lastAt: recent.lastAt,
      cooldownMs: config.cooldownMs,
      maxAttempts: MAX_ATTEMPTS,
    })
    persistGarden()
    const out = decide({
      // 教练模式更勤，伙伴更松；再按水位收紧：陷得越深越勤，间隔带随机，不让他摸出规律。
      // 在沉 / 陷住了就不掷骰子：该开口就开口。
      config: {
        ...config,
        ...limits,
        minGapMs: lastRhythm?.night === 'late' ? Math.min(8 * 60_000, trustGap(tideGap(limits.minGapMs, water.band), trust.band))
          : trustGap(tideGap(limits.minGapMs, water.band), trust.band),
        hourlyMax: tideHourly(limits.hourlyMax, water.band),
        diceThreshold: water.band === 'sink' || water.band === 'trap' ? 100
          : water.band === 'slide' ? Math.max(80, Number(config.diceThreshold) || 0) : config.diceThreshold,
      },
      now,
      tz,
      topic,
      sentToday: capCount(ledger, startOfDay(now, tz)),
      sentLastHour: sentLastHour(ledger, now),
      lastAt: recent.lastAt,
      roll: body?.roll,
      silence,
      mutedUntil: Number(config.muteUntil) || 0,
      nextTopicAt: nextTopicAt(garden, now),
      free: true,
    })
    // 他正在跟大肥鱼聊：不发主动球（发了也只会撞上"正忙"）。力度照常给手机——在游戏里边玩边聊也得锁。
    // 姐姐什么时候主动开口：妹妹递了急话，或者晚上 20–22 点（当地）一天一次对账。
    // 安静时段 / 他说了别烦我 时都不说；其余时候不受妹妹的阶梯和骰子影响（她们是两个人）。
    nextSpeaker = null
    // 档位和 Gemini 连麦：报到、忙完迎接、连麦中途跑了叫回去、连完读回来——这些比平时的发球优先。
    const gearNow = effectiveGear(config, now)
    const gem = trackGemini(loadGemini(), phone, now, geminiPackages())
    saveGemini(gem.state)
    const canTalk = out.reason !== 'muted' && out.reason !== 'quiet' && out.reason !== 'disabled'
    const thinker = config.dual !== false && config.strategistSessionId ? 'strategist' : 'companion'
    let hold = null
    noteFront(phone, now)
    // 违抗（第二轮）：保留闭环——当场升拒绝模式，但收尾的话由**墙**按模板说（锁屏页的 why），
    // 不再由鲸澜出面；姐妹照常陪聊，只是不当监工。
    if (canTalk && !gem.inside && lastRhythm?.morningGate && !lastRhythm.state.morningBall) {
      nextSpeaker = { role: thinker, reason: 'morning-gate', at: now }
    } else if (canTalk && !gem.inside && lastRhythm?.night === 'lock' && phone?.screenOn && now - (Number(lastRhythm.state.nightBallAt) || 0) >= 30 * 60_000) {
      nextSpeaker = { role: thinker, reason: 'night', at: now }
    } else if (canTalk && !gem.inside) {
      const gs = loadGearState()
      const patrol = gearNow === 'free' && config.proactiveTools !== false ? patrolSignal(phone, now, tz) : null
      if (gem.recap && config.proactiveTools !== false) {
        nextSpeaker = { role: thinker, reason: 'gemini-recap', at: now, mark: { recappedFor: Number(gem.state.inSince) },
          gemini: { minutes: Math.round((Number(gem.state.lastInAt) - Number(gem.state.inSince)) / 60_000), topic: gem.state.session?.topic } }
      } else if (gem.left) {
        nextSpeaker = { role: 'companion', reason: 'gemini-left', at: now, mark: { nagAt: now },
          topic: gem.session.topic, left: Math.max(1, Math.round((gem.session.until - now) / 60_000)), app: phone?.label || phone?.app }
      } else if (gearNow === 'free' && restlessSignal(phone, now)) {
        nextSpeaker = { role: 'companion', reason: 'restless', at: now, ...restlessSignal(phone, now) }
      } else if (gearNow === 'busy') {
        const last = Math.max(Number(config.gearSince) || 0, Number(gs.lastCheckAt) || 0)
        if (now - last >= BUSY_CHECK_MS) nextSpeaker = { role: thinker, reason: 'checkin', at: now, busy: { what: config.gearWhat, until: Number(config.gearUntil) } }
        else hold = last + BUSY_CHECK_MS
      } else if (config.gear === 'busy' && Number(config.gearUntil) <= now && now - Number(config.gearUntil) < 2 * 3_600_000 &&
        Number(gs.doneFor) !== Number(config.gearUntil) && !lastRhythm?.reviewGate) {
        // 对账门开着时不单独来验收任务：对账会一并清算（C5）。
        // 她们立的任务到点：姐姐来验收（不自动算完成）；他自己定的忙时到点：妹妹来迎接。
        nextSpeaker = config.taskBy
          ? { role: thinker, reason: 'task-due', at: now, busy: { goal: config.taskGoal, what: config.gearWhat }, digest: true }
          : { role: 'companion', reason: 'busy-done', at: now, what: config.gearWhat, digest: true,
            minutes: Math.round((Number(config.gearUntil) - Number(config.gearSince || config.gearUntil)) / 60_000) }
      } else if (patrol) {
        nextSpeaker = { role: thinker, reason: 'patrol', at: now, note: patrol }
      } else if (gearNow === 'free' && config.proactiveTools !== false && chaseSignal(phone, now, tz)) {
        nextSpeaker = { role: thinker, reason: 'notes-chase', at: now, note: chaseSignal(phone, now, tz) }
      }
    }
    if (nextSpeaker) Object.assign(out, { speak: true, reason: nextSpeaker.reason, nextCheckAt: now + 60_000 })
    else if (hold) Object.assign(out, { speak: false, reason: 'busy', nextCheckAt: Math.min(hold, Number(config.gearUntil) || hold) })
    if (!nextSpeaker && !hold && config.dual !== false && config.strategistSessionId && canTalk) {
      const handoff = pendingHandoff(now)
      const hour = phone?.local ? Number(phone.local.slice(0, 2)) : Math.floor(zoneMinuteOfDay(now, tz) / 60)
      const todayStart = startOfDay(now, tz)
      const reviewedToday = ledger.some((e) => e?.state === 'generated' && e.sessionId === config.strategistSessionId && Number(e.at) >= todayStart)
      if (handoff) nextSpeaker = { role: 'strategist', reason: 'handoff', note: handoff, at: now }
      // 晚上对账：21:30 开门（rhythm.js），门开着、还没来过就来。
      else if (lastRhythm?.reviewGate && !lastRhythm.state.reviewBall && !reviewedToday) nextSpeaker = { role: 'strategist', reason: 'review', at: now }
      // 申请窗口刚开：鲸澜来一句，告诉他现在能提明天的放松/收紧（主持修改申请窗口是鲸澜的事）。
      else if (lastApplyWindow?.justOpened) {
        nextSpeaker = { role: 'strategist', reason: 'apply-window', at: now, note: { applyWindow: { remaining: relaxRemainingNow(now, tz), closeAt: Number(lastApplyWindow.state?.closeAt) || 0 } } }
      }
      if (nextSpeaker) Object.assign(out, { speak: true, reason: 'strategist-' + nextSpeaker.reason, nextCheckAt: now })
    }
    // 他在 Gemini 里连麦：谁都不插嘴；两分钟看一次，好知道他什么时候出来（出来了要读回那段对话）。
    if (gem.inside) {
      nextSpeaker = null
      Object.assign(out, { speak: false, reason: 'in-gemini', nextCheckAt: now + 2 * 60_000 })
    }
    // 有一球正在路上（接下了、还没生成完）：再说开口就会连发两球（手机每次切 App 都会问一次）。
    desk.sweep()
    if (out.speak && desk.inFlight()) {
      Object.assign(out, { speak: false, reason: 'in-flight', nextCheckAt: now + 60_000 })
    }
    if (out.speak && inConversation(sessionId, now)) {
      Object.assign(out, { speak: false, reason: 'in-conversation', nextCheckAt: now + IN_CONVERSATION_MS })
    }
    // 一早第一球：今天还没开过口、屏幕亮着——不等骰子和间隔，先开球定今天的锚。
    if (!out.speak && (out.reason === 'dice' || out.reason === 'cooldown') && recent.counts.generated === 0 && phone?.screenOn && !inConversation(sessionId, now) && gearNow !== 'busy') {
      Object.assign(out, { speak: true, reason: 'morning', nextCheckAt: now })
    }
    // 待命计时器在走：到 10 分钟要回来开锁，别等下一次定期检查。他喊了安静、或者已经三球没接要退避 3 小时，都不抢。
    const backoff = out.reason === 'muted' || out.reason === 'quiet' || out.reason === 'disabled' || out.reason === 'no-take'
    if (Number(config.standbySince) && !backoff) out.nextCheckAt = Math.min(Number(out.nextCheckAt) || Infinity, Number(config.standbySince) + STANDBY_LOCK_MS)
    // 力度到了"锁 / 赶"：不管这一球说不说，手机都要执行（说到做到）。
    if (force.level >= 3) out.nextCheckAt = Math.min(Number(out.nextCheckAt) || now, now + 60_000)
    // R4-6 走神强拉：忙时他在娱乐 App 里坐着，直接约一场审讯式连麦（走 R4-4 那条路，手机到点自己拉起）。
    // 这一条比场面上的球要紧：拉起了就不说话，等他在 Gemini 里交代。
    const drift = config.control !== false && canTalk && !gem.inside ? driftPullNow(now, phone, tz) : null
    if (drift) Object.assign(out, { speak: false, reason: 'gemini-drift' })
    if (!out.speak) nextSpeaker = null
    return {
      v: 1, ...out, silence, sessionId, speaker: nextSpeaker?.role === 'strategist' ? 'strategist' : 'companion',
      tide: { level: water.level, band: water.band, why: water.why },
      // release：只有他讲通了（宽限期内）才放行大肥鱼加的锁。力度掉到 3 以下不等于放行——
      // 锁屏页一弹，前台就是 DSH 自己；赶回桌面后前台是桌面，这时力度本来就是 0。
      force: { level: force.level, name: force.name, why: force.why, app: phone?.app || undefined,
        release: onBreak(now) || (!force.refuse && trust.band !== 'low' && Number(mind.brain.graceUntil) > now) },
      trust: { score: trust.score, band: trust.band },
      // 临时封禁名单：手机把这些也当娱乐 App 管。
      block: blockedNow(now),
      // 通知防波堤：忙时 / 任务模式期间，非白名单通知收进缓存（手机端 NotificationShield）。
      shield: effectiveGear(config, now) === 'busy' || aiTask(config, now) ? { until: Math.max(Number(config.gearUntil) || 0, now + ROLL_MS) } : null,
      // 拒绝模式：手机记住到期时间，自己在每次切到娱乐 App 的那一刻就锁，不等这一问。
      refuse: refuseUntilAt(now) > now ? { until: refuseUntilAt(now) } : null,
      gear: effectiveGear(config, now),
      // Gemini 互动舱：这段时间只许 Gemini（allow 里的包名），其余 App 由手机直接锁。
      session: geminiSessionView(now),
      // D4：预约的下一场连麦（手机据此 arm 闹钟；到点调 /gemini/due 取 launch）。
      geminiSchedule: (() => {
        const s = loadGeminiSchedule()
        return s.at && !s.fired && Number(s.at) > now ? { at: Number(s.at), topic: String(s.topic || ''), minutes: Number(s.minutes) || 30 } : null
      })(),
      // 本地规则快照（C2）：手机存下来，引擎不可达时照这份执法。
      rules: rulesSnapshot(now, tz),
      // 全天状态机（C2/C3）：这一刻只有一个主状态，追问锁是叠在上面的临时层。
      state: stateView(now, tz, phone),
      followup: followupView(config.followup, now),
      obligations: obligationsView(config.obligations, now),
      // 第二轮·墙：此刻娱乐锁/开、申请窗口开没开、本周还剩几天放松额度（面板和手机据此显示）。
      wall: (() => {
        const wd = wallDecision(config, now, tz)
        return {
          locked: wd.locked, kind: wd.kind || null, openUntil: wd.openUntil || 0,
          applyWindowOpen: applyWindowOpen(config.applyWindow, now),
          applyWindowCloseAt: Number(config.applyWindow?.closeAt) || 0,
          relaxRemaining: relaxRemainingNow(now, tz),
          depositMissing: statusOf(depositToday(now)).missing.join('、'),
          relaxToday: Boolean(activeRelax(config, now, tz)),
          tightenToday: tightenActive(config, now, tz),
        }
      })(),
    }
  }

  function outcomeRoute(body) {
    const requestId = typeof body?.requestId === 'string' ? body.requestId : ''
    const stage = String(body?.stage || '')
    const allowed = new Set(['delivered', 'displayed', 'replied', 'substantive', 'dismissed'])
    if (!requestId || !allowed.has(stage)) {
      return { v: 1, error: { code: 'bad-outcome', message: 'requestId/stage' } }
    }
    record({
      requestId,
      state: stage,
      at: Number(body?.at) || Date.now(),
      replyClass: typeof body?.replyClass === 'string' ? body.replyClass.slice(0, 32) : undefined,
    })
    return { v: 1, ok: true }
  }

  function statusBody() {
    const agents = ctx.get?.('agents')
    const live = (agents?.roots?.() ?? agents?.list?.() ?? []).map((a) => ({ id: a.id, status: a.status }))
    const now = Date.now()
    const entries = readLedger(home(), 400)
    const today = summarize(entries, startOfDay(now, config.timeZone))
    return {
      v: 1,
      version: '0.2.0',
      config: publicConfig(),
      // 首页直接读这两项（不必再拆 config）：判球模型和最近 10 次的实测耗时。
      judgeProvider: config.judgeProvider || null,
      judgeModel: config.judgeModel || null,
      // D5：破窗扣多少分（默认 15）。
      breakglassPenalty: Number(config.breakglassPenalty) || 15,
      timings: readTimings(),
      sessionId: config.sessionId ?? null,
      agents: live,
      counters: desk.counters,
      today: today.counts,
      // 第六轮 B：今天三次审查各自的最后一条结论（null = 还没审，也不会有"审过了却查不到"）。
      anchors: anchorStatus(now, entries),
      // 第六轮 A：支线底座的状态（几条在跑、最近用过哪几条、哪几条配置不合格被丢掉了）。
      tracks: trackStatus(config.tracks, recentTracks(entries)),
      // 第六轮 C：今天暗思考跑了几次、最近一条整理了什么（只记账，没有要发出去的话）。
      muse: museStatus(entries, now),
      // 第六轮 D：自建工作流的配额与到期时间（几条在跑、还能提几条）。
      workflows: workflowStatus(config.workflows, entries, now, config.timeZone),
      // 好坏只看回合：有效回合 = 至少两拍且有一拍接住了（见 rally.js）。
      rallies: rallyStats(entries, startOfDay(now, config.timeZone)),
      // 「现在」这一层要能说清：上一球被漠视到哪一级、静音到什么时候。
      silence: silenceState(entries, { sessionId: config.sessionId, now }),
      lastTopicId: today.lastTopicId || null,
      memory: { path: memoryPath(), exists: existsSync(memoryPath()) },
      // 第五轮·每日四部门提交：今天交了几段、还缺哪几段（缺了就当天没有放风额度）。
      deposit: (() => {
        const d = depositToday(now)
        return { ...statusOf(d), missingText: missingText(d) }
      })(),
      // 应用管理页的「Gemini 互动」：进行中的连麦舱（null = 没开）。
      gemini: { session: geminiSessionView(now) },
      garden: gardenStats(garden, now),
      gardenTopics: garden.topics
        .filter((t) => t.status !== 'done')
        .slice(0, 8)
        .map(publicTopic),
      recent: readLedger(home(), 20),
    }
  }

  // ---------------------------------------------------------------- 「鱼丸 · 鲸澜」面板 + 家庭群
  /** 打开着的面板（按会话）：她们回话时刷新。 */
  const panelScopes = new Map()
  let panelApi = null
  function refreshPanels() {
    if (!panelApi) return
    for (const scope of panelScopes.values()) {
      try {
        panelApi.refresh('sisters', scope)
      } catch {
        // 面板关了
      }
    }
  }
  /** 最近一次手机现状（/plan 带来的）：面板的"专注"分要用。 */
  let lastPhone = null
  /**
   * R4-5 姐妹界面：手机上的原生页（两张角色卡 + 一块灰色的今日审计面板）。
   *
   * 只读。这一页刻意没有任何能放松规则的入口——放松只能走晚上对账后的申请窗口。
   * 数据不重算：记得的事、最近对话、墙此刻锁没锁都来自 panelSnapshot()，
   * 这里只补它没有的两块（角色卡头部、今日审计 + 近 14 天完成率）。
   */
  /**
   * 第六轮 A：支线的一页配置。
   * GET 形态（不带 tracks）只看状态；带了 tracks 数组就是整页替换。
   * 校验不过的条目不会静默丢掉——原样报回去，哪一项缺哪个槽位说清楚。
   */
  function workflowsRoute(body) {
    const now = Date.now()
    const tz = zoneOr(body?.timeZone || config.timeZone)
    const entries = readLedger(home(), 300)
    const read = () => ({ v: 1, ok: true, workflows: workflowStatus(config.workflows, entries, now, tz) })
    const save = () => {
      try {
        writeConfig(config)
      } catch {
        return false
      }
      return true
    }
    const action = typeof body?.action === 'string' ? body.action : ''
    // 不带 action = 只读（面板用）。
    if (!action) return read()

    if (action === 'propose') {
      const r = workflowPropose(config.workflows, entries, body?.workflow || body, now, tz)
      if (!r.ok) {
        // 被拒的提议也留痕：拒了几次、拒的是什么原因，验收要查得到。
        record({ state: 'workflow-refused', at: now, code: r.code, missing: r.missing, rejected: r.rejected })
        return { v: 1, ok: false, error: { code: r.code, missing: r.missing, rejected: r.rejected, quota: r.quota } }
      }
      config = { ...config, workflows: [...(config.workflows || []), r.workflow] }
      if (!save()) return { v: 1, ok: false, error: { code: 'save-failed' } }
      record({ state: 'workflow-proposed', at: now, workflowId: r.workflow.id, problem: r.workflow.problem, metric: r.workflow.metric })
      return { ...read(), ok: true, workflow: r.workflow }
    }

    if (action === 'trial' || action === 'stop') {
      const id = typeof body?.id === 'string' ? body.id : ''
      const r = workflowAct(config.workflows, id, action, now)
      if (!r.ok) return { v: 1, ok: false, error: { code: r.code, state: r.state } }
      config = { ...config, workflows: r.workflows }
      if (!save()) return { v: 1, ok: false, error: { code: 'save-failed' } }
      record({ state: 'workflow-' + action, at: now, workflowId: id })
      return { ...read(), ok: true, workflow: r.workflow }
    }

    if (action === 'effect') {
      // 可数效果只由账本记：count 必须 > 0，空转不算。
      const id = typeof body?.id === 'string' ? body.id : ''
      const count = Math.round(Number(body?.count) || 0)
      const known = (config.workflows || []).some((w) => w?.id === id)
      if (!known) return { v: 1, ok: false, error: { code: 'not-found' } }
      if (count <= 0) return { v: 1, ok: false, error: { code: 'no-count' } }
      record({ state: 'workflow-effect', at: now, workflowId: id, count })
      return { ...read(), ok: true }
    }

    return { v: 1, ok: false, error: { code: 'bad-action', action: action.slice(0, 40) } }
  }

  function tracksRoute(body) {
    const now = Date.now()
    const entries = readLedger(home(), 200)
    if (Array.isArray(body?.tracks)) {
      const before = normalizeTracks(config.tracks)
      const after = normalizeTracks(body.tracks)
      // 一条都没合格又确实给了东西：十成是写错了，别把现有配置清空。
      if (!after.tracks.length && Array.isArray(body.tracks) && body.tracks.length > 0) {
        return { v: 1, ok: false, error: { code: 'no-valid-track', message: '四条槽位缺一条就整条不生效', dropped: after.dropped } }
      }
      config = { ...config, tracks: body.tracks }
      try {
        writeConfig(config)
      } catch {
        // 这一轮照样生效
      }
      record({ state: 'tracks-set', at: now, from: before.tracks.length, to: after.tracks.length })
      return { v: 1, ok: true, ...trackStatus(config.tracks, recentTracks(entries)) }
    }
    return { v: 1, ok: true, ...trackStatus(config.tracks, recentTracks(entries)) }
  }

  function sistersRoute(body) {
    const now = Date.now()
    const tz = zoneOr(body?.timeZone || config.timeZone)
    const today = dayString(now, tz)
    const snap = panelSnapshot()
    const dir = notesDir()
    let auditText = ''
    if (dir) {
      try {
        auditText = readVaultText(join(dir, WALL_DIR, AUDIT_DIR, today + '.md')) || ''
      } catch {
        // 今天还没审过：没有原话，面板只说计数
      }
    }
    return {
      v: 1,
      ok: true,
      today,
      names: snap.names,
      cards: cardsOf(snap.names),
      memory: Array.isArray(snap.memory) ? snap.memory : [],
      timeline: Array.isArray(snap.timeline) ? snap.timeline : [],
      wall: snap.wall || null,
      audit: auditOf({ memory: loadWallMem(), auditText, today }),
    }
  }
  function panelSnapshot() {
    const now = Date.now()
    const entries = readLedger(home(), 600)
    const todayStart = startOfDay(now, config.timeZone)
    let facts = []
    const db = openMemoryDb(memoryPath())
    try {
      facts = backgroundFacts(loadCandidates(db, { sessionId: config.sessionId, now }), 6)
    } catch {
      // 记忆读不到就空着
    } finally {
      try {
        db?.close?.()
      } catch {
        // ignore
      }
    }
    return buildSnapshot({
      entries,
      todayEntries: entries.filter((e) => Number(e?.at) >= todayStart),
      trust: trustScore(entries, now, { weights: trustWeights() }),
      phone: lastPhone,
      names: roleNames(config),
      // 面板上的墙：此刻锁/开、本周剩余额度、今天有没有修改。
      wall: (() => {
        const tz = zoneOr(config.timeZone)
        const wd = wallDecision(config, now, tz)
        return {
          locked: wd.locked, kind: wd.kind || null, openUntil: wd.openUntil || 0,
          applyWindowOpen: applyWindowOpen(config.applyWindow, now),
          relaxRemaining: relaxRemainingNow(now, tz),
          depositMissing: statusOf(depositToday(now)).missing.join('、'),
          relaxToday: Boolean(activeRelax(config, now, tz)),
          tightenToday: tightenActive(config, now, tz),
          weeklyQuota: wallConfig(config).weeklyQuota,
        }
      })(),
      speakerOf,
      config,
      facts,
      commitments: readCommitments(home(), now, 3),
      gemini: loadGemini(),
      notesDir: notesDir(),
      now,
    })
  }
  /** 一句话发进去，等到回完（最多两分钟）。 */
  async function sayTo(sid, text) {
    const id = 'say-' + randomUUID().replace(/-/g, '').slice(0, 16)
    lastHumanAt.set(String(sid), Date.now())
    const r = await chat.request({ v: 1, requestId: id, eventId: id, kind: 'say', payload: { text: String(text).slice(0, 2_000) }, deadlineMs: 120_000, sessionId: sid })
    if (r.status >= 400) return null
    for (let i = 0; i < 6; i += 1) {
      const w = await chat.wait(id, 25_000)
      const st = w?.body?.state
      if (st === 'generated') return String(w.body.text || '')
      if (st === 'failed' || st === 'cancelled') return null
    }
    return null
  }
  /**
   * 家庭群：发给一个人，或者一起——鱼丸先回，鲸澜随后收到她的原话接着说。
   * 两人拿到的开场是同一句，只多一条聊天记录；不给模型发导演提示（见 sisters.js groupPrompts）。
   * 在后台跑，面板先返回，回复到了自动刷新。
   */
  function groupSay(text, to) {
    const names = roleNames(config)
    const said = String(text || '').trim().slice(0, 1_000)
    if (!said) return '说点什么再发。'
    const sis = config.sessionId
    const big = config.dual !== false ? config.strategistSessionId : ''
    record({ state: 'say-text', at: Date.now(), to, text: said.slice(0, 300), via: 'panel' })
    if (to === 'strategist' && !big) return '还没设谋士会话。'
    if (to === 'companion' || (to === 'both' && !big)) {
      if (!sis) return '还没设陪伴会话。'
      sayTo(sis, said).catch(() => {})
      return '发给' + names.companion + '了。'
    }
    if (to === 'strategist') {
      sayTo(big, said).catch(() => {})
      return '发给' + names.strategist + '了。'
    }
    ;(async () => {
      // 只配了一个会话时，缺的那个不往空 sessionId 发请求。
      const first = sis ? await sayTo(sis, groupPrompts({ names, said })) : null
      await sayTo(big, groupPrompts({ names, said, first, firstName: names.companion }))
      refreshPanels()
    })().catch(() => {})
    return '发到群里了：' + names.companion + '先回，' + names.strategist + '接着说。'
  }
  if (typeof ctx.inject === 'function') {
    try {
      ctx.inject(['dshGo'], (dctx) => {
        const d = dctx?.dshGo
        if (!d || typeof d.panel !== 'function') return
        panelApi = d
        const keyOf = (scope) => String((scope && scope.sessionId) || '')
        const values = (event) => {
          const v = event && event.value ? event.value.values || event.value : {}
          return v && typeof v === 'object' ? v : {}
        }
        if (typeof d.on === 'function') d.on('sisters-open', () => ({ open: 'panel:sisters' }))
        d.panel('sisters', {
          view: sistersView,
          data: (scope, emit) => {
            panelScopes.set(keyOf(scope), scope)
            emit(panelSnapshot())
            return () => panelScopes.delete(keyOf(scope))
          },
          actions: {
            'sisters.refresh': (scope) => {
              d.refresh('sisters', scope)
              return { toast: '' }
            },
            'group.say': (scope, event) => {
              const v = values(event)
              const toast = groupSay(v['data.form.text'], ['companion', 'strategist', 'both'].includes(v['data.form.to']) ? v['data.form.to'] : 'both')
              d.refresh('sisters', scope)
              return { toast }
            },
            // 面板只能收紧，不能放松：没有"关拒绝模式""结束任务"的按钮。
            'sisters.busy': (scope) => {
              setGear({ gear: 'busy', until: Date.now() + BUSY_DEFAULT_MIN * 60_000, what: '正事' }, 'panel')
              d.refresh('sisters', scope)
              return { toast: '忙时 ' + BUSY_DEFAULT_MIN + ' 分钟：不闲聊、定时报到、娱乐一开就锁。' }
            },
            'sisters.refuse': (scope) => {
              const until = Math.max(Date.now() + 30 * 60_000, Number(config.refuseUntil) || 0)
              config = { ...config, refuseUntil: until, refuseBy: config.refuseBy || 'panel' }
              try {
                writeConfig(config)
              } catch {
                // 这一轮照样生效
              }
              record({ state: 'control', at: Date.now(), action: 'refuse', minutes: 30, by: 'panel' })
              d.refresh('sisters', scope)
              return { toast: '拒绝模式 30 分钟。' }
            },
          },
        })
      })
    } catch (error) {
      console.error('[companion] 面板注册失败：' + String((error && error.message) || error))
    }
  }

  ctx.inject(['webServer'], (webCtx) => {
    webCtx.effect(
      () => webCtx.webServer.register({ kind: 'prefix', path: PREFIX, handler }),
      'dsh-go-companion: routes',
    )
    console.error('[companion] routes ' + PREFIX)
  })
}

function mergeConfig(current, body) {
  const next = { ...current }
  if (body && typeof body === 'object') {
    if (body.sessionId === null) next.sessionId = undefined
    else if (typeof body.sessionId === 'string' && body.sessionId.length <= 256) next.sessionId = body.sessionId
    if (typeof body.enabled === 'boolean') next.enabled = body.enabled
    for (const key of ['quietStart', 'quietEnd']) {
      if (Number.isFinite(Number(body[key]))) next[key] = Math.max(0, Math.min(23, Math.round(Number(body[key]))))
    }
    if (Number.isFinite(Number(body.dailyMax))) next.dailyMax = Math.max(0, Math.min(48, Math.round(Number(body.dailyMax))))
    if (Number.isFinite(Number(body.hourlyMax))) next.hourlyMax = Math.max(0, Math.min(12, Math.round(Number(body.hourlyMax))))
    // 一键安静：给 0 或过去的时间就是解除。
    if (Number.isFinite(Number(body.muteUntil))) next.muteUntil = Math.max(0, Number(body.muteUntil))
    if (body.persona && typeof body.persona === 'object') next.persona = cleanPersona(body.persona)
    if (typeof body.mode === 'string') next.mode = modeOr(body.mode)
    if (Number.isFinite(Number(body.modeUntil))) next.modeUntil = Math.max(0, Number(body.modeUntil))
    if (Number.isFinite(Number(body.refuseUntil))) next.refuseUntil = Math.max(0, Number(body.refuseUntil))
    if (typeof body.notesPath === 'string') next.notesPath = body.notesPath.trim().slice(0, 300)
    if (Number.isFinite(Number(body.coachMinGapMs))) next.coachMinGapMs = Math.max(60_000, Math.min(6 * 3_600_000, Number(body.coachMinGapMs)))
    if (Number.isFinite(Number(body.coachHourlyMax))) next.coachHourlyMax = Math.max(0, Math.min(20, Math.round(Number(body.coachHourlyMax))))
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
    if (body.strategistSessionId === null) next.strategistSessionId = undefined
    else if (typeof body.strategistSessionId === 'string' && body.strategistSessionId.length <= 256) next.strategistSessionId = body.strategistSessionId || undefined
    for (const k of ['companionName', 'strategistName']) if (typeof body[k] === 'string' && body[k].trim()) next[k] = body[k].trim().slice(0, 20)
    for (const k of ['companionCard', 'strategistCard']) if (typeof body[k] === 'string') next[k] = body[k].slice(0, 4000)
    if (typeof body.owner === 'string' && body.owner.trim()) next.owner = body.owner.trim().slice(0, 40)
    if (typeof body.self === 'string' && body.self.trim()) next.self = body.self.trim().slice(0, 40)
    if (typeof body.timeZone === 'string' && body.timeZone.trim()) next.timeZone = zoneOr(body.timeZone)
    // 判球模型：空字符串 = 清掉，回到沿用会话。
    if (typeof body.judgeProvider === 'string') next.judgeProvider = body.judgeProvider.trim().slice(0, 80) || undefined
    if (typeof body.judgeModel === 'string') next.judgeModel = body.judgeModel.trim().slice(0, 120) || undefined
    if (Number.isFinite(Number(body.breakglassPenalty))) next.breakglassPenalty = Math.max(0, Math.min(100, Math.round(Number(body.breakglassPenalty))))
  }
  return next
}
