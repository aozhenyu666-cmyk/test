// Builds GPT requests for every engine (OpenAI chat/completions format).
// Pure module shared by:
//   - server/llm.js (the local server forwards the request, keeping the key off the phone)
//   - the browser's direct mode, used when the local server is not running
//
// Works with OpenAI and any OpenAI-compatible service (DeepSeek, OpenRouter, Kimi, Qwen…).

import { SYSTEM_PROMPTS, SCHEMAS, buildUserMessage } from './prompts.js';
import { parseJson } from './json.js';

export const DEFAULT_BASE_URL = 'https://api.openai.com/v1';
export const DEFAULT_MODEL = 'gpt-4o-mini';

export class LlmError extends Error {
  constructor(message, status = 502) {
    super(message);
    this.status = status;
  }
}

function assertKind(kind) {
  if (!SYSTEM_PROMPTS[kind]) throw new LlmError(`未知的分析类型：${kind}`, 400);
}

export function normalizeBaseUrl(url) {
  return (url || DEFAULT_BASE_URL).replace(/\/+$/, '');
}

/**
 * How to ask for JSON.
 *   schema  response_format json_schema (strict): the reply always matches the schema.
 *           Supported by OpenAI's own API.
 *   object  response_format json_object, schema described in the prompt. Works on most
 *           OpenAI-compatible services (DeepSeek, Kimi, Qwen…).
 *   auto    schema for api.openai.com, object elsewhere.
 */
export function resolveJsonMode(cfg = {}) {
  const mode = cfg.jsonMode || 'auto';
  if (mode === 'schema' || mode === 'object') return mode;
  return normalizeBaseUrl(cfg.baseUrl).startsWith('https://api.openai.com') ? 'schema' : 'object';
}

/** chat/completions body for one engine. */
export function chatBody(kind, input, cfg = {}) {
  assertKind(kind);
  const jsonMode = resolveJsonMode(cfg);
  const system = jsonMode === 'schema'
    ? `${SYSTEM_PROMPTS[kind]}\n\n## 输出格式\n只输出一个 JSON 对象。`
    : `${SYSTEM_PROMPTS[kind]}\n\n## 输出格式\n只输出一个 JSON 对象，不要输出其他任何文字。JSON 必须符合下面的 JSON Schema（所有字段都必须出现）：\n${JSON.stringify(SCHEMAS[kind])}`;
  return {
    model: cfg.model || DEFAULT_MODEL,
    messages: [
      { role: 'system', content: system },
      { role: 'user', content: buildUserMessage(kind, input) },
    ],
    response_format: jsonMode === 'schema'
      ? { type: 'json_schema', json_schema: { name: `relation_spectrum_${kind}`, strict: true, schema: SCHEMAS[kind] } }
      : { type: 'json_object' },
  };
}

/** Full HTTP request, used by the server and by direct mode. */
export function buildRequest(kind, input, cfg = {}) {
  if (!cfg.apiKey) throw new LlmError('需要在设置页填写 GPT 的 API Key', 401);
  return {
    url: `${normalizeBaseUrl(cfg.baseUrl)}/chat/completions`,
    headers: { 'content-type': 'application/json', authorization: `Bearer ${cfg.apiKey}` },
    body: chatBody(kind, input, cfg),
  };
}

/** Turn a chat/completions response body into { result, model }. */
export function readChatResponse(data, fallbackModel) {
  const choice = data?.choices?.[0];
  if (choice?.message?.refusal) throw new LlmError(`模型拒绝回答：${choice.message.refusal}`, 422);
  if (choice?.finish_reason === 'length') throw new LlmError('回答太长被截断了，请把情况描述得更简洁一些再试。', 502);
  try {
    return { result: parseJson(choice?.message?.content || ''), model: data.model || fallbackModel };
  } catch (err) {
    throw new LlmError(err.message, 502);
  }
}

/** Map an HTTP error body to a readable message. */
export function httpErrorMessage(status, data) {
  const msg = data?.error?.message || data?.message || '未知错误';
  if (status === 401 || status === 403) return `API Key 无效（${status}）`;
  if (status === 429) return '请求太频繁或额度不足，请稍后再试';
  if (status === 404) return `找不到这个模型或接口地址（${msg}）`;
  return `接口返回 ${status}：${msg}`;
}
