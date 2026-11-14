// 第九轮第三项：工作包（纯规则）。
//
// 钉的是「计划里的每一局都说清做成什么算做成」：
//   - 第一步必须是一个 2 分钟内能开始的动作：有动词、落在某个应用或物件上、≤ 40 字；
//   - 缺交付物、缺验收方式的包进不了计划；
//   - 验收方式只有证据 / 依据 / 判断三类，判断类一天最多两个；
//   - 局官的问句对着工作包问（第一步 / 在第几步 / 交付物几张）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  ACCEPT_KINDS, FIRST_MAX, JUDGE_MAX_PER_DAY, acceptText, askOf, badPackets, checkFirst,
  judgeCount, judgeOver, normPacket, openSay, packetLines, packetProblems, packetPrompt,
} from '../lib/packet.js'

const APPS = [
  { pkg: 'com.hicorenational.antifraud', label: '国家反诈' },
  { pkg: 'com.android.browser', label: '浏览器' },
  { pkg: 'com.tencent.wework', label: '企业微信' },
]

const good = (over = {}) => ({
  goal: '投出 3 份简历',
  first: '打开浏览器，搜上海后端 1-3 年，看前 10 个',
  steps: ['选 3 个', '改简历第二段', '投', '截图'],
  deliverable: '3 张投递成功截图',
  accept: { kind: 'evidence', what: '截图时间在开局后，识图读到「已投递」，3 张' },
  stuck: '20 分钟无进展就喊卡在哪',
  ...over,
})

// ------------------------------------------------------------------ 第一步

test('W1 第一步合格：有动词、有物件、不超 40 字', () => {
  assert.equal(checkFirst('打开浏览器，搜上海后端，看前 10 个', { apps: APPS }), '')
})

test('W2 第一步没写：直接说没写', () => {
  assert.equal(checkFirst(''), '第一步还没写')
  assert.equal(checkFirst('   '), '第一步还没写')
})

test('W3 第一步超过 40 字不收，并且报出实际字数', () => {
  const long = '打开' + '浏览器'.repeat(20)
  assert.ok(long.length > FIRST_MAX)
  const why = checkFirst(long, { apps: APPS })
  assert.match(why, /太长/)
  assert.match(why, new RegExp(String(long.length)))
})

test('W4 第一步没有动词不收（「上海后端岗位」不是动作）', () => {
  assert.match(checkFirst('上海后端岗位', { apps: APPS }), /动词/)
})

test('W5 第一步有动词但没落在任何应用或物件上，不收', () => {
  assert.match(checkFirst('看看就好', { apps: APPS }), /应用|东西/)
})

test('W6 第一步提到应用标签就认（浏览器）', () => {
  assert.equal(checkFirst('打开浏览器', { apps: APPS }), '')
})

test('W7 第一步提到应用包名尾巴也认（wework 这种够长的）', () => {
  assert.equal(checkFirst('打开 wework 看今天的消息', { apps: APPS }), '')
})

test('W8 没有应用表时，靠物件也算数（拿简历）', () => {
  assert.equal(checkFirst('拿简历', { apps: [] }), '')
})

test('W9 一个字两个字的尾巴不当应用名（com.a.io 的 io）', () => {
  assert.match(checkFirst('打开 io 看看', { apps: [{ pkg: 'com.a.io', label: '' }] }), /应用|东西/)
})

test('W10 只有动词没有宾语也不收（打开）', () => {
  assert.match(checkFirst('打开', { apps: [] }), /应用|东西/)
})

test('W11 全角空格和多余空白先压掉再判：压完不超限就收', () => {
  assert.equal(checkFirst('  打开   浏览器  ', { apps: APPS }), '')
})

// ------------------------------------------------------------------ 工作包字段

test('W12 一格都没填不算工作包（why=empty），不挡计划', () => {
  const r = normPacket({})
  assert.equal(r.ok, false)
  assert.equal(r.why, 'empty')
  assert.equal(r.packet, null)
})

test('W13 洗字段：压空白、截长度、后续步骤最多 5 条', () => {
  const r = normPacket(good({ steps: ['a', 'b', 'c', 'd', 'e', 'f', 'g'], goal: '  投出   3 份简历  ' }))
  assert.equal(r.ok, true)
  assert.equal(r.packet.goal, '投出 3 份简历')
  assert.equal(r.packet.steps.length, 5)
  assert.equal(r.packet.steps[4], 'e')
})

test('W14 合格的包没有问题项', () => {
  const r = normPacket(good())
  assert.deepEqual(packetProblems(r.packet, { apps: APPS }), [])
})

test('W15 缺交付物：进不了计划', () => {
  const r = normPacket(good({ deliverable: '' }))
  assert.deepEqual(packetProblems(r.packet, { apps: APPS }), ['缺交付物'])
})

test('W16 缺验收方式：进不了计划', () => {
  const r = normPacket(good({ accept: { kind: '', what: '' } }))
  assert.ok(packetProblems(r.packet, { apps: APPS }).includes('缺验收方式'))
})

test('W17 验收方式类别不在三类里：当没写', () => {
  const r = normPacket(good({ accept: { kind: '随便', what: '他自己说做完了' } }))
  assert.deepEqual(packetProblems(r.packet, { apps: APPS }), ['缺验收方式'])
})

test('W18 写了类别没写怎么验：也不行', () => {
  const r = normPacket(good({ accept: { kind: 'evidence', what: '' } }))
  assert.deepEqual(packetProblems(r.packet, { apps: APPS }), ['验收方式没写清怎么验'])
})

