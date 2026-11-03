#!/usr/bin/env node
// 打印锁定会话的完整时间线（不用模型、不用手机），看驾驶员在每个时刻会做什么。
//   node bin/simulate.mjs

import { Agenda } from '../lib/core/agenda.js'
import { Driver } from '../lib/core/driver.js'

const BARD = 'com.google.android.apps.bard'
const hidden = { profile: { goals: ['考公行测'], habits: ['抓到一半就下结论'] }, memories: [], history: {} }
const agenda = new Agenda([], { newId: () => 'A1' })
const mmss = (ms) => `${String(Math.floor(ms / 60000)).padStart(2, '0')}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`
const show = (t, who, text) => console.log(`${mmss(t)}  ${who.padEnd(6, '　')}${text}`)
const s = (x) => x * 1000

function session(d) {
  const say = (t, list) => {
    for (const x of list) {
      const c = x.card
      const lv = x.level ? ` [level ${x.level}]` : ''
      const why = x.reason === 'turn_gap' ? '（Gemini 说完你没接）' : ''
      if (x.type === 'end') show(t, '结束', `${x.reason}｜结论：${x.conclusion ?? '（无）'}`)
      else if (x.type === 'release') show(t, '放行', `休息到 ${mmss(x.until)}`)
      else show(t, x.type === 'pullback' ? '拉回' : x.type === 'escalate' ? '强提醒' : '驾驶员', `${why}${c.text}${c.options ? `  A:${c.options.A} / B:${c.options.B}` : ''}${lv}`)
    }
  }
  const disp = (t) => d.surface({ at: t, cardId: d.state().card.id, status: 'displayed' })
  const me = async (t, text, choice) => { show(t, '我', choice ?? text); say(t, await d.reply({ at: t, cardId: d.state().card.id, text, choice })) }
  const gemini = async (from, to) => {
    show(s(from), 'Live', `（Gemini 说话 ${to - from} 秒）`)
    await d.audio({ at: s(from), assistantSpeaking: true })
    await d.audio({ at: s(to), assistantSpeaking: false })
  }
  const run = async (from, to) => { for (let t = from; t <= to; t++) { const o = await d.tick({ at: s(t) }); if (o.length) { say(s(t), o); await disp(s(t) + 500) } } }
  return { say, disp, me, gemini, run }
}

console.log('── 第一天 ──')
{
  const d = new Driver({ hidden, rng: () => 0.5, agenda })
  const { say, disp, me, gemini, run } = session(d)
  say(0, await d.start({ at: 0, topic: '行测', task: '第3题图形推理', anchors: [BARD] }))
  await d.live({ at: 0, active: true }); await disp(500)
  await me(s(2), '选D')
  await disp(s(3)); await me(s(14), '因为图形是对称的')
  await disp(s(15))
  show(s(40), '我', '（切去 B 站）'); say(s(40), await d.foreground({ at: s(40), pkg: 'tv.danmaku.bili' }))
  await run(41, 75)
  show(s(78), '我', '（回到 Gemini）'); await d.foreground({ at: s(78), pkg: BARD })
  await disp(s(78)); await me(s(80), '嗯')
  await disp(s(81)); await me(s(83), undefined, 'A')
  await disp(s(84))
  await gemini(90, 98)
  await run(99, 120)
  await me(s(125), '黑点数量每格加一')
  await disp(s(126))
  await gemini(130, 140)
  await gemini(150, 158)
  await run(159, 260)
  show(s(270), '我', '（点「休息 3 分钟」）'); say(s(270), await d.pause({ at: s(270), minutes: 3, reason: '喝水' }))
  for (let t = 271; t <= 460; t++) { const o = await d.tick({ at: s(t) }); if (o.length) { say(s(t), o); await disp(s(t)); break } }
  await me(s(455), undefined, 'B')
  const end = d.state().endsAt
  say(end, await d.tick({ at: end })); await disp(end + 500)
  await me(end + s(20), '第3题选D，因为左右对称且黑点数量递增')
  const x = d.state().summary
  console.log(`\n推进：醒 ${x.awake} 次｜回 ${x.responded} 次｜有依据的思考 ${x.grounded} 次｜切屏 ${x.interruptions} 次｜接回 ${x.resumed} 次`)
}

console.log('\n── 第二天：做到一半被电话打断 ──')
{
  const d = new Driver({ hidden, rng: () => 0.5, agenda, newId: (() => { let n = 0; return () => `d${++n}` })() })
  const { say, disp } = session(d)
  say(0, await d.start({ at: 0, topic: '行测', task: '第7题数量关系', anchors: [BARD] })); await disp(500)
  show(s(20), '我', '（切去微信）'); say(s(20), await d.foreground({ at: s(20), pkg: 'com.tencent.mm' }))
  show(s(25), '我', '（来电，点「紧急结束」）'); say(s(25), await d.stop({ at: s(25), reason: 'call' }))
  console.log(`议程里挂着：${agenda.open().map((a) => `「${a.anchor}」`).join('、')}`)
}

console.log('\n── 第三天：同一题回来 ──')
{
  const d = new Driver({ hidden, rng: () => 0.5, agenda, newId: (() => { let n = 0; return () => `e${++n}` })() })
  const { say, disp, me } = session(d)
  say(0, await d.start({ at: 0, topic: '行测', task: '第七题数量关系', anchors: [BARD] })); await disp(500)
  await me(s(3), undefined, 'A')
  await disp(s(4)); await me(s(15), '因为总数是 3 的倍数，所以排除 B')
  console.log(`议程状态：${agenda.items.map((a) => `「${a.anchor}」${a.status === 'done' ? '已解决' : a.status}`).join('、')}`)
}
