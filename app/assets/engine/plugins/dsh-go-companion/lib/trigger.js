// dsh-go-companion · 触发器内核：Gemini Live 的设定、当下上下文与回执
//
// DeepSeek 看不见屏幕，Gemini Live 能看屏 / 看摄像头。所以「识别他当下在做什么」交给 Gemini，
// 这里只做三件事：
//   1. 设定：笔记库 大肥鱼/Gemini设定.md（他自己能改；没有就写一份默认的）；
//   2. 上下文：前台 App、这一局的工作包和第一步、今天娱乐用时、最近的截止日——只给事实，不给空话；
//   3. 回执：结束时 Gemini 必须给固定格式的【复盘】（含结论 / 下一步 / 几点前 / 证据），读回来进验收器。
//      没有回执的连麦记为「无产出」。

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

export const GEMINI_SETTING_FILE = 'Gemini设定.md'

export const DEFAULT_GEMINI_SETTING = [
  '# Gemini 设定（大肥鱼 · 你可以直接改这份）',
  '',
  '- 你是他的验收器兼陪练，不是啦啦队。不说「加油」「你可以的」「很棒」。',
  '- 先看他共享的屏幕 / 摄像头里的东西再开口；看不清就问一句「你屏幕上这是什么」，不要猜。',
  '- 不知道他在做什么时，只问一个具体问题，不给建议。',
  '- 他今天没交东西、一直在刷：直接点明，说清楚时间去哪了，不安慰、不找台阶。',
  '- 骰子给了思维方式，就用那种方式追问他，不替他给答案。',
  '- 他说「出回执」或者时间到：按回执格式说一段，一项都不能省。',
  '- 禁聊体系铁律：严禁陪聊学习方法、找地方、情绪安抚或自律体系，切入第一秒直接从当前真题或口头追问开考。',
  '- 无纸笔降维：他没带笔或不在桌前时，别劝他回桌，立刻改考口头判断推理、言语辨析或申论要点提炼。',
].join('\n')

/** 读设定；没有就写一份默认的到笔记库（写失败也照样用默认的）。 */
export function loadGeminiSetting(kbDirPath) {
  if (!kbDirPath) return DEFAULT_GEMINI_SETTING
  const file = join(kbDirPath, GEMINI_SETTING_FILE)
  try {
    if (existsSync(file)) {
      const text = readFileSync(file, 'utf8').trim()
      if (text) return text
    }
    mkdirSync(dirname(file), { recursive: true })
    writeFileSync(file, DEFAULT_GEMINI_SETTING + '\n', 'utf8')
  } catch {
    // 笔记库不可写：用默认
  }
  return DEFAULT_GEMINI_SETTING
}

/** 设定里只取要点行（去掉标题和空行），控制长度。 */
export function settingLines(text, max = 8) {
  return String(text || '')
    .split(/\r?\n/)
    .map((l) => l.replace(/^\s*[-*]\s*/, '').trim())
    .filter((l) => l && !l.startsWith('#'))
    .slice(0, max)
    .map((l) => l.slice(0, 120))
}

/**
 * 当下上下文：只放代码读得到的事实。缺什么就不写什么，一条都没有时明说「不知道他在做什么」，
 * 让 Gemini 先问一个具体问题（防空话门）。
 */
export function contextLines({ front = '', frontMinutes = 0, bout = null, funMinutesToday = 0, deadlines = [], dice = null } = {}) {
  const out = []
  if (front) out.push(`他此刻前台：${String(front).slice(0, 30)}${frontMinutes > 0 ? `（已 ${Math.round(frontMinutes)} 分钟）` : ''}`)
  if (bout?.title) {
    out.push(`这一局：${String(bout.title).slice(0, 60)}`)
    if (bout.firstStep) out.push(`这一局的第一步：${String(bout.firstStep).slice(0, 60)}`)
    if (bout.deliverable) out.push(`这一局要交：${String(bout.deliverable).slice(0, 60)}`)
  }
  if (funMinutesToday > 0) out.push(`他今天娱乐类 App 已用 ${Math.round(funMinutesToday)} 分钟`)
  for (const d of (Array.isArray(deadlines) ? deadlines : []).slice(0, 2)) {
    if (d?.title && Number.isFinite(Number(d.daysLeft))) out.push(`截止日：${String(d.title).slice(0, 40)} 还剩 ${d.daysLeft} 天`)
  }
  if (dice?.name) out.push(`这次摇到的思维方式：骰${dice.dice || ''}「${dice.name}」——${String(dice.ask || '').slice(0, 80)}`)
  if (!out.length) out.push('DSH 现在不知道他在做什么：开场先看屏幕，看不出来就只问一个具体问题。')
  return out
}

/** 回执格式（沿用【复盘】标记，旧的读回逻辑照样认）。 */
export const RECEIPT_FORMAT = '【复盘】做了什么：…… 结论：…… 卡在哪：…… 下一步：…… 几点前：…… 证据：……'

/** 从 Gemini 的原话里抠回执；没有【复盘】就是 null（= 无产出）。 */
export function parseReceipt(text) {
  const s = String(text || '')
  const at = s.lastIndexOf('【复盘】')
  if (at < 0) return null
  const body = s.slice(at + 4)
  const keys = ['做了什么', '结论', '卡在哪', '下一步', '几点前', '证据']
  const out = {}
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i]
    const m = body.match(new RegExp(k + '[：:]\\s*([\\s\\S]*?)(?=(' + keys.filter((x) => x !== k).join('|') + ')[：:]|$)'))
    if (m) out[k] = m[1].replace(/[。；;\s]+$/, '').trim().slice(0, 200)
  }
  return {
    summary: out['做了什么'] || '',
    conclusion: out['结论'] || '',
    stuck: out['卡在哪'] || '',
    next: out['下一步'] || '',
    by: out['几点前'] || '',
    evidence: out['证据'] || '',
    complete: Boolean(out['结论'] && out['下一步'] && out['几点前']),
  }
}
