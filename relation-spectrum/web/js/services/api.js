// Client for AI and Notion calls.
//
// AI calls have three modes (settings → AI 模型 → 连接方式):
//   auto    use the local server when it answers, otherwise call the provider directly
//   server  always go through the local server (keys can stay in .env)
//   direct  always call the provider from the browser (works on a phone with no computer on)
// Notion always needs the server: Notion's API does not allow browser calls.
//
// Paths are relative ('api/...') so the app also works from a sub-path such as GitHub Pages.

import { getSettings } from './settings.js';
import { buildDirectRequest, readAnthropicMessage, readOpenAIMessage, httpErrorMessage } from '../core/llm-request.js';

function headers(extra = {}) {
  const s = getSettings();
  const h = { 'content-type': 'application/json', ...extra };
  if (s.accessToken) h['x-access-token'] = s.accessToken;
  return h;
}

class ApiError extends Error {
  constructor(message, status, { unreachable = false } = {}) {
    super(message);
    this.status = status;
    this.unreachable = unreachable;
  }
}

async function json(res) {
  const type = res.headers.get('content-type') || '';
  // A static host (GitHub Pages, a file server) answers /api/* with an HTML 404: no server here.
  if (!type.includes('application/json')) {
    throw new ApiError('本地服务器不可用', res.status, { unreachable: true });
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(data.error || data.message || `请求失败（${res.status}）`, res.status);
  return data;
}

async function serverFetch(path, init) {
  let res;
  try {
    res = await fetch(`api/${path}`, init);
  } catch {
    throw new ApiError('连不上本地服务器', 0, { unreachable: true });
  }
  return json(res);
}

// ---- server availability (cached for 60 s)

let serverState = { ok: null, at: 0, info: null };

export async function health({ force = false } = {}) {
  if (!force && serverState.ok !== null && Date.now() - serverState.at < 60000) {
    if (serverState.ok) return serverState.info;
    throw new ApiError('本地服务器不可用', 0, { unreachable: true });
  }
  try {
    const info = await serverFetch('health', { headers: headers() });
    serverState = { ok: true, at: Date.now(), info };
    return info;
  } catch (err) {
    // Cache only "no server". A server that answered with an error is checked again next time.
    serverState = err.unreachable ? { ok: false, at: Date.now(), info: null } : { ok: null, at: 0, info: null };
    throw err;
  }
}

/** True when a server answered, even with an error such as a wrong access passcode. */
export async function serverAvailable() {
  try {
    await health();
    return true;
  } catch (err) {
    return !err.unreachable;
  }
}

// ---- AI

/**
 * Run an AI engine.
 * kind: 'decode' | 'game' | 'audit' | 'parse' | 'roleplay'
 * Returns { result, model, provider, via: 'server' | 'direct' }.
 */
export async function analyze(kind, input) {
  const { llm } = getSettings();
  const mode = llm.mode || 'auto';
  if (mode === 'direct') return direct(kind, input);
  if (mode === 'auto' && !(await serverAvailable())) return direct(kind, input);
  try {
    return await viaServer(kind, input);
  } catch (err) {
    if (mode === 'auto' && err.unreachable) {
      serverState = { ok: false, at: Date.now(), info: null };
      return direct(kind, input);
    }
    throw err;
  }
}

async function viaServer(kind, input) {
  const { llm } = getSettings();
  const override = Object.fromEntries(
    Object.entries(llm).filter(([k, v]) => v && ['provider', 'model', 'apiKey', 'baseUrl', 'effort'].includes(k)),
  );
  const out = await serverFetch('analyze', {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ kind, input, llm: override }),
  });
  return { ...out, via: 'server' };
}

async function direct(kind, input) {
  const { llm } = getSettings();
  if (!llm.apiKey) {
    throw new ApiError('本地服务器没开。要在手机上单独使用，请在设置页把连接方式改为“自动”或“浏览器直连”，并填写 API Key。', 401);
  }
  const cfg = { provider: llm.provider || 'anthropic', apiKey: llm.apiKey, model: llm.model, baseUrl: llm.baseUrl, effort: llm.effort };
  const req = buildDirectRequest(kind, input, cfg);
  let res;
  try {
    res = await fetch(req.url, { method: 'POST', headers: req.headers, body: JSON.stringify(req.body) });
  } catch {
    throw new ApiError(
      req.provider === 'openai'
        ? `无法直连 ${req.url}。这个服务商可能不允许浏览器直接调用，换一个支持跨域的（如 OpenRouter、DeepSeek）或打开本地服务器。`
        : '无法连接 Claude，请检查网络。',
      0,
    );
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(httpErrorMessage(req.provider, res.status, data), res.status);
  const out = req.provider === 'openai' ? readOpenAIMessage(data, cfg.model) : readAnthropicMessage(data);
  return { ...out, provider: req.provider, via: 'direct' };
}

// ---- Notion (server only)

/** Raw Notion call through the proxy. path like 'databases/xxx/query'. */
export async function notion(method, path, body) {
  const { notion: n } = getSettings();
  const extra = n.token ? { 'x-notion-token': n.token } : {};
  for (let attempt = 0; attempt < 4; attempt++) {
    let res;
    try {
      res = await fetch(`api/notion/${path}`, { method, headers: headers(extra), body: body ? JSON.stringify(body) : undefined });
    } catch {
      throw new ApiError('Notion 同步需要连上本地服务器（npm start）。数据已安全保存在本机，回家后再同步。', 0, { unreachable: true });
    }
    if (res.status === 429) {
      const wait = Number(res.headers.get('retry-after')) || 2 ** attempt;
      await new Promise((r) => setTimeout(r, wait * 1000));
      continue;
    }
    try {
      return await json(res);
    } catch (err) {
      if (err.unreachable) throw new ApiError('Notion 同步需要连上本地服务器（npm start）。数据已安全保存在本机，回家后再同步。', 0, { unreachable: true });
      throw err;
    }
  }
  throw new ApiError('Notion 请求太频繁，请稍后再试', 429);
}
