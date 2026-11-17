// dsh-go-companion · 墙的眼睛（2026-11）
//
// 墙只看物理证据：文件长了 300 字节就算写了东西。可凑字数、粘一段无关的东西，墙照样放行。
// 这里给墙加一层「读内容」：文件证据在代码层已经过了之后，把立项后新写的那段交给模型，
// 只问一件事——这段是不是真的在做这件事。
//
// 规矩：
//   1. 只能把「过」改成「不过」，不能把代码判的「不过」改成「过」。墙依然不能被说服。
//   2. 模型连不上、超时、输出读不懂：按代码的结论走，并在墙的话里说一句没读成。
//   3. 判「不过」必须引一句原文，引不到原文的「不过」不算（防止模型凭空否定）。
//   4. 只读新写的那段，最多 3000 字；不读整份笔记。

import { closeSync, openSync, readSync, statSync } from 'node:fs'

export const EYE_TIMEOUT_MS = 20_000
export const EYE_MAX_CHARS = 3000

/** 立项后新写的那一段：从立项时的大小读到文件尾。读不到返回空串。 */
export function newContent(path, beforeSize = 0, maxChars = EYE_MAX_CHARS) {
  let fd = null
  try {
    const size = statSync(path).size
    const from = Math.max(0, Math.min(Number(beforeSize) || 0, size))
    const len = Math.min(size - from, maxChars * 4)
    if (len <= 0) return ''
    const buf = Buffer.alloc(len)
    fd = openSync(path, 'r')
    readSync(fd, buf, 0, len, from)
    return buf.toString('utf8').replace(/^�+/, '').slice(0, maxChars)
  } catch {
    return ''
  } finally {
    if (fd !== null) try { closeSync(fd) } catch {}
  }
}

export function eyeSystem() {
  return '你是墙的审读员。你只判断一段新写的文字是不是在实打实地完成指定任务。' +
    '凑字数、重复粘贴、乱码、和任务无关的内容、只有标题没有正文，都算没完成。' +
    '只输出一行 JSON：{"pass":true 或 false,"quote":"从原文里原样摘的一句（不超过 40 字）","why":"一句话理由"}，不要输出别的。'
}

export function eyeUser({ task, evidence, text }) {
  return '任务：' + String(task || '').slice(0, 200) + '\n完成物：' + String(evidence || '').slice(0, 200) +
    '\n立项后新写的内容：\n<<<\n' + String(text || '') + '\n>>>'
}

/** 解析模型的那一行 JSON。读不懂返回 null。 */
export function parseEye(raw, text = '') {
  const s = String(raw || '')
  const m = s.match(/\{[\s\S]*\}/)
  if (!m) return null
  let o
  try {
    o = JSON.parse(m[0])
  } catch {
    return null
  }
  if (typeof o.pass !== 'boolean') return null
  const quote = String(o.quote || '').trim().slice(0, 60)
  const why = String(o.why || '').trim().slice(0, 80)
  // 判「不过」要拿得出原文：引的这句必须真的在新写的内容里。
  const quoted = Boolean(quote) && String(text).replace(/\s+/g, '').includes(quote.replace(/\s+/g, ''))
  if (!o.pass && !quoted) return { pass: true, why: '审读员说不过但引不出原文，按代码结论', quote: '', overruled: true }
  return { pass: o.pass, why, quote: quoted ? quote : '' }
}

/**
 * 读一遍。返回 { judged, pass, why, quote }；judged=false 表示没读成（调用方按代码结论走）。
 */
export async function eyeCheck({ stream, provider, model, task, evidence, text, imageBase64 = '', imageMimeType = 'image/png', timeoutMs = EYE_TIMEOUT_MS }) {
  if (typeof stream !== 'function' || !provider || !model) return { judged: false, pass: true, why: '没有可用的模型' }
  const hasImage = typeof imageBase64 === 'string' && imageBase64.length > 20
  if (!String(text || '').trim() && !hasImage) return { judged: false, pass: true, why: '读不到新写的内容' }
  let timer
  const timeout = new Promise((resolve) => {
    timer = setTimeout(() => resolve(null), Math.max(1, Number(timeoutMs) || EYE_TIMEOUT_MS))
  })
  const content = [
    ...(hasImage ? [{ type: 'image', data: imageBase64, mimeType: imageMimeType || 'image/png' }] : []),
    {
      type: 'text',
      text: eyeUser({
        task,
        evidence,
        text: String(text || '').trim() || '（本次核验提交截图，请结合画面判断是否真实完成指定任务）',
      }),
    },
  ]
  const run = (async () => {
    let out = ''
    for await (const chunk of stream({
      provider,
      model,
      system: eyeSystem(),
      messages: [{ role: 'user', content }],
      maxTokens: 1200,
      temperature: 0,
    })) {
      if (!chunk) continue
      if (chunk.type === 'text-delta') out += chunk.text
      if (chunk.type === 'finish' && chunk.reason && chunk.reason.kind === 'error') return null
    }
    return out
  })().catch(() => null)
  let raw = null
  try {
    raw = await Promise.race([run, timeout])
  } finally {
    clearTimeout(timer)
  }
  const parsed = raw ? parseEye(raw, text) : null
  if (!parsed) return { judged: false, pass: true, why: raw === null ? '审读超时或出错' : '审读结果读不懂' }
  return { judged: true, ...parsed }
}
