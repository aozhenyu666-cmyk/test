/**
 * 人格模块 —— 读真实文件，不做模型生成
 *
 * 三条硬规矩：
 *   1. **身份只有一份真相**。M2 的 identity 卡就是这张卡；这里只「读 + 拼」，不新建
 *      第二个人格来源，也不新建 dsh-go-identity 插件。
 *   2. buildSelfIdentityBlock() 的输出 **作为 persona.md 的一节**（「## 自我身份」），
 *      不是另一段并排的提示词 —— 两份并排，模型迟早会挑一份信。
 *   3. 读不到就如实说读不到（missing / unreadable / empty 分开报），不拿默认人设顶上。
 *
 * 参考：surfcore 的 persona.md / self-identity 分层（MIT，见 THIRD_PARTY_NOTICES）。
 * 这里只借它的**分层思路**，不搬它的 Windows 运行时。
 */
import fs from 'node:fs'
import path from 'node:path'

import { dshHome, partnerDir } from './config.mjs'

function readText(p) {
  try {
    return fs.readFileSync(p, 'utf8').replace(/^\uFEFF/, '')
  } catch {
    return null
  }
}

function readJson(p) {
  const t = readText(p)
  if (t === null) return null
  try {
    return JSON.parse(t)
  } catch {
    return null
  }
}

/** surfcore 当前角色目录（它自己的 current.json 是它那份的真相来源）。 */
export function surfcoreCurrent() {
  const dir = path.join(dshHome(), 'surfcore', 'characters')
  const cur = readJson(path.join(dir, 'current.json'))
  if (!cur || !cur.id) return null
  const charDir = cur.dir || path.join(dir, String(cur.id))
  return { ...cur, dir: charDir }
}

/**
 * 候选人格文件，按优先级。
 * 明写的优先；其次 partner 自己的；再其次 surfcore 当前角色的。
 */
export function personaCandidates(cfg) {
  const out = []
  if (cfg.persona?.personaFile) out.push({ file: cfg.persona.personaFile, from: 'config' })
  out.push({ file: path.join(partnerDir(), 'persona.md'), from: 'partner' })
  const cur = surfcoreCurrent()
  if (cur) out.push({ file: path.join(cur.dir, 'persona.md'), from: 'surfcore' })
  return out
}

/** 候选身份卡（**一份**）：明写的 > partner 自己的 > surfcore character.json > QQ 桥那份。 */
export function identityCandidates(cfg) {
  const out = []
  if (cfg.persona?.identityFile) out.push({ file: cfg.persona.identityFile, from: 'config' })
  out.push({ file: path.join(partnerDir(), 'identity.json'), from: 'partner' })
  const cur = surfcoreCurrent()
  if (cur) out.push({ file: path.join(cur.dir, 'character.json'), from: 'surfcore' })
  out.push({ file: path.join(dshHome(), 'qq-bridge-config.json'), from: 'qq-bridge' })
  return out
}

/** 从各个来源里抽出「我叫什么 / 对方叫什么」——都来自文件里的真实字段。 */
export function readNames() {
  const out = { selfName: '', ownerName: '', aliases: [], from: '' }
  const moodCfg = readJson(path.join(dshHome(), 'feiyu-mood', 'config.json'))
  if (moodCfg) {
    if (moodCfg.selfName) out.selfName = String(moodCfg.selfName)
    if (moodCfg.ownerName) out.ownerName = String(moodCfg.ownerName)
    if (moodCfg.selfAliases) out.aliases = String(moodCfg.selfAliases).split(/[,，\s]+/).filter(Boolean)
    if (out.selfName || out.ownerName) out.from = 'feiyu-mood'
  }
  if (!out.selfName) {
    const cur = surfcoreCurrent()
    if (cur && cur.name) { out.selfName = String(cur.name); out.from = out.from || 'surfcore' }
  }
  if (!out.ownerName) {
    const qq = readJson(path.join(dshHome(), 'qq-bridge-config.json'))
    if (qq && qq['称呼']) { out.ownerName = String(qq['称呼']); out.from = out.from || 'qq-bridge' }
  }
  return out
}

/**
 * 身份块 —— 只写「谁是谁 + 不许自称什么」，不写性格（性格在 persona.md 正文里）。
 *
 * @param {{selfName?, ownerName?, aliases?, platform?, cardName?}} input
 * @param {{platform?: string, heading?: string}} [opts]
 * @returns {string} markdown 片段；没有任何真实字段时返回空串（**不编**）。
 */
export function buildSelfIdentityBlock(input = {}, opts = {}) {
  const self = String(input.selfName || '').trim()
  const owner = String(input.ownerName || '').trim()
  const aliases = (input.aliases || []).map((x) => String(x).trim()).filter(Boolean)
  if (!self && !owner) return ''
  const lines = []
  if (self) {
    lines.push('- 你自己叫「' + self + '」。被问「你是谁」就按这个名字答，别自称助手、模型或 AI 产品。')
    if (aliases.length) lines.push('- 别人叫你这些也算叫你：' + aliases.map((a) => '「' + a + '」').join('、') + '。')
  }
  if (owner) lines.push('- 对面这个人你称「' + owner + '」。')
  if (input.cardName && input.cardName !== self) lines.push('- 人格卡文件名是「' + input.cardName + '」，和上面的名字不同：以人格卡正文为准，这里只做对齐。')
  lines.push('- 身份只有这一份。别处出现另一个「你是谁」的说法时，按这里来。')
  const heading = opts.heading || '## 自我身份'
  return [heading, '', ...lines, ''].join('\n')
}