test('W19 没有工作包（null）不报问题——草案本来就没有', () => {
  assert.deepEqual(packetProblems(null), [])
  assert.deepEqual(packetProblems(undefined), [])
})

test('W20 第一步不合格时，问题项里带原因', () => {
  const r = normPacket(good({ first: '想一想' }))
  const probs = packetProblems(r.packet, { apps: APPS })
  assert.equal(probs.length, 1)
  assert.match(probs[0], /动词|应用|东西/)
})

test('W21 三类验收方式就这三个，顺序固定', () => {
  assert.deepEqual([...ACCEPT_KINDS], ['evidence', 'basis', 'judge'])
})

test('W22 验收方式的人话：证据 / 依据 / 判断', () => {
  assert.equal(acceptText({ kind: 'evidence', what: '3 张截图' }), '证据：3 张截图')
  assert.equal(acceptText({ kind: 'basis', what: '对照原文' }), '依据：对照原文')
  assert.equal(acceptText({ kind: 'judge', what: '按标准打分' }), '判断：按标准打分')
  assert.equal(acceptText({ kind: 'judge', what: '' }), '判断：（没写清）')
  assert.equal(acceptText({}), '')
})

// ------------------------------------------------------------------ 计划层面

const plan = (items) => ({ items })

test('W23 计划里点名是哪个包进不了计划，并说清做不到什么', () => {
  const p = plan([
    { title: '求职局', packet: normPacket(good()).packet },
    { title: '复习局', packet: normPacket(good({ deliverable: '', accept: { kind: '', what: '' } })).packet },
  ])
  const bad = badPackets(p, { apps: APPS })
  assert.equal(bad.length, 1)
  assert.equal(bad[0].title, '复习局')
  assert.match(bad[0].why, /缺交付物/)
  const line = packetPrompt(p, { apps: APPS })
  assert.match(line, /复习局/)
  assert.match(line, /证据、依据、判断/)
})

test('W24 全都合格时没有提示（空串）', () => {
  const p = plan([{ title: '求职局', packet: normPacket(good()).packet }])
  assert.equal(packetPrompt(p, { apps: APPS }), '')
  assert.deepEqual(badPackets(p, { apps: APPS }), [])
})

test('W25 判断类一天最多两个：第三个就超额', () => {
  const mk = (kind) => ({ packet: normPacket(good({ accept: { kind, what: '按标准打分' } })).packet })
  const two = plan([mk('judge'), mk('judge'), mk('evidence')])
  assert.equal(judgeCount(two), JUDGE_MAX_PER_DAY)
  assert.equal(judgeOver(two), '')
  const three = plan([mk('judge'), mk('judge'), mk('judge')])
  assert.equal(judgeCount(three), 3)
  assert.match(judgeOver(three), /最多 2 个/)
})

// ------------------------------------------------------------------ 局官的问句

const pkt = normPacket(good()).packet

test('W26 第一拍问「第一步做了吗」，念的就是工作包的第一步', () => {
  const s = askOf(pkt, { first: true, stepAsk: '' })
  assert.match(s, /第一步做了吗/)
  assert.match(s, new RegExp(pkt.first))
})

test('W27 后面的拍问「在第几步」，不再问第一步', () => {
  const s = askOf(pkt, { first: false, stepAsk: '' })
  assert.match(s, /在第几步/)
  assert.ok(!s.includes('第一步做了吗'))
})

test('W28 交付物一直追问「几张了」，把要几张念出来', () => {
  assert.match(askOf(pkt, { first: false }), /交付物有几张了（要 3 张投递成功截图）/)
})

test('W29 没有工作包就原样返回协议的问句（老局不变）', () => {
  assert.equal(askOf(null, { stepAsk: '这一拍你做了什么？' }), '这一拍你做了什么？')
  assert.equal(askOf(undefined, { stepAsk: '说说' }), '说说')
})

test('W30 有工作包时，协议原本的问句接在后面，不丢', () => {
  const s = askOf(pkt, { first: true, stepAsk: '这一拍你做了什么？' })
  assert.ok(s.startsWith('第一步做了吗'))
  assert.ok(s.endsWith('这一拍你做了什么？'))
})

test('W31 开局念的话：第一步在最前面，交付物跟着', () => {
  const s = openSay(pkt)
  assert.ok(s.startsWith('第一步：' + pkt.first))
  assert.match(s, /交付物：3 张投递成功截图/)
})

test('W32 没有工作包时开局不多念一句（空串）', () => {
  assert.equal(openSay(null), '')
  assert.equal(openSay({}), '')
})

test('W33 工作包的几行：目标/第一步/后面/交付物/验收/卡住时按顺序出', () => {
  const lines = packetLines(pkt)
  assert.match(lines[0], /^目标：/)
  assert.match(lines[1], /^第一步：/)
  assert.match(lines[2], /^后面：.* → /)
  assert.match(lines[3], /^交付物：/)
  assert.match(lines[4], /^验收：证据：/)
  assert.match(lines[5], /^卡住时：/)
})

test('W34 没有工作包时那几行是空的（不占位）', () => {
  assert.deepEqual(packetLines(null), [])
  assert.deepEqual(packetLines({}), [])
})

test('W35 脏输入不炸：数字、数组、字符串当工作包都退回 empty', () => {
  for (const bad of [null, undefined, 'abc', 42, [], true]) {
    const r = normPacket(bad)
    assert.equal(r.ok, false)
    assert.equal(r.packet, null)
  }
})
