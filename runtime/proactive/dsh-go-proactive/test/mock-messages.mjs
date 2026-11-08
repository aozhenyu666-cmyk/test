// Minimal Anthropic-Messages-compatible mock for driving a real DSH engine offline.
// Every request is appended to requests.jsonl; replies are canned text, streamed as SSE.
import { createServer } from 'node:http'
import { appendFileSync } from 'node:fs'
const port = Number(process.env.PORT || 18900)
const log = process.env.LOG || 'requests.jsonl'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
const delayFile = join(dirname(log), 'delay')
const delayMs = () => { try { return Number(readFileSync(delayFile, 'utf8')) || 0 } catch { return 0 } }
createServer(async (req, res) => {
  let body = ''
  for await (const c of req) body += c
  appendFileSync(log, JSON.stringify({ at: Date.now(), method: req.method, url: req.url, body: body.slice(0, 2000000) }) + '\n')
  if (req.method === 'GET' && req.url.includes('/models')) {
    res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify({ data: [{ id: 'deepseek-chat', type: 'model' }] })); return
  }
  if (!req.url.includes('/messages')) { res.writeHead(404); res.end(); return }
  let j = {}; try { j = JSON.parse(body) } catch {}
  const all = JSON.stringify(j.messages ?? [])
  let text = all.includes('kind=app_enter') ? 'MOCK_REPLY: 你好，我注意到你刚打开了测试应用。' : 'MOCK_REPLY: ok'
  // TOOL_PROBE: first answer with a tool call (to a tool that takes no required arguments), then,
  // once the tool result comes back, report what it was. Lets the e2e check the tool-layer guard.
  let toolUse
  const last = j.messages?.at(-1)
  const lastHasResult = JSON.stringify(last ?? '').includes('"tool_result"')
  if (all.includes('TOOL_PROBE')) {
    if (!lastHasResult) {
      const tool = (j.tools ?? []).find((t) => !(t.input_schema?.required?.length)) ?? (j.tools ?? [])[0]
      if (tool) toolUse = { id: `toolu_mock_${Date.now()}`, name: tool.name }
    } else {
      const block = (last.content ?? []).find((b) => b.type === 'tool_result') ?? {}
      const flat = JSON.stringify(block.content ?? '')
      text = `MOCK_REPLY: tool error=${block.is_error === true} result=${flat.slice(0, 160)}`
    }
  }
  const wait = delayMs(); if (wait) await new Promise(r => setTimeout(r, wait))
  const ev = (type, data) => res.write(`event: ${type}\ndata: ${JSON.stringify({ type, ...data })}\n\n`)
  if (toolUse && j.stream) {
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' })
    ev('message_start', { message: { id: 'msg_mock', type: 'message', role: 'assistant', model: j.model || 'deepseek-chat', content: [], stop_reason: null, usage: { input_tokens: 10, output_tokens: 0 } } })
    ev('content_block_start', { index: 0, content_block: { type: 'tool_use', id: toolUse.id, name: toolUse.name, input: {} } })
    ev('content_block_delta', { index: 0, delta: { type: 'input_json_delta', partial_json: '{}' } })
    ev('content_block_stop', { index: 0 })
    ev('message_delta', { delta: { stop_reason: 'tool_use', stop_sequence: null }, usage: { output_tokens: 5 } })
    ev('message_stop', {})
    res.end()
    return
  }
  if (!j.stream) {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ id: 'msg_mock', type: 'message', role: 'assistant', model: j.model, content: [{ type: 'text', text }], stop_reason: 'end_turn', usage: { input_tokens: 10, output_tokens: 5 } })); return
  }
  res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' })
  ev('message_start', { message: { id: 'msg_mock', type: 'message', role: 'assistant', model: j.model || 'deepseek-chat', content: [], stop_reason: null, usage: { input_tokens: 10, output_tokens: 0 } } })
  ev('content_block_start', { index: 0, content_block: { type: 'text', text: '' } })
  ev('content_block_delta', { index: 0, delta: { type: 'text_delta', text } })
  ev('content_block_stop', { index: 0 })
  ev('message_delta', { delta: { stop_reason: 'end_turn', stop_sequence: null }, usage: { output_tokens: 5 } })
  ev('message_stop', {})
  res.end()
}).listen(port, '127.0.0.1', () => console.log('mock on', port))
