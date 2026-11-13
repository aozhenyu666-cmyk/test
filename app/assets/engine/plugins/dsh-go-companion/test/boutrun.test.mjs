// 第八轮 J4 + J9（文字闭环）：五个内置协议真的能跑完，上限与混乱真的拦得住。
//
// 一个协议不算「写完」，要三种假用户都能正常收局：
//   配合：照着问的答，一路过关；
//   沉默：一句不说，靠超时出口走完，不能卡住；
//   口水话：只说「嗯」「行」，靠重打走到升级，不能死循环。
// 最后两个 describe 块是沟通上限与混乱处理的成组断言（J4 要求 ≥ 20 条）。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { builtinProtocolOf, builtinProtocols, brokenBuiltins } from '../lib/builtin.js'
import { openBoutsDb, listBeats, listVerdicts, runningBout } from '../lib/bouts.js'
import { createOfficial, judgeReply, checkPhrase, hisLimitOf, RETRY_LIMIT, OUTING_LIMIT, HIS_LIMIT, OFFICIAL_LIMIT, NUDGE_LEAD_SEC } from '../lib/boutrun.js'
import { loadProtocol } from '../lib/protocol.js'

const BUILTIN_IDS = ['execute', 'study', 'outing', 'design', 'rest']

function withDb(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-boutrun-'))
  const db = openBoutsDb(join(home, 'fish-memory', 'fish-memory.sqlite'))
  try {
    return fn({ home, db })
  } finally {
    try {
      db?.close?.()
    } catch {
      // 关不掉也不影响用例结论
    }
  }
}

/** 配合的人：每一步都给一句能过关的话。 */
const GOOD = {
  第一步: '先打开编辑器写好第一段',
  进展: '写完第三段，正在改第四段',
  收尾: '交了草稿一段',
  猜: '一\n二\n三',
  盖屏: '讲到哈希表的冲突',
  列点: '第一点\n第二点\n第三点',
  没懂: '冲突了怎么扩容？',
  还做吗: '还做',
  到了吗: '到店里了',
  小票: '拍好了',
  目标: '想清楚局的边界到底是哪三条',
  推进: '推到表结构和账本重建了',
  结论: '结论是分开两张表',
  提议: '已写进提议目录那一段',
}

function goodFor(stepId, turn = 0) {
  // 盖屏那一拍要求「不与前次重复」：同一个人照理也不会每次说一模一样的话，
  // 所以这里带上第几次，模拟「每 6 分钟说一句不一样的」。
  if (stepId === '盖屏') return '讲到哈希表的冲突第' + turn + '次'
  return GOOD[stepId] || '这一步说清楚了'
}

function openWith(protocolId) {
  const protocol = builtinProtocolOf(protocolId)
  assert.ok(protocol, '内置协议应当能取到：' + protocolId)
  return protocol
}

/**
 * 用一个人格跑一整局，返回结局。
 * 时钟是假的：每答一次往前推 advanceMs，周期步（每 10 分钟）靠 due() 到点才重问。
 * 沉默人格不给应答，只 tick，把每拍走到超时。
 */
function runBout(protocol, answerOf, { maxTurns = 400, advanceMs = 2 * 60_000 } = {}) {
  return withDb(({ home, db }) => {
    let clock = Date.parse('2026-11-12T09:00:00+08:00')
    const official = createOfficial({ protocol, db, home, now: () => clock })
    const started = official.start()
    assert.equal(started.ok, true, protocol.id + ' 应当能开局')
    let last = { state: 'running' }
    for (let i = 0; i < maxTurns; i += 1) {
      const stepId = official.view().stepId
      const text = answerOf({ id: stepId }, i)
      // 到点没答就是沉默；到点该重问的就让它问。
      clock += advanceMs
      if (text === null) {
        last = official.tick(clock)
      } else {
        const asked = official.due(clock)
        if (asked.due) last = { state: 'running', say: asked.say }
        else last = official.answer(text)
      }
      if (last.state === 'closed' || last.state === 'escalated') break
    }
    return { last, official }
  })
}

