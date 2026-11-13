// B3 素材池：例句池（文件格式/读取/去重/最近排除/薄池不可选）+ 关键词卡 + depth 预算。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, utimesSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  BANK_DIR, FACES, KEYWORD_MAX, MIN_POOL, RECENT_SKIP, SEED,
  countWords, depthBudget, depthFactor, keywordCard, parseSamples,
  pickSamples, readManualKeywords, readSamples, recentNoteTexts, SAMPLES_FILE, KEYWORDS_FILE,
} from '../lib/samples.js'
import { fitBudget } from '../lib/budget.js'

/** 造一个临时笔记库：$dir/大肥鱼/素材/例句.md */
function bank(files = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-bank-'))
  const d = join(dir, BANK_DIR)
  mkdirSync(d, { recursive: true })
  for (const [name, text] of Object.entries(files)) writeFileSync(join(d, name), text, 'utf8')
  return dir
}

const seq = (n) => Array.from({ length: n }, (_, i) => '第' + (i + 1) + '句。')

// ---- 文件格式与读取 -----------------------------------------------------------

test('S1 例句.md：## 档名分段，一行一句，列表符号和序号都去掉', () => {
  const got = parseSamples('## coax\n- 第一句\n1. 第二句\n\n* 第三句\n## jab\n第四句\n')
  assert.deepEqual(got, { coax: ['第一句', '第二句', '第三句'], jab: ['第四句'] })
})

test('S2 认不出的档名整段忽略，不会把句子塞进上一个档', () => {
  const got = parseSamples('## coax\n甲\n## 不认识\n乙\n## jab\n丙\n')
  assert.deepEqual(got, { coax: ['甲'], jab: ['丙'] })
})

test('S3 没有 ## 开头的句子不猜档，直接丢弃', () => {
  assert.deepEqual(parseSamples('甲\n乙\n'), {})
})

test('S4 没有文件时五个档都用内置种子，且每档 ≥ 15 句', () => {
  const pool = readSamples('')
  for (const face of FACES) {
    assert.ok(Array.isArray(pool[face]), face + ' 要有种子')
    assert.ok(pool[face].length >= 15, face + ' 种子不足 15 句：' + pool[face].length)
  }
  // 种子等于常量本身（不是被谁改过的副本）
  assert.deepEqual(pool.coax, SEED.coax)
})

test('S5 文件里的档整档替换种子：用户删掉的句子就该真的消失', () => {
  const dir = bank({ [SAMPLES_FILE]: '## jab\n只剩这一句。\n' })
  const pool = readSamples(dir)
  assert.deepEqual(pool.jab, ['只剩这一句。'])
  // 没写到的档仍用种子
  assert.deepEqual(pool.coax, SEED.coax)
})

test('S6 文件写了档名但一句都没有时不覆盖种子（空标题不该把池子清空）', () => {
  const dir = bank({ [SAMPLES_FILE]: '## jab\n## coax\n真的这句\n' })
  const pool = readSamples(dir)
  assert.deepEqual(pool.jab, SEED.jab)
  assert.deepEqual(pool.coax, ['真的这句'])
})

test('S7 写坏的素材文件不该让整个池子消失', () => {
  const dir = bank({ [SAMPLES_FILE]: '\u0000\u0001 乱码 \uffff\n' })
  const pool = readSamples(dir)
  for (const face of FACES) assert.ok(pool[face].length >= 15, face + ' 兜底没了')
})

// ---- 抽样 -------------------------------------------------------------------

test('S8 抽中的句子来自池子，且一次最多两句', () => {
  const pool = seq(20)
  const got = pickSamples(pool, [], () => 0.5)
  assert.ok(got.length >= 1 && got.length <= 2)
  for (const s of got) assert.ok(pool.includes(s))
  // 不给自己重复
  assert.equal(new Set(got).size, got.length)
})

test('S9 最近用过的句子要避开', () => {
  const pool = seq(30)
  // 最近用过的 = 最后 RECENT_SKIP 句（更早的已经滚出窗口，见 S10）
  const recent = pool.slice(-RECENT_SKIP)
  for (let i = 0; i < 20; i++) {
    const got = pickSamples(pool, recent, () => i / 20)
    assert.ok(got, '剩 20 句可选，不该是 null')
    for (const s of got) assert.ok(!recent.includes(s), '抽到了最近用过的：' + s)
  }
})

test('S10 只记最近 RECENT_SKIP 句：更早用过的可以回来', () => {
  const pool = seq(20)
  // 20 句全部用过，但只有最后 10 句算"最近"——剩下 10 句仍然可选
  assert.equal(RECENT_SKIP, 10)
  const got = pickSamples(pool, pool, () => 0)
  assert.ok(got, '全部用过也不该直接不可选（只避开最近 10 句）')
  for (const s of got) assert.ok(!pool.slice(-RECENT_SKIP).includes(s))
})

test('S11 可用句子不足 MIN_POOL 时返回 null——这个 arm 当轮不可选，不许空池硬凑', () => {
  assert.equal(MIN_POOL, 5)
  const pool = seq(6)
  // 6 句里最近用过 2 句 → 只剩 4 句 < 5 → null
  assert.equal(pickSamples(pool, pool.slice(0, 2), () => 0.5), null)
  // 只用了 1 句 → 剩 5 句 → 可以选
  assert.ok(pickSamples(pool, pool.slice(0, 1), () => 0.5))
})

