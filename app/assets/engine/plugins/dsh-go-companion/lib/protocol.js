// dsh-go-companion · 协议格式与解释器（第八轮 J2）
//
// 协议描述「一种局怎么走」，引擎装一次，以后新局只是导入一个协议文件。
// 正式格式是 JSON（施工书写「YAML（或 JSON）」；本插件没有 yaml 依赖，也不许为这点事加依赖，
// 所以只认 JSON——上面那两句就是为什么 YAML 会被明确拒掉，而不是悄悄当成 JSON 解析）。
//
// 每一步只有四样：怎么触发、限时多久、什么算过关、不过或超时去哪。
//
// 四条硬性要求（施工书 §3 J2）：
// 1. 每步必须有有限时和出口；加载时做可终止性检查，存在走不出去的路径就拒绝加载。
// 2. 用了能力库里没有的能力名 → 拒绝加载，并生成一条「缺能力」提议。
// 3. 不能声明任何墙的能力（锁、额度、信用分、规则、立场结算）；协议只能调「升级」这一个出口。
// 4. 引擎内只有一个局处于进行中——由 J1 的 bouts_one_running 唯一索引保证，runner 开局也要过它。

import { WALL_CAPABILITIES, capabilityOf, hasCapability, isWallCapability, measure, missingCapabilityProposal } from './capabilities.js'

export const PROTOCOL_VERSION = 1

/** 收局/升级这两个出口是终结的；协议不能自定义别的终点。 */
export const TERMINALS = ['收局', '升级']
export const EXIT_KINDS = ['next', 'retry', 'escalate', 'close']

const TOP_KEYS = new Set(['协议版本', 'id', '名称', '类型', '需要能力', '预算', '步骤', '收局'])
const STEP_KEYS = new Set(['id', '问', '限时', '过关', '超时', '不过', '触发', '每', '形态', '应答', '监视', '变速', '重复至'])
const BUDGET_KEYS = new Set(['分钟', '来回'])
const CLOSE_KEYS = new Set(['交', '判定'])
export const PROTOCOL_TYPES = ['行为', '思维']

/** 过关条件：名字 → 期望值怎么比。表里没有的名字在加载时就是错误（防写错条件名）。 */
const CONDITIONS = {
  行数: (input) => String(input.text || '').split('\n').filter((l) => l.trim()).length,
  每行最多字: (input) => Math.max(0, ...String(input.text || '').split('\n').map((l) => l.trim().length)),
  字数: (input) => measure('字数', input),
  具体名词数: (input) => measure('具体名词数', input),
  有无结论句: (input) => measure('有无结论句', input),
  问句数: (input) => measure('问句数', input),
  填充词比例: (input) => measure('填充词比例', input),
  应答延迟: (input) => measure('应答延迟', input),
  与他近5条的重合率: (input) => measure('与他近5条的重合率', input),
  // 返回「是否与上一条重复」；期望值 true 表示要求「重复」不成立，比较时反向。
  不与前次重复: (input) => {
    const norm = (s) => String(s || '').replace(/\s+/g, '')
    return (input.recent || []).slice(0, 1).some((r) => norm(r) === norm(input.text))
  },
}

export function conditionNames() {
  return Object.keys(CONDITIONS)
}

/**
 * 格式表的机器可读镜像。lib/protocol.schema.json 必须与这里一致，
 * 测试拿两边对全集——schema 是文档，漂了就没人信它了。
 */
export function formatKeys() {
  return {
    top: [...TOP_KEYS],
    step: [...STEP_KEYS],
    budget: [...BUDGET_KEYS],
    close: [...CLOSE_KEYS],
    conditions: conditionNames(),
  }
}

/** 90s / 6m / 1h / 45（秒）→ 秒。不认识返回 null。 */
export function parseDuration(value) {
  if (typeof value === 'number') return Number.isFinite(value) && value > 0 ? value : null
  const text = String(value || '').trim()
  const m = /^(\d+)\s*(s|m|h)?$/i.exec(text)
  if (!m) return null
  const n = Number(m[1])
  if (!n) return null
  const unit = (m[2] || 's').toLowerCase()
  return unit === 'h' ? n * 3600 : unit === 'm' ? n * 60 : n
}