test('J4 内置协议：五个文件全部通过 J2 校验，没有坏协议', () => {
  assert.deepEqual(brokenBuiltins(), [], '内置协议不该有加载失败的')
  const ids = builtinProtocols().filter((x) => x.ok).map((x) => x.protocol.id)
  // 按 id 比，不按文件名顺序：文件叫什么不重要，装进去的是哪五个局才重要。
  assert.deepEqual([...ids].sort(), [...BUILTIN_IDS].sort())
})

test('J4 内置协议：每个都是 J2 格式，重新解析结果一致', () => {
  for (const id of BUILTIN_IDS) {
    const protocol = openWith(id)
    const again = loadProtocol(JSON.stringify(protocol.raw))
    assert.equal(again.ok, true, id + ' 的原文应当能重新加载')
    assert.equal(again.protocol.id, protocol.id)
    assert.equal(again.protocol.steps.length, protocol.steps.length)
  }
})

test('J4 内置协议：局官写给自己的每一句都在 40 字以内、最多一个问句', () => {
  for (const id of BUILTIN_IDS) {
    for (const step of openWith(id).steps) {
      const verdict = checkPhrase(step.ask)
      assert.equal(verdict.ok, true, id + '/' + step.id + ' 的 问 不合规：' + verdict.reasons.join('、'))
    }
  }
})

for (const id of BUILTIN_IDS) {
  test('J4 假用户·配合：' + id + ' 一路过关，正常收局', () => {
    const protocol = openWith(id)
    const { last } = runBout(protocol, ({ id: stepId }, turn) => (stepId ? goodFor(stepId, turn) : '说清楚了'))
    assert.equal(last.state, 'closed', id + ' 应当收局，实际 ' + last.state)
  })

  test('J4 假用户·沉默：' + id + ' 一句不答也能靠超时走完，不会卡住', () => {
    const protocol = openWith(id)
    const { last } = runBout(protocol, () => null)
    assert.equal(last.state, 'closed', id + ' 沉默到底也应当收局，实际 ' + last.state)
  })

  test('J4 假用户·口水话：' + id + ' 只说「嗯」走到升级，不会死循环', () => {
    const protocol = openWith(id)
    const { last } = runBout(protocol, () => '嗯')
    // 休息局不判、只看超时；其余四种应当被重打上限逼到升级或收局，但绝不能停在 running。
    assert.ok(['closed', 'escalated'].includes(last.state), id + ' 口水话应当有结局，实际 ' + last.state)
  })
}

test('J9 局官：一拍一记录，beats 与 verdicts 都落在这一局上', () => {
  const protocol = openWith('execute')
  withDb(({ home, db }) => {
    const official = createOfficial({ protocol, db, home })
    official.start()
    official.answer(goodFor('第一步'))
    official.answer(goodFor('进展'))
    const bout = runningBout(db)
    assert.ok(bout, '进行中的那一局应当能查到')
    const beats = listBeats(db, bout.id)
    assert.equal(beats.length, 2)
    assert.deepEqual(beats.map((b) => b.stepId), ['第一步', '进展'])
    assert.equal(beats[0].channel, 'text')
    const verdicts = listVerdicts(db, bout.id)
    assert.equal(verdicts.length, 2)
    assert.equal(verdicts[0].layer, 'code')
    assert.equal(verdicts[0].result, 'pass')
  })
})

test('J9 局官：一局只能有一个在跑，第二局返回 busy', () => {
  const protocol = openWith('execute')
  withDb(({ home, db }) => {
    const first = createOfficial({ protocol, db, home })
    assert.equal(first.start().ok, true)
    const second = createOfficial({ protocol, db, home })
    assert.deepEqual(second.start(), { ok: false, error: 'busy' })
  })
})

test('J9 局官：超字数的那一条不算应答，这一拍不往前走', () => {
  const protocol = openWith('execute')
  withDb(({ home, db }) => {
    const official = createOfficial({ protocol, db, home })
    official.start()
    const before = official.view().index
    const r = official.answer('啊'.repeat(HIS_LIMIT + 1))
    assert.equal(r.accepted, false)
    assert.equal(r.overflow, true)
    assert.equal(official.view().index, before, '超字数的回复不该推进这一拍')
  })
})

