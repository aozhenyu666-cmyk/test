// dsh-go-companion · P4 升级阶梯的接线（第八轮）
//
// 上一批把 reply.js（这条回答算什么）和 escalate.js（该不该升级、升到哪一级）都写完了，
// 但两个模块都没有被 import 过——能力层全在、接线一条没有。这个文件就是那条接线：
// 一条回答 → 分类 → 记敷衍 → 判升级 → 翻译成手机认得的动作。
//
// 上一批的账要记清楚：e6c33a9a 的标题写「E1–E3 / L1–L3 全部进代码」，实际只进了能力层——
// reply.js 和 escalate.js 写全了、测试也有，但没有任何生产代码 import 过它们：判球不记账、
// 记账不判升级、判了升级也没有一条路径把动作发到手机，等于零接线。
// 写在这里而不是 docs 里，是因为这份工程的文档目录不在稀疏检出集内，注释才是跟代码同行的进度记录。
// 本文件 + index.js 的 ladderAnswer / ladderEPrime / ladderTick / applyLanding 才是那条接线。
// 以后再看到「全部进代码」这类标题，照三样核对：有没有 import、有没有调用点、有没有发到手机的形状。
//
// 手机只认两种东西能改它的执法行为（见 ProactiveRuntime.applyPlan）：
//   1. `session`：Gemini 连麦舱，舱里只放行 `allow` 里的包，第三方 App 宽限 `graceMs`；
//   2. `state.layers`：娱乐锁到几点（`fun-lock-day` 走的是墙的收紧，等于当天剩余时段全锁）。
// 别的形状发过去等于没发。所以升级动作只落这两处，不另造一套。
//
// 出口只有三个（escalate.js 的 EXITS）：答出那条指令并通过判定 / 紧急应用 / 破窗。
// 前两个分别由「舱到期」和 SessionPolicy.NEVER_LOCK 兜住，第三个是既有机制。

import { classify, handlingOf } from './reply.js'
import { dayState, evaluate, notePerfunctory } from './escalate.js'

/** 舱的三种形态。`all-lock` 就是同一个舱，只是放行名单为空。 */
export const CELL = 'cell'
export const ALL_LOCK = 'all-lock'
/** 娱乐锁到当天结束（墙的收紧）。 */
export const FUN_LOCK_DAY = 'fun-lock-day'
/** 直接锁 + 进计划模态。 */
export const FUN_LOCK_PLAN = 'fun-lock-plan'
/** L2 的两种：问一句 / 锁那个包。 */
export const CONFIRM = 'confirm'
export const LOCK_PACKAGE = 'lock-package'

/** 计划模态的兜底下限：调用方没给 endOfDay 时才按 10 分钟算（沿用 dayplan.js）。 */
export const PLAN_LOCK_MS = 10 * 60_000

/**
 * 一条回答过一遍阶梯。
 *
 * 顺序不能换：先分类，只有「敷衍」才记一次、才判升级。答错和混乱在这条链上不动手机——
 * 答错只重打本拍，混乱只发压缩指令，两者都不该把人送进审讯舱。
 *
 * @param {object} state escalate.js 的当天状态（调用方从 stateFile('escalate.json') 取）
 * @param {{text?:string, timedOut?:boolean, passed?:boolean|null, recent?:string[],
 *          now:number, day:number, min?:number, dayKind?:string, settings:object,
 *          countIt?:boolean}} input
 *          countIt=false 表示只判升级、不新增一次敷衍（用于 L 串的每分钟节拍）
 * @returns {{state:object, kind:string, why:string, note:string, verdict:object|null}}
 */
export function step(state, input) {
  const day = Number(input?.day) || 0
  const now = Number(input?.now) || 0
  const s = dayState(state, day)

  const c = classify({
    text: input?.text,
    timedOut: input?.timedOut === true,
    passed: input?.passed,
    recent: input?.recent,
  })
  const handle = handlingOf(c.kind)

  // 答错 / 混乱 / 过：只回一句处置，不动手机。
  if (!handle.penalize) {
    return { state: s, kind: c.kind, why: c.why, note: handle.note, verdict: null }
  }

  const counted = input?.countIt === false
    ? s
    : notePerfunctory(s, { day, now, windowMs: input?.settings?.escalate?.E1WindowMs })

  const v = evaluate({
    state: counted,
    now,
    day,
    min: Number(input?.min) || 0,
    // 注意键名：evaluateL 里的 `kind` 是「这一天算什么日」（工作日/周末），不是应答分类。
    kind: input?.dayKind,
    settings: input?.settings || {},
  })
  return {
    state: v.state,
    kind: c.kind,
    why: c.why,
    note: handle.note,
    verdict: v.rule ? v : null,
  }
}

