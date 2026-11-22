// 4.10 四部门极简收件箱纯模块（lib/inbox_dept.js）单元测试
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  INBOX_FILE,
  INBOX_MAX_BYTES,
  INBOX_MAX_DAYS,
  INBOX_MAX_ENTRIES,
  appendInboxEntry,
  buildStrategizeBundle,
  compactInbox,
  readInboxEntries,
  resolveInboxPath,
} from '../lib/inbox_dept.js'

function withTempDir(fn) {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-inbox-'))
  try {
    return fn(dir)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

test('INBOX_FILE 常量与 kbDir 空值/防穿越校验', () => {
  assert.equal(INBOX_FILE, '大肥鱼/收件箱.md')
  assert.equal(appendInboxEntry('', { text: '记一条' }).ok, false)
  assert.equal(appendInboxEntry('   ', { text: '记一条' }).ok, false)
  assert.equal(appendInboxEntry(null, { text: '记一条' }).ok, false)
  assert.equal(resolveInboxPath('a\0b').ok, false)
  assert.deepEqual(readInboxEntries('', { limit: 10 }), [])
})

test('appendInboxEntry 只追加不覆盖、按日期分段、兼容 notesDir 与 notes.kbDir 两种路径', () => {
  withTempDir((vault) => {
    const t1 = Date.parse('2026-11-21T09:15:00+08:00')
    const t2 = Date.parse('2026-11-21T14:30:00+08:00')
    const t3 = Date.parse('2026-11-22T08:05:00+08:00')

    const r1 = appendInboxEntry(vault, { text: '资料分析截位直除老是犹豫三位还是两位', tag: '卡点', at: t1 })
    assert.equal(r1.ok, true)
    assert.equal(r1.day, '2026-11-21')
    assert.equal(r1.time, '09:15')

    // 第二条传 notes.kbDir（即 <vault>/大肥鱼）也落到同一个 大肥鱼/收件箱.md，绝不嵌套 大肥鱼/大肥鱼
    const r2 = appendInboxEntry(join(vault, '大肥鱼'), { text: '晚上找 Gemini 拆一下申论开头模板', tag: '谋划', at: t2 })
    assert.equal(r2.ok, true)
    assert.equal(r2.file, r1.file)

    const r3 = appendInboxEntry(vault, { text: '早起先刷 10 道言语', tag: '随记', at: t3 })
    assert.equal(r3.ok, true)
    assert.equal(r3.day, '2026-11-22')

    const raw = readFileSync(r1.file, 'utf8')
    assert.equal(raw.match(/## 2026-11-21/g).length, 1, '同一天只写一次日期标题')
    assert.equal(raw.match(/## 2026-11-22/g).length, 1)
    assert.match(raw, /- 09:15 \[卡点\] 资料分析截位直除老是犹豫三位还是两位/)
    assert.match(raw, /- 14:30 \[谋划\] 晚上找 Gemini 拆一下申论开头模板/)
    assert.match(raw, /- 08:05 \[随记\] 早起先刷 10 道言语/)

    const entries = readInboxEntries(vault, { limit: 2 })
    assert.equal(entries.length, 2)
    assert.equal(entries[0].day, '2026-11-21')
    assert.equal(entries[0].tag, '谋划')
    assert.equal(entries[1].day, '2026-11-22')
    assert.equal(entries[1].time, '08:05')
  })
})

test('buildStrategizeBundle：打包收件箱 + 卡壳记录 + 未过保底验收点 + 到期复习摘要', () => {
  withTempDir((vault) => {
    const t1 = Date.parse('2026-11-21T10:00:00+08:00')
    appendInboxEntry(vault, { text: '想把下午刷题节奏从 20 题调到 15 题', tag: '调整', at: t1 })

    const bundle = buildStrategizeBundle({
      kbDir: vault,
      inboxLimit: 15,
      today: '2026-11-21',
      stuckItems: [{ title: '数量关系工程问题', why: '设特值总设反' }],
      overdueSeals: [{ title: '行测一套（上午）', status: 'below', actual: 6, floor: 10 }],
      dueReviews: [
        { topic: '资料分析', q: '基期倍数公式与现期倍数差值？', lapses: 2 },
        { topic: '言语', q: '并列结构转折后重点在哪？', lapses: 0 },
      ],
    })

    assert.equal(bundle.ok, true)
    assert.equal(bundle.inboxCount, 1)
    assert.equal(bundle.stuckCount, 2)
    assert.equal(bundle.dueReviewCount, 2)
    assert.match(bundle.markdown, /# 谋划思考部交接包（2026-11-21）/)
    assert.match(bundle.markdown, /\[调整\] 想把下午刷题节奏从 20 题调到 15 题/)
    assert.match(bundle.markdown, /数量关系工程问题（卡点：设特值总设反）/)
    assert.match(bundle.markdown, /\[未过保底\] 行测一套（上午）（完成 6\/10）/)
    assert.match(bundle.markdown, /\[资料分析\] 基期倍数公式与现期倍数差值？（错 2 次）/)
  })
})

test('收件箱硬上限：超 14 天 / 120 条 / 16KB 自动按月归档到 大肥鱼/归档/收件箱-YYYY-MM.md', () => {
  assert.equal(INBOX_MAX_DAYS, 14)
  assert.equal(INBOX_MAX_ENTRIES, 120)
  assert.equal(INBOX_MAX_BYTES, 16 * 1024)

  // 1) 超 14 天按月归档
  withTempDir((vault) => {
    const tOldOct = Date.parse('2026-10-05T10:00:00+08:00')
    const tRecent = Date.parse('2026-11-22T09:00:00+08:00')
    appendInboxEntry(vault, { text: '十月初的老碎片需要归档', tag: '随记', at: tOldOct })
    const res = appendInboxEntry(vault, { text: '今天的新碎片留在活跃文件', tag: '灵感', at: tRecent })
    assert.equal(res.ok, true)
    assert.equal(res.archivedCount, 1)

    const active = readInboxEntries(vault, { limit: 200 })
    assert.equal(active.length, 1)
    assert.equal(active[0].text, '今天的新碎片留在活跃文件')

    const archOct = join(vault, '大肥鱼', '归档', '收件箱-2026-10.md')
    assert.equal(existsSync(archOct), true)
    assert.match(readFileSync(archOct, 'utf8'), /十月初的老碎片需要归档/)
  })

  // 2) 超 120 条上限自动归档最旧条目
  withTempDir((vault) => {
    const baseAt = Date.parse('2026-11-22T08:00:00+08:00')
    for (let i = 1; i <= 130; i += 1) {
      const r = appendInboxEntry(vault, { text: `第 ${i} 条短记录`, tag: '随记', at: baseAt + i * 1000 })
      assert.equal(r.ok, true)
    }
    const active = readInboxEntries(vault, { limit: 200 })
    assert.equal(active.length, 120)
    assert.equal(active[0].text, '第 11 条短记录')
    assert.equal(active[active.length - 1].text, '第 130 条短记录')

    const archNov = join(vault, '大肥鱼', '归档', '收件箱-2026-11.md')
    assert.equal(existsSync(archNov), true)
    const archText = readFileSync(archNov, 'utf8')
    assert.match(archText, /第 1 条短记录/)
    assert.match(archText, /第 10 条短记录/)
  })

  // 3) 超 16KB 硬上限自动截断归档（保持在 Gemini Bridge 24KB 读取窗内）
  withTempDir((vault) => {
    const baseAt = Date.parse('2026-11-22T09:00:00+08:00')
    const chunk = '长内容测试'.repeat(160) // 800 汉字约 2.4KB/条
    for (let i = 1; i <= 12; i += 1) {
      appendInboxEntry(vault, { text: `长条${i}-${chunk}`, tag: '灵感', at: baseAt + i * 1000 })
    }
    const inboxPath = resolveInboxPath(vault).file
    assert.ok(statSync(inboxPath).size <= INBOX_MAX_BYTES, '活跃收件箱必须 <= 16KB')
    const cmp = compactInbox(vault, { now: baseAt + 20_000 })
    assert.equal(cmp.ok, true)
    assert.equal(cmp.changed, false)
    const archNov = join(vault, '大肥鱼', '归档', '收件箱-2026-11.md')
    assert.equal(existsSync(archNov), true)
    assert.match(readFileSync(archNov, 'utf8'), /长条1-/)
  })
})
