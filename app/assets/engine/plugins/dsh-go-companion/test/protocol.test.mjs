import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  PROTOCOL_VERSION, PROTOCOL_TYPES, TERMINALS, checkPass, conditionNames, createRunner, formatKeys, loadProtocol,
  normalizeExit, parseDuration, validateProtocol,
} from '../lib/protocol.js'
import { hasCapability, measure } from '../lib/capabilities.js'

const NOW = Date.parse('2026-11-12T09:00:00+08:00')

/** 最小合法协议；测试按需改写字段。 */
function mini(over = {}) {
  return {
    '协议版本': 1,
    id: 'mini',
    名称: '最小局',
    类型: '行为',
    需要能力: ['计时', '语音问', '语音答'],
    预算: { 分钟: 10, 来回: 3 },
    步骤: [
      { id: 'one', 问: '说三行', 限时: '90s', 过关: { 行数: 3 }, 超时: '收局' },
    ],
    收局: { 交: ['one'], 判定: '最小标准' },
    ...over,
  }
}

/** 换步骤时必须把 收局.交 一起修好，否则满屏 bad-close 会盖住真正要测的东西。 */
function withSteps(list, over = {}) {
  return mini({ ...over, 步骤: list, 收局: { 交: list.map((s) => s.id), 判定: '最小标准' } })
}

function codes(result) {
  return result.errors.map((e) => e.code)
}

// ── 格式常量与时长 ───────────────────────────────────────────

test('J2 格式：协议版本只认 1', () => {
  assert.equal(PROTOCOL_VERSION, 1)
  assert.deepEqual(codes(validateProtocol(mini({ 协议版本: 2 }))), ['bad-version'])
})

test('J2 格式：类型只有行为局与思维局', () => {
  assert.deepEqual(PROTOCOL_TYPES, ['行为', '思维'])
  assert.deepEqual(codes(validateProtocol(mini({ 类型: '娱乐' }))), ['bad-type'])
  assert.equal(validateProtocol(mini({ 类型: '思维' })).ok, true)
})

test('J2 格式：终点只有收局与升级', () => {
  assert.deepEqual(TERMINALS, ['收局', '升级'])
})

test('J2 时长：90s / 6m / 1h / 裸数字都认得', () => {
  assert.equal(parseDuration('90s'), 90)
  assert.equal(parseDuration('6m'), 360)
  assert.equal(parseDuration('1h'), 3600)
  assert.equal(parseDuration(45), 45)
  assert.equal(parseDuration('45'), 45)
})

test('J2 时长：认不出来的返回 null（不当成 0 秒糊过去）', () => {
  assert.equal(parseDuration('一会儿'), null)
  assert.equal(parseDuration('0s'), null)
  assert.equal(parseDuration(-5), null)
  assert.equal(parseDuration(''), null)
})

test('J2 出口：五个出口名归一化', () => {
  assert.deepEqual(normalizeExit('收局'), { kind: 'close' })
  assert.deepEqual(normalizeExit('升级'), { kind: 'escalate' })
  assert.deepEqual(normalizeExit('重打'), { kind: 'retry' })
  assert.deepEqual(normalizeExit('下一步'), { kind: 'next' })
  assert.deepEqual(normalizeExit('跳步并记账'), { kind: 'next' })
  assert.equal(normalizeExit('锁机'), null)
})

// ── 加载与 JSON 格式 ────────────────────────────────────────

