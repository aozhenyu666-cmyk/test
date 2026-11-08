// 找一个**真的** @deepseek-ai/dsh-tools（不在 DSH profile 里时不报错，返回 null）。
// 有了它就能证明参数谱真的过得了 DSH 的 schema 编译 —— 假替身证明不了这件事。
// 用法：DSH_TOOLS_DIR=<含 lib/index.js 的目录> node --test
import { existsSync, readdirSync, realpathSync } from 'node:fs'
import { delimiter, dirname, join } from 'node:path'
import { pathToFileURL } from 'node:url'

function hasLib(dir) {
  return existsSync(join(dir, 'lib', 'index.js')) || existsSync(join(dir, 'index.js'))
}

export function findRealDshTools() {
  const candidates = []
  if (process.env.DSH_TOOLS_DIR) candidates.push(process.env.DSH_TOOLS_DIR)
  // <profile>/node_modules/@deepseek-ai/dsh-tools（profile 由 DSH_HOME 决定，测试里会被换掉）
  const home = process.env.USERPROFILE || process.env.HOME || ''
  if (home) candidates.push(join(home, '.dsh', 'profiles', 'desktop', 'node_modules', '@deepseek-ai', 'dsh-tools'))
  for (const dir of (process.env.PATH ?? '').split(delimiter)) {
    const bin = join(dir, 'dsh')
    if (!existsSync(bin)) continue
    try {
      const pkg = dirname(dirname(realpathSync(bin)))
      candidates.push(join(pkg, 'node_modules', '@deepseek-ai', 'dsh-tools'))
    } catch {}
  }
  return candidates.find(hasLib) ?? null
}

/** 加载真 defineTool；拿不到就返回 null（调用方 skip，不假装通过）。 */
export async function loadRealDefineTool() {
  const dir = findRealDshTools()
  if (dir === null) return null
  try {
    const entry = existsSync(join(dir, 'lib', 'index.js')) ? join(dir, 'lib', 'index.js') : join(dir, 'index.js')
    const mod = await import(pathToFileURL(entry).href)
    return typeof mod.defineTool === 'function' ? mod.defineTool : null
  } catch {
    // profile 里那份通常 re-export 自 app.asar，普通 node 读不到 —— 这不算失败，算「这机器上没有」
    return null
  }
}

export { readdirSync }
