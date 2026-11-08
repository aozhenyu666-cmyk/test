/**
 * cap-ledger 的纯内核：**四个状态必须分得开**，只显示插件名等于没有台账。
 *
 *   installed      已安装：这个包在磁盘上、被 profile 选中（能看到 package.json 与入口文件）
 *   loaded         已加载：apply() 真的跑起来了（本进程里那个模块被实例化）
 *   toolRegistered 工具已注册：ctx.tools.register 返回了 disposer
 *   lastRunOk      最近一次执行：true / false / null（null = 还没跑过，**不是失败**）
 *
 * 三条不许违反的规则（都是踩过的坑）：
 *   1. **不猜**。拿不到就是 null / 'unknown'，不默认成 true。假绿比不显示更坏。
 *   2. **失败要带原因**。只有 lastRunOk=false 没有 code/message，等于没有可行动信息。
 *   3. **纯函数 + 薄 IO**。这里不碰文件系统、不碰网络、不读时间以外的外部状态，
 *      所以每条判据都能 node --test 直接验。
 *
 * 零第三方依赖。
 */

export const LEDGER_VERSION = 2

/** 四个状态的名字，给外面做遍历/校验用（顺序 = 面板上的显示顺序）。 */
/**
 * 鍏€佺姸鎬? 纭寲杞帴鏃剁殑鍏€? 姣忎竴姝ラ兘鏄?竴涓笉鍚岀殑浜嬪疄锛? *   installed      宸插畨瑁?  璺熶笂鍐?  *   loaded         宸插姞杞?  apply() 鐪熺殑璺戣捣鏉ヤ簡
 *   toolRegistered 宸ュ叿宸叉敞鍐?  ctx.tools.register 杩斿洖浜?disposer
 *   available      鍙敤锛氬悓鏃舵弧瓒?installed + loaded + toolRegistered锛屽苟涓旀病鏈夊け璐ョ殑鎵ц
 *   executed       鐪熺殑鎵ц杩囷紙runs > 0锛夆€斺€?鏈夋病鏈夎窇杩囧拰璺戝緱瀵逛笉瀵规槸涓ゅ洖浜?
 *   failed         鎵ц繃涓斿け璐?  lastRunOk === false
 */
export const STATES = ['installed', 'loaded', 'toolRegistered', 'available', 'executed', 'failed']

/** 一个插件的空台账。字段全在，缺的显式写 null。 */
export function emptyEntry(plugin = '') {
  return {
    plugin: String(plugin),
    installed: null,
    loaded: null,
    toolRegistered: null,
    available: null,
    executed: null,
    failed: null,
    lastRunOk: null,
    lastRunAt: null,
    lastRunCode: null,
    lastRunMessage: null,
    runs: 0,
    fails: 0,
    updatedAt: null,
  }
}

/** 一整份台账。 */
export function emptyLedger() {
  return { v: LEDGER_VERSION, plugins: {}, updatedAt: null }
}

const isObject = (v) => typeof v === 'object' && v !== null && !Array.isArray(v)
const tri = (v) => (v === true || v === false ? v : null)
const nonNegInt = (v) => (Number.isInteger(v) && v >= 0 ? v : 0)
const text = (v) => (typeof v === 'string' && v !== '' ? v : null)

/**
 * 规整一条插件台账。**不信任任何输入**：类型不对退回默认，不抛。
 * @param {unknown} raw
 * @param {string} [plugin] 兜底的插件名
 */
