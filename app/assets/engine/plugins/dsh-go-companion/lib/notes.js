// dsh-go-companion · 知识闭环（笔记库里的 大肥鱼/我.md、大肥鱼/判断.md）+ 未完成笔记追杀
//
// 我.md   —— 他写的：这一阶段的目标、每天的硬安排、红线（什么时候必须锁我 / 必须放我）、希望她们怎么对他。
//            两人每一轮都读它的摘要，以它为准。还没有时，鲸澜起草一版（只在文件不存在时能写），他来改。
// 判断.md —— 鲸澜写的：她对他的判断和证据（几点最容易滑、哪句话说了等于没说……）。每晚对账后改一次，
//            每次最多 5 条变化。他可以直接改、删，她下次以他改过的为准。
// 写权限：她们只能写 大肥鱼/ 和 Gemini/；别的目录只读。日记 / 私密目录连读都不读。
// 冲突时：他写的 > 他最近亲口说的 > 记忆库 > 她们的推断。

import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { join, relative } from 'node:path'

export const SELF_FILE = '我.md'
export const JUDGE_FILE = '判断.md'
export const KB_DIR = '大肥鱼'
/** 不读的目录（名字里含这些就跳过）。 */
export const PRIVATE_DIRS = ['日记', 'Diary', 'diary', 'Journal', 'journal', '私密', 'Private', 'private', '.obsidian', '.trash']

/** 知识库目录：笔记库/大肥鱼；没有笔记库就放在 DSH 自己的目录里。 */
export function kbDir(notesDir, home) {
  return notesDir ? join(notesDir, KB_DIR) : join(home, 'companion', KB_DIR)
}

function readText(file, max = 4000) {
  try {
    return readFileSync(file, 'utf8').slice(0, max)
  } catch {
    return ''
  }
}

/** 我.md 的摘要（进每一轮人设）：去掉空行和 front-matter，限 1200 字。 */
export function selfDigest(dir) {
  let t = readText(join(dir, SELF_FILE), 6000)
  if (!t.trim()) return ''
  t = t.replace(/^---[\s\S]*?---\s*/, '')
  return t.split('\n').map((l) => l.trimEnd()).filter((l) => l.trim()).join('\n').slice(0, 1200)
}

/** 判断.md 的摘要（进鲸澜每一轮，妹妹只拿前几条）。 */
export function judgeDigest(dir, max = 800) {
  const t = readText(join(dir, JUDGE_FILE), 6000).replace(/^---[\s\S]*?---\s*/, '')
  return t.split('\n').filter((l) => l.trim()).join('\n').slice(0, max)
}

/** 拼进两人的聊天人设。 */
export function knowledgeLines(dir, role) {
  const self = selfDigest(dir)
  const judge = judgeDigest(dir, role === 'strategist' ? 800 : 300)
  const lines = []
  if (self) {
    lines.push('他自己写的规则（' + KB_DIR + '/' + SELF_FILE + '，以它为准；跟别的说法冲突时：他写的 > 他最近亲口说的 > 记忆 > 你们的推断）：', self)
  } else {
    lines.push('他还没写 ' + KB_DIR + '/' + SELF_FILE + '（他的目标、硬安排、红线、希望你们怎么对他）。' +
      (role === 'strategist' ? '找个他不忙的时候，用 kb_write file=self 按你知道的起草一版，跟他说一声让他改；文件已经存在就不能覆盖。' : '提醒姐姐去起草，或者让他自己写。'))
  }
  if (judge) lines.push((role === 'strategist' ? '你之前写下的判断（' : '姐姐写下的对他的判断（') + KB_DIR + '/' + JUDGE_FILE + '，他可能改过，以文件为准）：', judge)
  lines.push('笔记库的规矩：你们只能写 ' + KB_DIR + '/ 和 Gemini/；别的目录只读，绝不改；日记、私密目录不读。读屏和相册看到的东西只用来当下说一句，不记进记忆或笔记。')
  return lines
}

/**
 * 写知识库：self（我.md，只在不存在时起草）/ judge（判断.md，整份替换，每次最多 5 条变化由人设约束）。
 * @returns {{ok:boolean, text:string, file?:string}}
 */