/**
 * 只跑沉迷那一串：/plan 节拍用手机快照算 L1–L3，不涉及应答分类。
 * @param {object} state
 * @param {{now:number, day:number, min:number, dayKind?:string, settings:object,
 *          foreground?:string|null, funPackage?:boolean, declared?:string[],
 *          funDeltaMs?:number, answeredAt?:number}} input
 */
export function funStep(state, input) {
  const day = Number(input?.day) || 0
  const v = evaluate({
    state: dayState(state, day),
    now: Number(input?.now) || 0,
    day,
    min: Number(input?.min) || 0,
    kind: input?.dayKind,
    settings: input?.settings || {},
    foreground: input?.foreground,
    funPackage: input?.funPackage === true,
    declared: input?.declared,
    funDeltaMs: Number(input?.funDeltaMs) || 0,
    answeredAt: Number(input?.answeredAt) || 0,
    inBout: input?.inBout === true,
  })
  return { state: v.state, verdict: v.rule ? v : null }
}

/**
 * 升级动作 → 手机上真正生效的东西。
 *
 * 返回 null 表示这条动作在这一层不需要落手机（比如只记账）。字段含义：
 *   cell    '' | 'cell' | 'all-lock'：写进 gemini.json 的 session.cell，手机据此开舱
 *   until   舱或计划模态的到期时刻（0 = 没有）
 *   tighten 是否要收紧墙（今天娱乐锁到零点）
 *   plan    是否要进计划模态
 *   block   要单独锁掉的包名（L2 的 lock-package）
 *
 * @param {{action?:object|null}} verdict
 * @param {{now?:number, endOfDay?:number}} ctx
 */
export function landing(verdict, ctx = {}) {
  const a = verdict?.action
  if (!a || typeof a !== 'object') return null
  const now = Number(ctx.now) || 0
  const endOfDay = Number(ctx.endOfDay) || 0
  if (a.type === CELL) {
    const minutes = Math.max(1, Number(a.minutes) || 0)
    return { cell: CELL, until: now + minutes * 60_000, tighten: false, plan: false, block: '' }
  }
  if (a.type === ALL_LOCK) {
    // 全机锁没有固定时长：出口是「答出那条指令并通过判定」。给到当天结束是为了引擎崩了
    // 手机也不会被锁过夜（RuleBook 那边还有 12 小时封顶，两层都在）。
    // shortcut: 到期只看日期，不看判定；升级后答对不会提前解舱，要等这一舱走完。
    return { cell: ALL_LOCK, until: Math.max(now + 60_000, endOfDay), tighten: false, plan: false, block: '' }
  }
  if (a.type === FUN_LOCK_DAY) {
    return { cell: '', until: 0, tighten: true, plan: false, block: '' }
  }
  if (a.type === FUN_LOCK_PLAN) {
    // 「直接锁，进入计划模态」：锁由 plan 层的 funLocked 承担，不用再收紧墙。
    // 出口是开出那一局（planBeat 开局时清 planUntil），所以锁到当天结束，不是 10 分钟——
    // 给 10 分钟等于「等一会儿就自己解了」。
    return { cell: '', until: Math.max(now + PLAN_LOCK_MS, endOfDay), tighten: false, plan: true, block: '' }
  }
  if (a.type === LOCK_PACKAGE) {
    return { cell: '', until: now + 60 * 60_000, tighten: false, plan: false, block: String(a.pkg || '') }
  }
  // confirm（L2 核对）：问一句就够，锁由「2 分钟没答」那一拍自己升级上来。
  if (a.type === CONFIRM) {
    return { cell: '', until: 0, tighten: false, plan: false, block: '', ask: String(a.pkg || ''), minutes: Number(a.minutes) || 2 }
  }
  return null
}

/** 升级表里那一行的措辞（reason 给墙看，level 给规则看）。 */
export function rowOf(verdict) {
  const rule = String(verdict?.rule || '')
  const why = String(verdict?.why || '')
  return { level: rule || 'L1', reason: why.slice(0, 200) }
}
