// 大肥鱼端到端：真 DSH 引擎 + 我们的五个插件 + 假模型，按手机的调用顺序走。
// 用法：node e2e.mjs warm|cold   （run.sh 会先跑 warm，重启引擎，再跑 cold）
import { readFileSync, writeFileSync, appendFileSync } from 'node:fs'
import { rpc, route, sleep, ledger, waitResult, prompt } from './app.mjs'
const W = process.env.E2E_WORK
let failed = 0
function check(name, ok, detail) {
  console.log((ok ? '  ✓ ' : '  ✗ ') + name + (ok ? '' : '  ← ' + JSON.stringify(detail).slice(0, 300)))
  if (!ok) failed += 1
}
// 15:00：避开第 8 节作息的早上门（5–12 点）、晚上对账（21:30 起）和深夜锁（0:30–7 点）。
const game = (minutes) => ({ app: 'com.tencent.tmgp.sgame', label: '王者荣耀', minutes, fun: true, screenOn: true, local: '15:00',
  today: [{ app: 'com.tencent.tmgp.sgame', label: '王者荣耀', minutes, fun: true }] })
const phase = process.argv[2]

if (phase === 'warm') {
  console.log('【新装 · 热会话】')
  const c = await rpc('session/create', { request: { cwd: W } })
  const sid = c.body?.result?.value?.sessionId
  check('建会话', !!sid, c.body)
  writeFileSync(W + '/sid.txt', sid || '')
  await route('POST', '/config', { sessionId: sid, mode: 'coach', quietStart: 3, quietEnd: 3 })

  // P6: /status carries the judge model and the measured medians; /config can set a faster judge.
  const st0 = await route('GET', '/status')
  check('/status 给出判球模型与耗时中位数', st0.body?.timings !== undefined && 'judgeModel' in (st0.body || {}), st0.body)
  const jc = await route('POST', '/config', { judgeProvider: 'deepseek', judgeModel: 'deepseek-flash' })
  check('判球模型可单独配置', jc.body?.config?.judgeModel === 'deepseek-flash', jc.body)
  await route('POST', '/config', { judgeProvider: '', judgeModel: '' })

  // 1. 记忆是空的、在游戏里 40 分钟：必须开口（以前 no-topic 永远闭嘴）
  const plan = await route('POST', '/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone: game(40) })
  check('空记忆也开口（/plan speak）', plan.body.speak === true, plan.body)
  check('游戏 40 分钟 → 力度锁', plan.body.force?.name === 'lock', plan.body.force)
  const id = 'req-e2e-' + Date.now()
  const req = await route('POST', '/request', { v: 1, requestId: id, eventId: 'evt-' + id, kind: 'random_chat', payload: {}, deadlineMs: 45000, phone: game(40) })
  check('发球被接受', req.status === 202, req.body)
  const res = await waitResult(id)
  check('拿到大肥鱼的话', res?.state === 'generated' && !!res.text, res)
  await route('POST', '/outcome', { requestId: id, stage: 'displayed' })

  // 2. 他在 App 里打字回（Dart 的 session/prompt）：插件必须认出这是他的回复
  await prompt(sid, '我打这局是因为作业写完了')
  for (let i = 0; i < 40 && !ledger().some((e) => e.requestId === id && e.state === 'replied'); i++) await sleep(250)
  const rep = ledger().find((e) => e.requestId === id && e.state === 'replied')
  check('App 里的回复被认出来并判球', !!rep && rep.judged === true, rep)
  check('讲了理由 → 先松 20 分钟', ledger().some((e) => e.state === 'grace'), ledger().map((e) => e.state))
  const st1 = await route('GET', '/status')
  check('发球与判球都有实测耗时', Number.isFinite(st1.body?.timings?.generate) && Number.isFinite(st1.body?.timings?.judge), st1.body?.timings)
  await sleep(1500)
  const plan2 = await route('POST', '/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone: game(45) })
  check('松的这段时间不锁', plan2.body.force?.level <= 1, plan2.body.force)
  check('讲通了 → 告诉手机放行大肥鱼加的锁', plan2.body.force?.release === true, plan2.body.force)

  // 3. 浮层里直接聊
  const say = await route('POST', '/say', { v: 1, requestId: 'say-' + Date.now(), text: '那你说我打完这局去干嘛' })
  check('/say 被接受', say.status === 202, say.body)
  const sr = say.body?.requestId ? await waitResult(say.body.requestId) : null
  check('/say 拿到回复', sr?.state === 'generated' && !!sr.text, sr)
  check('聊天不算主动发球', !ledger().some((e) => e.requestId === say.body?.requestId && e.state === 'generated'), '')

  // 4. 他正在聊（大肥鱼还在回）：不发主动球；浮层里接着说要排队，不报"忙"
  await prompt(sid, '我在想今晚要不要再开一把')
  await sleep(500)
  const busyPlan = await route('POST', '/plan', { roll: 0, timeZone: 'Asia/Shanghai', phone: game(12) })
  check('正在聊 → 不发主动球', busyPlan.body.speak === false && busyPlan.body.reason === 'in-conversation', busyPlan.body)
  const busySay = await route('POST', '/say', { v: 1, requestId: 'say-busy-' + Date.now(), text: '算了不开了' })
  check('它在回话时浮层发言排队（不报忙）', busySay.status === 202, busySay.body)
  const br = busySay.body?.requestId ? await waitResult(busySay.body.requestId) : null
  check('排队的那句也拿到回复', br?.state === 'generated', br)

  // 5. 阶梯：一球发出去 6 分钟没人接 → 下一球换轻的（时间注入：账本里写一条 6 分钟前的发球）
  // 用一个独立会话，避免刚接过的那一球把 silence 判成 none。
  // 第 7 节起"他在这之后说过任何话就算接过"（跨会话），所以先把前面几步的账本清掉再注入。
  writeFileSync(W + '/home/companion/ledger.jsonl', '')
  const ladderSid = 'session-ladder-' + Date.now()
  appendFileSync(W + '/home/companion/ledger.jsonl', JSON.stringify({ v: 1, requestId: 'old-silent', sessionId: ladderSid, state: 'generated', at: Date.now() - 6 * 60_000, serveType: 'provoke', text: 'x', rallyId: 'rally-x', shot: 1 }) + '\n')
  const lad = await route('POST', '/plan', { roll: 100, timeZone: 'Asia/Shanghai', sessionId: ladderSid, phone: { ...game(12), app: 'com.tencent.mm', label: '微信', fun: false } })
  check('6 分钟没接 → 阶梯换轻的球', lad.body.reason === 'ladder-lighter' || lad.body.reason === 'in-conversation', lad.body)
}

if (phase === 'cold') {
  console.log('【引擎重启 · 冷会话】')
  const sid = readFileSync(W + '/sid.txt', 'utf8').trim()
  const id = 'req-cold-' + Date.now()
  const req = await route('POST', '/request', { v: 1, requestId: id, eventId: 'evt-' + id, kind: 'random_chat', payload: {}, deadlineMs: 45000, phone: game(20) })
  check('冷会话被唤醒并接受发球', req.status === 202 && req.body.cold === true, req.body)
  const res = await waitResult(id)
  check('冷会话拿到大肥鱼的话', res?.state === 'generated', res)
  const say = await route('POST', '/say', { v: 1, requestId: 'say-cold-' + Date.now(), text: '我回来了' })
  const sr = say.body?.requestId ? await waitResult(say.body.requestId) : null
  check('冷会话里浮层聊天', sr?.state === 'generated', sr || say.body)
  void sid
}
console.log(failed ? '✗ ' + failed + ' 项失败' : '✓ 全部通过')
process.exit(failed ? 1 : 0)
