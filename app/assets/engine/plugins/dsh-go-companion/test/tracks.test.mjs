// 第六轮 A · 支线底座
//
// 这里守的是两条硬验收：
//   ① 新增第三条支线时引擎层 diff = 0（只改一页配置）
//   ② 关掉全部支线，主干最小闭环仍跑通
// 以及 A 的定义性约束：业务词一个都不许进代码。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  MAX_TRACKS, SLOT_KEYS, SLOT_LABEL,
  analyzeTrack, normalizeTrack, normalizeTracks, pickTrack, recentTracks, trackLines, trackStatus,
} from '../lib/tracks.js'
import { buildPrompt } from '../lib/prompt.js'

const HERE = dirname(fileURLToPath(import.meta.url))
const SRC = readFileSync(join(HERE, '..', 'lib', 'tracks.js'), 'utf8')

/** 一条合格的支线（内容随便，反正它来自配置不来自代码）。 */
function t(id, extra = {}) {
  return {
    id,
    name: '方向' + id,
    scene: '场景' + id,
    first: '第一反应' + id,
    missed: '漏掉的' + id,
    blind: '盲区' + id,
    ...extra,
  }
}

// ---------------------------------------------------------------- 硬约束：业务词不进代码

test('A1 代码里不许出现业务词（业务只在配置里）', () => {
  for (const word of ['错题', '国考', '面试', 'Bug', 'bug', '考公', '刷题']) {
    assert.equal(SRC.includes(word), false, 'lib/tracks.js 里出现了业务词：' + word)
  }
})

test('A2 底座只认四个固定槽位，槽位名是协议', () => {
  assert.deepEqual(SLOT_KEYS, ['scene', 'first', 'missed', 'blind'])
  assert.deepEqual(Object.keys(SLOT_LABEL), ['scene', 'first', 'missed', 'blind'])
  assert.equal(SLOT_LABEL.scene, '触发场景')
  assert.equal(SLOT_LABEL.first, '第一反应判断')
  assert.equal(SLOT_LABEL.missed, '漏掉的条件或反例')
  assert.equal(SLOT_LABEL.blind, '盲区标签')
})

// ---------------------------------------------------------------- 硬验收①：加第三条只改配置

test('A3 新增第三条支线只靠配置，代码零改动（同一份配置数组越长越好用）', () => {
  const two = normalizeTracks([t('a'), t('b')])
  assert.equal(two.tracks.length, 2)
  // 第三条只是配置里多一个对象：没有任何代码分支认识它
  const three = normalizeTracks([t('a'), t('b'), t('c')])
  assert.equal(three.tracks.length, 3)
  assert.equal(three.dropped.length, 0)
  // 第三条能被真的挑到（不是只进了数组）
  const picked = pickTrack(three.tracks, ['a', 'b'], () => 0.99)
  assert.equal(picked.id, 'c')
  // 而且它照样能生成 prompt 行
  assert.match(trackLines(picked).join('\n'), /场景c/)
})

test('A4 配置是任意长度，超过上限只截断并报出来，不静默', () => {
  const many = Array.from({ length: MAX_TRACKS + 2 }, (_, i) => t('x' + i))
  const n = normalizeTracks(many)
  assert.equal(n.tracks.length, MAX_TRACKS)
  assert.equal(n.over.length, 2)
  assert.deepEqual(n.over, ['x' + MAX_TRACKS, 'x' + (MAX_TRACKS + 1)])
})

// ---------------------------------------------------------------- 硬验收②：全关掉主干照跑

test('A5 关掉全部支线：底座全返回空，主干 prompt 一字不受影响', () => {
  assert.deepEqual(normalizeTracks([]), { tracks: [], dropped: [], over: [] })
  assert.deepEqual(normalizeTracks(undefined), { tracks: [], dropped: [], over: [] })
  assert.deepEqual(normalizeTracks(null), { tracks: [], dropped: [], over: [] })
  assert.deepEqual(normalizeTracks('不是数组'), { tracks: [], dropped: [], over: [] })
  assert.equal(pickTrack([], ['a']), null)
  assert.deepEqual(trackLines(null), [])
  assert.deepEqual(trackLines(undefined), [])

  // 主干：没有 track 字段时，prompt 跟接底座之前一模一样（用固定输入比两次）
  const base = { topic: { kind: 'share', text: '今天看到一只很像鲸鱼的云' }, facts: ['她对芒果过敏'], episodes: [] }
  const withoutTrack = buildPrompt(base)
  const emptyTrack = buildPrompt({ ...base, track: null })
  assert.equal(withoutTrack, emptyTrack)
  // 主干该有的东西还在
  assert.match(withoutTrack, /大肥鱼/)
  assert.match(withoutTrack, /她对芒果过敏/)
})

test('A6 有支线时 prompt 多出四行，四个槽位的内容都露头；没有就一行不多', () => {
  const base = { topic: { kind: 'share', text: 'x' }, facts: [], episodes: [] }
  const plain = buildPrompt(base)
  const track = normalizeTracks([t('a')]).tracks[0]
  const withTrack = buildPrompt({ ...base, track })
  assert.ok(withTrack.length > plain.length)
  for (const line of trackLines(track)) {
    assert.ok(withTrack.includes(line), 'prompt 里少了这一行：' + line.slice(0, 30))
  }
  // 四个槽位的内容都进了 prompt（槽位名是协议，进 prompt 的是内容）
  for (const key of SLOT_KEYS) assert.ok(withTrack.includes(track[key]), '槽位 ' + key + ' 没进 prompt')
  // 盲区标签是唯一按名字点出来的那一条
  assert.match(withTrack, /盲区标签是「盲区a」/)
})