test('J2 加载：施工书 §3 J2 的示例能加载通过（按本引擎格式补齐的三处见注释）', () => {
  // 施工书里的示例是 YAML 示意，逐字不能当输入：则: 二选一[回去, 收局] 不是合法 YAML；
  // list 步没写限时和出口（要求 1 说每步必须有限时）；具体名词 与能力库里的 具体名词数 不同名。
  // 这里按本引擎的正式格式补齐这三处，其余逐字照抄。
  const spec = {
    '协议版本': 1,
    id: 'study-video',
    名称: '学习局',
    类型: '行为',
    需要能力: ['计时', '前台应用', '盖屏', '语音问', '语音答', '拍照'],
    预算: { 分钟: 30, 来回: 6 },
    步骤: [
      { id: 'guess', 问: '这节会讲什么？猜 3 条。', 限时: '90s', 过关: { 行数: 3, 每行最多字: 20 }, 超时: '跳步并记账' },
      {
        id: 'watch', 每: '6m', 触发: ['语音问', '盖屏'], 问: '刚才讲的是____，因为____',
        限时: '3m', 过关: { 具体名词数: 1, 不与前次重复: true }, 不过: '升级',
        监视: { 离开声明应用超过: '2m', 则: ['下一步', '收局'] },
      },
      { id: 'list', 问: '停。不看，列出讲了哪几点。', 限时: '5m', 形态: '手写拍照', 超时: '收局' },
    ],
    收局: { 交: ['guess', 'list'], 判定: '学习局标准' },
  }
  const r = loadProtocol(JSON.stringify(spec))
  assert.equal(r.ok, true, JSON.stringify(r.errors || []))
  assert.equal(r.protocol.id, 'study-video')
  assert.equal(r.protocol.steps.length, 3)
  assert.equal(r.protocol.steps[0].limitSec, 90)
  assert.equal(r.protocol.steps[1].periodSec, 360)
  assert.deepEqual(r.protocol.close.hand, ['guess', 'list'])
})

test('J2 加载：YAML 被明确拒绝，并说清为什么（不是悄悄当 JSON 解析）', () => {
  const r = loadProtocol('协议版本: 1\nid: x\n')
  assert.equal(r.ok, false)
  assert.deepEqual(codes(r), ['need-json'])
  assert.match(r.errors[0].message, /yaml/i)
})

test('J2 加载：坏 JSON 报 bad-json，空串报 empty', () => {
  assert.deepEqual(codes(loadProtocol('{ 坏')), ['bad-json'])
  assert.deepEqual(codes(loadProtocol('   ')), ['empty'])
})

test('J2 加载：不是对象就拒绝', () => {
  assert.deepEqual(codes(loadProtocol('[1,2]')), ['not-object'])
})

test('J2 加载：不认识的顶层字段拒绝（防手滑写错键名）', () => {
  assert.deepEqual(codes(validateProtocol(mini({ 超时: '收局' }))), ['unknown-key'])
})

test('J2 加载：id 只收小写字母数字连字符', () => {
  assert.deepEqual(codes(validateProtocol(mini({ id: 'Study_Video' }))), ['bad-id'])
  assert.deepEqual(codes(validateProtocol(mini({ id: '' }))), ['bad-id'])
})

test('J2 加载：缺名称拒绝', () => {
  assert.deepEqual(codes(validateProtocol(mini({ 名称: '  ' }))), ['no-name'])
})

test('J2 加载：预算必须是正数，字段名要对', () => {
  assert.deepEqual(codes(validateProtocol(mini({ 预算: { 分钟: 0 } }))), ['bad-budget'])
  assert.deepEqual(codes(validateProtocol(mini({ 预算: { 小时: 1 } }))), ['unknown-key'])
  assert.deepEqual(codes(validateProtocol(mini({ 预算: '半小时' }))), ['bad-budget'])
})

test('J2 加载：没有步骤拒绝', () => {
  assert.deepEqual(codes(validateProtocol(mini({ 步骤: [] }))), ['no-steps'])
  assert.deepEqual(codes(validateProtocol(mini({ 步骤: undefined }))), ['no-steps'])
})

test('J2 加载：必须写收局', () => {
  assert.deepEqual(codes(validateProtocol(mini({ 收局: undefined }))), ['no-close'])
})

test('J2 加载：收局.交 里写了不存在的步骤拒绝', () => {
  assert.deepEqual(codes(validateProtocol(mini({ 收局: { 交: ['nope'] } }))), ['bad-close'])
})

