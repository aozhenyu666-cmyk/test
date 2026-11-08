/**
 * partner 自己的配置（人格 / 记忆 / 情绪三个模块的独立开关）
 *
 * 为什么是文件而不是 `export const Config`：
 *   本包坚持零第三方依赖（import 只有 node: 内置模块），而 DSH 的原生设置表单要
 *   schemastery 的 schema 对象。硬塞一个假 Config 会让 App 画出一张改不动的表单 ——
 *   比没有更糟。所以只认一个 JSON 文件，路径可被环境变量覆盖。
 *
 * 三个模块**互相独立**：任何一个 enabled=false，它就既不进面板、也不进系统提示、
 * 也不注册工具，其余两个照常。
 */
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'

/** 延迟读：模块级 const 会在 import 那一刻定死，测试里换 DSH_HOME 就不生效了。 */
export const dshHome = () => process.env.DSH_HOME || path.join(os.homedir(), '.dsh')

/** partner 自己的数据目录（只写这里，绝不去改 fish-memory / feiyu-mood 的文件）。 */
export const partnerDir = () => path.join(dshHome(), 'partner')

export const configPath = () => process.env.DSH_PARTNER_CONFIG || path.join(partnerDir(), 'config.json')

export const DEFAULTS = Object.freeze({
  persona: {
    enabled: true,
    // 空 = 自动找（见 persona.mjs 的 candidates）。写了就只读这一个。
    personaFile: '',
    // 额外的表达风格约定（用户自己写的，不是模型猜的）
    style: '',
    // 主动话题偏好：她主动开口时更喜欢聊什么
    topics: [],
    // 身份卡：M2 的 identity 卡就是这一张；这里只覆盖不新建
    identityFile: '',
    sectionOrder: 100,
  },
  memory: {
    enabled: true,
    /** 短期记忆的时间窗（小时）：这段时间内记下的算「刚刚记住的」 */
    shortTermHours: 24,
    /** 检索默认返回条数 */
    recallLimit: 6,
    /** 检索最低分（0–1），低于它不返回 */
    recallFloor: 0.12,
    /** 一页记忆面板最多画多少行 */
    panelLimit: 40,
  },
  mood: {
    enabled: true,
    /** 把此刻心情作为上下文交给 Agent */
    inject: true,
    contextOrder: 880,
    /** 时间线最多画几条 */
    timelineLimit: 8,
  },
  tools: {
    /** 注册 partner_recall / partner_remember / partner_correct / partner_mood */
    enabled: true,
  },
  /** 常驻系统提示：告诉她「你自己有记忆和情绪」 */
  hint: { enabled: true, sectionOrder: 89990 },
})

function clone(v) {
  if (Array.isArray(v)) return v.map(clone)
  if (v && typeof v === 'object') {
    const out = {}
    for (const k of Object.keys(v)) out[k] = clone(v[k])
    return out
  }
  return v
}

function merge(base, patch) {
  // ⚠️ 必须**新拷贝**：DEFAULTS 是 Object.freeze 的，直接返回它再写 __file 会抛
  //   「Cannot add property __file, object is not extensible」—— 这个 bug 只在「没有配置文件」时出现，
  //   也就是**刚装上的第一台机器**，最容易被漏测。
  if (!patch || typeof patch !== 'object' || Array.isArray(patch)) return clone(base)
  const out = { ...base }
  for (const [k, v] of Object.entries(patch)) {
    if (v === undefined) continue
    const cur = out[k]
    if (cur && typeof cur === 'object' && !Array.isArray(cur) && v && typeof v === 'object' && !Array.isArray(v)) {
      out[k] = merge(cur, v)
    } else if (Array.isArray(cur)) {
      out[k] = Array.isArray(v) ? v.slice() : cur
    } else if (typeof cur === 'boolean') {
      out[k] = Boolean(v)
    } else if (typeof cur === 'number') {
      const n = Number(v)
      out[k] = Number.isFinite(n) ? n : cur
    } else if (typeof cur === 'string') {
      out[k] = typeof v === 'string' ? v : String(v)
    } else {
      out[k] = v
    }
  }
  return out
}

/**
 * 读配置。永远返回一个完整对象（缺文件 = 全默认），永不抛错 ——
 * 配置坏了不能让插件整个起不来，那样用户连「出问题了」都看不到。
 */
export function loadConfig(file = configPath()) {
  let raw = null
  try {
    raw = JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''))
  } catch {
    raw = null
  }
  const cfg = merge(DEFAULTS, raw)
  cfg.__file = file
  cfg.__ok = raw !== null
  cfg.__reason = raw !== null ? '' : (fs.existsSync(file) ? 'unreadable' : 'missing')
  return cfg
}

/** 三个模块是否至少开着一个 —— 全关就什么也不注册，宿主那边干净退出。 */
export function anyModuleOn(cfg) {
  return Boolean(cfg.persona?.enabled || cfg.memory?.enabled || cfg.mood?.enabled)
}
