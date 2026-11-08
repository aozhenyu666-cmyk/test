// 测试辅助：让 `import { defineTool } from '@deepseek-ai/dsh-tools'` 在 DSH 之外也能解析。
//
// 找得到**能被真的 import 进来**的 dsh-tools 就用真的 —— 这样 output.schema 的作者 DSL
// 会被真编译器校验，而不是被一个「什么都接受」的 stub 放过。找不到（或那份只是指向
// Electron asar 的转发 shim、在纯 node 下 import 不了）就用 stub。
//
// 用法与 examples/plugins/dsh-go-example-weather/test/dsh-tools.js 一致：**先 import 本文件**。
import { existsSync, readFileSync, readdirSync, realpathSync } from 'node:fs'
import { registerHooks } from 'node:module'
import { homedir } from 'node:os'
import { delimiter, dirname, join } from 'node:path'
import { pathToFileURL } from 'node:url'

/** 本机上可能的 dsh-tools 位置。**只收集，不判断能不能 import。** */
function candidateEntries() {
  const dirs = []
  if (process.env.DSH_TOOLS_DIR) dirs.push(process.env.DSH_TOOLS_DIR)
  for (const dir of (process.env.PATH ?? '').split(delimiter)) {
    if (dir === '') continue
    const bin = join(dir, 'dsh')
    if (!existsSync(bin)) continue
    try {
      // <prefix>/bin/dsh -> <prefix>/lib/node_modules/@deepseek-ai/dsh-tools
      const prefix = dirname(dirname(realpathSync(bin)))
      dirs.push(join(prefix, 'node_modules', '@deepseek-ai', 'dsh-tools'))
    } catch {}
  }
  try {
    const profiles = join(homedir(), '.dsh', 'profiles')
    for (const name of readdirSync(profiles)) dirs.push(join(profiles, name, 'node_modules', '@deepseek-ai', 'dsh-tools'))
  } catch {}
  // DSH 桌面版自己那一份（在 Electron 里 existsSync 才能看见 asar 内路径）
  if (process.env.DSH_DESKTOP_RESOURCES) {
    dirs.push(join(process.env.DSH_DESKTOP_RESOURCES, 'app.asar', 'dsh', 'node_modules', '@deepseek-ai', 'dsh-tools'))
  }
  dirs.push(join(process.env.LOCALAPPDATA ?? '', 'Programs', 'DeepSeek Harness', 'resources', 'app.asar', 'dsh', 'node_modules', '@deepseek-ai', 'dsh-tools'))

  const entries = []
  for (const dir of dirs) {
    try {
      if (!existsSync(join(dir, 'package.json'))) continue
      const pkg = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8'))
      const main = typeof pkg.main === 'string' ? pkg.main : 'index.js'
      const entry = join(dir, main)
      if (existsSync(entry)) entries.push(pathToFileURL(entry).href)
    } catch {}
  }
  return entries
}

/**
 * 真的能 import 进来才有用。桌面版 profile 里那份是一个转发 shim，指向 Electron 的
 * app.asar —— 纯 node 下 import 会 ERR_MODULE_NOT_FOUND，这里必须实测过才算数。
 */
async function firstLoadable(entries) {
  for (const url of entries) {
    try {
      const mod = await import(url)
      if (typeof mod.defineTool === 'function') return url
    } catch {}
  }
  return undefined
}

export const realDshTools = await firstLoadable(candidateEntries())
const target = realDshTools ?? new URL('./dsh-tools-stub.js', import.meta.url).href

registerHooks({
  resolve(specifier, context, next) {
    if (specifier === '@deepseek-ai/dsh-tools') return { url: target, shortCircuit: true }
    return next(specifier, context)
  },
})

/**
 * 用一个最小的假 ctx 加载插件的 apply()。
 * **只提供 tools**：这正是「有 ctx.tools、没有 dsh-go 桥接」的宿主形态。
 * @param {{ inject?: boolean, effect?: boolean, tools?: boolean }} [shape] 想砍掉哪些宿主能力
 */
export async function loadPlugin(shape = {}) {
  const plugin = await import('../lib/index.js')
  const tools = new Map()
  const disposers = []
  const ctx = {
    inject() {},
    on() {},
    effect: shape.effect === false ? undefined : (fn) => {
      const dispose = fn()
      if (typeof dispose === 'function') disposers.push(dispose)
      return dispose
    },
    get: (name) => (name === 'tools' && shape.tools !== false ? ctx.tools : undefined),
    ...(shape.tools === false ? {} : {
      tools: {
        register: (def) => {
          tools.set(def.name, def)
          return () => tools.delete(def.name)
        },
      },
    }),
  }
  const handle = plugin.apply(ctx)
  return { plugin, tools, handle, ctx, disposers, dispose: () => { for (const d of disposers.splice(0)) d() } }
}
