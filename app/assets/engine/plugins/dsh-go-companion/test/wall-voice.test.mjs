// A3 墙说的话：每个判定码都有一句固定话、没有模型调用、气泡只有三种。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { AUDIT_CODES, GROWTH_MIN_BYTES, auditTask } from '../lib/wall_auditor.js'
import {
  APPEAL_CODES, APPEAL_MAX_PER_DAY, BUBBLE_KINDS, VOICE_TEMPLATES, appealLeft, appealVoice,
  ruleVoice, verdictVoice, wallThread,
} from '../lib/wall_voice.js'

const NOW = 1_700_000_000_000
const SINCE = NOW - 3_600_000

/** 造一个「审一件事」的上下文。 */
const ctx = (over = {}) => ({ now: NOW, since: SINCE, notesDir: '/notes', stat: () => null, screenshots: [], usage: [], ...over })

/** 走遍 auditTask 的每一条分支，收集它真正能吐出来的码。 */
function driveAllBranches() {
  const seen = new Map()
  const take = (task, over) => {
    const r = auditTask(task, ctx(over))
    seen.set(r.code, { code: r.code, vars: r.vars, r })
  }
  // file：不存在 / 立项后没动过 / 长了但不够 / 一个字节没长 / 够了
  take({ task: 'a', evidence: '大肥鱼/周报.md' }, { stat: () => null })
  take({ task: 'a', evidence: '大肥鱼/周报.md' }, { stat: () => ({ size: 500, mtimeMs: SINCE - 1000 }), baseline: { path: '/notes/大肥鱼/周报.md', size: 500 } })
  take({ task: 'a', evidence: '大肥鱼/周报.md' }, { stat: () => ({ size: 612, mtimeMs: NOW }), baseline: { path: '/notes/大肥鱼/周报.md', size: 500 } })
  take({ task: 'a', evidence: '大肥鱼/周报.md' }, { stat: () => ({ size: 500, mtimeMs: NOW }), baseline: { path: '/notes/大肥鱼/周报.md', size: 500 } })
  take({ task: 'a', evidence: '大肥鱼/周报.md' }, { stat: () => ({ size: 900, mtimeMs: NOW }), baseline: { path: '/notes/大肥鱼/周报.md', size: 500 } })
  // screenshot：有 / 没有
  take({ task: 'b', evidence: '截图' }, { screenshots: [{ name: 'a.png', mtimeMs: NOW }] })
  take({ task: 'b', evidence: '截图' }, { screenshots: [{ name: 'a.png', mtimeMs: SINCE - 1000 }] })
  // app：够 / 不够 / 0 分钟 / 记录里没有
  take({ task: 'c', evidence: '刷题App 40分钟' }, { usage: [{ label: '刷题App', minutes: 55 }] })
  take({ task: 'c', evidence: '刷题App 40分钟' }, { usage: [{ label: '刷题App', minutes: 12 }] })
  take({ task: 'c', evidence: '刷题App 40分钟' }, { usage: [{ label: '刷题App', minutes: 0 }] })
  take({ task: 'c', evidence: '刷题App 40分钟' }, { usage: [{ label: '别的App', minutes: 90 }] })
  // 查不了的完成物
  take({ task: 'd', evidence: '我今天很努力' }, {})
  return [...seen.values()]
}

test('A3 判定码枚举：auditTask 能吐的每一个码都在 AUDIT_CODES 里，反过来也都真能吐出来', () => {
  const driven = driveAllBranches()
  const drivenCodes = new Set(driven.map((d) => d.code))
  // 少配了：auditor 加了分支没登记，这里是第一个响的地方。
  for (const c of drivenCodes) assert.ok(AUDIT_CODES.includes(c), 'auditor 吐了一个没登记的码：' + c)
  // 多配了 / 走不到：枚举里列了但没有任何分支能产出来，等于死模板。
  for (const c of AUDIT_CODES) assert.ok(drivenCodes.has(c), '枚举里有这个码，但没有任何分支能产出它：' + c)
  assert.equal(drivenCodes.size, AUDIT_CODES.length)
})

