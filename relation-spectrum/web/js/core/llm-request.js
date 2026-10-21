// Builds LLM requests for every engine. Pure module shared by:
//   - server/llm.js (Claude via the official SDK, OpenAI-compatible via fetch)
//   - the browser's direct mode, used when the local server is not running. A browser
//     page without a build step cannot load the SDK, so direct mode sends the same
//     request over raw HTTP.

import { SYSTEM_PROMPTS, SCHEMAS, LIGHT_KINDS, buildUserMessage } from './prompts.js';
import { parseJson } from './json.js';

export const DEFAULT_MODELS = { anthropic: 'claude-opus-5-5', openai: 'gpt-4o-mini' };

// Models that accept server-side refusal fallbacks (`fallbacks: "default"`).
export const FALLBACK_MODELS = new Set(['claude-opus-5-5', 'claude-opus-5', 'claude-sonnet-5-5', 'claude-fable-5-1']);
export const FALLBACK_BETA = 'server-side-fallback-2026-07-01';

export class LlmError extends Error {
  constructor(message, status = 502) {
    super(message);
    this.status = status;
  }
}

function assertKind(kind) {
  if (!SYSTEM_PROMPTS[kind]) throw new LlmError(`未知的分析类型：${kind}`, 400);
}

/** Messages API params for Claude. Returns { params, betas }. */
export function anthropicParams(kind, input, cfg = {}) {
  assertKind(kind);
  const model = cfg.model || DEFAULT_MODELS.anthropic;
  const effort = LIGHT_KINDS.includes(kind) ? 'low' : cfg.effort || 'medium';
  const params = {
    model,
    max_tokens: 16000,
    system: SYSTEM_PROMPTS[kind],
    messages: [{ role: 'user', content: buildUserMessage(kind, input) }],
    output_config: { effort, format: { type: 'json_schema', schema: SCHEMAS[kind] } },
  };
  const betas = [];
  if (FALLBACK_MODELS.has(model)) {
    params.fallbacks = 'default';
    betas.push(FALLBACK_BETA);
  }
  return { params, betas };
}

/** chat/completions body for OpenAI-compatible vendors (OpenAI, DeepSeek, Kimi, Qwen, OpenRouter, 硅基流动…). */
export function openaiBody(kind, input, cfg = {}) {
  assertKind(kind);
  // json_object mode is the most widely supported across vendors,
  // so the schema goes into the system prompt instead of response_format.
  return {
    model: cfg.model || DEFAULT_MODELS.openai,
    messages: [
      {
        role: 'system',
        content: `${SYSTEM_PROMPTS[kind]}\n\n## 输出格式\n只输出一个 JSON 对象，不要输出其他任何文字。JSON 必须符合下面的 JSON Schema（所有字段都必须出现）：\n${JSON.stringify(SCHEMAS[kind])}`,
      },
      { role: 'user', content: buildUserMessage(kind, input) },
    ],
    response_format: { type: 'json_object' },
    temperature: kind === 'roleplay' ? 0.8 : 0.4,
  };
}

/** Full HTTP request for direct (server-less) mode. */
export function buildDirectRequest(kind, input, cfg) {
  if (!cfg.apiKey) throw new LlmError('直连模式需要在设置页填写 API Key', 401);
  if (cfg.provider === 'openai') {
    const base = (cfg.baseUrl || 'https://api.openai.com/v1').replace(/\/+$/, '');
    return {
      provider: 'openai',
      url: `${base}/chat/completions`,
      headers: { 'content-type': 'application/json', authorization: `Bearer ${cfg.apiKey}` },
      body: openaiBody(kind, input, cfg),
    };
  }
  const { params, betas } = anthropicParams(kind, input, cfg);
  const headers = {
    'content-type': 'application/json',
    'x-api-key': cfg.apiKey,
    'anthropic-version': '2023-06-01',
    // Lets a browser page call the API directly. The key then lives in this browser,
    // so only use it on your own device.
    'anthropic-dangerous-direct-browser-access': 'true',
  };
  if (betas.length) headers['anthropic-beta'] = betas.join(',');
  return { provider: 'anthropic', url: 'https://api.anthropic.com/v1/messages', headers, body: params };
}

/** Turn an Anthropic Messages response body into the parsed result. */
export function readAnthropicMessage(message) {
  if (message.stop_reason === 'refusal') throw new LlmError('模型拒绝回答这个请求。可以换一种描述方式再试。', 422);
  if (message.stop_reason === 'max_tokens') throw new LlmError('回答太长被截断了，请把情况描述得更简洁一些再试。', 502);
  const text = (message.content || []).filter((b) => b.type === 'text').map((b) => b.text).join('');
  return { result: parseOrThrow(text), model: message.model };
}

export function readOpenAIMessage(data, fallbackModel) {
  return { result: parseOrThrow(data.choices?.[0]?.message?.content || ''), model: data.model || fallbackModel };
}

function parseOrThrow(text) {
  try {
    return parseJson(text);
  } catch (err) {
    throw new LlmError(err.message, 502);
  }
}

/** Map an HTTP error body from either vendor to a readable message. */
export function httpErrorMessage(provider, status, data) {
  const msg = data?.error?.message || data?.message || '未知错误';
  if (status === 401 || status === 403) return `${provider === 'anthropic' ? 'Claude' : '接口'} API Key 无效（${status}）`;
  if (status === 429) return '请求太频繁或额度不足，请稍后再试';
  return `接口返回 ${status}：${msg}`;
}

/**
 * One-string prompt for hosts that take no system prompt or schema parameter, such as the
 * claude.ai artifact viewer's `sample` capability. Returns { prompt, modelTier }.
 */
export function buildSamplePrompt(kind, input) {
  assertKind(kind);
  const prompt = [
    SYSTEM_PROMPTS[kind],
    `## 输出格式\n只输出一个 JSON 对象，不要输出其他任何文字。JSON 必须符合下面的 JSON Schema（所有字段都必须出现）：\n${JSON.stringify(SCHEMAS[kind])}`,
    '## 本次输入',
    buildUserMessage(kind, input),
  ].join('\n\n');
  return { prompt, modelTier: LIGHT_KINDS.includes(kind) ? 'quick' : 'default' };
}
