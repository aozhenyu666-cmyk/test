// Dev-only mock backend for testing the plugin without a phone:
//   /v1/chat/completions       scripted OpenAI-compatible model, NO CORS (like most LLM APIs from a WebView)
//   /cors/v1/chat/completions  same model with CORS + SSE streaming (exercises the streaming path)
//   /v1/models                 model list
//   /mcp                       stateful Streamable-HTTP MCP server answering over SSE
//   /harness.html              fake SP host page (see harness.html)
import http from 'node:http';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
// PLUGIN_DIR=dist/build tests the minified package that actually ships.
const pluginDir = () => process.env.PLUGIN_DIR || join(here, '..', 'plugin');
export const requests = [];

function toolCall(name, args) {
  return { id: 'call_' + Math.random().toString(36).slice(2, 10), type: 'function', function: { name, arguments: JSON.stringify(args) } };
}

// Deterministic "model": decides the next step from the conversation so far.
function fakeModel(body) {
  const msgs = body.messages;
  const last = msgs[msgs.length - 1];
  const lastUser = [...msgs].reverse().find((m) => m.role === 'user').content;
  const toolNames = (body.tools || []).map((t) => t.function.name);
  const call = (name, args, content = null) => ({ content, tool_calls: [toolCall(name, args)] });
  const system = msgs[0].role === 'system' ? msgs[0].content : '';

  // diagnosis pass: no tools, answers with JSON built only from the facts it was given
  if (system.startsWith('你是参谋的「诊断」环节')) {
    const facts = /【事实：最近 7 天/.test(system) && /期限：.*国考/.test(system);
    const commit = (system.match(/\[承诺 [^\]]*\] ([^\n]+)/) || [])[1];
    return { content: '好的，诊断如下：\n' + JSON.stringify({
      complaint: '觉得方法不对，想换刷题软件或再做一套计划系统',
      real: facts ? '离国考笔试不到两个月，问题是每天有没有固定的刷题时间，不是工具' : '不清楚',
      conflicts: commit ? ['说“什么都没干成” ↔ 案卷里承诺过：' + commit + '，记录里看不到兑现'] : [],
      assumptions: ['换工具能解决动力问题'],
      gap: '每天实际能坐下来学习的时段',
      move: 'ask',
      question: '昨天你在什么时候、因为什么没按计划开始？',
      opening: '先别换工具。',
    }) };
  }

  if (last.role === 'user') {
    if (system.includes('这一轮的动作：ask')) {
      const q = (system.match(/要问：([^）]+)）/) || [])[1];
      return call('ask_user', { question: q, options: ['睡过头了', '刷手机', '不知道从哪开始'] }, (system.match(/建议第一句：(.+)/) || [])[1] || null);
    }
    if (lastUser === '只回复 OK') return { content: 'OK' };
    if (lastUser.includes('定今天的计划')) return call('get_today', {});
    if (lastUser === '就按这个来') {
      return call('save_daily_plan', {
        theme: '备考为主，顺手推进求职',
        items: [
          { title: '行测刷题 40 道', goal: '国考', time: '09:00', minutes: 60 },
          { title: '投两份简历', goal: '找工作', minutes: 30 },
        ],
        note: '下午精力差，难的放上午',
      });
    }
    if (lastUser.includes('复盘')) {
      return call('save_daily_review', { wins: '刷完了 40 道题', problems: '下午刷手机太久', lessons: '手机放远一点', tomorrow: '9 点开始申论', mood: 4, energy: 3 });
    }
    if (lastUser.includes('周报')) {
      return call('create_task', { title: '写周报', due_day: 'tomorrow', due_time: '15:00', estimate_min: 30, goal: '找工作', remind_at: 'tomorrow 14:50' });
    }
    if (lastUser.includes('删除')) return call('list_tasks', { query: '买牛奶' });
    if (lastUser.includes('远程')) {
      const remote = toolNames.find((n) => n.startsWith('mcp_'));
      return remote ? call(remote, { text: 'ping' }) : { content: '没有远程工具' };
    }
    if (lastUser.includes('记住')) return call('remember', { fact: '晚上 10 点后效率低' });
    if (lastUser.includes('从明天起')) return call('remember', { fact: '每天 9 点前开始刷行测', kind: 'commit' });
    return { content: '你好！我是你的**参谋**。\n\n- 可以帮你定计划\n- 也可以复盘\n\n| 目标 | 剩余 |\n|---|---|\n| 国考 | 58 天 |' };
  }

  if (last.role === 'tool') {
    const prevAssistant = [...msgs].reverse().find((m) => m.role === 'assistant' && m.tool_calls);
    const called = prevAssistant.tool_calls[0].function.name;
    if (called === 'get_today' && lastUser.includes('计划')) {
      return call('ask_user', { question: '今天的主线放在哪？', options: ['就按这个来', '先学习', '先求职'] }, '看了下今天的情况：国考还有不到 60 天，建议上午刷行测。');
    }
    if (called === 'list_tasks' && lastUser.includes('删除')) {
      const t = JSON.parse(last.content).tasks[0];
      return call('delete_task', { task_id: t.id });
    }
    return { content: '已处理：' + last.content.slice(0, 160) };
  }
  return { content: '?' };
}

function readBody(req) {
  return new Promise((resolve) => { let b = ''; req.on('data', (c) => (b += c)); req.on('end', () => resolve(b)); });
}

