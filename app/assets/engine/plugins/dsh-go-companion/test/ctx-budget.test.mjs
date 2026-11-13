import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, readFileSync, existsSync, appendFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { apply, setStatePin } from '../lib/index.js'
import { STATE_TABLE, MAX_TOOLS_PER_STATE, ALL_TOOLS, toolsFor, toolsElsewhere } from '../lib/machine.js'
import { DEFAULT_BUDGET, INJECT_OPEN, INJECT_CLOSE, stripInjection } from '../lib/budget.js'

function agentOf(id) {
  const contexts = []
  const tools = {}
  return {
    id, status: 'idle', inbox: { hasPending: false }, sent: [], contexts, tools,
    followup() {},
    ctx: {
      systemPrompt: { context(def) { contexts.push(def); return () => { def.removed = true } } },
      tools: { register(def) { tools[def.name] = def; return () => { delete tools[def.name] } } },
    },
  }
}

function host(agents, state = 'standby') {
  let handler
  const listeners = new Map()
  const ctx = {
    get(name) {
      if (name === 'agents') return { get: (id) => agents[id], roots: () => Object.values(agents), list: () => Object.values(agents) }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async (id) => ({ agent: agents[id] }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on(name, fn) { if (!listeners.has(name)) listeners.set(name, []); listeners.get(name).push(fn) },
    inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(fn) { fn() } }) },
    effect(fn) { fn() },
  }
  setStatePin(state)
  apply(ctx)
  return { handler: () => handler }
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-ctx-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try { return await fn(home) } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    rmSync(home, { recursive: true, force: true })
  }
}

/** 挂一对姐妹，返回两个会话（人设文本从 contexts[0].text() 取）。 */
function mountPair(home, cfg = {}, state = 'standby') {
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({
    sessionId: 'session-sis', strategistSessionId: 'session-big', quietStart: 12, quietEnd: 12, ...cfg,
  }))
  const sis = agentOf('session-sis')
  const big = agentOf('session-big')
  host({ 'session-sis': sis, 'session-big': big }, state)
  return { sis, big }
}

test('C1 任一主状态的工具子集都不超过 8 个', () => {
  for (const row of STATE_TABLE) {
    assert.ok(row.tools && row.tools.length <= MAX_TOOLS_PER_STATE,
      row.id + ' 挂了 ' + (row.tools || []).length + ' 个工具')
  }
})

test('C2 全部工具每一个都至少在一个状态下可达', () => {
  assert.equal(ALL_TOOLS.length, 30) // 22 个原有工具 + fish_index（P0-3）+ take_stance/revise_stance（A1）+ mount_propose/mount_park/mount_close（C1）+ preset（R9 A）+ brain（行为大脑第一版）
  assert.equal(new Set(ALL_TOOLS).size, ALL_TOOLS.length, '并集里不许有重复')
  for (const name of ALL_TOOLS) {
    const reachable = STATE_TABLE.filter((r) => r.tools.includes(name)).map((r) => r.id)
    assert.ok(reachable.length > 0, name + ' 在任何状态下都拿不到')
  }
})

test('C3 被收起的工具说得出什么时候能用', () => {
  const gone = toolsElsewhere('night')
  assert.ok(gone.length > 0, '深夜锁应该收起大部分工具')
  for (const g of gone) assert.ok(g.when.length > 0, g.name + ' 没写什么时候能用')
  // 深夜锁自己能用的不会被算进"收起"
  assert.equal(gone.some((g) => g.name === 'fish_index'), false)
  assert.deepEqual(toolsElsewhere('night').map((g) => g.name).includes('notes_vault'), false)
})

test('C4 fish_index 在任一状态都拿得到（它是被收起能力的出口）', () => {
  for (const row of STATE_TABLE) {
    assert.ok(toolsFor(row.id).includes('fish_index'), row.id + ' 没有 fish_index，能力就说不出来')
  }
})

test('C14 挂出来的工具就是 toolsFor 那一套（表写得对不算，真挂上才算）', async () => {
  await withHome(async (home) => {
    for (const row of STATE_TABLE) {
      const { sis, big } = mountPair(home, {}, row.id)
      const got = Object.keys(sis.tools).sort()
      assert.deepEqual(got, [...toolsFor(row.id)].sort(), row.id + ' 挂出来的和表不一致')
      assert.ok(got.length <= MAX_TOOLS_PER_STATE, row.id + ' 挂了 ' + got.length + ' 个')
      // 两个会话挂的是同一套（R4-2 的权限一致，逐状态成立）。
      assert.deepEqual(Object.keys(big.tools).sort(), got, row.id + ' 下两姐妹挂出来的不一样')
    }
  })
})

test('C15 主状态一变，手上的工具跟着换（旧的注销、新的挂上）', async () => {
  await withHome(async (home) => {
    const { sis } = mountPair(home, {}, 'standby')
    assert.ok(sis.tools.mount_close, '待命该有挂载卡三件套')
    assert.equal(sis.tools.gemini_end, undefined, '待命不该有共脑工具')
    setStatePin('brain')
    assert.ok(sis.tools.gemini_end, '切到共脑该挂上 gemini_end')
    assert.equal(sis.tools.mount_close, undefined, '切到共脑后待命那套要注销')
    assert.equal(Object.keys(sis.tools).sort().join(','), [...toolsFor('brain')].sort().join(','))
  })
})

