import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { classifyPcActivity, collectAndPush, summarizeHeartbeat } from '../sync.mjs'

test('M4.3: classifyPcActivity 准确区分编码/文档/学习视频/纯娱乐/AFK', () => {
  assert.equal(classifyPcActivity('Code.exe', 'index.js - repo', 'not-afk'), 'coding')
  assert.equal(classifyPcActivity('Obsidian.exe', '认知笔记', 'not-afk'), 'docs')
  assert.equal(classifyPcActivity('chrome.exe', 'Distributed Systems docs & arxiv paper', 'not-afk'), 'research')
  assert.equal(classifyPcActivity('bilibili.exe', '算法与源码拆解 tutorial', 'not-afk'), 'study_video')
  assert.equal(classifyPcActivity('bilibili.exe', '搞笑鬼畜合集', 'not-afk'), 'entertainment')
  assert.equal(classifyPcActivity('Code.exe', 'index.js', 'afk'), 'afk')
})

test('M4.3: summarizeHeartbeat 与 collectAndPush 聚合最近活跃事件并推送伴侣端点', async () => {
  const now = Date.parse('2026-11-14T10:00:00Z')
  const summary = summarizeHeartbeat({
    now,
    afkEvents: [{ timestamp: '2026-11-14T09:58:00Z', duration: 120, data: { status: 'not-afk' } }],
    windowEvents: [
      { timestamp: '2026-11-14T09:57:00Z', duration: 300, data: { app: 'Code.exe', title: 'protocol.js' } },
      { timestamp: '2026-11-14T09:50:00Z', duration: 180, data: { app: 'bilibili.exe', title: '综艺摸鱼' } },
    ],
  })
  assert.equal(summary.isAfk, false)
  assert.equal(summary.category, 'coding')
  assert.equal(summary.focusMinutes, 5)
  assert.equal(summary.entertainmentMinutes, 3)

  const posted = []
  const fakeFetch = async (url, init = {}) => {
    if (url.endsWith('/api/0/buckets/')) {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          'aw-watcher-window_pc': { id: 'aw-watcher-window_pc' },
          'aw-watcher-afk_pc': { id: 'aw-watcher-afk_pc' },
        }),
      }
    }
    if (url.includes('/aw-watcher-window_pc/events')) {
      return {
        ok: true,
        status: 200,
        json: async () => [{ timestamp: '2026-11-14T09:57:00Z', duration: 600, data: { app: 'Obsidian.exe', title: '周复盘' } }],
      }
    }
    if (url.includes('/aw-watcher-afk_pc/events')) {
      return {
        ok: true,
        status: 200,
        json: async () => [{ timestamp: '2026-11-14T09:59:00Z', duration: 60, data: { status: 'not-afk' } }],
      }
    }
    if (url === 'http://100.64.0.12:3000/dsh-go-companion/v1/event') {
      posted.push(JSON.parse(init.body))
      return { ok: true, status: 200, json: async () => ({ ok: true }) }
    }
    return { ok: false, status: 404, json: async () => ({}) }
  }

  const tmp = mkdtempSync(join(tmpdir(), 'aw-sync-'))
  try {
    const outFile = join(tmp, 'hb.json')
    const r = await collectAndPush({
      awUrl: 'http://127.0.0.1:5600',
      companionUrl: 'http://100.64.0.12:3000/dsh-go-companion/v1/event',
      outFile,
      now,
      fetchFn: fakeFetch,
    })
    assert.equal(r.ok, true)
    assert.equal(r.pushed, true)
    assert.equal(posted.length, 1)
    assert.equal(posted[0].category, 'docs')
    assert.equal(posted[0].focusMinutes, 10)
    const disk = JSON.parse(readFileSync(outFile, 'utf8'))
    assert.equal(disk.activeApp, 'Obsidian.exe')
  } finally {
    rmSync(tmp, { recursive: true, force: true })
  }
})