test('J2 加载：步骤 id 重复拒绝', () => {
  const spec = withSteps([
    { id: 'a', 问: 'x', 限时: '90s', 超时: '收局' },
    { id: 'a', 问: 'y', 限时: '90s', 超时: '收局' },
  ])
  assert.deepEqual(codes(validateProtocol(spec)), ['dup-step-id'])
})

test('J2 加载：步骤里不认识的字段拒绝', () => {
  const spec = withSteps([{ id: 'a', 问: 'x', 限时: '90s', 超时: '收局', 声音: '温柔' }])
  assert.deepEqual(codes(validateProtocol(spec)), ['unknown-key'])
})

// ── 硬性要求 1：每步有限时和出口，可终止性 ────────────────────

test('J2 可终止性：每步必须有限时', () => {
  const spec = withSteps([{ id: 'a', 问: 'x', 超时: '收局' }])
  assert.deepEqual(codes(validateProtocol(spec)), ['no-limit'])
})

test('J2 可终止性：限时认不出来也等于没有限时', () => {
  const spec = withSteps([{ id: 'a', 问: 'x', 限时: '一会儿', 超时: '收局' }])
  assert.deepEqual(codes(validateProtocol(spec)), ['no-limit'])
})

test('J2 可终止性：「每」可以当限时用（施工书的周期步只写 每）', () => {
  const spec = withSteps([{ id: 'a', 问: 'x', 每: '6m', 超时: '收局' }])
  const r = validateProtocol(spec)
  assert.equal(r.ok, true, JSON.stringify(r.errors || []))
  assert.equal(r.protocol.steps[0].periodSec, 360)
  assert.equal(r.protocol.steps[0].limitSec, 360)
})

test('J2 可终止性：不认识的出口名拒绝', () => {
  const spec = withSteps([{ id: 'a', 问: 'x', 限时: '90s', 超时: '放我走' }])
  assert.deepEqual(codes(validateProtocol(spec)), ['bad-exit'])
})

test('J2 可终止性：最后一步的「下一步」等于收局，合法', () => {
  const spec = withSteps([{ id: 'a', 问: 'x', 限时: '90s', 超时: '下一步' }])
  assert.equal(validateProtocol(spec).ok, true)
})

test('J2 可终止性：超时是「重打」→ 不说话就永远走不出去，拒绝', () => {
  const spec = withSteps([{ id: 'a', 问: 'x', 限时: '90s', 过关: { 行数: 1 }, 超时: '重打' }])
  const r = validateProtocol(spec)
  assert.equal(r.ok, false)
  assert.deepEqual(codes(r), ['no-exit'])
  assert.match(r.errors[0].message, /重打/)
})

test('J2 可终止性：不过重打、超时收局 → 允许（答错重来是明写的设计）', () => {
  const spec = withSteps([{ id: 'a', 问: 'x', 限时: '90s', 过关: { 行数: 1 }, 不过: '重打', 超时: '收局' }])
  assert.equal(validateProtocol(spec).ok, true)
})

test('J2 可终止性：中间一步不过重打、超时走下一步，最后到收局 → 合法', () => {
  const spec = withSteps([
    { id: 'a', 问: 'x', 限时: '90s', 过关: { 行数: 1 }, 不过: '重打', 超时: '下一步' },
    { id: 'b', 问: 'y', 限时: '90s', 过关: { 行数: 1 }, 超时: '收局' },
  ])
  assert.equal(validateProtocol(spec).ok, true)
})

test('J2 可终止性：监视里的二选一出口不合法就拒绝', () => {
  const spec = withSteps([{
    id: 'a', 问: 'x', 限时: '90s', 超时: '收局',
    监视: { 离开声明应用超过: '2m', 则: ['回去', '收局'] },
  }])
  assert.deepEqual(codes(validateProtocol(spec)), ['bad-exit'])
})

test('J2 可终止性：监视没有两个出口就拒绝', () => {
  const spec = withSteps([{ id: 'a', 问: 'x', 限时: '90s', 超时: '收局', 监视: { 离开声明应用超过: '2m', 则: ['收局'] } }])
  assert.deepEqual(codes(validateProtocol(spec)), ['bad-monitor'])
})