/**
 * 「重复至」：周期步重复到预算剩多少为止。只认 1/4、1/3、一半 → 比例。
 * 写别的在加载期就拒绝：收尾时机靠猜的话，学习局（剩 1/4 列点）和
 * 执行局（剩 1/3 交什么）会错位。
 */
const REPEAT_UNTIL = { '1/4': 0.25, '1/3': 1 / 3, '一半': 0.5 }

export function parseRepeatUntil(value) {
  if (value === undefined || value === null) return undefined
  return Object.hasOwn(REPEAT_UNTIL, String(value).trim()) ? REPEAT_UNTIL[String(value).trim()] : null
}

/**
 * 出口归一化。
 * 收局 / 升级 是终结；下一步 / 跳步 走下一拍；重打 是原地重来（有 限时 兜底）。
 * 二选一 是监视里的出口，形状见 normalizeMonitor。
 */
export function normalizeExit(value) {
  const text = String(value == null ? '' : value).trim()
  if (!text) return null
  if (text === '收局') return { kind: 'close' }
  if (text === '升级') return { kind: 'escalate' }
  if (text === '重打') return { kind: 'retry' }
  if (text === '下一步' || text === '继续' || text === '跳步' || text === '跳步并记账') return { kind: 'next' }
  return null
}

function isPlainObject(v) {
  return Boolean(v) && typeof v === 'object' && !Array.isArray(v)
}

function unknownKeys(obj, allowed) {
  return Object.keys(obj).filter((k) => !allowed.has(k))
}

/** 把协议里出现的能力名全收出来（需要能力 + 每步的触发/形态/应答/变速）。 */
function collectCapabilities(raw) {
  const names = []
  const push = (v) => {
    if (Array.isArray(v)) for (const x of v) push(x)
    else if (typeof v === 'string' && v.trim()) names.push(v.trim())
  }
  push(raw['需要能力'])
  for (const step of Array.isArray(raw['步骤']) ? raw['步骤'] : []) {
    if (!isPlainObject(step)) continue
    push(step['触发'])
    push(step['形态'])
    push(step['应答'])
    push(step['变速'])
  }
  return [...new Set(names)]
}

/** 协议里任何地方出现墙的能力名都算越权。 */
function collectWallNames(raw) {
  const found = new Set()
  const walk = (node) => {
    if (typeof node === 'string') {
      if (isWallCapability(node.trim())) found.add(node.trim())
      return
    }
    if (Array.isArray(node)) {
      for (const x of node) walk(x)
      return
    }
    if (isPlainObject(node)) {
      for (const [k, v] of Object.entries(node)) {
        if (isWallCapability(k)) found.add(k)
        walk(v)
      }
    }
  }
  walk(raw)
  return [...found]
}

/**
 * 校验一个已经 JSON.parse 过的协议对象。不抛错，返回错误数组。
 * @returns {{ok:true, protocol:object}|{ok:false, errors:{code:string,where:string,message:string}[], proposal?:object}}
 */