test('C5 人设每轮不超过 2400 字符（妹妹）', async () => {
  await withHome(async (home) => {
    const { sis } = mountPair(home)
    const text = sis.contexts[0].text()
    assert.ok(text.length <= DEFAULT_BUDGET.total, '妹妹的人设 ' + text.length + ' 字，超了 ' + DEFAULT_BUDGET.total)
    assert.ok(text.length > 0)
    // 约束层必须真的在里面：人设底稿、能力说明、议程一个都不能少
    assert.match(text, /你是鱼丸/)
    assert.match(text, /submit_to_wall/)
    assert.match(text, /待命|晨间立项|深夜锁|晚间对账|验收|追问锁|冲刺|共脑|吃饭|放风/)
  })
})

test('C6 人设每轮不超过 2400 字符（姐姐，最长的那份）', async () => {
  await withHome(async (home) => {
    const { big } = mountPair(home)
    const text = big.contexts[0].text()
    assert.ok(text.length <= DEFAULT_BUDGET.total, '姐姐的人设 ' + text.length + ' 字，超了 ' + DEFAULT_BUDGET.total)
    assert.match(text, /你是鲸澜/)
    assert.match(text, /读他的笔记和日程再推演/)
  })
})

test('C7 注入块有首尾标记，且整段能剥掉', async () => {
  await withHome(async (home) => {
    const { sis } = mountPair(home)
    const text = sis.contexts[0].text()
    const opens = text.split(INJECT_OPEN).length - 1
    const closes = text.split(INJECT_CLOSE).length - 1
    assert.ok(opens > 0, '一个注入块都没有？')
    assert.equal(opens, closes, '标记必须成对')
    const stripped = stripInjection(text)
    assert.equal(stripped.includes(INJECT_OPEN), false)
    assert.equal(stripped.includes(INJECT_CLOSE), false)
    // 剥掉的只能是注入块：人设底稿还在
    assert.match(stripped, /你是鱼丸/)
  })
})

test('C8 每轮落一条 ctx-budget（kept/dropped/chars 都在）', async () => {
  await withHome(async (home) => {
    const { sis } = mountPair(home)
    sis.contexts[0].text()
    const file = join(home, 'companion', 'ctx-budget.jsonl')
    assert.equal(existsSync(file), true, '没落账')
    const rows = readFileSync(file, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))
    assert.ok(rows.length >= 1)
    const last = rows[rows.length - 1]
    assert.ok(Array.isArray(last.kept) && last.kept.length > 0)
    assert.ok(Array.isArray(last.dropped))
    assert.equal(typeof last.chars, 'number')
    assert.equal(last.role, 'companion')
  })
})

test('C12 B4 情绪以一行进状态层（`[情绪] 火气 N · 耐心 M`），不占约束层', async () => {
  await withHome(async (home) => {
    const { sis } = mountPair(home)
    const text = sis.contexts[0].text()
    assert.match(text, /\[情绪\] 火气 \d+(（[^）]*）)? · 耐心 \d+/, '状态层里没有情绪那一行：' + text.slice(0, 400))
    const file = join(home, 'companion', 'ctx-budget.jsonl')
    const last = readFileSync(file, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l)).pop()
    assert.ok(last.kept.includes('emotion'), '情绪块被裁掉了（它很短，不该被裁）：' + JSON.stringify(last))
  })
})

test('C13 B4 账本里有敷衍，情绪那一行跟着变（不是写死的）', async () => {
  await withHome(async (home) => {
    const { sis } = mountPair(home)
    const anger0 = Number((/火气 (\d+)/.exec(sis.contexts[0].text()) || [])[1])
    assert.equal(anger0, 15, '一开始是日常值')
    const at = Date.now()
    appendFileSync(join(home, 'companion', 'ledger.jsonl'), [1, 2, 3].map((i) =>
      JSON.stringify({ v: 1, sessionId: 'session-sis', state: 'replied', requestId: 'rf' + i, verdict: 'brief', at: at - i * 1000 })).join('\n') + '\n')
    const after = sis.contexts[0].text()
    assert.match(after, /（敷衍了 3 次）/, '连击次数要写出来：' + after.slice(0, 400))
    assert.ok(Number((/火气 (\d+)/.exec(after) || [])[1]) > anger0, '火气该比之前高：' + /火气 \d+/.exec(after))
  })
})

test('C9 账本里的 say-text 不带注入标记（出口剥除）', async () => {  await withHome(async (home) => {
    mkdirSync(join(home, 'companion'), { recursive: true })
    const { stripInjection: strip } = await import('../lib/budget.js')
    // 出口剥除是纯函数：模型把标记原样复述回来也不会流进账本
    const echoed = '我看到了 ' + INJECT_OPEN + '\n他说在刷视频\n' + INJECT_CLOSE + ' 就这些'
    assert.equal(strip(echoed), '我看到了  就这些')
  })
})

test('C11 fish_index 无参返回目录，且不超过 600 字符', async () => {
  await withHome(async (home) => {
    const { sis } = mountPair(home)
    const tool = sis.tools.fish_index
    assert.ok(tool, 'fish_index 没挂上')
    const r = tool.execute({})
    assert.equal(r.ok, true)
    assert.ok(r.text.length <= 600, 'fish_index 返回 ' + r.text.length + ' 字，超了 600')
    // 目录该有的东西：主状态、今天几件事、四段、笔记库、被收起的能力
    assert.match(r.text, /现在的主状态/)
    assert.match(r.text, /今天定了/)
    assert.match(r.text, /四段汇报/)
    assert.match(r.text, /笔记库/)
    assert.match(r.text, /收起的能力|没有收起任何能力/)
  })
})

test('C10 空配置 / 单人格不炸，也不写坏账', async () => {
  await withHome(async (home) => {
    const { sis } = mountPair(home, { dual: false })
    const text = sis.contexts[0].text()
    assert.ok(text.length > 0, '单人格也该有人设')
    assert.ok(text.length <= DEFAULT_BUDGET.total)
  })
})
