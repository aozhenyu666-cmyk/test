// dsh-go-companion · 从账本重建局的八张表
//
// 用法：node lib/rebuild.js --home <引擎 home 目录>
// 账本是权威：库和账本冲突时以账本为准，这个脚本按账本把八张表重放一遍。
//
// 只读账本、只写 fish-memory.sqlite 里的八张局表，不碰 facts / topics / episodes。

import { join } from 'node:path'
import { rebuildFromLedgerFile, openBoutsDb } from './bouts.js'

export function rebuildHome(home) {
  const file = join(home, 'fish-memory', 'fish-memory.sqlite')
  const db = openBoutsDb(file)
  if (!db) return { ok: false, error: 'no-db', file }
  try {
    const r = rebuildFromLedgerFile(db, home)
    return { ...r, file }
  } finally {
    try {
      db.close()
    } catch {
      // 关不掉不影响已经写下去的数据
    }
  }
}

function argValue(name) {
  const i = process.argv.indexOf(name)
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : ''
}

if (process.argv[1] && process.argv[1].endsWith('rebuild.js')) {
  const home = argValue('--home') || process.env.DSH_HOME || ''
  if (!home) {
    console.error('用法：node lib/rebuild.js --home <引擎 home 目录>')
    process.exit(2)
  }
  const result = rebuildHome(home)
  console.log(JSON.stringify(result))
  process.exit(result.ok ? 0 : 1)
}
