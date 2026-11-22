import { test } from 'node:test'
import assert from 'node:assert/strict'

import { wallAuditView, wallView } from '../lib/wall_view.js'

/** 视图里所有绑定路径都要能在数据里取到：面板最怕的是绑到不存在的键（那格永远是空的）。 */
function binds(node, out = []) {
  if (!node || typeof node !== 'object') return out
  if (node.$ && typeof node.$ === 'string') out.push(node.$)
  for (const v of Object.values(node)) {
    if (Array.isArray(v)) for (const x of v) binds(x, out)
    else if (v && typeof v === 'object') binds(v, out)
  }
  return out
}

function doc() {
  return {
    lock: { headline: '', tone: '', caption: '', locked: false, until: '' },
    state: { label: '', agenda: '', id: '' },
    layers: [], layersNote: '',
    plan: { headline: '', rows: [], note: '', accepted: false },
    escalate: { rows: [], note: '' },
    audit: { rows: [], note: '' },
    rules: { rows: [], note: '' },
  }
}

function pick(data, path) {
  let cur = data
  for (const part of path.replace(/^data\./, '').split('.')) {
    if (cur === undefined || cur === null) return undefined
    cur = cur[part]
  }
  return cur
}

test('墙的每一处绑定都在数据里', () => {
  const view = wallView({}, {})
  const data = doc()
  const all = binds(view)
  assert.ok(all.length >= 12, '绑定太少了：' + all.length)
  for (const p of all) assert.notEqual(pick(data, p), undefined, '绑到了不存在的键：' + p)
})

test('审查页的每一处绑定都在数据里', () => {
  const data = doc()
  for (const p of binds(wallAuditView({}, {}))) {
    assert.notEqual(pick(data, p), undefined, '绑到了不存在的键：' + p)
  }
})

test('墙只有刷新一个动作——面板不能自己解锁', () => {
  const view = wallView({}, {})
  const actions = []
  const walk = (n) => {
    if (!n || typeof n !== 'object') return
    if (n.type === 'actions') for (const i of n.items || []) actions.push(i.action)
    for (const v of Object.values(n)) {
      if (Array.isArray(v)) v.forEach(walk)
      else if (v && typeof v === 'object') walk(v)
    }
  }
  walk(view)
  walk(wallAuditView({}, {}))
  assert.deepEqual([...new Set(actions)], ['wall.refresh'])
})

test('两个页面各是独立的 stack，标题不一样', () => {
  assert.equal(wallView({}, {}).type, 'stack')
  assert.equal(wallView({}, {}).title, '墙')
  assert.equal(wallAuditView({}, {}).title, '审查')
})

test('审查页不给申诉表单：面板上的时刻对不上账本条目的毫秒', () => {
  const view = wallAuditView({}, { components: ['form'] })
  const json = JSON.stringify(view)
  assert.equal(json.includes('"form"'), false)
  assert.equal(json.includes('wall.appeal'), false)
})
