#!/usr/bin/env node
// 生成一个新的配对码，打印出来给「思维驾驶员」APP 输入。10 分钟内有效，只能用一次。
//   node bin/pair-code.mjs            生成配对码
//   node bin/pair-code.mjs --revoke   解除所有已配对的 APP
// 端口读不到时按 DSH Go 本机引擎默认的 3080；不对的话加 --port <端口>。

import { Pairing } from '../lib/pairing.js'

const args = process.argv.slice(2)
const p = new Pairing()
if (args.includes('--revoke')) {
  p.revokeAll()
  console.log('已解除所有配对。')
} else {
  const i = args.indexOf('--port')
  const port = i >= 0 ? Number(args[i + 1]) : 3080
  const shown = p.issueCode(Number.isInteger(port) && port > 0 ? port : 3080)
  console.log(`配对码：${shown}`)
  console.log('在「思维驾驶员」首页 → 连接大肥鱼，输入这串（10 分钟内有效，只能用一次）。')
}