export function validateProtocol(raw) {
  const errors = []
  const err = (code, where, message) => errors.push({ code, where, message })

  if (!isPlainObject(raw)) {
    err('not-object', '', '协议必须是 JSON 对象')
    return { ok: false, errors }
  }
  const version = Number(raw['协议版本'])
  if (!Number.isInteger(version) || version !== PROTOCOL_VERSION) {
    err('bad-version', '协议版本', '只认 ' + PROTOCOL_VERSION)
  }
  for (const key of unknownKeys(raw, TOP_KEYS)) err('unknown-key', key, '协议里不认识的字段：' + key)
  const id = String(raw.id || '').trim()
  if (!/^[a-z0-9][a-z0-9-]*$/.test(id)) err('bad-id', 'id', 'id 只能是小写字母、数字、连字符')
  if (!String(raw['名称'] || '').trim()) err('no-name', '名称', '缺名称')
  if (!PROTOCOL_TYPES.includes(String(raw['类型'] || ''))) {
    err('bad-type', '类型', '类型只能是 ' + PROTOCOL_TYPES.join(' / '))
  }

  // 预算
  if (raw['预算'] !== undefined) {
    if (!isPlainObject(raw['预算'])) err('bad-budget', '预算', '预算必须是对象')
    else {
      for (const key of unknownKeys(raw['预算'], BUDGET_KEYS)) err('unknown-key', '预算.' + key, '预算里不认识的字段：' + key)
      for (const key of ['分钟', '来回']) {
        if (raw['预算'][key] !== undefined && (!Number.isFinite(Number(raw['预算'][key])) || Number(raw['预算'][key]) <= 0)) {
          err('bad-budget', '预算.' + key, key + ' 必须是正数')
        }
      }
    }
  }

  // 越权：墙的能力
  const wall = collectWallNames(raw)
  if (wall.length) {
    err('wall-capability', '需要能力', '协议不能声明墙的能力：' + wall.join('、') +
      '（升级和锁由引擎按 P4 执行，协议只能调「升级」这一个出口）')
  }

  // 能力名
  const used = collectCapabilities(raw)
  const missing = used.filter((n) => !hasCapability(n) && !isWallCapability(n))
  if (missing.length) {
    err('missing-capability', '需要能力', '能力库没有：' + missing.join('、'))
  }

  // 步骤
  const steps = raw['步骤']
  if (!Array.isArray(steps) || !steps.length) {
    err('no-steps', '步骤', '至少要一步')
    return { ok: false, errors, ...(missing.length ? { proposal: missingCapabilityProposal(missing, id) } : {}) }
  }
  const seen = new Set()
  steps.forEach((step, i) => {
    const at = '步骤[' + i + ']'
    if (!isPlainObject(step)) {
      err('bad-step', at, '步骤必须是对象')
      return
    }
    for (const key of unknownKeys(step, STEP_KEYS)) err('unknown-key', at + '.' + key, '步骤里不认识的字段：' + key)
    const sid = String(step.id || '').trim()
    if (!sid) err('no-step-id', at + '.id', '每一步都要 id')
    else if (seen.has(sid)) err('dup-step-id', at + '.id', '步骤 id 重复：' + sid)
    else seen.add(sid)

    // 限时：每步都必须有。周期步用「每」当限时——施工书 §3 J2 的示例就是这么写的
    // （watch 那一步只有 每: 6m，没有 限时），照文档抄的协议不该加载失败。
    const limit = parseDuration(step['限时']) ?? parseDuration(step['每'])
    if (limit === null) err('no-limit', at + '.限时', '每一步都必须有限时（限时: 90s，或周期步写 每: 6m）')

    // 重复至：只有周期步（写了 每）才谈得上重复到预算剩多少；比例还得是认得的那个。
    const repeatUntil = parseRepeatUntil(step['重复至'])
    if (repeatUntil === null) {
      err('bad-repeat', at + '.重复至', '重复至只认 1/4 / 1/3 / 一半')
    } else if (repeatUntil !== undefined && step['每'] === undefined) {
      err('bad-repeat', at + '.重复至', '重复至只对周期步有意义：这一步没有写 每')
    }

    // 出口：过关走下一拍，超时与不过必须有一个明确的去处
    if (step['过关'] !== undefined) {
      if (!isPlainObject(step['过关'])) err('bad-pass', at + '.过关', '过关必须是对象')
      else {
        for (const key of Object.keys(step['过关'])) {
          if (!Object.hasOwn(CONDITIONS, key)) err('unknown-condition', at + '.过关.' + key, '不认识的过关条件：' + key)
        }
      }
    } else if (!String(step['问'] || '').trim()) {
      err('no-ask', at + '.问', '没有过关条件的步骤必须写明问什么')
    }
    if (step['问'] !== undefined && !String(step['问']).trim()) err('no-ask', at + '.问', '问不能是空的')

    const timeout = normalizeExit(step['超时'])
    const fail = normalizeExit(step['不过'])
    // 只在「写了出口但认不出来」时报错；没写出口由下面的可终止性检查管。
    if (step['超时'] !== undefined && timeout === null) {
      err('bad-exit', at + '.超时', '不认识的出口：' + String(step['超时']))
    }
    if (step['不过'] !== undefined && fail === null) {
      err('bad-exit', at + '.不过', '不认识的出口：' + String(step['不过']))
    }
    // 真陷阱：超时 → 重打。重打会把限时重新计时（tick 里重置 askedAt），所以他一句不说
    // 就能一直重打，限时永远不到，这一拍永远走不出去。静态可查，直接拒绝加载。
    // 「不过 → 重打」不拦：答错重来是明写的设计，他一直在说话，不会卡死。
    if (timeout && timeout.kind === 'retry') {
      err('no-exit', at + '.超时', '超时不能是「重打」：重打会重置限时，他不说话就永远走不出去。' +
        '把超时改成 收局 / 升级 / 下一步，重打留给「不过」。')
    }

    // 监视（可选）：离开声明应用超过 X，则二选一
    if (step['监视'] !== undefined) {
      if (!isPlainObject(step['监视'])) err('bad-monitor', at + '.监视', '监视必须是对象')
      else {
        const leave = parseDuration(step['监视']['离开声明应用超过'])
        if (leave === null) err('bad-monitor', at + '.监视.离开声明应用超过', '监视的时限认不出来')
        const options = step['监视']['则']
        if (!Array.isArray(options) || options.length !== 2) {
          err('bad-monitor', at + '.监视.则', '监视的出口必须是二选一的两个')
        } else {
          for (const o of options) {
            if (normalizeExit(o) === null) err('bad-exit', at + '.监视.则', '不认识的出口：' + String(o))
          }
        }
      }
    }
  })

  // 收局
  if (raw['收局'] !== undefined) {
    if (!isPlainObject(raw['收局'])) err('bad-close', '收局', '收局必须是对象')
    else {
      for (const key of unknownKeys(raw['收局'], CLOSE_KEYS)) err('unknown-key', '收局.' + key, '收局里不认识的字段：' + key)
      if (raw['收局']['交'] !== undefined) {
        const hand = raw['收局']['交']
        if (!Array.isArray(hand)) err('bad-close', '收局.交', '交必须是步骤 id 的数组')
        else for (const x of hand) {
          if (!seen.has(String(x))) err('bad-close', '收局.交', '交里写了不存在的步骤：' + String(x))
        }
      }
    }
  } else {
    err('no-close', '收局', '协议必须写收局')
  }

  // 可终止性：每一步都要能走到 收局 或 升级
  if (!errors.some((e) => e.code === 'no-steps' || e.code === 'bad-step')) {
    const reach = reachableToTerminal(steps)
    for (let i = 0; i < steps.length; i += 1) {
      if (!reach[i]) {
        err('no-exit', '步骤[' + i + ']', '这一步走不出去：没有一条路能到收局或升级')
      }
    }
  }

  if (errors.length) {
    return { ok: false, errors, ...(missing.length ? { proposal: missingCapabilityProposal(missing, id) } : {}) }
  }
  return { ok: true, protocol: normalizeProtocol(raw) }
}

