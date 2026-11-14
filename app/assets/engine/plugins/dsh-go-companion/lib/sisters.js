// dsh-go-companion · R4-5 姐妹界面
//
// 只补 panelSnapshot() 里没有的两块：角色卡头部，和「今日审计 + 近 14 天完成率」。
// 「她们记得的你」「最近对话」「墙此刻锁没锁」已经在 buildSnapshot 里算好了，这里不重算。
//
// 不读盘、不查库：真实读取在 index.js 做完传进来，这一层才能脱机单测。

import { COMPANION_LIFE, DEFAULT_NAMES, STRATEGIST_LIFE } from './roles.js'
import { RATE_DAYS, loadWallMemory, recentRate } from './wall_auditor.js'

export const SISTERS = ['companion', 'strategist']

// 一句话定位。取自 roles.js 文件头对两人的定义：妹妹陪玩、姐姐管正事。
const LINE = {
  companion: '陪你玩的那个。有脾气、嘴欠、会吃醋；正事不归她。',
  strategist: '出主意的那个。冷静、看得远；把局面摊开，给这周的第一步。',
}

const SETTING = /^你的设定（前后要一致）：/

export function sisterLine(role) {
  return LINE[role] || ''
}

/** 固定个人设定，去掉写给模型看的抬头，当「人设」给用户看。 */
export function personaOf(role) {
  const raw = role === 'strategist' ? STRATEGIST_LIFE : COMPANION_LIFE
  const first = String(raw || '')
    .split('\n')
    .map((s) => s.trim())
    .find(Boolean) || ''
  return first.replace(SETTING, '').trim().slice(0, 200)
}

/** 两张角色卡。顺序固定：妹妹在前、姐姐在后。 */
export function cardsOf(names) {
  const n = names || DEFAULT_NAMES
  return SISTERS.map((role) => ({
    role,
    name: String((n && n[role]) || DEFAULT_NAMES[role]).slice(0, 20),
    line: sisterLine(role),
    persona: personaOf(role),
  }))
}

/** 引擎会给自己那轮回话前面加〔标签〕，转述前先摘掉。 */
const TAG = /^〔[^〕]{1,20}〕/

/**
 * 家庭群这一轮真正发进两个会话的原话。
 *
 * 两个人拿到的是同一句开场，后说的那个多一条先说的原话——就是聊天记录的样子。
 * 以前后说的那个会额外收到「你接着说：可以接她的话，也可以反驳她，别重复她」，
 * 等于给模型发导演提示，两个人一眼就能看出是在演戏；这里不加。
 *
 * @param {object} input
 * @param {{companion?: string, strategist?: string}} [input.names]
 * @param {string} input.said       他发的那句
 * @param {string} [input.first]    先说的那个已经回出来的原话；还没回就留空
 * @param {string} [input.firstName] 先说的那个叫什么
 * @returns {string}
 */
export function groupPrompts({ names, said, first, firstName } = {}) {
  const n = names || DEFAULT_NAMES
  const who = [n.companion || DEFAULT_NAMES.companion, n.strategist || DEFAULT_NAMES.strategist]
  const head = '（家庭群，他、' + who[0] + '、' + who[1] + ' 都在）'
  const body = head + String(said || '').trim().slice(0, 1000)
  const line = String(first || '').replace(TAG, '').trim().slice(0, 300)
  if (!line) return body
  const who2 = String(firstName || '').trim()
  return body + '\n\n' + (who2 ? who2 + '：' : '') + line
}

/**
 * 今日审计面板。
 * @param {object} input
 * @param {object} input.memory    wall_memory.json 的原始内容（loadWallMemory 收）
 * @param {string} input.auditText 大肥鱼/墙/审计/<今天>.md 的原话；没有就空
 * @param {string} input.today     YYYY-MM-DD（当地时间）
 * @param {number} [input.days]    完成率窗口，默认和墙自己报的一致（14 天）
 */
export function auditOf({ memory, auditText, today, days = RATE_DAYS } = {}) {
  const mem = loadWallMemory(memory)
  const day = (today && mem.days[today]) || {}
  const { done, total } = recentRate(mem, today, days)
  const promised = Math.max(0, Math.round(Number(day.promised) || 0))
  const finished = Math.max(0, Math.round(Number(day.done) || 0))
  return {
    day: String(today || ''),
    promised,
    done: finished,
    rateDone: done,
    rateTotal: total,
    ratePct: total > 0 ? Math.round((done / total) * 100) : 0,
    rateDays: days,
    text: String(auditText || '').trim().slice(0, 2000),
  }
}
