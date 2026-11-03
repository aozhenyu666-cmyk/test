import { maxSimilarity } from './core/similar.js'
import { normalize } from './core/text.js'

export const INTERACTIONS = {
  recall: '回忆：用自己的话复述或纠正自己的历史记录。',
  challenge: '反驳：提出与上次判断相反的具体说法，让他举反例或指出漏洞。',
  teach: '教 Gemini：把刚才的要点念给 Gemini，并要求它挑一个漏洞。',
  task: '小任务：给出 30 秒内能完成的具体动作，例如在材料中定位关键条件。',
  connect: '连接主线：把眼前材料和他自己的主线目标连起来，给出具体使用场景。',
}
export const BLACKLIST = ['依据是什么', '下一步是什么', '说一个词']
export function formulaic(text) {
  const value = String(text ?? '')
  const question = value.includes('\n') ? value.slice(value.indexOf('\n') + 1) : value
  return question.split(/[\n。！？?!]/).filter((x) => x.trim()).some((clause) =>
    BLACKLIST.some((template) => normalize(clause).includes(template) || maxSimilarity(clause, [template]) >= 0.6))
}
export function materialsOf(ctx, reports) {
  const h = ctx.hidden, s = ctx.surface
  return [...new Set([
    ...h.unresolved, ...reports.map((r) => r.conclusion), ...h.memories,
    ...s.recentReplies, ...h.goals, s.task, s.topic,
  ].filter((x) => typeof x === 'string' && x.trim().length >= 3).map((x) => x.trim().slice(0, 100)))].slice(0, 24)
}
export function citesMaterial(text, materials) {
  const normalized = normalize(text)
  return materials.some((m) => normalized.includes(normalize(m)))
}
