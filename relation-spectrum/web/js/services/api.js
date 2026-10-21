// Client for the local server's /api endpoints.

import { getSettings } from './settings.js';

function headers(extra = {}) {
  const s = getSettings();
  const h = { 'content-type': 'application/json', ...extra };
  if (s.accessToken) h['x-access-token'] = s.accessToken;
  return h;
}

async function json(res) {
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(data.error || data.message || `请求失败（${res.status}）`);
    err.status = res.status;
    err.data = data;
    throw err;
  }
  return data;
}

export async function health() {
  const res = await fetch('/api/health', { headers: headers() });
  return json(res);
}

/**
 * Run an AI engine.
 * kind: 'decode' | 'game' | 'audit'
 * input: { situation, draft?, entity?, interactions?, analysis? }
 */
export async function analyze(kind, input) {
  const { llm } = getSettings();
  const override = Object.fromEntries(Object.entries(llm).filter(([, v]) => v));
  const res = await fetch('/api/analyze', {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ kind, input, llm: override }),
  });
  return json(res);
}

/** Raw Notion call through the proxy. path like 'databases/xxx/query'. */
export async function notion(method, path, body) {
  const { notion: n } = getSettings();
  const extra = n.token ? { 'x-notion-token': n.token } : {};
  for (let attempt = 0; attempt < 4; attempt++) {
    const res = await fetch(`/api/notion/${path}`, {
      method,
      headers: headers(extra),
      body: body ? JSON.stringify(body) : undefined,
    });
    if (res.status === 429) {
      const wait = Number(res.headers.get('retry-after')) || 2 ** attempt;
      await new Promise((r) => setTimeout(r, wait * 1000));
      continue;
    }
    return json(res);
  }
  throw new Error('Notion 请求太频繁，请稍后再试');
}
