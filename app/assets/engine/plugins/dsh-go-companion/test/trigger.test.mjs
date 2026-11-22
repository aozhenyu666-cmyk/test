// 2026-11-18 触发器：Gemini 设定 + 当下事实 + 回执。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { contextLines, DEFAULT_GEMINI_SETTING, loadGeminiSetting, parseReceipt, settingLines } from '../lib/trigger.js'
import { geminiBrief } from '../lib/gemini.js'

test('设定：笔记库没有就写一份默认的；他改过就用他的', () => {
  const dir = mkdtempSync(join(tmpdir(), 'trigger-'))
  try {
    const kb = join(dir, '大肥鱼')
    assert.equal(loadGeminiSetting(kb), DEFAULT_GEMINI_SETTING)
    assert.match(readFileSync(join(kb, 'Gemini设定.md'), 'utf8'), /验收器/)
    mkdirSync(kb, { recursive: true })
    writeFileSync(join(kb, 'Gemini设定.md'), '# 我的\n- 说话狠一点\n')
    assert.deepEqual(settingLines(loadGeminiSetting(kb)), ['说话狠一点'])
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('当下事实：只写读得到的；什么都不知道时让 Gemini 先问一个具体问题', () => {
  assert.match(contextLines({}).join('\n'), /只问一个具体问题/)
  const lines = contextLines({ front: '哔哩哔哩', frontMinutes: 23, funMinutesToday: 95,
    bout: { title: '行测资料分析', firstStep: '打开粉笔做 5 道增长率' },
    deadlines: [{ title: '国考报名', daysLeft: 3 }], dice: { dice: 4, name: '关键提问', ask: '哪个未知条件一变结论就翻盘？' } }).join('\n')
  for (const want of ['哔哩哔哩', '23 分钟', '行测资料分析', '粉笔', '95 分钟', '国考报名 还剩 3 天', '关键提问']) assert.ok(lines.includes(want), want)
})

test('简报：带上他的设定、当下事实和完整回执格式', () => {
  const brief = geminiBrief({ topic: '资料分析', setting: '- 不许说加油', context: ['他此刻前台：粉笔'] })
  assert.match(brief, /不许说加油/)
  assert.match(brief, /他此刻前台：粉笔/)
  assert.match(brief, /【复盘】做了什么：…… 结论：…… 卡在哪：…… 下一步：…… 几点前：…… 证据：……/)
  assert.match(brief, /出回执/)
})

test('回执：抠出六项；缺结论 / 下一步 / 几点前就不算完整；没有【复盘】= 无产出', () => {
  const r = parseReceipt('好的。【复盘】做了什么：做了 12 道增长率 结论：基期量公式记反了 卡在哪：混合增长率 下一步：再做 10 道混合增长率 几点前：21:30 证据：粉笔练习记录截图')
  assert.equal(r.summary, '做了 12 道增长率')
  assert.equal(r.conclusion, '基期量公式记反了')
  assert.equal(r.by, '21:30')
  assert.equal(r.evidence, '粉笔练习记录截图')
  assert.equal(r.complete, true)
  assert.equal(parseReceipt('【复盘】做了什么：聊了聊').complete, false)
  assert.equal(parseReceipt('今天聊得不错'), null)
})