/**
 * 反着走一遍：哪些步骤能到终点。
 * 边：过关 → 下一步；超时/不过 → 声明的出口；监视的二选一 → 各自的出口。
 */
function reachableToTerminal(steps) {
  const n = steps.length
  const edges = Array.from({ length: n }, () => [])
  const terminalReachable = new Array(n).fill(false)
  const pushTarget = (from, exit) => {
    if (!exit) return
    if (exit.kind === 'close' || exit.kind === 'escalate') terminalReachable[from] = true
    else if (exit.kind === 'retry') edges[from].push(from)
    else if (exit.kind === 'next') {
      if (from + 1 < n) edges[from].push(from + 1)
      else terminalReachable[from] = true
    }
  }
  steps.forEach((step, i) => {
    if (!isPlainObject(step)) return
    // 过关（或没有过关条件时的默认）走下一拍
    edges[i].push(i + 1 < n ? i + 1 : i)
    if (i + 1 >= n) terminalReachable[i] = true
    pushTarget(i, normalizeExit(step['超时']))
    pushTarget(i, normalizeExit(step['不过']))
    const monitor = step['监视']
    if (isPlainObject(monitor) && Array.isArray(monitor['则'])) {
      for (const o of monitor['则']) pushTarget(i, normalizeExit(o))
    }
  })
  const reach = terminalReachable.slice()
  let changed = true
  while (changed) {
    changed = false
    for (let i = 0; i < n; i += 1) {
      if (reach[i]) continue
      if (edges[i].some((j) => reach[j])) {
        reach[i] = true
        changed = true
      }
    }
  }
  return reach
}

