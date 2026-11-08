// C2：全天状态机。一天里每一刻只有一个主状态，多锁并存时放行范围取最严、界面只显示最高那一项。
// 纯函数，用假时钟跑一整天（每 5 分钟一格），不读文件、不取系统时间。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { LAYER_PRIORITY, STATE_TABLE, agendaLine, nextChange, resolveState } from '../lib/machine.js'

const TZ = 'Asia/Shanghai'
// 2026-03-10 00:00 当地 = 2026-03-09T16:00Z。用 UTC 造一个当地零点。
const DAY = Date.parse('2026-03-09T16:00:00Z')

const at = (hour, minute = 0) => DAY + (hour * 60 + minute) * 60_000

function state(hour, minute = 0, extra = {}) {
  return resolveState({
    now: at(hour, minute),
    tz: TZ,
    config: { wall: {}, ...(extra.config || {}) },
    rhythm: extra.rhythm || {},
    followup: extra.followup || { active: false, reason: '', since: 0, until: 0 },
    session: extra.session || null,
  })
}

test('C2 每 5 分钟一格跑一整天：每个时刻只有一个主状态，且取的是最高优先级的那个', () => {
  for (let min = 0; min < 1440; min += 5) {
    const s = state(Math.floor(min / 60), min % 60)
    assert.ok(STATE_TABLE.some((r) => r.id === s.state), min + ' 分钟：' + s.state + ' 不在表里')
    const row = STATE_TABLE.find((r) => r.id === s.state)
    assert.equal(s.priority, row.priority, min + ' 分钟：优先级要和表一致')
    // 没有比它更高优先级的行同时成立。
    assert.ok(
      STATE_TABLE.filter((r) => r.priority > row.priority).every((r) => r.id !== s.state),
      min + ' 分钟：不该有更高优先级的行被选中',
    )
    // 层按优先级从高到低排，界面只看第一层。
    for (let i = 1; i < s.layers.length; i += 1) {
      assert.ok(s.layers[i - 1].priority >= s.layers[i].priority, min + ' 分钟：层没排序')
    }
    assert.ok(s.agenda.length > 0, min + ' 分钟：主状态要带议程')
    assert.ok(Number(s.nextChangeAt) > at(Math.floor(min / 60), min % 60), min + ' 分钟：下一次换状态要在未来')
  }
})

test('C2 深夜锁压过一切：0:30–7:00 娱乐锁着，且优先于冲刺和追问锁', () => {
  const night = state(3, 0, {
    config: { gear: 'busy', gearUntil: at(4, 0), sprintUntil: at(4, 0) },
    followup: { active: true, reason: 'standby', since: at(2, 0), until: at(3, 30) },
  })
  assert.equal(night.state, 'night')
  assert.equal(night.priority, LAYER_PRIORITY.night)
  assert.equal(night.funLocked, true)
  // 放行范围取最严：深夜锁不定放行名单。
  assert.deepEqual(night.allow, [])
  const kinds = night.layers.map((l) => l.kind)
  assert.equal(kinds[0], 'night', '最高那层是深夜锁：' + kinds.join(','))
})

test('C2 吃饭时段娱乐开放：放行范围为空、不下发锁', () => {
  // 默认墙的午饭时段是 11:30–12:30。
  const meal = state(12, 0)
  assert.equal(meal.state, 'meal')
  assert.equal(meal.funLocked, false)
  assert.deepEqual(meal.allow, [])
})

test('C2 共脑只放行 Gemini 的包名', () => {
  const brain = state(15, 0, {
    config: { geminiPackages: ['com.google.android.apps.bard', 'com.google.android.googlequicksearchbox'] },
    session: { until: at(15, 30) },
  })
  assert.equal(brain.state, 'brain')
  assert.equal(brain.funLocked, true)
  assert.ok(brain.allow.includes('com.google.android.apps.bard'), JSON.stringify(brain.allow))
})

test('C2 追问锁是临时层：结束后回到原来的主状态', () => {
  const locked = state(15, 0, { followup: { active: true, reason: 'standby', since: at(14, 50), until: at(15, 20) } })
  assert.equal(locked.state, 'followup')
  assert.equal(locked.followup.reason, 'standby')
  // 待命是底层主状态（15 点不是吃饭/放风），锁结束后回去。
  assert.equal(locked.backTo, 'standby')
  const top = locked.layers[0]
  assert.equal(top.kind, 'followup')
  assert.equal(top.reason, 'standby')
})

test('C2 冲刺压过吃饭：他在冲刺，吃饭时段也不放开', () => {
  // 冲刺叠在吃饭上：吃饭层让娱乐开，冲刺层锁着，取最严。
  const sprint = state(12, 0, { config: { gear: 'busy', gearUntil: at(13, 0), sprintUntil: at(13, 0) } })
  assert.equal(sprint.state, 'sprint')
  assert.equal(sprint.funLocked, true)
  const kinds = sprint.layers.map((l) => l.kind)
  assert.ok(kinds.includes('sprint'), kinds.join(','))
})

test('C2 默认严格：白天非吃饭时段娱乐锁着，锁到下一段吃饭 / 深夜 / 今天结束', () => {
  const day = state(15, 0)
  assert.equal(day.state, 'standby')
  assert.equal(day.funLocked, true)
  assert.equal(day.layers[0].kind, 'standby')
  assert.ok(Number(day.until) > at(15, 0))
  assert.ok(Number(day.until) <= DAY + 86_400_000)
})

test('C2 下一次换状态：落在下一个边界上，不是当前时刻', () => {
  const s = state(11, 0)
  const next = nextChange({ now: at(11, 0), tz: TZ, config: { wall: {} }, midnight: DAY, endOfDay: DAY + 86_400_000, sprint: {}, brain: {}, followup: {}, relax: null }, s.layers)
  assert.ok(next > at(11, 0))
  // 11:00 往后最近的是午饭 11:30。
  assert.equal(next, at(11, 30))
})

test('C2 每个主状态都有议程，且议程里点明怎么算过关', () => {
  for (const row of STATE_TABLE) {
    assert.ok(row.agenda.length > 0, row.id + ' 缺议程')
    assert.equal(agendaLine(row.id), row.agenda)
  }
  assert.match(agendaLine('morning'), /完成物/)
  assert.match(agendaLine('review'), /完成物/)
  assert.match(agendaLine('evening'), /对账/)
})
