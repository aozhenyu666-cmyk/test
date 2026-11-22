// P0-3 先量再改：把每轮真正注入的每一块量出来，改之前和改之后各跑一次，贴输出。
// 只读：不启动引擎、不写状态，纯函数入参出参。
// shortcut: 工具定义只量 name + description（parameters 是结构化的固定开销，不参与比较），
//           要精确到 token 级再换成真引擎的 /count_tokens。
import { readFileSync, existsSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const LIB = join(HERE, '..', '..', 'app', 'assets', 'engine', 'plugins', 'dsh-go-companion', 'lib')
const INDEX = join(LIB, 'index.js')
const libImport = (file) => import(pathToFileURL(join(LIB, file)).href)

const { companionChatText, strategistChatText, capabilityLines, agendaLines } = await libImport('roles.js')
const { STATE_TABLE } = await libImport('machine.js')

// 一份接近真机的配置：双人格、有笔记库、两个名字都改过、开着严格模式。
const CONFIG = {
  dual: true,
  companionName: '鱼丸',
  strategistName: '鲸澜',
  owner: '他',
  mode: 'coach',
  notesPath: '/sdcard/Obsidian/fish',
  control: true,
}

const rows = []
const add = (id, text) => rows.push({ id, chars: String(text).length })

// ---- 人设底稿与能力说明（roles.js 的纯函数，跟 index.js 用的同一份）
add('companionChatText 底稿', companionChatText(CONFIG, []))
add('strategistChatText 底稿', strategistChatText(CONFIG, [], []))
add('capabilityLines（陪伴）', capabilityLines('companion', { notesDir: CONFIG.notesPath }))
add('capabilityLines（谋士）', capabilityLines('strategist', { notesDir: CONFIG.notesPath }))

// ---- 议程：每个主状态一条，量最长的那个（最坏情况）
let agendaWorst = 0
for (const row of STATE_TABLE) {
  const t = agendaLines(row.id).join('\n')
  if (t.length > agendaWorst) agendaWorst = t.length
}
add('agendaLines（最长主状态）', 'x'.repeat(agendaWorst))

// ---- 状态层六段：用真函数的真输出，不估
const { trustLines } = await libImport('trust.js')
const { rhythmLines } = await libImport('rhythm.js')
const { gearLines } = await libImport('gear.js')
const { geminiLines } = await libImport('gemini.js')
const { depositLines } = await libImport('deposit.js')
const { knowledgeLines } = await libImport('notes.js')

add('stateLines: 信用分（低分最长）', trustLines({ score: 12, band: 'low', counts: { hit: 3, slip: 2, honest: 1 } }).join('\n'))
add('stateLines: 作息（全开门最长）', rhythmLines({ morningGate: true, reviewGate: true, weekReviewGate: true, night: 'lock' }).join('\n'))
add('stateLines: 档位（任务模式最长）', gearLines({ gear: 'busy', taskBy: 'strategist', taskGoal: '把第七轮 P0-3 的预算做出来', gearUntil: Date.now() + 45 * 60_000, taskReason: '他自己定的' }, Date.now()).join('\n'))
add('stateLines: Gemini（连麦 + 复盘）', geminiLines({ session: { topic: '复习线代', until: Date.now() + 20 * 60_000 }, recaps: [{ at: Date.now() - 3_600_000, topic: '线代', summary: '讲了特征值和对角化，卡在相似变换的直观理解上，下一步做三道题。', stuck: '相似变换到底在干什么', next: '先做 3.1 的三道' }] }, Date.now()).join('\n'))
add('stateLines: 四段汇报（缺 4 段）', depositLines({ entries: {} }, true).join('\n'))
// 知识库：用真实目录（本机跑时 notesPath 不存在 → 走"他还没写"那支，比真机短，如实标注）
add('stateLines: 知识库（空库）', knowledgeLines(existsSync(CONFIG.notesPath) ? CONFIG.notesPath : '/nonexistent/fish', 'strategist').join('\n'))

// ---- 工具定义：扫源码里每个 xxxTool() 返回的 name + description
const src = readFileSync(INDEX, 'utf8')
const toolNames = [...src.matchAll(/^ {6}name: '([a-z0-9_]+)',$/gm)].map((m) => m[1])
let toolChars = 0
let toolCount = 0
for (const m of toolNames.length ? src.matchAll(/^ {6}name: '([a-z0-9_]+)',$/gm) : []) {
  const after = src.slice(m.index, m.index + 4000)
  const d = after.match(/^ {6}description: '((?:[^'\\]|\\.)*)',$/m)
  if (!d) continue
  toolChars += m[1].length + d[1].length
  toolCount += 1
}

// ---- 打印
const W = Math.max(...rows.map((r) => r.id.length))
console.log('块'.padEnd(W) + '  字符数')
console.log('-'.repeat(W + 10))
for (const r of rows) console.log(r.id.padEnd(W) + String(r.chars).padStart(8))

const stateSum = rows.filter((r) => r.id.startsWith('stateLines:')).reduce((n, r) => n + r.chars, 0)
const baseSum = rows.filter((r) => /ChatText 底稿|capabilityLines/.test(r.id)).reduce((n, r) => n + r.chars, 0)
const agenda = rows.find((r) => r.id.startsWith('agendaLines')).chars
console.log('-'.repeat(W + 10))
console.log('每轮常驻合计（底稿 + 能力 + 状态六段 + 议程）'.padEnd(W) + String(baseSum + stateSum + agenda).padStart(8))
console.log('其中：底稿 + 能力说明'.padEnd(W) + String(baseSum).padStart(8))
console.log('其中：状态层（无上限）'.padEnd(W) + String(stateSum).padStart(8))
console.log('其中：议程'.padEnd(W) + String(agenda).padStart(8))
console.log('')
console.log('工具定义（name + description，' + toolCount + ' 个）'.padEnd(W) + String(toolChars).padStart(8))
console.log('工具名字样本：' + toolNames.slice(0, 6).join(', ') + ' …共 ' + toolNames.length + ' 个 name 字面量')
