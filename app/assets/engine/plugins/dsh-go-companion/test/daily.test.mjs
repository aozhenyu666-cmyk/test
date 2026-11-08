// 每日文件 + 按完成物对账（第二轮）。
//
// 对账不看说法只看证据：知识库文件、Gemini 复盘账本、App 使用时长。
// 时间全部注入，不依赖真实时钟。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  checkEvidence, dailyPath, dayString, keywordsOf, loadDaily, normalizeTasks,
  reconcile, renderDaily, reviewText, writeDaily,
} from '../lib/daily.js'

const TZ = 'Asia/Shanghai'
// 2026-11-08 20:00 +08:00
const NOW = Date.UTC(2026, 10, 8, 12, 0, 0)

function withDir(fn) {
  const dir = mkdtempSync(join(tmpdir(), 'companion-daily-'))
  try {
    return fn(dir)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

test('按手机时区算日期、拼每日文件路径', () => {
  assert.equal(dayString(NOW, TZ), '2026-11-08')
  // 同一时刻：UTC 还是 7 号 17:00，+08:00 已经是 8 号凌晨——不能串天。
  const edge = Date.UTC(2026, 10, 7, 17, 0, 0)
  assert.equal(dayString(edge, 'UTC'), '2026-11-07')
  assert.equal(dayString(edge, TZ), '2026-11-08')
  const p = dailyPath('/kb', NOW, TZ)
  assert.equal(p.replace(/\\/g, '/'), '/kb/每日/2026-11-08.md')
})

test('早上定事：每件事都必须带一个看得见的完成物', () => {
  assert.equal(normalizeTasks([]).ok, false)
  assert.equal(normalizeTasks([{ task: '写申论' }]).ok, false, '没完成物不收')
  const ok = normalizeTasks([{ task: '写申论', evidence: '大肥鱼/判断.md 里写一段' }])
  assert.equal(ok.ok, true)
  assert.equal(ok.tasks.length, 1)
  // 空的 task 整条丢掉，不占额
  assert.equal(normalizeTasks([{ task: '', evidence: 'x' }, { task: 'A', evidence: 'B' }]).tasks.length, 1)
})

test('关键词：去掉标点，最多 4 个', () => {
  assert.deepEqual(keywordsOf('线代第三章，做完课后题'), ['线代第三章', '做完课后题'])
  assert.ok(keywordsOf('一 二 三 四 五 六').length <= 4)
  assert.deepEqual(keywordsOf(''), [])
})

test('证据一：完成物写了文件路径，文件今天改过就算做完', () => {
  withDir((dir) => {
    const f = join(dir, '判断.md')
    writeFileSync(f, '# 判断\n')
    const r = checkEvidence({ task: '写判断', evidence: f }, { now: NOW, tz: TZ, notes: [] })
    assert.equal(r.verdict, 'done', r.why)
  })
})

test('证据一：今天没动过的文件不算证据', () => {
  withDir((dir) => {
    const f = join(dir, '旧.md')
    writeFileSync(f, '# 旧\n')
    // 一天以后再对账：文件不是今天改的
    const r = checkEvidence({ task: '写笔记', evidence: f }, { now: NOW + 86_400_000, tz: TZ, notes: [] })
    assert.equal(r.verdict, 'miss', r.why)
  })
})

test('证据二：今天动过的笔记里有完成物的关键词', () => {
  const notes = [{ path: '/kb/大肥鱼/判断.md', rel: '大肥鱼/判断.md', text: '今天把线代第三章的课后题做完了，错了两道' }]
  const r = checkEvidence({ task: '线代', evidence: '线代第三章 课后题' }, { now: NOW, tz: TZ, notes })
  assert.equal(r.verdict, 'done', r.why)
  // 只命中一半：记一半
  const half = checkEvidence({ task: 'x', evidence: '线代第三章 面试复盘' }, { now: NOW, tz: TZ, notes })
  assert.equal(half.verdict, 'partial', half.why)
})

test('证据三：今天有一条 Gemini 复盘对得上', () => {
  const ledger = [{ state: 'gemini-recap', at: NOW - 60_000, topic: '线代第三章', summary: '讲完了矩阵' }]
  const r = checkEvidence({ task: '连麦', evidence: '线代第三章' }, { now: NOW, tz: TZ, ledger, notes: [] })
  assert.equal(r.verdict, 'done', r.why)
  // 昨天的复盘不能给今天作证
  const old = checkEvidence({ task: '连麦', evidence: '线代第三章' }, {
    now: NOW, tz: TZ, notes: [], ledger: [{ state: 'gemini-recap', at: NOW - 86_400_000, topic: '线代第三章' }],
  })
  assert.equal(old.verdict, 'miss', old.why)
})

test('证据四：App 使用时长要够完成物里写的分钟数', () => {
  const usage = [{ label: 'Anki', minutes: 40 }]
  const done = checkEvidence({ task: '背单词', evidence: 'Anki 用够 30 分钟' }, { now: NOW, tz: TZ, usage, notes: [] })
  assert.equal(done.verdict, 'done', done.why)
  const part = checkEvidence({ task: '背单词', evidence: 'Anki 用够 60 分钟' }, { now: NOW, tz: TZ, usage, notes: [] })
  assert.equal(part.verdict, 'partial', part.why)
})

test('什么都没有就是没做完', () => {
  const r = checkEvidence({ task: '写申论', evidence: '申论大作文一篇' }, { now: NOW, tz: TZ, notes: [], ledger: [], usage: [] })
  assert.equal(r.verdict, 'miss')
})

test('对账的额度后果只作用于明天：全做完 +1、全没做 -1、一半 0', () => {
  const notes = [{ path: '/kb/a.md', rel: 'a.md', text: '线代第三章 课后题 全做完了' }]
  const all = reconcile(
    { day: '2026-11-08', tasks: [{ task: '线代', evidence: '线代第三章 课后题' }] },
    { now: NOW, tz: TZ, notes },
  )
  assert.equal(all.done, 1)
  assert.equal(all.quotaDelta, 1)

  const none = reconcile(
    { day: '2026-11-08', tasks: [{ task: '写申论', evidence: '申论大作文' }] },
    { now: NOW, tz: TZ, notes: [] },
  )
  assert.equal(none.miss, 1)
  assert.equal(none.quotaDelta, -1)

  const mixed = reconcile(
    { day: '2026-11-08', tasks: [
      { task: '线代', evidence: '线代第三章 课后题' },
      { task: '写申论', evidence: '申论大作文' },
    ] },
    { now: NOW, tz: TZ, notes },
  )
  assert.equal(mixed.done, 1)
  assert.equal(mixed.miss, 1)
  assert.equal(mixed.quotaDelta, 0)
})

test('早上没定事就没有可对的账', () => {
  const out = reconcile({ day: '2026-11-08', tasks: [] }, { now: NOW, tz: TZ, notes: [] })
  assert.equal(out.total, 0)
  assert.equal(out.quotaDelta, 0)
  assert.match(reviewText(out), /没定事/)
})

test('机器状态按天清：昨天的 daily.json 不能带到今天', () => {
  const raw = { day: '2026-11-07', tasks: [{ task: '旧的', evidence: '旧的' }], review: { done: 1 } }
  const s = loadDaily(raw, NOW, TZ)
  assert.equal(s.day, '2026-11-08')
  assert.deepEqual(s.tasks, [], '过了零点就是新的一天')
  assert.equal(s.review, null)
  // 同一天要读回来
  const same = loadDaily({ day: '2026-11-08', tasks: [{ task: 'A', evidence: 'B' }] }, NOW, TZ)
  assert.equal(same.tasks.length, 1)
})

test('每日文件是人看的：写完能看到事、完成物和对账结论', () => {
  withDir((dir) => {
    const state = {
      day: '2026-11-08',
      tasks: [{ task: '线代第三章', evidence: '课后题做完' }],
      review: { done: 1, partial: 0, miss: 0, total: 1, quotaDelta: 1, results: [{ task: '线代第三章', why: '笔记里有', verdict: 'done' }] },
    }
    const w = writeDaily(dir, state)
    assert.equal(w.ok, true, w.text)
    const md = readFileSync(join(dir, '每日', '2026-11-08.md'), 'utf8')
    assert.match(md, /线代第三章/)
    assert.match(md, /完成物：课后题做完/)
    assert.match(md, /做完 1 件/)
    assert.match(md, /\+1 天/)
    // 只渲染不写盘也要稳
    assert.match(renderDaily({ day: '2026-11-08', tasks: [], review: null }), /（还没定）/)
  })
})