/**
 * 角色状态：**全部是派生量**，不存第二份真相。
 * 第一次见面 = 记忆里最早一条；最近一次 = 最新一条；认识多久 = 两者之差。
 */
export function roleState(memStats = {}, moodBand = null) {
  const first = Number(memStats.firstAt) || 0
  const last = Number(memStats.lastAt) || 0
  const days = first ? Math.max(0, Math.floor((Date.now() - first) / 86400000)) : 0
  return {
    firstMetAt: first || null,
    lastAt: last || null,
    daysTogether: first ? days : null,
    memoryCount: Number(memStats.total) || 0,
    facts: Number(memStats.facts) || 0,
    mood: moodBand || null,
  }
}

/**
 * 加载人格：文本 + 身份块 + 表达风格 + 话题偏好。
 * @returns {{ok, reason, file, from, text, body, identity, identityFrom, names, style, topics}}
 */
export function loadPersona(cfg, opts = {}) {
  const names = readNames()
  const identHit = identityCandidates(cfg).map((c) => ({ ...c, json: readJson(c.file) })).find((c) => c.json)
  const ident = identHit?.json || {}
  const selfName = String(ident.selfName || ident.name || ident.personaMeta?.name || names.selfName || '')
  const ownerName = String(ident.ownerName || names.ownerName || '')
  const aliases = Array.isArray(ident.aliases) ? ident.aliases : names.aliases
  const identity = buildSelfIdentityBlock(
    { selfName, ownerName, aliases, cardName: ident.name },
    { heading: '## 自我身份' },
  )

  let hit = null
  const tried = []
  for (const c of personaCandidates(cfg)) {
    tried.push(c.file)
    const t = readText(c.file)
    if (t === null) continue
    hit = { ...c, text: t }
    break
  }
  if (!hit) {
    return {
      ok: false,
      reason: 'no-persona',
      tried,
      identity,
      identityFrom: identHit?.from || '',
      names: { selfName, ownerName },
      style: String(cfg.persona?.style || ''),
      topics: Array.isArray(cfg.persona?.topics) ? cfg.persona.topics.slice() : [],
    }
  }
  const text = hit.text.trim()
  if (!text) {
    return {
      ok: false,
      reason: 'empty-persona',
      file: hit.file,
      from: hit.from,
      tried,
      identity,
      identityFrom: identHit?.from || '',
      names: { selfName, ownerName },
      style: String(cfg.persona?.style || ''),
      topics: Array.isArray(cfg.persona?.topics) ? cfg.persona.topics.slice() : [],
    }
  }
  // 身份块**作为一节**接在人设正文后面（正文里已经有「## 自我身份」就不重复加）
  const hasSection = /^##\s*自我身份/m.test(text)
  const body = hasSection ? text.replace(/^##\s*自我身份[\s\S]*?(?=^##\s|\Z)/m, identity).trim() : (identity ? (text + '\n\n' + identity).trim() : text)
  return {
    ok: true,
    reason: '',
    file: hit.file,
    from: hit.from,
    tried,
    text,
    body,
    identity,
    identityFrom: identHit?.from || '',
    names: { selfName, ownerName },
    style: String(cfg.persona?.style || ''),
    topics: Array.isArray(cfg.persona?.topics) ? cfg.persona.topics.slice() : [],
  }
}

/** 人格标题：从正文第一行「你是「X」」里抠；抠不到用名字；都没有就说「人格」。 */
export function personaTitle(p) {
  const m = /你是[「『"]([^」』"]{1,16})[」』"]/.exec(p.text || '')
  return m ? m[1] : (p.names?.selfName || '人格')
}

/**
 * 交给 Agent 的那一段（系统提示用）。
 * 只拼**真实读到的东西**：正文 + 身份 + 风格 + 话题。读不到正文时只给身份，并且明说。
 */
export function personaPromptText(p, opts = {}) {
  if (!p.ok && !p.identity) return ''
  const parts = []
  if (p.ok) parts.push(p.body)
  else parts.push('## 人格\n\n（没找到人格卡正文：' + (p.reason === 'empty-persona' ? '文件是空的' : '文件不存在') + '。下面只对齐身份，不现编性格。）\n\n' + p.identity)
  if (p.style.trim()) parts.push('## 表达风格\n\n' + p.style.trim())
  if (p.topics.length) parts.push('## 主动开口时偏爱的话题\n\n' + p.topics.map((t) => '- ' + String(t)).join('\n'))
  return parts.filter(Boolean).join('\n\n').trim()
}
