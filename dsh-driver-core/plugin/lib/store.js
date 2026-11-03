// 落盘与隐秘层读取。全部在 $DSH_HOME（默认 ~/.dsh）下：
//   storages/dsh-driver-core/config.json      出题会话、记忆来源、允许的应用
//   storages/dsh-driver-core/profile.json     你自己写的主线目标和惯性死穴（手改或走 /profile）
//   storages/dsh-driver-core/history.json     跨会话统计：骰子推进率、切屏分钟分布、回复耗时
//   storages/dsh-driver-core/agenda.json      思考议程：没接上的断点、没补上的依据、没把握的结论
//   storages/dsh-driver-core/sessions/<id>.jsonl  每次锁定的推进日志
// fish-memory 的库只读：fish-memory/session-<sid>.json，不写、不改。

import { appendFileSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

export function home(env = process.env) {
  return env.DSH_HOME || join(homedir(), '.dsh')
}

const dir = (h) => join(h, 'storages', 'dsh-driver-core')

function readJson(p, fallback) {
  try { return JSON.parse(readFileSync(p, 'utf8')) } catch { return fallback }
}

function writeJson(p, v) {
  mkdirSync(join(p, '..'), { recursive: true })
  writeFileSync(p + '.tmp', JSON.stringify(v, null, 2))
  renameSync(p + '.tmp', p)
}

export function makeStore(h = home()) {
  const d = dir(h)
  return {
    loadConfig: () => readJson(join(d, 'config.json'), {}),
    saveConfig: (c) => writeJson(join(d, 'config.json'), c),
    loadProfile: () => readJson(join(d, 'profile.json'), { goals: [], habits: [] }),
    saveProfile: (p) => writeJson(join(d, 'profile.json'), p),
    loadHistory: () => readJson(join(d, 'history.json'), {}),
    saveHistory: (x) => writeJson(join(d, 'history.json'), x),
    loadAgenda: () => readJson(join(d, 'agenda.json'), []),
    saveAgenda: (items) => writeJson(join(d, 'agenda.json'), items),
    appender: (sessionId) => {
      const p = join(d, 'sessions', `${sessionId}.jsonl`)
      mkdirSync(join(p, '..'), { recursive: true })
      return (row) => appendFileSync(p, JSON.stringify(row) + '\n')
    },
    /** 读 fish-memory 1.4.x 的库（schema v2：{memories:[{text, at, tags, category, importance}]}）。 */
    loadFishMemories: (sessionIds = []) => {
      const out = []
      for (const sid of sessionIds) {
        const id = String(sid).startsWith('session-') ? String(sid) : `session-${sid}`
        const data = readJson(join(h, 'fish-memory', `${id}.json`), null)
        for (const m of data?.memories ?? []) if (m && typeof m.text === 'string') out.push(m)
      }
      return out
    },
  }
}
