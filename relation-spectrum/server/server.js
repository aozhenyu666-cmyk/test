// Local server: serves the web app, runs the AI engines, and proxies Notion
// (Notion's API does not allow direct browser calls).
//
//   npm start            → http://127.0.0.1:8787
//   HOST=0.0.0.0 ACCESS_TOKEN=xxx npm start   → reachable from your phone on the same Wi-Fi

import http from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { runEngine, resolveConfig, LlmError } from './llm.js';
import { NOTION_VERSION } from '../web/js/core/notion-schema.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const webRoot = path.join(root, 'web');

loadDotEnv(path.join(root, '.env'));

const PORT = Number(process.env.PORT) || 8787;
const HOST = process.env.HOST || '127.0.0.1';
const ACCESS_TOKEN = process.env.ACCESS_TOKEN || '';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.webmanifest': 'application/manifest+json',
};

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    if (url.pathname.startsWith('/api/')) {
      if (ACCESS_TOKEN && req.headers['x-access-token'] !== ACCESS_TOKEN) {
        return sendJson(res, 401, { error: '访问口令不正确（在设置页填写 ACCESS_TOKEN）' });
      }
      return await handleApi(req, res, url);
    }
    return await serveStatic(res, url.pathname);
  } catch (err) {
    console.error(err);
    sendJson(res, 500, { error: err.message || '服务器错误' });
  }
});

async function handleApi(req, res, url) {
  if (url.pathname === '/api/health' && req.method === 'GET') {
    const llm = resolveConfig();
    return sendJson(res, 200, {
      ok: true,
      llm: { provider: llm.provider, model: llm.model, hasKey: Boolean(llm.apiKey) },
      notion: {
        hasToken: Boolean(process.env.NOTION_TOKEN),
        dbs: {
          entities: process.env.NOTION_ENTITIES_DB || '',
          interactions: process.env.NOTION_INTERACTIONS_DB || '',
          cases: process.env.NOTION_CASES_DB || '',
        },
      },
      accessTokenRequired: Boolean(ACCESS_TOKEN),
    });
  }

  if (url.pathname === '/api/analyze' && req.method === 'POST') {
    const body = await readJson(req);
    try {
      const out = await runEngine(body.kind, body.input || {}, body.llm || {});
      return sendJson(res, 200, out);
    } catch (err) {
      const status = err instanceof LlmError ? err.status : 500;
      return sendJson(res, status, { error: err.message });
    }
  }

  if (url.pathname.startsWith('/api/notion/')) {
    const token = req.headers['x-notion-token'] || process.env.NOTION_TOKEN;
    if (!token) return sendJson(res, 400, { error: '没有配置 Notion Integration Token' });
    const target = 'https://api.notion.com/v1/' + url.pathname.slice('/api/notion/'.length) + url.search;
    const body = ['GET', 'HEAD'].includes(req.method) ? undefined : await readRaw(req);
    const upstream = await fetch(target, {
      method: req.method,
      headers: {
        authorization: `Bearer ${token}`,
        'notion-version': NOTION_VERSION,
        'content-type': 'application/json',
      },
      body,
    });
    const text = await upstream.text();
    const headers = { 'content-type': 'application/json; charset=utf-8' };
    const retryAfter = upstream.headers.get('retry-after');
    if (retryAfter) headers['retry-after'] = retryAfter;
    res.writeHead(upstream.status, headers);
    return res.end(text);
  }

  sendJson(res, 404, { error: 'Not found' });
}

async function serveStatic(res, pathname) {
  const rel = decodeURIComponent(pathname === '/' ? '/index.html' : pathname);
  const file = path.normalize(path.join(webRoot, rel));
  if (!file.startsWith(webRoot + path.sep)) return sendJson(res, 403, { error: 'Forbidden' });
  let target = file;
  try {
    if (!(await stat(target)).isFile()) throw new Error('not a file');
  } catch {
    target = path.join(webRoot, 'index.html'); // SPA fallback
  }
  const data = await readFile(target);
  res.writeHead(200, {
    'content-type': MIME[path.extname(target)] || 'application/octet-stream',
    'cache-control': 'no-cache',
  });
  res.end(data);
}

function sendJson(res, status, obj) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(obj));
}

async function readRaw(req) {
  const chunks = [];
  let size = 0;
  for await (const c of req) {
    size += c.length;
    if (size > 2_000_000) throw new Error('请求体太大');
    chunks.push(c);
  }
  return Buffer.concat(chunks).toString('utf8');
}

async function readJson(req) {
  const raw = await readRaw(req);
  try {
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

/** Minimal .env loader so the app needs no extra dependency. */
function loadDotEnv(file) {
  if (!existsSync(file)) return;
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
    if (m && process.env[m[1]] === undefined) process.env[m[1]] = m[2].replace(/^['"]|['"]$/g, '');
  }
}

server.listen(PORT, HOST, () => {
  const llm = resolveConfig();
  console.log(`关系光谱已启动：http://${HOST === '0.0.0.0' ? 'localhost' : HOST}:${PORT}`);
  console.log(`  大模型：${llm.provider} / ${llm.model} ${llm.apiKey ? '' : '（未在环境变量配置 Key，可在设置页填写）'}`);
  console.log(`  Notion：${process.env.NOTION_TOKEN ? '已配置' : '未配置（可选）'}`);
  if (HOST === '0.0.0.0' && !ACCESS_TOKEN) {
    console.warn('  警告：服务对局域网开放但没有设置 ACCESS_TOKEN，任何人都能用你的 API Key。');
  }
});