test('S12 空池 / 未知脸 / 脏输入都返回 null，不抛错', () => {
  assert.equal(pickSamples([], [], () => 0.5), null)
  assert.equal(pickSamples(null, null, () => 0.5), null)
  assert.equal(pickSamples([undefined, '', null], [], () => 0.5), null)
})

// ---- 关键词卡 ---------------------------------------------------------------

test('S13 关键词卡三处合并去重，手写优先，最多 KEYWORD_MAX 个', () => {
  assert.equal(KEYWORD_MAX, 3)
  const got = keywordCard({
    manual: ['手写一', '手写二'],
    links: ['手写一', '链接一', '链接二'],
    fresh: ['链接一', '高频一', '高频二'],
  })
  assert.deepEqual(got, ['手写一', '手写二', '链接一'])
})

test('S14 关键词卡剔掉停用词、过长词和空串', () => {
  const got = keywordCard({
    manual: ['', '  ', '这个', '一个特别特别特别长的词条不该进来'],
    links: [],
    fresh: ['写论文'],
  })
  assert.deepEqual(got, ['写论文'])
})

test('S15 手写关键词文件：一行一个，注释和空行不要', () => {
  const dir = bank({ [KEYWORDS_FILE]: '\n// 这是注释\n背单词\n\n  跑步  \n' })
  assert.deepEqual(readManualKeywords(dir), ['背单词', '跑步'])
  assert.deepEqual(readManualKeywords(''), [])
})

test('S16 词频统计：中文二字以上，按次数降序，停用词不算', () => {
  const words = countWords(['写论文 写论文 这个', '跑步 写论文'])
  assert.equal(words[0], '写论文')
  assert.ok(!words.includes('这个'))
})

test('S17 近 7 天笔记：只收 .md、只收最近改过的、读不到的目录就当没有', () => {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-notes-'))
  const now = Date.now()
  mkdirSync(join(dir, 'sub'), { recursive: true })
  writeFileSync(join(dir, 'new.md'), '新的写论文', 'utf8')
  writeFileSync(join(dir, 'sub', 'nested.md'), '嵌套的跑步', 'utf8')
  writeFileSync(join(dir, 'old.md'), '旧的消失了', 'utf8')
  writeFileSync(join(dir, 'note.txt'), '不是 md', 'utf8')
  // 把 old.md 改成 30 天前
  const past = (now - 30 * 86_400_000) / 1000
  utimesSync(join(dir, 'old.md'), past, past)

  const texts = recentNoteTexts(dir, now)
  const all = texts.join('\n')
  assert.ok(all.includes('新的写论文'))
  assert.ok(all.includes('嵌套的跑步'), '子目录也要收')
  assert.ok(!all.includes('旧的消失了'), '30 天前的不该收')
  assert.ok(!all.includes('不是 md'))
  // 目录不存在不抛错
  assert.deepEqual(recentNoteTexts(join(dir, 'nope'), now), [])
  assert.deepEqual(recentNoteTexts('', now), [])
})

// ---- depth 与预算 -----------------------------------------------------------

test('S18 depth 三档是 30% / 60% / 100%，认不出的档按 100%', () => {
  assert.equal(depthFactor('low'), 0.3)
  assert.equal(depthFactor('mid'), 0.6)
  assert.equal(depthFactor('high'), 1)
  assert.equal(depthFactor(undefined), 1)
  assert.equal(depthFactor('乱写'), 1)
})

test('S19 depth 只压内容层，状态层和约束层一个字不动', () => {
  const base = { total: 2400, perLayer: { constraint: 2100, state: 1000, content: 500 } }
  const low = depthBudget('low', base)
  assert.equal(low.perLayer.content, 150)   // 500 * 0.3
  assert.equal(low.perLayer.state, 1000)
  assert.equal(low.perLayer.constraint, 2100)
  assert.equal(low.total, 2400)
  // 不改进来的对象
  assert.equal(base.perLayer.content, 500)
})

test('S20 depth=high 时预算原样透传（默认行为一字不差）', () => {
  const base = { total: 2400, perLayer: { content: 500 } }
  assert.equal(depthBudget('high', base), base)
  assert.equal(depthBudget(undefined, base), base)
  assert.equal(depthBudget('high', undefined), undefined)
})

test('S21 depth 真的影响 fitBudget 装得下几块内容', () => {
  const blocks = [
    { id: 'persona', layer: 'constraint', priority: 100, text: 'x'.repeat(100) },
    { id: 'vibe', layer: 'content', priority: 20, text: 'y'.repeat(200) },
    { id: 'notes', layer: 'content', priority: 10, text: 'z'.repeat(200) },
  ]
  // 100%：两块内容都装得下
  const full = fitBudget(blocks, depthBudget('high', { total: 2400, perLayer: { content: 500 } }))
  assert.deepEqual(full.kept.filter((id) => id === 'vibe' || id === 'notes').sort(), ['notes', 'vibe'])
  // 30%：内容层只剩 150 字，一块都装不下（每块 200 字）
  const low = fitBudget(blocks, depthBudget('low', { total: 2400, perLayer: { content: 500 } }))
  assert.deepEqual(low.kept.filter((id) => id === 'vibe' || id === 'notes'), [])
  assert.equal(low.dropped.filter((d) => d.reason === 'layer').length, 2)
  // 约束层照旧全装
  assert.ok(low.kept.includes('persona'))
})