test('J2 可终止性：监视的时限认不出来就拒绝', () => {
  const spec = withSteps([{ id: 'a', 问: 'x', 限时: '90s', 超时: '收局', 监视: { 离开声明应用超过: '一会儿', 则: ['下一步', '收局'] } }])
  assert.deepEqual(codes(validateProtocol(spec)), ['bad-monitor'])
})

// ── 硬性要求 2：缺能力 → 拒绝并生成提议 ──────────────────────

test('J2 缺能力：用了能力库没有的名字，拒绝加载', () => {
  const r = validateProtocol(mini({ 需要能力: ['计时', '读心术'] }))
  assert.equal(r.ok, false)
  assert.deepEqual(codes(r), ['missing-capability'])
  assert.match(r.errors[0].message, /读心术/)
})

test('J2 缺能力：拒绝的同时生成一条缺能力提议', () => {
  const r = validateProtocol(mini({ id: 'x-needs', 需要能力: ['计时', '读心术', '预知'] }))
  assert.equal(r.ok, false)
  assert.equal(r.proposal.kind, 'missing-capability')
  assert.equal(r.proposal.protocolId, 'x-needs')
  assert.deepEqual(r.proposal.capabilities.map((c) => c.name), ['读心术', '预知'])
  assert.equal(r.proposal.capabilities[0].class, null)
  assert.ok(r.proposal.capabilities[0].need.length >= 3)
})

test('J2 缺能力：提议里同一个名字只出现一次', () => {
  const r = validateProtocol(mini({ 需要能力: ['读心术', '读心术'] }))
  assert.equal(r.proposal.capabilities.length, 1)
})

test('J2 缺能力：步骤里的触发/形态/应答也走同一套校验', () => {
  const spec = withSteps([{
    id: 'a', 问: 'x', 限时: '90s', 超时: '收局', 触发: ['读心术'], 形态: '读心', 应答: '读心',
  }])
  const r = validateProtocol(spec)
  assert.deepEqual(codes(r), ['missing-capability'])
  assert.deepEqual(r.proposal.capabilities.map((c) => c.name), ['读心术', '读心'])
})

test('J2 缺能力：能力名在库里就通过（计时 / 语音问 / 拍照）', () => {
  assert.equal(hasCapability('计时'), true)
  assert.equal(hasCapability('语音问'), true)
  assert.equal(hasCapability('拍照'), true)
})

// ── 硬性要求 3：不能声明墙的能力，只能调升级 ──────────────────

test('J2 越权：声明锁就拒绝', () => {
  const r = validateProtocol(mini({ 需要能力: ['计时', '锁'] }))
  assert.equal(r.ok, false)
  assert.deepEqual(codes(r), ['wall-capability'])
  assert.match(r.errors[0].message, /锁/)
})

test('J2 越权：额度 / 信用分 / 规则 / 立场结算 全拒绝', () => {
  for (const name of ['额度', '信用分', '规则', '立场结算', '全机锁']) {
    const r = validateProtocol(mini({ 需要能力: ['计时', name] }))
    assert.equal(r.ok, false, name + ' 应该被拒')
    assert.deepEqual(codes(r), ['wall-capability'], name)
  }
})

test('J2 越权：墙的能力写在步骤里也一样拒绝', () => {
  const spec = withSteps([{ id: 'a', 问: 'x', 限时: '90s', 超时: '收局', 触发: ['锁'] }])
  assert.deepEqual(codes(validateProtocol(spec)), ['wall-capability'])
})

test('J2 越权：墙的能力当字段名也一样拒绝', () => {
  const spec = withSteps([{ id: 'a', 问: 'x', 限时: '90s', 超时: '收局', 信用分: 90 }])
  const r = validateProtocol(spec)
  assert.ok(codes(r).includes('wall-capability'), JSON.stringify(r.errors))
})