export function normalizeEntry(raw, plugin = '') {
  const out = emptyEntry(plugin)
  if (!isObject(raw)) return out
  out.plugin = text(raw.plugin) ?? String(plugin)
  out.installed = tri(raw.installed)
  out.loaded = tri(raw.loaded)
  out.toolRegistered = tri(raw.toolRegistered)
  // 鍏€佺殑鍚庝袱浣?*鐢卞墠闈紑鍜岃绠楄繃绋嬪緱鍑?*锛屼笉淇′换鍏ュ弬锛屼篃涓嶈鎵嬪啓銆?  out.executed = out.runs > 0 ? true : null
  out.failed = out.runs > 0 ? out.lastRunOk === false : null
  out.available = out.installed === true && out.loaded === true && out.toolRegistered === true ? true : (out.installed === false || out.loaded === false || out.toolRegistered === false ? false : null)
  out.lastRunOk = tri(raw.lastRunOk)
  out.lastRunAt = Number.isFinite(raw.lastRunAt) ? raw.lastRunAt : null
  out.lastRunCode = text(raw.lastRunCode)
  out.lastRunMessage = text(raw.lastRunMessage)
  out.runs = nonNegInt(raw.runs)
  out.fails = Math.min(nonNegInt(raw.fails), nonNegInt(raw.runs))
  out.updatedAt = Number.isFinite(raw.updatedAt) ? raw.updatedAt : null
  // 「还没跑过」和「失败」是两件事：runs=0 时 lastRunOk 只能是 null
  if (out.runs === 0) {
    out.lastRunOk = null
    out.lastRunAt = null
    out.lastRunCode = null
    out.lastRunMessage = null
  }
  // 失败的记录必须带原因（老数据/手工写坏时补一句，而不是留一个空）
  if (out.lastRunOk === false && out.lastRunCode === null) out.lastRunCode = 'E_UNKNOWN'
  return out
}

/**
 * 规整一整份台账（磁盘上的旧文件 / 测试里造的假数据都走这里）。
 * @param {unknown} raw
 */
export function normalizeLedger(raw) {
  const out = emptyLedger()
  if (!isObject(raw)) return out
  if (raw.v !== LEDGER_VERSION) return out
  if (isObject(raw.plugins)) {
    for (const [name, entry] of Object.entries(raw.plugins)) {
      if (name === '') continue
      out.plugins[name] = normalizeEntry(entry, name)
    }
  }
  out.updatedAt = Number.isFinite(raw.updatedAt) ? raw.updatedAt : null
  return out
}

/** 取一条（没有就建一条空的，不自动写回）。 */
export function entryOf(ledger, plugin) {
  const l = normalizeLedger(ledger)
  return l.plugins[plugin] ?? emptyEntry(plugin)
}

/** 就地写入一条（调用方持有单例，所以这里改的是同一份）。 */
export function putEntry(ledger, entry) {
  const e = normalizeEntry(entry, entry?.plugin)
  ledger.plugins[e.plugin] = e
  ledger.updatedAt = e.updatedAt ?? ledger.updatedAt
  return ledger
}

/**
 * 记「已安装」—— 判定依据是**看得见这个包**（package.json 与入口文件都在）。
 * @param {object} entry
 * @param {{ ok: boolean, at?: number }} evidence
 */
export function markInstalled(entry, evidence) {
  entry.installed = evidence?.ok === true
  entry.updatedAt = Number.isFinite(evidence?.at) ? evidence.at : entry.updatedAt
  return entry
}

/**
 * 记「已加载」—— 判定依据是 apply() 真的执行到了这里。
 * @param {object} entry
 * @param {{ at?: number }} [evidence]
 */
export function markLoaded(entry, evidence = {}) {
  entry.loaded = true
  entry.updatedAt = Number.isFinite(evidence.at) ? evidence.at : entry.updatedAt
  return entry
}

/**
 * 记「工具已注册」—— 判定依据是 register() **返回了 disposer**，
 * 不是「我调用过 register」。调了但抛错 = false。
 * @param {object} entry
 * @param {{ ok: boolean, error?: string, at?: number }} evidence
 */
export function markToolRegistered(entry, evidence) {
  entry.toolRegistered = evidence?.ok === true
  if (entry.toolRegistered === false && typeof evidence?.error === 'string' && evidence.error !== '') {
    // 注册失败本身就是一次「最近一次不成功」，原因要落下来
    entry.lastRunOk = false
    entry.lastRunCode = 'E_REGISTER'
    entry.lastRunMessage = '工具注册失败：' + evidence.error
    entry.fails += 1
    entry.runs += 1
  }
  entry.updatedAt = Number.isFinite(evidence?.at) ? evidence.at : entry.updatedAt
  return entry
}

