// D5 起晨间对线是"5 点后第一次亮屏"（没有 10 点上限）。端到端在 15:00 跑，先把今天那次
// 标成已完成，否则 morning-gate 会抢在主动发球 / 忙时 / 对账前面。
//
//   node seed-rhythm.mjs <home>/companion/rhythm.json
import { writeFileSync } from 'node:fs'

const [path] = process.argv.slice(2)
if (!path) {
  console.error('usage: node seed-rhythm.mjs <companion/rhythm.json>')
  process.exit(2)
}
// Asia/Shanghai 没有夏令时：当地零点 = UTC 零点 - 8h。
const OFFSET = 8 * 3_600_000
const DAY = 86_400_000
const now = Date.now()
const morningDay = Math.floor((now + OFFSET) / DAY) * DAY - OFFSET
writeFileSync(path, JSON.stringify({ morningDay, morningDone: true, morningSince: now - 3_600_000 }))