// ---- 沟通上限（J4 表格，代码执行）----

test('J4 上限：他单条 150 字，外出局只有 50', () => {
  assert.equal(hisLimitOf(openWith('execute')), HIS_LIMIT)
  assert.equal(hisLimitOf(openWith('outing')), OUTING_LIMIT)
  assert.equal(HIS_LIMIT, 150)
  assert.equal(OUTING_LIMIT, 50)
})

test('J4 上限：刚好 150 字不算超，151 字算超', () => {
  const ok = judgeReply('字'.repeat(150), { limit: 150 })
  assert.equal(ok.overflow, false)
  const bad = judgeReply('字'.repeat(151), { limit: 150 })
  assert.equal(bad.overflow, true)
  assert.equal(bad.say, '超了。分成两条，先说第一条。')
})

test('J4 上限：超字数时局官不回答内容，只让他拆开重说', () => {
  const r = judgeReply('今天我先去买了东西然后又去看了几眼手机结果什么都没做完'.repeat(8), { limit: 150 })
  assert.equal(r.overflow, true)
  assert.equal(r.chaos, false, '超字数优先于混乱判定')
  assert.equal(checkPhrase(r.say).ok, true, '局官自己那句话也要合规')
})

test('J4 上限：局官单条 40 字，超了就判不合规', () => {
  assert.equal(OFFICIAL_LIMIT, 40)
  // 40 个字符正好到线，多一个字就超：字 39 个 + 一个问号 = 40。
  assert.equal(checkPhrase('字'.repeat(39) + '？').ok, true)
  const long = checkPhrase('这一句故意写得非常长'.repeat(5))
  assert.equal(long.ok, false)
  assert.ok(long.reasons.includes('超过 40 字'))
})

test('J4 上限：局官一条里最多一个问句', () => {
  assert.equal(checkPhrase('第一步是什么？').ok, true)
  assert.equal(checkPhrase('第一步是什么？第二步呢？').ok, false)
})

test('J4 上限：给了局内对象，局官那句话就必须提到它', () => {
  assert.equal(checkPhrase('那天讲的那一节呢？', { object: '那节' }).ok, false)
  assert.equal(checkPhrase('那节讲了什么？', { object: '那节' }).ok, true)
  // 没给对象就不查这一条：代码认不出哪个词是对象，不拿猜出来的标准发合格证。
  assert.equal(checkPhrase('那节讲了什么？').ok, true)
})

test('J4 上限：外出局 50 字以内算过，51 字算超', () => {
  assert.equal(judgeReply('字'.repeat(50), { limit: OUTING_LIMIT }).overflow, false)
  assert.equal(judgeReply('字'.repeat(51), { limit: OUTING_LIMIT }).overflow, true)
})

test('J4 上限：超字数的那一拍不进账本（没被当成应答）', () => {
  const protocol = openWith('outing')
  withDb(({ home, db }) => {
    const official = createOfficial({ protocol, db, home })
    official.start()
    official.answer('字'.repeat(60))
    const bout = runningBout(db)
    assert.equal(listBeats(db, bout.id).length, 0)
  })
})

// ---- 混乱处理（§2.2：混乱的输入得到的不是回应，而是一个压缩动作）----

test('J4 混乱：说满 3 件事就判混乱', () => {
  const r = judgeReply('第一件事是买菜。第二件事是修手机。第三件事是写周报。')
  assert.equal(r.chaos, true)
  assert.equal(r.metrics.话题数, 3)
})

test('J4 混乱：A、B、C 由代码从名词簇里抽，不由模型总结', () => {
  const r = judgeReply('买菜。修手机。写周报。')
  assert.equal(r.chaos, true)
  assert.ok(r.nouns.length >= 1)
  assert.ok(r.say.includes('选一件。'))
  assert.ok(r.say.includes(r.nouns[0]), '提示里要出现代码抽出的名词')
})

