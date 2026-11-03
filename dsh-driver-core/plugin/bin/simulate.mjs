#!/usr/bin/env node
// 打印一次 15 分钟锁定的完整时间线（不用模型、不用手机），看驾驶员在每个时刻会做什么。
//   node bin/simulate.mjs

import { Driver } from '../lib/core/driver.js'

const BARD = 'com.google.android.apps.bard'
const d = new Driver({
  hidden: { profile: { goals: ['考公行测'], habits: ['抓到一半就下结论'] }, memories: [], history: {} },
})
const mmss = (ms) => `${String(Math.floor(ms / 60000)).padStart(2, '0')}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`
const show = (t, who, text) => console.log(`${mmss(t)}  ${who.padEnd(6, '　')}${text}`)
const say = (t, list) => {
  for (const x of list) {
    const c = x.card
    const lv = x.level ? ` [level ${x.level}]` : ''
    if (x.type === 'end') show(t, '结束', `${x.reason}｜结论：${x.conclusion ?? '（无）'}`)
    else if (x.type === 'release') show(t, '放行', `休息到 ${mmss(x.until)}`)
    else show(t, x.type === 'pullback' ? '拉回' : x.type === 'escalate' ? '强提醒' : '驾驶员', `${c.text}${c.options ? `  A:${c.options.A} / B:${c.options.B}` : ''}${lv}`)
  }
}
const disp = (t) => d.surface({ at: t, cardId: d.state().card.id, status: 'displayed' })
const me = async (t, text, choice) => { show(t, '我', choice ?? text); say(t, await d.reply({ at: t, cardId: d.state().card.id, text, choice })) }
const s = (x) => x * 1000

say(0, await d.start({ at: 0, topic: '行测', task: '第3题图形推理', anchors: [BARD] }))
await d.live({ at: 0, active: true }); await disp(500)
await me(s(2), '选D')
await disp(s(3)); await me(s(14), '因为图形是对称的')
await disp(s(15))
show(s(40), '我', '（切去 B 站）'); say(s(40), await d.foreground({ at: s(40), pkg: 'tv.danmaku.bili' }))
for (let t = 41; t <= 75; t++) say(s(t), await d.tick({ at: s(t) }))
show(s(78), '我', '（回到 Gemini）'); await d.foreground({ at: s(78), pkg: BARD })
await disp(s(78)); await me(s(80), '嗯')
await disp(s(81)); await me(s(83), undefined, 'A')
await disp(s(84)); await me(s(100), '黑点数量每格加一')
for (let t = 101; t <= 260; t += 1) {
  const out = await d.tick({ at: s(t) })
  if (out.length) { say(s(t), out); await disp(s(t + 1)) }
  if (t === 190) { show(s(t), 'Live', '（Gemini 开口了）'); await d.audio({ at: s(t), assistantSpeaking: true }) }
}
show(s(270), '我', '（点「休息 3 分钟」）'); say(s(270), await d.pause({ at: s(270), minutes: 3, reason: '喝水' }))
for (let t = 271; t <= 460; t++) { const out = await d.tick({ at: s(t) }); if (out.length) { say(s(t), out); await disp(s(t)); break } }
await me(s(455), undefined, 'B')
const end = d.state().endsAt
say(end, await d.tick({ at: end })); await disp(end + 500)
await me(end + s(20), '第3题选D，因为左右对称且黑点数量递增')

console.log('\n推进统计：', d.state().summary)