export function kbWrite(dir, file, markdown, now = Date.now()) {
  const body = String(markdown || '').trim()
  if (!body) return { ok: false, text: '内容是空的。' }
  if (body.length > 8000) return { ok: false, text: '太长了，8000 字以内。' }
  const name = file === 'self' ? SELF_FILE : file === 'judge' ? JUDGE_FILE : ''
  if (!name) return { ok: false, text: 'file 只能是 self 或 judge。' }
  const path = join(dir, name)
  if (name === SELF_FILE && existsSync(path) && readText(path).trim()) return { ok: false, text: SELF_FILE + ' 已经是他写的了，你不能覆盖；想改哪条就跟他说。' }
  try {
    mkdirSync(dir, { recursive: true })
    const stamp = '<!-- ' + (name === SELF_FILE ? '鲸澜起草，等他改' : '鲸澜更新') + ' · ' + new Date(now).toISOString().slice(0, 16).replace('T', ' ') + ' UTC -->\n'
    writeFileSync(path, stamp + body + '\n', 'utf8')
    return { ok: true, text: '写好了：' + path, file: path }
  } catch (error) {
    return { ok: false, text: '写不进去：' + String((error && error.message) || error) }
  }
}

// ---------------------------------------------------------------- 未完成笔记追杀

const MARK = /#待思考|#待想|#todo|TODO|- \[ \]/i

/** 一篇笔记像不像写了一半：结尾没有句号类标点，或者最后一行是列表项 / 标题。 */
function looksUnfinished(text) {
  const t = text.trimEnd()
  if (t.length < 80) return false
  const last = t.split('\n').pop().trim()
  if (/^#{1,6}\s/.test(last) || /^[-*]\s*$/.test(last)) return true
  return !/[。！？.!?）)」』"”…]$/.test(last)
}

/**
 * 扫笔记库，找值得追问的笔记：带 #待思考 / TODO / 未勾选的待办，或者两天到两周没动、明显写了一半的。
 * 跳过 大肥鱼/、Gemini/、日记私密目录。最多扫 600 个文件、深 5 层，免得卡住。
 * @returns {{path:string, rel:string, mtime:number, why:string, excerpt:string}[]}
 */
export function scanUnfinished(notesDir, { now = Date.now(), asked = [], limit = 600 } = {}) {
  if (!notesDir) return []
  const out = []
  let seen = 0
  const skip = new Set(asked)
  const walk = (dir, depth) => {
    if (depth > 5 || seen > limit) return
    let names = []
    try {
      names = readdirSync(dir)
    } catch {
      return
    }
    for (const name of names) {
      if (seen > limit) return
      if (name.startsWith('.') || PRIVATE_DIRS.some((p) => name.includes(p))) continue
      const path = join(dir, name)
      let st
      try {
        st = statSync(path)
      } catch {
        continue
      }
      if (st.isDirectory()) {
        if (depth === 0 && (name === KB_DIR || name === 'Gemini')) continue
        walk(path, depth + 1)
        continue
      }
      if (!name.endsWith('.md')) continue
      seen += 1
      const age = now - st.mtimeMs
      if (age > 30 * 86_400_000 || skip.has(path)) continue
      const text = readText(path, 20_000)
      const marked = MARK.test(text)
      const stale = age >= 2 * 86_400_000 && age <= 14 * 86_400_000 && looksUnfinished(text)
      if (!marked && !stale) continue
      const at = marked ? text.search(MARK) : Math.max(0, text.trimEnd().length - 300)
      out.push({
        path,
        rel: relative(notesDir, path),
        mtime: st.mtimeMs,
        why: marked ? '标了待思考 / 有没做的待办' : Math.round(age / 86_400_000) + ' 天没动、像是写了一半',
        excerpt: text.slice(Math.max(0, at - 120), at + 300).replace(/\s+/g, ' ').trim(),
      })
    }
  }
  walk(notesDir, 0)
  // 带标记的优先，其次越久没动越先追。
  out.sort((a, b) => (MARK.test(b.excerpt) - MARK.test(a.excerpt)) || a.mtime - b.mtime)
  return out.slice(0, 5)
}