test('A3 一个判定码一句固定话：遍历枚举，缺一条模板就失败，且不留没填的占位符', () => {
  for (const d of driveAllBranches()) {
    assert.ok(Object.prototype.hasOwnProperty.call(VOICE_TEMPLATES, d.code), '缺模板：' + d.code)
    const line = verdictVoice(d.code, d.vars)
    assert.ok(line.length > 0, d.code + ' 说了句空话')
    assert.ok(!/\{\w+\}/.test(line), d.code + ' 里还有没填的占位符：' + line)
    // 冷、短、带数字：每句话都要有「要求」和「实测」。
    assert.ok(line.includes('要求'), d.code + ' 没说要求：' + line)
    assert.ok(line.includes('实测'), d.code + ' 没说实测：' + line)
    assert.ok(line.length <= 80, d.code + ' 太长，墙不说话这么多：' + line)
  }
  // 模板表不能比枚举多（多了就是给不存在的码配话）。
  for (const code of Object.keys(VOICE_TEMPLATES)) assert.ok(AUDIT_CODES.includes(code), '给不存在的码配了话：' + code)
  assert.equal(Object.keys(VOICE_TEMPLATES).length, AUDIT_CODES.length)
})

test('A3 话里带的是真数字，不是占位', () => {
  // 长了 112 字节、要求 300：差 188。
  const r = auditTask({ task: 'a', evidence: '大肥鱼/周报.md' }, ctx({
    stat: () => ({ size: 612, mtimeMs: NOW }), baseline: { path: '/notes/大肥鱼/周报.md', size: 500 },
  }))
  assert.equal(r.code, 'file:short')
  const line = verdictVoice(r.code, r.vars)
  assert.ok(line.includes('+112'), line)
  assert.ok(line.includes(String(GROWTH_MIN_BYTES)), line)
  assert.ok(line.includes('差 ' + (GROWTH_MIN_BYTES - 112)), line)
  // 够了的那句不带「差」。
  const pass = verdictVoice('file:pass', { min: 300, grew: 400 })
  assert.ok(pass.includes('+400'), pass)
  assert.ok(!pass.includes('差'), pass)
})

test('A3 没配的码直接抛，不返回兜底文本', () => {
  assert.throws(() => verdictVoice('file:nope', {}), /没有这个判定码的模板/)
  assert.throws(() => appealVoice('appeal:nope'), /没有这个申诉结论的模板/)
})

test('A3 三种气泡：提交 / 判定 / 规则变更，别的都不许有', () => {
  assert.deepEqual([...BUBBLE_KINDS], ['submit', 'verdict', 'rule'])
})

test('A3 线程：账本还原成倒序气泡，提交和判定成对出现', () => {
  const entries = [
    { state: 'wall-audit', at: NOW - 3000, kind: '写周报', pass: true, by: 'companion' },
    { state: 'wall-audit', at: NOW - 2000, kind: '刷题', pass: false, by: '他', missing: ['还差 28 分钟'] },
    { state: 'verdict', at: NOW - 1000, kind: 'morning', satisfied: false, summary: '晨间立项没过：完成物查不了。' },
    { state: 'rule-change', at: NOW - 500, by: '他', label: '每周放松天数', from: 3, to: 5, effect: 'pending' },
    // 太旧的丢掉：14 天窗口外。
    { state: 'wall-audit', at: NOW - 20 * 86_400_000, kind: '老事', pass: true },
  ]
  const t = wallThread(entries, { now: NOW })
  const kinds = t.bubbles.map((b) => b.kind)
  assert.ok(kinds.includes('submit'), kinds.join(','))
  assert.ok(kinds.includes('verdict'), kinds.join(','))
  assert.ok(kinds.includes('rule'), kinds.join(','))
  // 倒序：最新的在最上面。
  for (let i = 1; i < t.bubbles.length; i += 1) assert.ok(t.bubbles[i - 1].at >= t.bubbles[i].at, '不是倒序')
  // 20 天前那条不在。
  assert.ok(!t.bubbles.some((b) => b.text.includes('老事')), JSON.stringify(t.bubbles))
  // 没过的判定要把「还缺」说出来。
  const bad = t.bubbles.find((b) => b.kind === 'verdict' && b.pass === false && b.text.includes('还差'))
  assert.ok(bad, JSON.stringify(t.bubbles.map((b) => b.text)))
  // 规则变更那句带上前后值和「冷却期内不生效」。
  const rule = t.bubbles.find((b) => b.kind === 'rule')
  assert.ok(rule.text.includes('每周放松天数'), rule.text)
  assert.ok(rule.text.includes('3 → 5'), rule.text)
  assert.ok(rule.text.includes('冷却期'), rule.text)
})

