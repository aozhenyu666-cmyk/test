// 部署清单守卫：lib/ 下每个文件都必须写进 BuiltinPluginGuest.kt 的 PLUGINS 清单。
// 漏一个，真机启动时 import 就是 MODULE_NOT_FOUND（踩过），这里提前变红。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const plugins = join(here, '..', '..')
const guest = join(
  here, '..', '..', '..', '..', '..',
  'android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/BuiltinPluginGuest.kt',
)

function listed(source, id) {
  const at = source.indexOf('id = "' + id + '"')
  assert.ok(at >= 0, id + ' 不在 PLUGINS 里')
  const end = source.indexOf('),', source.indexOf('files = listOf(', at))
  const block = source.slice(at, end)
  return new Set([...block.matchAll(/"([^"]+)"/g)].map((m) => m[1]))
}

for (const id of ['dsh-go-companion', 'dsh-go-memory', 'dsh-go-relationship']) {
  test(id + '：lib/ 下的每个文件都在部署清单里', () => {
    const source = readFileSync(guest, 'utf8')
    const files = listed(source, id)
    const walk = (dir, prefix) => {
      for (const d of readdirSync(dir, { withFileTypes: true })) {
        const rel = prefix + d.name
        if (d.isDirectory()) walk(join(dir, d.name), rel + '/')
        else if (/\.(m?js)$/.test(d.name)) assert.ok(files.has(rel), rel + ' 没写进 BuiltinPluginGuest.kt，真机会 MODULE_NOT_FOUND')
      }
    }
    walk(join(plugins, id, 'lib'), 'lib/')
  })
}
