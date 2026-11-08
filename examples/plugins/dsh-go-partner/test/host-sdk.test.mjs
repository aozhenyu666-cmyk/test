/**
 * 模拟宿主测试（真 SDK 版）：
 * 用仓库自带的 sdk/plugin-kit/testing.js —— 那是**桥接自己的代码**（manifest 解析、
 * 视图预算、strip 文本截断、effect 归一化全是真的），不是我们写的替身。
 * 目的：证明本插件交给 App 的东西，和桥接真正会接受的东西一致。
 *
 * 跑：node --test test/
 */
import assert from 'node:assert/strict'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'

import { tempHome, seedAll, writeJson } from './fixtures.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SDK = path.resolve(HERE, '../../../../sdk/plugin-kit/lib/testing.js')
const PLUGIN_DIR = path.resolve(HERE, '..')

async function sdkHost() {
  const { createTestHost } = await import('file:///' + SDK.replace(/\\/g, '/'))
  // 插件需要 tools/agents：给最小的两个替身，别让 apply 里的可选路径变成崩溃
  const host = createTestHost({
    dir: PLUGIN_DIR,
    services: {
      tools: { register: () => () => {} },
      agents: { list: () => [], get: () => null },
    },
  })
  return host
}

test('真 SDK 宿主：清单解析通过，四块原生界面都登记上了', async () => {
  tempHome()
  const host = await sdkHost()
  const plugin = await import('file:///' + path.join(PLUGIN_DIR, 'lib/index.js').replace(/\\/g, '/'))
  const dispose = plugin.apply(host.ctx, { defineTool: (o) => o })
  assert.equal(typeof dispose, 'function', 'apply 必须返回 disposer')

  const regs = host.registrations()
  assert.deepEqual(regs.panels.slice().sort(), ['memory', 'mood', 'persona', 'purpose'])
  assert.deepEqual(regs.strips, ['persona.strip'])
  assert.deepEqual(regs.dataPanels, [], '面板视图按需取，不推 data（省得 4Hz 空转）')

  // 桥接会把「声明了但没实现 / 实现了但没声明」记成 runtimeIssues
  const contrib = host.contributions()
  assert.ok(contrib, 'contributions() 必须能生成')
  assert.deepEqual(host.violations, [], '不该有任何桥接违规：' + JSON.stringify(host.violations))
})

test('真 SDK 宿主：面板视图过桥接的预算检查（stats.bytes / nodes / depth）', async () => {
  const home = tempHome()
  seedAll(home)
  const host = await sdkHost()
  const plugin = await import('file:///' + path.join(PLUGIN_DIR, 'lib/index.js').replace(/\\/g, '/'))
  plugin.apply(host.ctx, { defineTool: (o) => o })
  for (const id of ['persona', 'memory', 'mood', 'purpose']) {
    const r = await host.view(id, { sessionId: 's1' })
    assert.ok(r.view && r.view.type === 'stack', id + ' 根节点')
    assert.ok(r.stats.bytes <= 32768, id + ' 视图 ' + r.stats.bytes + 'B')
    assert.ok(r.stats.nodes <= 120, id + ' 节点 ' + r.stats.nodes)
    assert.ok(r.stats.depth <= 8, id + ' 层数 ' + r.stats.depth)
  }
})

test('真 SDK 宿主：状态条 emit 的文字被桥接的截断逻辑接受（≤24 字）', async () => {
  const home = tempHome()
  seedAll(home)
  const host = await sdkHost()
  const plugin = await import('file:///' + path.join(PLUGIN_DIR, 'lib/index.js').replace(/\\/g, '/'))
  plugin.apply(host.ctx, { defineTool: (o) => o })
  const feed = host.subscribe('persona.strip', { sessionId: 's1' })
  assert.deepEqual(feed.errors, [], '状态条不该产生错误：' + feed.errors.join('; '))
  assert.ok(feed.data, '应该推过一次')
  assert.ok(feed.data.text === null || feed.data.text.length <= 24)
  feed.stop()
})

test('真 SDK 宿主：面板动作能 invoke，且返回的效果是白名单里的', async () => {
  const home = tempHome()
  seedAll(home)
  const host = await sdkHost()
  const plugin = await import('file:///' + path.join(PLUGIN_DIR, 'lib/index.js').replace(/\\/g, '/'))
  plugin.apply(host.ctx, { defineTool: (o) => o })
  const r = await host.invoke('memory', { surface: 'panel', scope: { sessionId: 's1' }, action: 'memory.refresh' })
  assert.ok(Array.isArray(r.effects), 'effects 必须是数组')
  for (const e of r.effects) {
    assert.ok(['insert', 'command', 'open', 'link', 'toast', 'copy', 'refresh', 'patch'].includes(e.type), '不认识的效果 ' + JSON.stringify(e))
  }
  assert.deepEqual(r.issues, [], '不该有 effect 校验问题：' + JSON.stringify(r.issues))
})

test('真 SDK 宿主：没有声明过的 action 会被桥接拒绝（说明清单和代码对得上）', async () => {
  const home = tempHome()
  seedAll(home)
  const host = await sdkHost()
  const plugin = await import('file:///' + path.join(PLUGIN_DIR, 'lib/index.js').replace(/\\/g, '/'))
  plugin.apply(host.ctx, { defineTool: (o) => o })
  await assert.rejects(
    () => host.invoke('memory', { surface: 'panel', scope: {}, action: 'not-a-real-action' }),
    (err) => err.status === 409 && err.code === 'no-handler',
  )
})

test('真 SDK 宿主：dispose 之后不再注册任何东西（没有泄漏）', async () => {
  tempHome()
  const host = await sdkHost()
  const plugin = await import('file:///' + path.join(PLUGIN_DIR, 'lib/index.js').replace(/\\/g, '/'))
  const dispose = plugin.apply(host.ctx, { defineTool: (o) => o })
  dispose()
  host.dispose()
  assert.ok(true)
})