function normalizeProtocol(raw) {
  const steps = raw['步骤'].map((step, i) => ({
    index: i,
    id: String(step.id),
    ask: String(step['问'] || ''),
    limitSec: parseDuration(step['限时']) ?? parseDuration(step['每']),
    periodSec: step['每'] === undefined ? null : parseDuration(step['每']),
    repeatUntil: parseRepeatUntil(step['重复至']) ?? null,
    pass: isPlainObject(step['过关']) ? { ...step['过关'] } : null,
    timeout: normalizeExit(step['超时']) || { kind: 'next' },
    fail: normalizeExit(step['不过']) || { kind: 'retry' },
    triggers: Array.isArray(step['触发']) ? step['触发'].map(String) : [],
    form: step['形态'] ? String(step['形态']) : '',
    answer: step['应答'] ? String(step['应答']) : '',
    monitor: isPlainObject(step['监视'])
      ? { leaveSec: parseDuration(step['监视']['离开声明应用超过']), options: step['监视']['则'].map(normalizeExit) }
      : null,
  }))
  return {
    version: Number(raw['协议版本']),
    id: String(raw.id),
    name: String(raw['名称']),
    type: String(raw['类型']),
    needs: collectCapabilities(raw),
    budget: isPlainObject(raw['预算']) ? { minutes: Number(raw['预算']['分钟']) || 0, turns: Number(raw['预算']['来回']) || 0 } : null,
    steps,
    close: {
      hand: Array.isArray(raw['收局'] && raw['收局']['交']) ? raw['收局']['交'].map(String) : [],
      standard: String((raw['收局'] && raw['收局']['判定']) || ''),
    },
    raw,
  }
}

/** 解析 + 校验。text 给字符串时按 JSON 解析；认出是 YAML 就明确拒绝。 */
export function loadProtocol(input) {
  let raw = input
  if (typeof input === 'string') {
    const text = input.trim()
    if (!text) return { ok: false, errors: [{ code: 'empty', where: '', message: '协议是空的' }] }
    try {
      raw = JSON.parse(text)
    } catch (error) {
      // 先按 JSON 解析。解析不了再看它像不像 YAML：像就明说只收 JSON，
      // 免得有人以为引擎悄悄把他的 YAML 吃下去了。
      if (!text.startsWith('{') && !text.startsWith('[')) {
        return {
          ok: false,
          errors: [{
            code: 'need-json', where: '', message: '协议只收 JSON：这台引擎没有 yaml 依赖，也不许为协议格式加依赖。' +
              '把 YAML 转成 JSON 再导入。',
          }],
        }
      }
      return { ok: false, errors: [{ code: 'bad-json', where: '', message: 'JSON 解析失败：' + String((error && error.message) || error) }] }
    }
  }
  return validateProtocol(raw)
}

/**
 * 过关判定：条件全过才算过。input = {text, askedAt, answeredAt, recent}
 * 返回 {pass, results:[{name, want, got, ok}]}。
 */
export function checkPass(conditions, input = {}) {
  const results = []
  let pass = true
  for (const [name, want] of Object.entries(conditions || {})) {
    const fn = CONDITIONS[name]
    if (!fn) {
      results.push({ name, want, got: null, ok: false, error: 'unknown-condition' })
      pass = false
      continue
    }
    const got = fn(input)
    const ok = compareCondition(name, got, want)
    if (!ok) pass = false
    results.push({ name, want, got, ok })
  }
  return { pass, results }
}

function compareCondition(name, got, want) {
  if (name === '每行最多字' || name === '填充词比例' || name === '应答延迟' || name === '与他近5条的重合率') {
    return Number(got) <= Number(want)
  }
  if (name === '有无结论句') return Boolean(got) === Boolean(want)
  // 这个条件的 got 是「与上一条重复」，want=true 要的是「不重复」，所以反过来比。
  if (name === '不与前次重复') return Boolean(want) ? !got : Boolean(got)
  return Number(got) >= Number(want)
}