// ---------------------------------------------------------------- 校验：缺一条就整条不算

test('A7 四条槽位缺任何一条，整条丢掉并报出缺哪条（不拿默认值糊过去）', () => {
  for (const key of SLOT_KEYS) {
    const broken = t('bad')
    delete broken[key]
    const one = normalizeTrack(broken, 0)
    assert.equal(one.ok, false)
    assert.deepEqual(one.missing, [SLOT_LABEL[key]])
    const n = normalizeTracks([broken, t('good')])
    assert.equal(n.tracks.length, 1)
    assert.equal(n.tracks[0].id, 'good')
    assert.deepEqual(n.dropped, [{ id: 'bad', reason: 'slot-missing', missing: [SLOT_LABEL[key]] }])
  }
})

test('A8 空白字符串不算填了；显式 enabled:false 才算关掉', () => {
  assert.equal(normalizeTrack(t('a', { scene: '   ' }), 0).ok, false)
  assert.equal(normalizeTrack(t('a', { blind: '' }), 0).ok, false)
  const off = normalizeTracks([t('a'), t('b', { enabled: false })])
  assert.deepEqual(off.tracks.map((x) => x.id), ['a'])
  assert.deepEqual(off.dropped, [{ id: 'b', reason: 'disabled' }])
  // 写错类型不当成关（enabled:'no' 还是开）
  assert.equal(normalizeTracks([t('c', { enabled: 'no' })]).tracks.length, 1)
})

test('A9 超长内容被截断，不会把上下文挤爆', () => {
  const long = 'x'.repeat(500)
  const one = normalizeTracks([t('a', { scene: long })]).tracks[0]
  assert.ok(one.scene.length <= 200)
  assert.ok(one.scene.endsWith('…'))
})

// ---------------------------------------------------------------- 挑题：最近三条避开

test('A10 挑支线避开最近用过的三条，池子空了就放开', () => {
  const tracks = normalizeTracks([t('a'), t('b'), t('c'), t('d')]).tracks
  // 最近的 a/b/c 要避开，只剩 d
  assert.equal(pickTrack(tracks, ['a', 'b', 'c'], () => 0).id, 'd')
  // 只取最近三条：给四条也不算"全都在最近里"，d 还是唯一可选
  assert.equal(pickTrack(tracks, ['a', 'b', 'c', 'd'], () => 0).id, 'd')
  // 真的全都在最近三条里 → 放开，按 rnd 落点取
  assert.equal(pickTrack(tracks, ['a', 'b', 'c'], () => 0).id, 'd')
  const three = normalizeTracks([t('a'), t('b'), t('c')]).tracks
  assert.equal(pickTrack(three, ['a', 'b', 'c'], () => 0).id, 'a')
  // rnd 越界（1 或 NaN）也不会取到 undefined
  assert.ok(pickTrack(tracks, [], () => 1))
  assert.ok(pickTrack(tracks, [], () => NaN))
})

test('A11 从账本里只认 generated 且带 track 的条目，新→旧', () => {
  const entries = [
    { state: 'generated', track: 'old' },
    { state: 'generated', drill: 'thesis' },
    { state: 'replied' },
    { state: 'generated', track: 'mid' },
    { state: 'generated', track: 'new' },
  ]
  assert.deepEqual(recentTracks(entries), ['new', 'mid', 'old'])
  assert.deepEqual(recentTracks(entries, 2), ['new', 'mid'])
  assert.deepEqual(recentTracks([]), [])
  assert.deepEqual(recentTracks(undefined), [])
})

// ---------------------------------------------------------------- 判定只吃客观量

test('A12 判过只看三个客观量，三条全中才算过，自述进不来', () => {
  const track = normalizeTracks([t('a')]).tracks[0]
  assert.deepEqual(analyzeTrack(track, { sceneHit: true, firstHit: true, missedHit: true }), {
    pass: true, blind: '盲区a', why: '全中',
  })
  const half = analyzeTrack(track, { sceneHit: true, firstHit: false, missedHit: true })
  assert.equal(half.pass, false)
  assert.equal(half.blind, '盲区a')
  assert.match(half.why, /第一反应/)
  // 少给字段 = 不算中（默认不放水）
  assert.equal(analyzeTrack(track, {}).pass, false)
  assert.equal(analyzeTrack(track, undefined).pass, false)
  assert.deepEqual(analyzeTrack(null, { sceneHit: true, firstHit: true, missedHit: true }), {
    pass: false, blind: '', why: 'no-track',
  })
})

// ---------------------------------------------------------------- /status 与 /tracks 视图

test('A13 状态视图能看出几条在跑、哪几条不合格', () => {
  const st = trackStatus([t('a'), { id: 'broken' }], ['a'])
  assert.equal(st.count, 1)
  assert.equal(st.max, MAX_TRACKS)
  assert.deepEqual(st.recent, ['a'])
  assert.deepEqual(st.tracks, [{ id: 'a', name: '方向a', blind: '盲区a' }])
  assert.deepEqual(st.dropped, [{ id: 'broken', reason: 'slot-missing', missing: ['触发场景', '第一反应判断', '漏掉的条件或反例', '盲区标签'] }])
  assert.equal(st.slots.length, 4)
  // 全关掉也是一份正经状态，不是报错
  const empty = trackStatus([])
  assert.equal(empty.count, 0)
  assert.deepEqual(empty.tracks, [])
})