const sessions = new Set();
const CORS = {
  'Access-Control-Allow-Origin': '*',
  // A bare '*' would NOT cover Authorization; list the headers explicitly.
  'Access-Control-Allow-Headers': 'Content-Type, Accept, Authorization, Mcp-Session-Id, MCP-Protocol-Version',
  'Access-Control-Allow-Methods': 'POST, GET, DELETE, OPTIONS',
  'Access-Control-Expose-Headers': 'Mcp-Session-Id',
};

async function handleMcp(req, res) {
  if (req.method === 'OPTIONS') return res.writeHead(204, CORS).end();
  if (req.headers.authorization !== 'Bearer mcp-secret') return res.writeHead(401, CORS).end('{"error":"unauthorized"}');
  const msg = JSON.parse(await readBody(req));
  const sid = req.headers['mcp-session-id'];
  if (msg.method === 'initialize') {
    const newSid = 'sess-' + Math.random().toString(36).slice(2);
    sessions.add(newSid);
    const result = { protocolVersion: '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'mock-mcp', version: '1' } };
    res.writeHead(200, { ...CORS, 'Content-Type': 'text/event-stream', 'Mcp-Session-Id': newSid });
    return res.end(`event: message\ndata: ${JSON.stringify({ jsonrpc: '2.0', id: msg.id, result })}\n\n`);
  }
  if (!sid || !sessions.has(sid)) {
    return res.writeHead(400, { ...CORS, 'Content-Type': 'application/json' })
      .end(JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: -32000, message: 'Bad Request: No valid session ID provided' } }));
  }
  if (msg.id === undefined) return res.writeHead(202, CORS).end();
  let result;
  if (msg.method === 'tools/list') {
    result = { tools: [{ name: 'echo.remote', description: 'Echo text back', inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] } }] };
  } else if (msg.method === 'tools/call') {
    result = { content: [{ type: 'text', text: 'remote says: ' + msg.params.arguments.text }] };
  } else {
    return res.writeHead(200, { ...CORS, 'Content-Type': 'application/json' })
      .end(JSON.stringify({ jsonrpc: '2.0', id: msg.id, error: { code: -32601, message: 'no such method' } }));
  }
  res.writeHead(200, { ...CORS, 'Content-Type': 'text/event-stream' });
  res.end(`data: ${JSON.stringify({ jsonrpc: '2.0', id: msg.id, result })}\n\n`);
}

async function handleChat(req, res, withCors) {
  const extra = withCors ? CORS : {};
  if (req.method === 'OPTIONS') return res.writeHead(withCors ? 204 : 404, extra).end();
  if (req.headers.authorization !== 'Bearer sk-test') {
    return res.writeHead(401, { ...extra, 'Content-Type': 'application/json' }).end(JSON.stringify({ error: { message: 'Invalid API key' } }));
  }
  const body = JSON.parse(await readBody(req));
  const message = { role: 'assistant', ...fakeModel(body) };
  if (!body.stream) {
    return res.writeHead(200, { ...extra, 'Content-Type': 'application/json' })
      .end(JSON.stringify({ id: 'x', choices: [{ index: 0, message, finish_reason: message.tool_calls ? 'tool_calls' : 'stop' }] }));
  }
  res.writeHead(200, { ...extra, 'Content-Type': 'text/event-stream' });
  const chunk = (delta) => res.write('data: ' + JSON.stringify({ choices: [{ index: 0, delta }] }) + '\n\n');
  const text = message.content || '';
  for (let i = 0; i < text.length; i += 4) { chunk({ content: text.slice(i, i + 4) }); await new Promise((r) => setTimeout(r, 5)); }
  (message.tool_calls || []).forEach((c, index) => {
    chunk({ tool_calls: [{ index, id: c.id, type: 'function', function: { name: c.function.name, arguments: '' } }] });
    const a = c.function.arguments;
    for (let i = 0; i < a.length; i += 10) chunk({ tool_calls: [{ index, function: { arguments: a.slice(i, i + 10) } }] });
  });
  res.end('data: [DONE]\n\n');
}

export function startServer(port = 0) {
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://x');
    requests.push(`${req.method} ${url.pathname}${req.method === 'POST' ? '' : ''}`);
    if (url.pathname === '/harness.html') return res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }).end(readFileSync(join(here, 'harness.html')));
    if (url.pathname.startsWith('/plugin/')) {
      const name = url.pathname.slice('/plugin/'.length);
      if (!/^[\w.-]+$/.test(name)) return res.writeHead(400).end();
      return res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' }).end(readFileSync(join(pluginDir(), name)));
    }
    if (url.pathname === '/mcp') return handleMcp(req, res);
    if (url.pathname === '/v1/models') return res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ data: [{ id: 'mock-large' }, { id: 'mock-small' }] }));
    if (url.pathname === '/v1/chat/completions') return handleChat(req, res, false);
    if (url.pathname === '/cors/v1/chat/completions') return handleChat(req, res, true);
    res.writeHead(404).end('not found');
  });
  return new Promise((resolve) => server.listen(port, '127.0.0.1', () => resolve(server)));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const s = await startServer(Number(process.env.PORT) || 8799);
  console.log('mock server on http://127.0.0.1:' + s.address().port + '/harness.html?native=1');
}
