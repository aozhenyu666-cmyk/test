// 假模型：DeepSeek/OpenAI 兼容 /chat/completions（流式）。把每个请求记到 llm.log。
import http from 'node:http'
import { appendFileSync } from 'node:fs'
const LOG = process.argv[2]
const DELAY = Number(process.env.FAKE_DELAY_MS || 300)
function textOf(c) { return typeof c === 'string' ? c : (c || []).map((x) => x.text || (typeof x.content === 'string' ? x.content : '')).join('') }
function lastUser(msgs) {
  for (let i = msgs.length - 1; i >= 0; i--) if (msgs[i].role === 'user') { const t = textOf(msgs[i].content); if (t.trim()) return t }
  return ''
}
function answer(body) {
  const msgs = body.messages || []
  const sys = textOf(body.system) + msgs.filter((m) => m.role === 'system').map((m) => textOf(m.content)).join('\n')
  const u = lastUser(msgs)
  if (/你在判断对方刚打回来的一球/.test(sys + u)) {
    const reply = (u.match(/对方这一球打回来的是：([\s\S]*?)\n判这一球/) || [])[1] || u
    if (/作业|写完|因为/.test(reply)) return { kind: 'judge', text: '{"verdict":"pushback","why":"给了具体理由","next":"deeper"}' }
    if (/\d/.test(reply)) return { kind: 'judge', text: '{"verdict":"engaged","why":"给了数字","next":"deeper"}' }
    return { kind: 'judge', text: '{"verdict":"brief","why":"太短、凭感觉","next":"angle"}' }
  }
  if (/这不是.*说的话，是 DSH Go 手机端发起的一次主动交流|这不是.*说的话，是手机端让你主动去找他的一次机会/.test(u)) return { kind: 'serve', text: '你在王者里泡了四十分钟了。我猜你在躲那份作业——说说看我猜错没有？' }
  if (/心情|mood|情绪/.test(sys) && /JSON/.test(sys)) return { kind: 'mood', text: '{"delta":0}' }
  return { kind: 'chat', text: '我听懂了：你是说「' + u.slice(0, 30) + '」。这个理由站得住，这局打完就收。' }
}
http.createServer((req, res) => {
  let raw = ''
  req.on('data', (c) => (raw += c))
  req.on('end', () => {
    let body = {}
    try { body = JSON.parse(raw || '{}') } catch {}
    const a = req.url.includes('chat/completions') || req.url.includes('/messages') ? answer(body) : null
    // 主动发球和判球的完整输入另记一份，验收时核对"这一球到底带了什么"。
    if (a && (a.kind === 'serve' || a.kind === 'judge' || a.kind === 'chat')) {
      appendFileSync(LOG.replace(/llm\.log$/, 'prompts.jsonl'), JSON.stringify({ at: Date.now(), kind: a.kind, text: lastUser(body.messages || []), system: textOf(body.system).slice(0, 4000),
        all: (textOf(body.system) + '\n' + (body.messages || []).map((m) => textOf(m.content)).join('\n')).slice(-8000) }) + '\n')
    }
    if (a && req.url.includes('/messages')) {
      appendFileSync(LOG, JSON.stringify({ at: new Date().toISOString(), url: req.url, kind: a.kind, lastUser: lastUser(body.messages || []).slice(0, 120), tools: (body.tools || []).length }) + '\n')
      const send = (ev, data) => res.write('event: ' + ev + '\ndata: ' + JSON.stringify({ type: ev, ...data }) + '\n\n')
      const mid = 'msg_' + Date.now()
      if (!body.stream) {
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ id: mid, type: 'message', role: 'assistant', model: body.model, content: [{ type: 'text', text: a.text }], stop_reason: 'end_turn', usage: { input_tokens: 10, output_tokens: 10 } }))
        return
      }
      res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' })
      setTimeout(() => {
        send('message_start', { message: { id: mid, type: 'message', role: 'assistant', model: body.model, content: [], stop_reason: null, usage: { input_tokens: 10, output_tokens: 1 } } })
        send('content_block_start', { index: 0, content_block: { type: 'text', text: '' } })
        send('content_block_delta', { index: 0, delta: { type: 'text_delta', text: a.text } })
        send('content_block_stop', { index: 0 })
        send('message_delta', { delta: { stop_reason: 'end_turn', stop_sequence: null }, usage: { output_tokens: 10 } })
        send('message_stop', {})
        res.end()
      }, DELAY)
      return
    }
    appendFileSync(LOG, JSON.stringify({ at: new Date().toISOString(), url: req.url, model: body.model, stream: body.stream, kind: a?.kind, lastUser: a ? lastUser(body.messages || []).slice(0, 160) : undefined, tools: (body.tools || []).length }) + '\n')
    if (!a) { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify({ object: 'list', data: [{ id: 'deepseek-chat', object: 'model' }, { id: 'deepseek-reasoner', object: 'model' }] })); return }
    const id = 'cmpl-' + Date.now()
    if (!body.stream) {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ id, object: 'chat.completion', model: body.model, choices: [{ index: 0, message: { role: 'assistant', content: a.text }, finish_reason: 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 } }))
      return
    }
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' })
    const chunk = (delta, finish) => res.write('data: ' + JSON.stringify({ id, object: 'chat.completion.chunk', model: body.model, choices: [{ index: 0, delta, finish_reason: finish }] }) + '\n\n')
    setTimeout(() => {
      chunk({ role: 'assistant', content: a.text }, null)
      res.write('data: ' + JSON.stringify({ id, object: 'chat.completion.chunk', model: body.model, choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 } }) + '\n\n')
      res.end('data: [DONE]\n\n')
    }, DELAY)
  })
}).listen(Number(process.env.PORT || 18900), process.env.HOST || '127.0.0.1')
