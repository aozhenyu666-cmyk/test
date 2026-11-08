/**
 * 真 defineTool 验证：参数谱 / 输出 schema 必须过得了 **DSH 自己的** schema 编译。
 *
 * 这一条和 apply.test.mjs 里的「子集检查」不是重复：
 *   · 那边检查的是「我只用了白名单关键字」（静态规则，我自己写的）；
 *   · 这边直接调真的 defineTool()，让它自己的编译器判（真实现，可能比规则更严）。
 *
 * 拿不到真的 dsh-tools 时**跳过并说明**，不假装通过。
 * 想在这台机器上跑：DSH_TOOLS_DIR=<含 lib/index.js 或 index.js 的 dsh-tools 目录>。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'

import { loadRealDefineTool } from './dsh-tools.js'
import { tempHome, seedAll } from './fixtures.mjs'
import { registerTools } from '../lib/index.js'

test('真 defineTool：四个工具都能通过 DSH 的 schema 编译', async (t) => {
  const defineTool = await loadRealDefineTool()
  if (defineTool === null) {
    t.skip('这台机器上没有可被普通 node 加载的 @deepseek-ai/dsh-tools（设 DSH_TOOLS_DIR 后可跑）')
    return
  }
  const home = tempHome()
  seedAll(home)
  const tools = []
  const ctx = { tools: { register: (def) => { tools.push(def); return () => {} } } }
  const r = await registerTools(ctx, {}, defineTool)
  assert.deepEqual(r.registered.slice().sort(), ['partner_correct', 'partner_mood', 'partner_recall', 'partner_remember'])
  assert.equal(r.reason, '')
  assert.equal(tools.length, 4)
  for (const def of tools) {
    assert.equal(def.parameters.type, 'object', def.name + ' 参数根必须是 object')
    assert.equal(def.output.schema.type, 'object')
    assert.equal(typeof def.output.render, 'function')
    assert.equal(typeof def.execute, 'function')
  }
})
