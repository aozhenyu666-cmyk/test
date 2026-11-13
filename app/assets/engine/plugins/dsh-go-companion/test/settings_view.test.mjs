import { test } from 'node:test'
import assert from 'node:assert/strict'

import { settingsDayView, settingsLimitsView } from '../lib/settings_view.js'
import { DEFAULT_SETTINGS, buildSettingsSnapshot } from '../lib/settings_panel.js'

function binds(node, out = []) {
  if (!node || typeof node !== 'object') return out
  if (node.$ && typeof node.$ === 'string') out.push(node.$)
  for (const v of Object.values(node)) {
    if (Array.isArray(v)) for (const x of v) binds(x, out)
    else if (v && typeof v === 'object') binds(v, out)
  }
  return out
}

const data = buildSettingsSnapshot({ settings: DEFAULT_SETTINGS, now: 0 })

function pick(path) {
  const rest = path.replace(/^data\./, '')
  // `data.form.budget.study`：后半截就算键名本身带点，也当成一个键取，不再往下拆。
  if (rest.startsWith('form.')) return data.form[rest.slice('form.'.length)]
  let cur = data
  for (const part of rest.split('.')) {
    if (cur === undefined || cur === null) return undefined
    cur = cur[part]
  }
  return cur
}

test('作息页的每一处绑定都在数据里', () => {
  const all = binds(settingsDayView({}, { components: ['form'] }))
  assert.ok(all.length >= 15, '绑定太少了：' + all.length)
  for (const p of all) assert.notEqual(pick(p), undefined, '绑到了不存在的键：' + p)
})

test('阈值页的每一处绑定都在数据里', () => {
  for (const p of binds(settingsLimitsView({}, { components: ['form'] }))) {
    assert.notEqual(pick(p), undefined, '绑到了不存在的键：' + p)
  }
})

test('没有表单能力时不给表单，页面照样能看', () => {
  for (const view of [settingsDayView, settingsLimitsView]) {
    const json = JSON.stringify(view({}, {}))
    assert.equal(json.includes('"form"'), false)
    assert.equal(json.includes('textField'), false)
  }
})

test('每个输入框都预填了现在的值（改一个数不该从空白开始填）', () => {
  const fields = []
  const walk = (n) => {
    if (!n || typeof n !== 'object') return
    if (n.type === 'textField') fields.push(n)
    for (const v of Object.values(n)) {
      if (Array.isArray(v)) v.forEach(walk)
      else if (v && typeof v === 'object') walk(v)
    }
  }
  walk(settingsDayView({}, { components: ['form'] }))
  walk(settingsLimitsView({}, { components: ['form'] }))
  assert.ok(fields.length >= 17, '输入框太少了：' + fields.length)
  for (const fld of fields) {
    assert.ok(fld.bind.startsWith('data.form.'), '绑错了：' + fld.bind)
    const key = fld.bind.slice('data.form.'.length)
    assert.notEqual(data.form[key], undefined, '预填缺了 ' + key)
    assert.equal(fld.value.$, fld.bind, '预填必须和输入框绑同一个键：' + key)
    assert.equal(typeof data.form[key], 'string', '预填值要给字符串：' + key)
  }
})

test('两个页面各自一个保存动作，互不串台', () => {
  const submitOf = (view) => {
    let out = ''
    const walk = (n) => {
      if (!n || typeof n !== 'object') return
      if (n.type === 'form') out = n.submit
      for (const v of Object.values(n)) {
        if (Array.isArray(v)) v.forEach(walk)
        else if (v && typeof v === 'object') walk(v)
      }
    }
    walk(view)
    return out
  }
  assert.equal(submitOf(settingsDayView({}, { components: ['form'] })), 'settings.saveDay')
  assert.equal(submitOf(settingsLimitsView({}, { components: ['form'] })), 'settings.saveLimits')
})
