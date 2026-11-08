/**
 * cap-ledger —— 能力台账的最小实现（纯 JS、零第三方依赖、可 node --test 单测）。
 *
 * 台账必须能区分**四个状态**，不能只显示插件名：
 *   installed        已安装：插件包在磁盘上、DSH 把它列进 profile
 *   loaded           已加载：apply() 真的跑起来了（本模块被实例化就是证据）
 *   toolRegistered   工具已注册：ctx.tools.register 成功返回了 disposer
 *   lastRunOk        最近一次执行成功/失败 + 失败原因（code + message）
 *
 * 三条设计取舍：
 *   1. **只记事实，不猜**。拿不到某一项就写 'unknown'，不要默认成 true ——
 *      一个假绿的状态条比没有状态条更坏。
 *   2. **落盘是可选增强**。写不进去（只读 home / 手机上没权限）就在内存里记，
 *      不让观测本身变成故障源。
 *   3. **纯函数内核 + 薄 IO 外壳**：capacity 判断和形状规整都能单测，不需要文件系统。
 */

export const LEDGER_VERSION = 1

/** 四个状态 + 运行计数。字段全在，缺的显式写 null / 'unknown'。 */
export function emptyLedger() {
  return {
    v: LEDGER_VERSION,
    plugin: 'dsh-go-bili',
    // ① 已安装：由调用方按「自己跑起来了 → 包一定在磁盘上」判定
    installed: null,
    // ② 已加载：apply() 跑到这里就是 true
    loaded: null,
    // ③ 工具已注册：register 返回 disposer 才是 true
    toolRegistered: null,
    // ④ 最近一次执行：null = 还没跑过（**不是**失败）
    lastRunOk: null,
    lastRunAt: null,
    lastRunCode: null,
    lastRunMessage: null,
    runs: 0,
    fails: 0,
    // 每次状态变化的时间，方便面板显示「多久以前」
    updatedAt: null,
  }
}

const isObject = (v) => typeof v === 'object' && v !== null && !Array.isArray(v)

/**
 * 把任意来源（磁盘上的旧文件、测试里的假数据）规整成合法台账。
 * **不信任输入**：类型不对就退回默认值，不抛。
 * @param {unknown} raw
 * @returns {object} 一份新的 emptyLedger 形状
 */
export function normalizeLedger(raw) {
  const out = emptyLedger()
  if (!isObject(raw)) return out
  if (raw.v !== LEDGER_VERSION) return out
  const tri = (v) => (v === true || v === false ? v : null)
  out.installed = tri(raw.installed)
  out.loaded = tri(raw.loaded)
  out.toolRegistered = tri(raw.toolRegistered)
  out.lastRunOk = tri(raw.lastRunOk)
  out.lastRunAt = Number.isFinite(raw.lastRunAt) ? raw.lastRunAt : null
  out.lastRunCode = typeof raw.lastRunCode === 'string' ? raw.lastRunCode : null
  out.lastRunMessage = typeof raw.lastRunMessage === 'string' ? raw.lastRunMessage : null
  out.runs = Number.isInteger(raw.runs) && raw.runs >= 0 ? raw.runs : 0
  out.fails = Number.isInteger(raw.fails) && raw.fails >= 0 ? raw.fails : 0
  out.updatedAt = Number.isFinite(raw.updatedAt) ? raw.updatedAt : null
  // fails 不可能多于 runs
  if (out.fails > out.runs) out.fails = out.runs
  return out
}

/**
 * 记一次执行结果。**只改 ④ 与计数**，前三态由安装/加载/注册流程各自负责。
 * @param {object} ledger 会被就地修改（调用方通常是插件里那个单例）
 * @param {{ ok: boolean, code?: string, message?: string, at?: number }} run
 * @returns {object} 同一个对象，方便链式使用
 */
export function recordRun(ledger, run) {
  const at = Number.isFinite(run?.at) ? run.at : Date.now()
  ledger.runs += 1
  ledger.lastRunOk = run?.ok === true
  ledger.lastRunAt = at
  if (ledger.lastRunOk) {
    ledger.lastRunCode = null
    ledger.lastRunMessage = null
  } else {
    // 失败原因必须落下来 —— 这正是这个台账存在的理由
    ledger.lastRunCode = typeof run?.code === 'string' ? run.code : 'E_INTERNAL'
    ledger.lastRunMessage = typeof run?.message === 'string' ? run.message : '（没有给出原因）'
    ledger.fails += 1
  }
  ledger.updatedAt = at
  return ledger
}

/**
 * 四个状态各自怎么读 —— 面板与状态条都用这一份，避免两处口径不一致。
 * @param {object} ledger
 * @returns {{ installed: string, loaded: string, toolRegistered: string, lastRunOk: string, healthy: boolean }}
 */
export function describe(ledger) {
  const l = normalizeLedger(ledger)
  const yes = (v) => (v === true ? '是' : v === false ? '否' : '未知')
  return {
    installed: yes(l.installed),
    loaded: yes(l.loaded),
    toolRegistered: l.toolRegistered === true ? 'peek_bili_video' : yes(l.toolRegistered),
    lastRunOk: l.lastRunAt === null
      ? '还没跑过'
      : l.lastRunOk === true
        ? '成功'
        : '失败' + (l.lastRunCode === null ? '' : '（' + l.lastRunCode + '）'),
    // 只有「已加载 + 工具已注册 + 上次不是失败」才算健康；拿不到就判不健康，宁可保守
    healthy: l.loaded === true && l.toolRegistered === true && l.lastRunOk !== false,
  }
}

/** 台账是否「前三态都齐」—— 用来判断这个插件到底能不能被用。 */
export function isUsable(ledger) {
  const l = normalizeLedger(ledger)
  return l.installed === true && l.loaded === true && l.toolRegistered === true
}

/** 序列化成落盘字符串。落盘失败由调用方处理（不在这里静默吞掉）。 */
export function serialize(ledger) {
  return JSON.stringify(normalizeLedger(ledger))
}

/**
 * 从落盘字符串读回来。**坏文件不抛**：给一份空的，让插件正常起来。
 * @param {unknown} text
 */
export function deserialize(text) {
  if (typeof text !== 'string' || text === '') return emptyLedger()
  try {
    return normalizeLedger(JSON.parse(text))
  } catch {
    return emptyLedger()
  }
}