test('J2 越权：出口只能调升级，不能自己锁机', () => {
  const spec = withSteps([{ id: 'a', 问: 'x', 限时: '90s', 超时: '锁机' }])
  assert.deepEqual(codes(validateProtocol(spec)), ['bad-exit'])
})

test('J2 越权：升级出口合法（升不升、升到哪由引擎定）', () => {
  const spec = withSteps([{ id: 'a', 问: 'x', 限时: '90s', 过关: { 行数: 3 }, 不过: '升级', 超时: '收局' }])
  assert.equal(validateProtocol(spec).ok, true)
})

// ── 过关条件 ────────────────────────────────────────────────

test('J2 过关：不认识的过关条件拒绝（防写错条件名）', () => {
  const spec = withSteps([{ id: 'a', 问: 'x', 限时: '90s', 超时: '收局', 过关: { 感觉对: true } }])
  assert.deepEqual(codes(validateProtocol(spec)), ['unknown-condition'])
})

test('J2 过关：行数与每行最多字', () => {
  const { pass, results } = checkPass({ 行数: 3, 每行最多字: 20 }, { text: '一行\n二行\n三行' })
  assert.equal(pass, true)
  assert.deepEqual(results.map((r) => r.got), [3, 2])
})

test('J2 过关：行数不够就是没过', () => {
  assert.equal(checkPass({ 行数: 3 }, { text: '只有一行' }).pass, false)
})

test('J2 过关：有一行超字就不过（每行最多字是上限）', () => {
  assert.equal(checkPass({ 每行最多字: 5 }, { text: '这一行很长很长' }).pass, false)
})

test('J2 过关：具体名词数走度量能力', () => {
  const r = checkPass({ 具体名词数: 2 }, { text: '哈希表和红黑树' })
  assert.equal(r.pass, true)
  assert.equal(r.results[0].got, measure('具体名词数', { text: '哈希表和红黑树' }))
})

test('J2 过关：结论句是布尔判定', () => {
  assert.equal(checkPass({ 有无结论句: true }, { text: '讲了缓存。所以关键是命中率' }).pass, true)
  assert.equal(checkPass({ 有无结论句: true }, { text: '讲了缓存' }).pass, false)
})

test('J2 过关：不与前次重复', () => {
  assert.equal(checkPass({ 不与前次重复: true }, { text: '同一句', recent: ['同一句'] }).pass, false)
  assert.equal(checkPass({ 不与前次重复: true }, { text: '另起一句', recent: ['同一句'] }).pass, true)
})

test('J2 过关：填充词比例是上限', () => {
  assert.equal(checkPass({ 填充词比例: 0.5 }, { text: '嗯嗯嗯嗯' }).pass, false)
  assert.equal(checkPass({ 填充词比例: 0.5 }, { text: '哈希表是数组加链表' }).pass, true)
})

test('J2 过关：条件是全过才算过，不是有一个过就算过', () => {
  const r = checkPass({ 行数: 3, 每行最多字: 2 }, { text: '很长的一行\n短\n短' })
  assert.equal(r.pass, false)
  assert.deepEqual(r.results.map((x) => x.ok), [true, false])
})

test('J2 过关：条件名表包含施工书示例用到的两个', () => {
  assert.ok(conditionNames().includes('行数'))
  assert.ok(conditionNames().includes('每行最多字'))
})

// 「15~25 字」这种区间以前写不出来：字数只比「至少」。现在补了一条比「至多」的。
test('J2 过关：字数上限比的是「不超过」，不是「至少」', () => {
  assert.equal(checkPass({ 字数上限: 25 }, { text: '十'.repeat(25) }).pass, true)
  assert.equal(checkPass({ 字数上限: 25 }, { text: '十'.repeat(26) }).pass, false)
  // 和「字数」同时写才是区间；只写上限时下限不设，一两个字也算过。
  const range = { 字数: 15, 字数上限: 25 }
  assert.equal(checkPass(range, { text: '十'.repeat(10) }).pass, false)
  assert.equal(checkPass(range, { text: '十'.repeat(20) }).pass, true)
  assert.equal(checkPass(range, { text: '十'.repeat(30) }).pass, false)
})