/**
 * 局中的执行器。只管推进状态机：问下一拍、判过关、超时、按出口走。
 * 真正发声、盖屏、拍照由引擎按 triggers 接到能力实现上；这里不假装已经接了。
 */
export function createRunner(protocol, options = {}) {
  const now = () => (typeof options.now === 'function' ? Number(options.now()) : Number(options.now || Date.now()))
  const steps = protocol.steps
  const answers = []
  let index = 0
  let retries = 0
  let lastPass = false
  let askedAt = now()
  let state = 'running'
  let endedAt = 0
  let endReason = ''

  function settle(kind, reason) {
    state = kind
    endedAt = now()
    endReason = reason
    return { state, reason, index }
  }

  function goExit(exit, why, allowRepeat = false) {
    if (!exit) return settle('closed', 'no-exit')
    if (exit.kind === 'close') return settle('closed', why)
    if (exit.kind === 'escalate') return settle('escalated', why)
    if (exit.kind === 'retry') {
      retries += 1
      askedAt = now()
      return { state, reason: 'retry', index, retries }
    }
    if (index + 1 >= steps.length) return settle('closed', 'done')
    // 周期步（每 6m / 每 10m）：过关后预算还剩得多就原地重问，剩到「重复至」那条线才进下一步。
    // 只在「过关」时重复：超时也重复的话，他不说话这一拍就永远走不出去。
    // remaining() 由调用方给（它知道预算和开局时间）；没给就按普通步前进，不猜。
    const here = steps[index]
    if (allowRepeat && here.repeatUntil !== null && typeof options.remaining === 'function') {
      if (Number(options.remaining()) > here.repeatUntil) {
        retries = 0
        askedAt = now()
        return { state, reason: 'repeat', index, nextAskAt: askedAt + (here.periodSec || 0) * 1000 }
      }
    }
    index += 1
    retries = 0
    askedAt = now()
    return { state, reason: 'next', index }
  }

  return {
    protocolId: protocol.id,
    get state() {
      return state
    },
    get index() {
      return index
    },
    get retries() {
      return retries
    },
    /**
     * 上一次 answer() 过没过关。
     * 单独一个方法而不是塞进返回值：返回值是「怎么走的」（next / retry / repeat / closed），
     * 「过没过」是另一件事——不过也可能被协议配成「下一步」，两者不能互相推。
     */
    lastPass() {
      return lastPass
    },
    get endedAt() {
      return endedAt
    },
    get endReason() {
      return endReason
    },
    answers() {
      return answers.slice()
    },
    current() {
      if (state !== 'running') return null
      return steps[index] || null
    },
    askedAt() {
      return askedAt
    },
    /** 他答了。 */
    answer(text) {
      if (state !== 'running') return { state, reason: 'not-running', index }
      const step = steps[index]
      const input = { text: String(text || ''), askedAt, answeredAt: now(), recent: answers.slice(-5) }
      const verdict = checkPass(step.pass || {}, input)
      answers.push(String(text || ''))
      lastPass = verdict.pass
      if (verdict.pass) return goExit({ kind: 'next' }, 'pass', true)
      return goExit(step.fail, 'fail')
    },
    /** 到点没答：走超时出口。 */
    tick(at) {
      if (state !== 'running') return { state, reason: 'not-running', index }
      const step = steps[index]
      const when = Number(at) || now()
      if (!step.limitSec) return { state, reason: 'no-limit', index }
      if (when - askedAt < step.limitSec * 1000) return { state, reason: 'not-yet', index }
      answers.push('')
      return goExit(step.timeout, 'timeout')
    },
    /** 周期步到点重问同一句：只把这一拍的起点挪到 now，不动进度。 */
    reask(at) {
      if (state !== 'running') return { state, reason: 'not-running', index }
      askedAt = Number(at) || now()
      retries = 0
      return { state, reason: 'reask', index, askedAt }
    },
    /** 收局（他说停 / 引擎要求）。 */
    abandon(reason = 'abandoned') {
      if (state !== 'running') return { state, reason: 'not-running', index }
      return settle('closed', reason)
    },
  }
}