/**
 * 记「最近一次执行」。
 * @param {object} entry
 * @param {{ ok: boolean, code?: string, message?: string, at?: number }} run
 */
export function markRun(entry, run) {
  const at = Number.isFinite(run?.at) ? run.at : Date.now()
  entry.runs += 1
  entry.lastRunOk = run?.ok === true
  entry.lastRunAt = at
  if (entry.lastRunOk) {
    // 成功后不该还挂着上一次的错误码
    entry.lastRunCode = null
    entry.lastRunMessage = null
  } else {
    entry.lastRunCode = text(run?.code) ?? 'E_UNKNOWN'
    entry.lastRunMessage = text(run?.message) ?? '（没有给出原因）'
    entry.fails += 1
  }
  entry.updatedAt = at
  return entry
}

/**
 * 四态各自怎么读 —— 面板与状态条共用这一份，避免两处口径不一致。
 * @param {object} entry
 * @returns {{ installed: string, loaded: string, toolRegistered: string, lastRunOk: string, healthy: boolean, usable: boolean }}
 */
export function describeEntry(entry) {
  const e = normalizeEntry(entry, entry?.plugin)
  const yesNo = (v) => (v === true ? '是' : v === false ? '否' : '未知')
  const lastRunOk = e.runs === 0
    ? '还没跑过'
    : e.lastRunOk === true
      ? '成功'
      : '失败（' + String(e.lastRunCode) + '）'
  const available = e.available === true
    ? '能用'
    : e.available === false
      ? '不能用'
      : '未知'
  const executed = e.runs === 0 ? '还没跑过' : `跑过 ${e.runs} 次`
  const failed = e.runs === 0 ? '还没跑过' : e.failed === true ? `失败 ${e.fails} 次` : '没有失败'
  return {
    installed: yesNo(e.installed),
    loaded: yesNo(e.loaded),
    toolRegistered: yesNo(e.toolRegistered),
    available,
    executed,
    failed,
    lastRunOk,
    // 健康 = 前三态都 true 且上次不是失败。any null 判 false（不报假绿）。
    healthy: e.installed === true && e.loaded === true && e.toolRegistered === true && e.lastRunOk !== false,
    usable: e.installed === true && e.loaded === true && e.toolRegistered === true,
  }
}

/** 汇总：几成插件真的能用（给状态条一句话）。 */
export function summarize(ledger) {
  const l = normalizeLedger(ledger)
  const names = Object.keys(l.plugins)
  const total = names.length
  let healthy = 0
  let failing = 0
  for (const name of names) {
    const d = describeEntry(l.plugins[name])
    if (d.healthy) healthy += 1
    if (l.plugins[name].lastRunOk === false) failing += 1
  }
  return { total, healthy, failing }
}

/**
 * 判断一份磁盘上的插件目录「算不算已安装」。
 * **只看文件在不在**，不猜它能不能跑（那是 loaded 的事）。
 * @param {(p: string) => boolean} exists 注入的 existsSync，方便单测
 * @param {string} dir 插件目录
 * @param {string} main package.json 里声明的入口（相对路径）
 */
export function looksInstalled(exists, dir, main) {
  if (typeof dir !== 'string' || dir === '') return false
  if (typeof exists !== 'function') return false
  const join = (a, b) => a.replace(/[\\/]+$/, '') + '/' + b.replace(/^\.\//, '')
  if (!exists(join(dir, 'package.json'))) return false
  if (typeof main !== 'string' || main === '') return true
  return exists(join(dir, main))
}

/** 序列化 / 反序列化（落盘由调用方做；这里不碰文件系统）。 */
export function serialize(ledger) {
  return JSON.stringify(normalizeLedger(ledger))
}

export function deserialize(text) {
  if (typeof text !== 'string' || text === '') return emptyLedger()
  try {
    return normalizeLedger(JSON.parse(text))
  } catch {
    return emptyLedger()
  }
}