test('J2 过关：模糊词数是「越少越好」，期望 0 时出现一个就不过', () => {
  assert.equal(checkPass({ 模糊词数: 0 }, { text: '哈希表查找是 O(1)' }).pass, true)
  assert.equal(checkPass({ 模糊词数: 0 }, { text: '大概就是这样' }).pass, false)
})

test('J2 过关：因果连接词数是「至少几个」，没有连接词就不过', () => {
  assert.equal(checkPass({ 因果连接词数: 1 }, { text: '因为命中率高，所以延迟低' }).pass, true)
  assert.equal(checkPass({ 因果连接词数: 1 }, { text: '哈希表查找是 O(1)' }).pass, false)
})

// ── 解释器 ──────────────────────────────────────────────────

function runnerOf(spec, clock = { now: NOW }) {
  const loaded = validateProtocol(spec)
  assert.equal(loaded.ok, true, JSON.stringify(loaded.errors || []))
  return createRunner(loaded.protocol, { now: () => clock.now })
}

test('J2 解释器：开局停在第 1 步，能拿到这一步的问', () => {
  const clock = { now: NOW }
  const runner = runnerOf(mini(), clock)
  assert.equal(runner.state, 'running')
  assert.equal(runner.index, 0)
  assert.equal(runner.current().id, 'one')
  assert.equal(runner.current().ask, '说三行')
})

test('J2 解释器：过关就走下一拍；最后一步过关即收局', () => {
  const clock = { now: NOW }
  const runner = runnerOf(mini(), clock)
  assert.deepEqual(runner.answer('一\n二\n三'), { state: 'closed', reason: 'done', index: 0 })
  assert.equal(runner.state, 'closed')
  assert.equal(runner.endReason, 'done')
})

test('J2 解释器：不是最后一步就停在下一拍，不提前收局', () => {
  const clock = { now: NOW }
  const spec = withSteps([
    { id: 'a', 问: 'x', 限时: '90s', 过关: { 行数: 1 }, 超时: '收局' },
    { id: 'b', 问: 'y', 限时: '90s', 过关: { 行数: 1 }, 超时: '收局' },
  ])
  const runner = runnerOf(spec, clock)
  assert.deepEqual(runner.answer('一'), { state: 'running', reason: 'next', index: 1 })
  assert.equal(runner.current().id, 'b')
  assert.equal(runner.state, 'running')
})

test('J2 解释器：不过按「不过」出口走（默认重打，重打次数加一）', () => {
  const clock = { now: NOW }
  const runner = runnerOf(mini(), clock)
  const r = runner.answer('只写了一行')
  assert.deepEqual(r, { state: 'running', reason: 'retry', index: 0, retries: 1 })
  assert.equal(runner.retries, 1)
  assert.equal(runner.state, 'running')
})

test('J2 解释器：不过 → 升级就收在 escalated，不自己决定升到几级', () => {
  const clock = { now: NOW }
  const spec = withSteps([{ id: 'one', 问: 'x', 限时: '90s', 过关: { 行数: 3 }, 不过: '升级', 超时: '收局' }])
  const runner = runnerOf(spec, clock)
  const r = runner.answer('一行')
  assert.deepEqual(r, { state: 'escalated', reason: 'fail', index: 0 })
  assert.equal(runner.state, 'escalated')
})

test('J2 解释器：到点没答走超时出口', () => {
  const clock = { now: NOW }
  const runner = runnerOf(mini(), clock)
  assert.equal(runner.tick(NOW + 30_000).reason, 'not-yet')
  clock.now = NOW + 91_000
  const r = runner.tick(clock.now)
  assert.deepEqual(r, { state: 'closed', reason: 'timeout', index: 0 })
  assert.equal(runner.endReason, 'timeout')
})

