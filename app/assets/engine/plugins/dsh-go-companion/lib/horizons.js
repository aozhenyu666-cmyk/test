// dsh-go-companion · 自由口径三层主线引擎 (Horizons: 今日 / 中期战役 / 长期主航道)
// 不被周报月报日历格律绑架：
// - 长期 (long): 终局主航道（如“考公上岸”“核心岗位就业落地”），与 Obsidian 长期规划.md 双向同步；
// - 中期 (medium): 不固定天数的阶段战役（3–14+ 天，如“资料分析 200 题”“简历与模拟面 15 场”）；
// - 今日 (short): 今日作战单（关联 parentMediumId / parentLongId，做完自动回涨上层进展）。

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

export function cleanHorizons(raw) {
  const s = raw && typeof raw === 'object' ? raw : {}
  const normItem = (it, defaultHorizon) => {
    if (!it || typeof it !== 'object') return null
    const id = String(it.id || '').trim()
    const title = String(it.title || '').trim().slice(0, 100)
    if (!id || !title) return null
    return {
      id,
      horizon: String(it.horizon || defaultHorizon),
      title,
      note: String(it.note || '').trim().slice(0, 240),
      parentId: String(it.parentId || '').trim(),
      spanHint: String(it.spanHint || (defaultHorizon === 'long' ? '长期主航道' : '阶段战役')).slice(0, 32),
      targetSteps: Math.max(1, Math.min(100, Number(it.targetSteps) || 5)),
      doneSteps: Math.max(0, Number(it.doneSteps) || 0),
      status: it.status === 'done' || it.status === 'archived' ? it.status : 'active',
      createdAt: Number(it.createdAt) || Date.now(),
      updatedAt: Number(it.updatedAt) || Date.now(),
    }
  }
  return {
    long: (Array.isArray(s.long) ? s.long : []).map((x) => normItem(x, 'long')).filter(Boolean),
    medium: (Array.isArray(s.medium) ? s.medium : []).map((x) => normItem(x, 'medium')).filter(Boolean),
  }
}

export function syncLongGoalsFromMarkdown(horizonsRaw, mdPath) {
  const state = cleanHorizons(horizonsRaw)
  if (!mdPath || !existsSync(mdPath)) return state
  try {
    const content = readFileSync(mdPath, 'utf8')
    const lines = content.split(/\r?\n/)
    const existingTitles = new Set(state.long.map((x) => x.title))
    for (const line of lines) {
      const m = line.match(/^\s*(?:[-*]|\d+\.)\s+(?:\[[ xX]\]\s*)?(.+)$/)
      if (!m) continue
      const title = m[1].replace(/（.*?）/g, '').trim().slice(0, 100)
      if (!title || title.startsWith('#') || existingTitles.has(title)) continue
      existingTitles.add(title)
      const isDone = /^\s*(?:[-*]|\d+\.)\s+\[[xX]\]/.test(line)
      state.long.push({
        id: `long-${state.long.length + 1}-${Date.now().toString(36).slice(-4)}`,
        horizon: 'long',
        title,
        note: '同步自 长期规划.md',
        parentId: '',
        spanHint: '长期主航道',
        targetSteps: 10,
        doneSteps: isDone ? 10 : 0,
        status: isDone ? 'done' : 'active',
        createdAt: Date.now(),
        updatedAt: Date.now(),
      })
    }
  } catch {
    // ignore read error
  }
  return state
}

export function mirrorHorizonsToMarkdown(stateRaw, mdPath) {
  if (!mdPath) return
  try {
    const s = cleanHorizons(stateRaw)
    mkdirSync(dirname(mdPath), { recursive: true })
    const lines = ['# 长期规划与中期战役（DSH Go 三层主线双向镜像）', '']
    lines.push('## 一、长期主航道（终局方向）')
    if (!s.long.length) lines.push('- （暂无，请在主控台「三层主线 · 长期」添加你的就业/考公核心目标）')
    for (const item of s.long) {
      const mark = item.status === 'done' ? 'x' : ' '
      lines.push(`- [${mark}] ${item.title}（${item.spanHint} · 进度 ${item.doneSteps}/${item.targetSteps}）${item.note ? ` — ${item.note}` : ''}`)
    }
    lines.push('', '## 二、中期战役（不定期阶段攻坚）')
    if (!s.medium.length) lines.push('- （暂无阶段战役）')
    for (const item of s.medium) {
      const mark = item.status === 'done' ? 'x' : ' '
      lines.push(`- [${mark}] ${item.title}（${item.spanHint} · 进度 ${item.doneSteps}/${item.targetSteps}）${item.note ? ` — ${item.note}` : ''}`)
    }
    writeFileSync(mdPath, lines.join('\n') + '\n', 'utf8')
  } catch {
    // ignore write error
  }
}

export function upsertHorizonItem(stateRaw, payload = {}, now = Date.now()) {
  const s = cleanHorizons(stateRaw)
  const bucket = payload.horizon === 'long' ? 'long' : 'medium'
  const title = String(payload.title || '').trim().slice(0, 100)
  if (!title && !payload.id) return { ok: false, error: '请填写目标标题', state: s }

  const list = [...s[bucket]]
  const idx = payload.id ? list.findIndex((x) => x.id === String(payload.id)) : -1
  if (idx >= 0) {
    const prev = list[idx]
    const nextDone = payload.bumpStep
      ? Math.min(prev.targetSteps, prev.doneSteps + 1)
      : (payload.doneSteps !== undefined ? Math.max(0, Number(payload.doneSteps) || 0) : prev.doneSteps)
    const nextStatus = payload.status
      || (nextDone >= prev.targetSteps ? 'done' : prev.status)
    list[idx] = {
      ...prev,
      ...(title ? { title } : {}),
      ...(payload.note !== undefined ? { note: String(payload.note).trim().slice(0, 240) } : {}),
      ...(payload.spanHint !== undefined ? { spanHint: String(payload.spanHint).trim().slice(0, 32) } : {}),
      ...(payload.parentId !== undefined ? { parentId: String(payload.parentId).trim() } : {}),
      ...(payload.targetSteps !== undefined ? { targetSteps: Math.max(1, Math.min(100, Number(payload.targetSteps) || 5)) } : {}),
      doneSteps: nextDone,
      status: nextStatus,
      updatedAt: now,
    }
    const next = { ...s, [bucket]: list }
    return { ok: true, item: list[idx], state: next }
  }

  const item = {
    id: `${bucket === 'long' ? 'L' : 'M'}-${now.toString(36).slice(-5)}-${Math.floor(Math.random() * 90 + 10)}`,
    horizon: bucket,
    title,
    note: String(payload.note || '').trim().slice(0, 240),
    parentId: String(payload.parentId || '').trim(),
    spanHint: String(payload.spanHint || (bucket === 'long' ? '长期主航道' : '阶段战役')).slice(0, 32),
    targetSteps: Math.max(1, Math.min(100, Number(payload.targetSteps) || (bucket === 'long' ? 10 : 5))),
    doneSteps: 0,
    status: 'active',
    createdAt: now,
    updatedAt: now,
  }
  list.unshift(item)
  return { ok: true, item, state: { ...s, [bucket]: list.slice(0, 40) } }
}

export function deleteHorizonItem(stateRaw, id) {
  const s = cleanHorizons(stateRaw)
  const target = String(id || '').trim()
  return {
    long: s.long.filter((x) => x.id !== target),
    medium: s.medium.filter((x) => x.id !== target),
  }
}

export function archivePathInKb(kbDir) {
  return kbDir ? join(kbDir, '长期规划.md') : ''
}
