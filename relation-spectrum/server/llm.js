// GPT router: forwards engine requests to OpenAI or any OpenAI-compatible service.
// Request shapes come from web/js/core/llm-request.js, so direct mode sends the same thing.

import {
  DEFAULT_BASE_URL, DEFAULT_MODEL, LlmError, buildRequest, readChatResponse, httpErrorMessage,
} from '../web/js/core/llm-request.js';

export { LlmError };
export { parseJson } from '../web/js/core/json.js';

/** Resolve settings: per-request overrides from the app win over env vars. */
export function resolveConfig(override = {}) {
  return {
    provider: 'openai',
    apiKey: override.apiKey || process.env.OPENAI_API_KEY || '',
    baseUrl: override.baseUrl || process.env.OPENAI_BASE_URL || DEFAULT_BASE_URL,
    model: override.model || process.env.OPENAI_MODEL || DEFAULT_MODEL,
    jsonMode: override.jsonMode || process.env.OPENAI_JSON_MODE || 'auto',
  };
}

/** Run an engine and return { result, model, provider }. */
export async function runEngine(kind, input, override) {
  const cfg = resolveConfig(override);
  if (!cfg.apiKey) throw new LlmError('没有配置 GPT 的 API Key：在设置页填写，或写进 .env 的 OPENAI_API_KEY', 401);
  const req = buildRequest(kind, input, cfg);
  let res;
  try {
    res = await fetch(req.url, { method: 'POST', headers: req.headers, body: JSON.stringify(req.body) });
  } catch (err) {
    throw new LlmError(`无法连接 ${req.url}：${err.message}`, 502);
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new LlmError(httpErrorMessage(res.status, data), res.status === 401 ? 401 : 502);
  return { ...readChatResponse(data, cfg.model), provider: 'openai' };
}