test('J2 解释器：超时 → 跳步并记账走到下一步', () => {
  const clock = { now: NOW }
  const spec = withSteps([
    { id: 'a', 问: 'x', 限时: '90s', 超时: '跳步并记账' },
    { id: 'b', 问: 'y', 限时: '90s', 超时: '收局' },
  ])
  const runner = runnerOf(spec, clock)
  clock.now = NOW + 91_000
  assert.deepEqual(runner.tick(clock.now), { state: 'running', reason: 'next', index: 1 })
  assert.equal(runner.current().id, 'b')
})

test('J2 解释器：收局之后不再接受应答（不会把话说进一个已经结束的局）', () => {
  const clock = { now: NOW }
  const runner = runnerOf(mini(), clock)
  runner.answer('一\n二\n三')
  assert.equal(runner.state, 'closed')
  assert.deepEqual(runner.answer('再说一句'), { state: 'closed', reason: 'not-running', index: 0 })
  assert.equal(runner.current(), null)
})

test('J2 解释器：他自己喊停就收局，原因是给的', () => {
  const clock = { now: NOW }
  const runner = runnerOf(mini(), clock)
  assert.deepEqual(runner.abandon('abandoned'), { state: 'closed', reason: 'abandoned', index: 0 })
  assert.equal(runner.endReason, 'abandoned')
})

test('J2 解释器：应答都记着，供「与他近 5 条重合率」用', () => {
  const clock = { now: NOW }
  const spec = withSteps([{ id: 'a', 问: 'x', 限时: '90s', 过关: { 行数: 999 }, 不过: '重打', 超时: '收局' }])
  const runner = runnerOf(spec, clock)
  runner.answer('第一句')
  runner.answer('第二句')
  assert.equal(runner.state, 'running')
  assert.deepEqual(runner.answers(), ['第一句', '第二句'])
})

test('J2 解释器：重打会把限时重新计时（不是永远沿用第一次的起点）', () => {
  const clock = { now: NOW }
  const runner = runnerOf(mini(), clock)
  assert.equal(runner.askedAt(), NOW)
  clock.now = NOW + 50_000
  runner.answer('一行')
  assert.equal(runner.askedAt(), NOW + 50_000)
  assert.equal(runner.tick(NOW + 60_000).reason, 'not-yet')
})

// ── 格式文档：lib/protocol.schema.json 与代码里的格式表不许漂 ──────────

const SCHEMA = JSON.parse(readFileSync(new URL('../lib/protocol.schema.json', import.meta.url), 'utf8'))
const STEP_SCHEMA = SCHEMA.properties['步骤'].items

function sorted(list) {
  return list.slice().sort()
}

test('J2 格式文档：顶层字段与代码一致', () => {
  assert.deepEqual(sorted(Object.keys(SCHEMA.properties)), sorted(formatKeys().top))
})

test('J2 格式文档：步骤字段与代码一致', () => {
  assert.deepEqual(sorted(Object.keys(STEP_SCHEMA.properties)), sorted(formatKeys().step))
})

test('J2 格式文档：预算与收局的字段与代码一致', () => {
  assert.deepEqual(sorted(Object.keys(SCHEMA.properties['预算'].properties)), sorted(formatKeys().budget))
  assert.deepEqual(sorted(Object.keys(SCHEMA.properties['收局'].properties)), sorted(formatKeys().close))
})

test('J2 格式文档：过关条件名与代码一致', () => {
  const inSchema = STEP_SCHEMA.properties['过关'].propertyNames.enum
  assert.deepEqual(sorted(inSchema), sorted(formatKeys().conditions))
})

test('J2 格式文档：出口名与 normalizeExit 认得的名字一致', () => {
  const exits = SCHEMA.$defs.exit.enum
  for (const name of exits) assert.notEqual(normalizeExit(name), null, name + ' schema 里有但代码不认')
  // 反过来：代码认「下一步/继续/跳步/跳步并记账」都是 next，schema 里都得列出来。
  for (const name of ['收局', '升级', '重打', '下一步', '跳步并记账']) {
    assert.ok(exits.includes(name), name + ' 代码认但 schema 没写')
  }
})