test('J4 混乱：80 字以上没有结论句也算混乱', () => {
  const text = '我先是想了一下这个事'.repeat(9)
  assert.ok(text.replace(/\s/g, '').length > 80)
  const r = judgeReply(text)
  assert.equal(r.chaos, true)
})

test('J4 混乱：80 字以上但有结论句就不算混乱', () => {
  const text = '我把几条路都比过一遍了，'.repeat(4) + '所以先做这一条。'
  const r = judgeReply(text)
  assert.equal(r.chaos, false)
})

test('J4 混乱：混乱时局官只发压缩指令，不回答他提的任何内容', () => {
  const r = judgeReply('为什么这样不行？是不是该换一个？还有别的办法吗？')
  assert.equal(r.chaos, true)
  assert.equal(checkPhrase(r.say).ok, true)
  assert.ok(r.say.includes('选一件。'))
  assert.ok(!r.say.includes('因为'), '不许回答内容')
})

test('J4 混乱：混乱的那一条不算应答，这一拍不往前走', () => {
  const protocol = openWith('execute')
  withDb(({ home, db }) => {
    const official = createOfficial({ protocol, db, home })
    official.start()
    const before = official.view().index
    const r = official.answer('买菜。修手机。写周报。')
    assert.equal(r.accepted, false)
    assert.equal(r.chaos, true)
    assert.equal(official.view().index, before)
  })
})

test('J4 混乱：连续两次混乱，本局切写模式', () => {
  const protocol = openWith('execute')
  withDb(({ home, db }) => {
    const official = createOfficial({ protocol, db, home })
    official.start()
    official.answer('买菜。修手机。写周报。')
    assert.equal(official.writeMode, false)
    official.answer('买菜。修手机。写周报。')
    assert.equal(official.writeMode, true)
  })
})

test('J4 混乱：写模式下文字不再算应答，只有照片算', () => {
  const protocol = openWith('execute')
  withDb(({ home, db }) => {
    const official = createOfficial({ protocol, db, home })
    official.start()
    official.answer('买菜。修手机。写周报。')
    official.answer('买菜。修手机。写周报。')
    const blocked = official.answer(goodFor('第一步'))
    assert.equal(blocked.accepted, false)
    assert.equal(blocked.say, '写模式：只收手写照片。')
    const photo = official.answer('[手写照片]', { form: 'photo' })
    assert.equal(photo.accepted, true)
  })
})

test('J4 混乱：一次正常应答会把混乱计数清零', () => {
  const protocol = openWith('execute')
  withDb(({ home, db }) => {
    const official = createOfficial({ protocol, db, home })
    official.start()
    official.answer('买菜。修手机。写周报。')
    assert.equal(official.chaosStreak, 1)
    official.answer(goodFor('第一步'))
    assert.equal(official.chaosStreak, 0)
  })
})

// ---- 敷衍走升级，不留死循环 ----

test('J4 敷衍：同一拍重打到上限就升级，不会永远重打', () => {
  const protocol = openWith('execute')
  const { last } = withDb(({ home, db }) => {
    const official = createOfficial({ protocol, db, home })
    official.start()
    let result = official.answer('嗯')
    for (let i = 0; i < RETRY_LIMIT + 2 && result.state === 'running'; i += 1) result = official.answer('嗯')
    return { last: result }
  })
  assert.equal(last.state, 'escalated')
})

test('J4 敷衍：升级后这一局结束，不会再接应答', () => {
  const protocol = openWith('rest')
  withDb(({ home, db }) => {
    const official = createOfficial({ protocol, db, home })
    official.start()
    official.close('abandoned')
    assert.equal(official.answer('还算数吗').accepted, false)
  })
})

test('J4 收支：收局后不再接受应答', () => {
  const protocol = openWith('execute')
  withDb(({ home, db }) => {
    const official = createOfficial({ protocol, db, home })
    official.start()
    official.close('abandoned')
    assert.equal(official.view().closed, true)
    assert.equal(official.answer('还有一句话').accepted, false)
  })
})

