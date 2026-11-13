// dsh-go-companion · 内置协议（第八轮 J4）
//
// 五个行为局各一个文件，放在 lib/protocols/*.json，内容就是 J2 的协议格式。
// 这里只做三件事：读目录、按 J2 解释器校验、按 id 取。
//
// 校验不是走过场：文件是手写的，写错了（缺出口、写错能力名、忘了限时）
// 应当在加载期就拒绝开局，而不是等真机上走到那一拍才发现走不下去。
// 所以坏协议不吞、不修，原样把错误带回去。

import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadProtocol } from './protocol.js'

const DIR = join(dirname(fileURLToPath(import.meta.url)), 'protocols')

let cache = null

/**
 * 读出全部内置协议并校验。
 * @returns {{ok:true, file:string, protocol:object}|{ok:false, file:string, errors:object[]}[]}
 */
export function builtinProtocols() {
  if (cache) return cache
  const files = readdirSync(DIR).filter((f) => f.endsWith('.json')).sort()
  cache = files.map((file) => {
    let text = ''
    try {
      text = readFileSync(join(DIR, file), 'utf8')
    } catch (error) {
      return { ok: false, file, errors: [{ code: 'unreadable', where: '', message: String((error && error.message) || error) }] }
    }
    const result = loadProtocol(text)
    return result.ok ? { ok: true, file, protocol: result.protocol } : { ok: false, file, errors: result.errors }
  })
  return cache
}

/** 按 id 取一个校验过的内置协议；取不到返回 null。 */
export function builtinProtocolOf(id) {
  const want = String(id)
  for (const item of builtinProtocols()) {
    if (item.ok && item.protocol.id === want) return item.protocol
  }
  return null
}

/** 加载期拒绝的东西：没通过校验的那几个文件与它们的错误。 */
export function brokenBuiltins() {
  return builtinProtocols().filter((item) => !item.ok)
}

/** 测试用：丢掉缓存，重新读一遍磁盘。 */
export function resetBuiltinCache() {
  cache = null
}