test('A3 线程：空账本给空数组，界面显示空状态；脏数据不抛', () => {
  assert.deepEqual(wallThread([], { now: NOW }).bubbles, [])
  assert.deepEqual(wallThread(null, { now: NOW }).bubbles, [])
  assert.deepEqual(wallThread([null, 5, {}, { state: 'wall-audit' }], { now: NOW }).bubbles, [])
  // limit 生效，且至少给一条。
  const many = Array.from({ length: 30 }, (_, i) => ({ state: 'wall-audit', at: NOW - i * 1000, kind: 'k' + i, pass: true }))
  assert.equal(wallThread(many, { now: NOW, limit: 5 }).bubbles.length, 5)
  assert.ok(wallThread(many, { now: NOW, limit: 0 }).bubbles.length >= 1)
})

test('A3 申诉额度：每天 2 次，用完就说用完', () => {
  const dayStart = NOW - 86_400_000
  assert.equal(APPEAL_MAX_PER_DAY, 2)
  assert.equal(appealLeft([], dayStart), 2)
  assert.equal(appealLeft([{ state: 'appeal', at: NOW }], dayStart), 1)
  assert.equal(appealLeft([{ state: 'appeal', at: NOW }, { state: 'appeal', at: NOW - 1 }], dayStart), 0)
  // 昨天的额度不算今天的。
  assert.equal(appealLeft([{ state: 'appeal', at: dayStart - 1 }], dayStart), 2)
  // 判定本身不占额度。
  assert.equal(appealLeft([{ state: 'verdict', at: NOW }, { state: 'wall-audit', at: NOW }], dayStart), 2)
  // 脏数据不抛。
  assert.equal(appealLeft(null, dayStart), 2)
})

test('A3 申诉的四种结论都有话，且都不是商量的语气', () => {
  for (const code of APPEAL_CODES) {
    const t = appealVoice(code)
    assert.ok(t.length > 0, code + ' 是空话')
    assert.ok(/驳回|重算/.test(t), code + ' 没有结论：' + t)
  }
})

test('A3 源码扫描：wall_voice.js 里不出现任何模型调用', () => {
  const src = readFileSync(new URL('../lib/wall_voice.js', import.meta.url), 'utf8')
  // 这些是「问模型」的痕迹。墙只读证据，一个字都不问。
  const banned = [/askModel/, /callModel/, /invokeModel/, /\bllm\b/i, /chatCompletion/i, /\bopenai\b/i, /\bgemini\b/i, /fetch\s*\(/, /https?:\/\//]
  for (const re of banned) assert.ok(!re.test(src), 'wall_voice.js 里出现了模型调用痕迹：' + re)
  // 它只该 import 审计码这一样东西。
  const imports = [...src.matchAll(/^import .*from '(.+)'$/gm)].map((m) => m[1])
  assert.deepEqual(imports, ['./wall_auditor.js'])
})
