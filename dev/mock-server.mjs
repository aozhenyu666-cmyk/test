// Dev-only mock backend for testing the plugin without a phone:
//   /v1/chat/completions  scripted OpenAI-compatible model that issues tool calls
//   /v1/models            model list
//   /mcp                  stateful Streamable-HTTP MCP server answering over SSE
//   /harness.html         fake SP host page (see harness.html)
// No CORS headers on /v1 on purpose: the browser `fetch` channel must fail there
// and the plugin has to fall back to the "native" (host window) channel, just
// like it does against most LLM APIs on a phone.
import http from 'node:http';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const pluginDir = join(here, '..', 'plugin');
export const requests = [];

function toolCall(name, args) {
  return {
    id: 'call_' + Math.random().toString(36).slice(2, 10),
    type: 'function',
    function: { name, arguments: JSON.stringify(args) },
  };
}

// Deterministic "model": decides the next step from the conversation so far.
function fakeModel(body) {
  const msgs = body.messages;
  const last = msgs[msgs.length - 1];
  const lastUser = [...msgs].reverse().find((m) => m.role === 'user').content;
  const toolNames = (body.tools || []).map((t) => t.function.name);

  if (last.role === 'user') {
    if (lastUser === '只回复 OK') return { content: 'OK' };
    if (lastUser.includes('周报')) {
      return {
        content: null,
        tool_calls: [
          toolCall('create_task', {
            title: '写周报',
            due_day: 'tomorrow',
            due_time: '15:00',
            estimate_min: 30,
            tags: ['工作'],
          }),
        ],
      };
    }
    if (lastUser.includes('完成')) return { content: null, tool_calls: [toolCall('list_tasks', { query: '买牛奶' })] };
    if (lastUser.includes('远程')) {
      const remote = toolNames.find((n) => n.startsWith('mcp_'));
      return remote
        ? { content: null, tool_calls: [toolCall(remote, { text: 'ping' })] }
        : { content: '没有远程工具' };
    }
    if (lastUser.includes('今天')) return { content: null, tool_calls: [toolCall('today_summary', {})] };
    return { content: '你好！' };
  }

  if (last.role === 'tool') {
    const prevAssistant = [...msgs].reverse().find((m) => m.role === 'assistant' && m.tool_calls);
    const called = prevAssistant.tool_calls[0].function.name;
    if (called === 'list_tasks' && lastUser.includes('完成')) {
      const res = JSON.parse(last.content);
      const t = res.tasks && res.tasks[0];
      if (t) return { content: '找到了，正在标记完成。', tool_calls: [toolCall('complete_task', { task_id: t.id })] };
    }
    return { content: '已处理：' + last.content.slice(0, 120) };
  }
  return { content: '?' };
}

function readBody(req) {
  return new Promise((resolve) => {
    let b = '';
    req.on('data', (c) => (b += c));
    req.on('end', () => resolve(b));
  });
}

const sessions = new Set();

async function handleMcp(req, res) {
  const cors = {
    'Access-Control-Allow-Origin': '*',
    // A bare '*' would NOT cover Authorization; list the headers explicitly.
    'Access-Control-Allow-Headers': 'Content-Type, Accept, Authorization, Mcp-Session-Id, MCP-Protocol-Version',
    'Access-Control-Allow-Methods': 'POST, GET, DELETE, OPTIONS',
    'Access-Control-Expose-Headers': 'Mcp-Session-Id',
  };
  if (req.method === 'OPTIONS') return res.writeHead(204, cors).end();
  if (req.headers.authorization !== 'Bearer mcp-secret') return res.writeHead(401, cors).end('{"error":"unauthorized"}');
  const msg = JSON.parse(await readBody(req));
  const sid = req.headers['mcp-session-id'];
  if (msg.method === 'initialize') {
    const newSid = 'sess-' + Math.random().toString(36).slice(2);
    sessions.add(newSid);
    const result = { protocolVersion: '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'mock-mcp', version: '1' } };
    res.writeHead(200, { ...cors, 'Content-Type': 'text/event-stream', 'Mcp-Session-Id': newSid });
    return res.end(`event: message\ndata: ${JSON.stringify({ jsonrpc: '2.0', id: msg.id, result })}\n\n`);
  }
  if (!sid || !sessions.has(sid)) {
    return res.writeHead(400, { ...cors, 'Content-Type': 'application/json' })
      .end(JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: -32000, message: 'Bad Request: No valid session ID provided' } }));
  }
  if (msg.id === undefined) return res.writeHead(202, cors).end();
  let result;
  if (msg.method === 'tools/list') {
    result = { tools: [{ name: 'echo.remote', description: 'Echo text back', inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] } }] };
  } else if (msg.method === 'tools/call') {
    result = { content: [{ type: 'text', text: 'remote says: ' + msg.params.arguments.text }] };
  } else {
    return res.writeHead(200, { ...cors, 'Content-Type': 'application/json' })
      .end(JSON.stringify({ jsonrpc: '2.0', id: msg.id, error: { code: -32601, message: 'no such method' } }));
  }
  res.writeHead(200, { ...cors, 'Content-Type': 'text/event-stream' });
  res.end(`data: ${JSON.stringify({ jsonrpc: '2.0', id: msg.id, result })}\n\n`);
}

export function startServer(port = 0) {
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://x');
    requests.push(`${req.method} ${url.pathname}`);
    if (url.pathname === '/harness.html') {
      return res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }).end(readFileSync(join(here, 'harness.html')));
    }
    if (url.pathname === '/plugin/index.html') {
      return res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' }).end(readFileSync(join(pluginDir, 'index.html')));
    }
    if (url.pathname === '/mcp') return handleMcp(req, res);
    if (url.pathname === '/v1/models') {
      return res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ data: [{ id: 'mock-large' }, { id: 'mock-small' }] }));
    }
    if (url.pathname === '/v1/chat/completions' && req.method === 'POST') {
      if (req.headers.authorization !== 'Bearer sk-test') {
        return res.writeHead(401, { 'Content-Type': 'application/json' }).end(JSON.stringify({ error: { message: 'Invalid API key' } }));
      }
      const body = JSON.parse(await readBody(req));
      const message = { role: 'assistant', ...fakeModel(body) };
      return res.writeHead(200, { 'Content-Type': 'application/json' })
        .end(JSON.stringify({ id: 'x', choices: [{ index: 0, message, finish_reason: message.tool_calls ? 'tool_calls' : 'stop' }] }));
    }
    res.writeHead(404).end('not found');
  });
  return new Promise((resolve) => server.listen(port, '127.0.0.1', () => resolve(server)));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const s = await startServer(Number(process.env.PORT) || 8799);
  console.log('mock server on http://127.0.0.1:' + s.address().port + '/harness.html');
}
