// 4.10 四部门极简收件箱纯模块（lib/inbox_dept.js）单元测试
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  INBOX_FILE,
  appendInboxEntry,
  buildStrategizeBundle,
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