// ---------------------------------------------------------------- P5 双信号提醒（一）
//
// 信号一：这一拍限时到点没交。到点由 tick 走超时出口（这个出口以前没有任何调用方，
// 所以「限时」在代码里只是一块面板上的数字）；nudge 负责在到点之前先催一句。
// 到点那一刻才说话已经晚了——那一刻这一拍已经按超时出口走掉。

const CLOCK0 = Date.parse('2026-11-12T09:00:00+08:00')

/** 让局的时钟走到开局后 offsetSec 秒，连催 times 次。 */
function nudgeAfter(protocolId, offsetSec, times = 1) {
  const protocol = openWith(protocolId)
  return withDb(({ home, db }) => {
    const clock = { v: CLOCK0 }
    const official = createOfficial({ protocol, db, home, now: () => clock.v })
    official.start()
    const limitSec = Number(protocol.steps[0].limitSec)
    clock.v = CLOCK0 + offsetSec * 1000
    const out = []
    for (let i = 0; i < times; i += 1) out.push(official.nudge(clock.v))
    return { limitSec, out, view: official.view() }
  })
}

test('P5 催：离到点还远就不吵他', () => {
  const r = nudgeAfter('execute', 30)
  assert.equal(r.limitSec, 120)
  assert.equal(r.out[0].due, false)
  assert.equal(r.out[0].say, '')
  assert.equal(r.view.nudge, '')
})

test('P5 催：剩不到 NUDGE_LEAD_SEC 秒才催，并且说清还剩多少秒', () => {
  const r = nudgeAfter('execute', 120 - NUDGE_LEAD_SEC + 1, 1)
  assert.equal(r.out[0].due, true)
  assert.equal(r.out[0].leftSec, NUDGE_LEAD_SEC - 1)
  assert.match(r.out[0].say, /还剩 \d+ 秒。交。/)
})

test('P5 催：同一拍只催一次（每 minute 说一遍就成了复读机）', () => {
  const r = nudgeAfter('execute', 61, 2)
  assert.equal(r.out[0].due, true)
  assert.equal(r.out[1].due, false)
})

test('P5 催：催过的那一句留在 view 上，常驻通知读它', () => {
  const r = nudgeAfter('execute', 61, 1)
  assert.equal(r.view.nudge, r.out[0].say)
  assert.notEqual(r.view.nudge, '')
})

test('P5 催：已经过点不催——那是 tick 的活，这一拍已经算没交', () => {
  const r = nudgeAfter('execute', 120, 1)
  assert.equal(r.out[0].due, false)
  assert.equal(r.view.nudge, '')
})

test('P5 到点没交：这一拍落成一条 timeout，账上看得见', () => {
  const protocol = openWith('execute')
  withDb(({ home, db }) => {
    let clock = CLOCK0
    const official = createOfficial({ protocol, db, home, now: () => clock })
    official.start()
    const stepId = official.view().stepId
    clock = CLOCK0 + 121 * 1000
    const r = official.tick(clock)
    assert.equal(r.state, 'running', 'execute 的超时出口是下一步，不是收局')
    const bout = runningBout(db)
    const beats = listBeats(db, bout.id)
    assert.equal(beats.length, 1, '没交也是一拍，不能凭空消失')
    assert.equal(beats[0].stepId, stepId)
    assert.equal(beats[0].verdict, 'timeout')
    assert.equal(beats[0].channel, 'none')
    const verdicts = listVerdicts(db, bout.id)
    assert.equal(verdicts.length, 1)
    assert.equal(verdicts[0].result, 'timeout')
    assert.equal(verdicts[0].score, 0)
  })
})

test('P5 还没到点不落账：催和记是两回事', () => {
  const protocol = openWith('execute')
  withDb(({ home, db }) => {
    let clock = CLOCK0
    const official = createOfficial({ protocol, db, home, now: () => clock })
    official.start()
    clock = CLOCK0 + 30 * 1000
    assert.equal(official.nudge(clock).due, false)
    official.tick(clock)
    assert.equal(listBeats(db, runningBout(db).id).length, 0)
  })
})
