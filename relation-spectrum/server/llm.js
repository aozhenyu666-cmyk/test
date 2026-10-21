// LLM router: Claude via the official SDK, or any OpenAI-compatible endpoint
// (OpenAI, DeepSeek, Kimi, Qwen...) via its chat/completions API.
// Request shapes come from web/js/core/llm-request.js so direct mode sends the same thing.

import Anthropic from '@anthropic-ai/sdk';
import {
  DEFAULT_MODELS, LlmError, anthropicParams, openaiBody,
  readAnthropicMessage, readOpenAIMessage, httpErrorMessage,
} from '../web/js/core/llm-request.js';

export { LlmError };
export { parseJson } from '../web/js/core/json.js';

/** Resolve provider settings: per-request overrides from the UI win over env vars. */
export function resolveConfig(override = {}) {
  const provider = override.provider || process.env.LLM_PROVIDER || 'anthropic';
  if (provider === 'openai') {
    return {
      provider,
      apiKey: override.apiKey || process.env.OPENAI_API_KEY || '',
      baseUrl: (override.baseUrl || process.env.OPENAI_BASE_URL || 'https://api.openai.com/v1').replace(/\/+$/, ''),
      model: override.model || process.env.OPENAI_MODEL || DEFAULT_MODELS.openai,
    };
  }
  return {
    provider: 'anthropic',
    apiKey: override.apiKey || process.env.ANTHROPIC_API_KEY || '',
    model: override.model || process.env.ANTHROPIC_MODEL || DEFAULT_MODELS.anthropic,
    effort: override.effort || process.env.ANTHROPIC_EFFORT || 'medium',
  };
}

/** Run an engine and return { result, model, provider }. */
export async function runEngine(kind, input, override) {
  const cfg = resolveConfig(override);
  const out = cfg.provider === 'openai' ? await callOpenAI(cfg, kind, input) : await callAnthropic(cfg, kind, input);
  return { ...out, provider: cfg.provider };
}

async function callAnthropic(cfg, kind, input) {
  const { params, betas } = anthropicParams(kind, input, cfg);
  // With no key in env or settings, the SDK still tries ANTHROPIC_AUTH_TOKEN / `ant auth login`.
  const client = new Anthropic(cfg.apiKey ? { apiKey: cfg.apiKey } : {});
  let message;
  try {
    message = betas.length
      ? await client.beta.messages.create({ ...params, betas })
      : await client.messages.create(params);
  } catch (err) {
    if (err instanceof Anthropic.AuthenticationError) throw new LlmError('Claude API Key 无效或未配置', 401);
    if (err instanceof Anthropic.RateLimitError) throw new LlmError('Claude 请求太频繁或额度不足，请稍后再试', 429);
    if (err instanceof Anthropic.BadRequestError) throw new LlmError(`请求被拒绝：${err.message}`, 400);
    if (err instanceof Anthropic.APIError) throw new LlmError(`Claude API 错误 ${err.status}：${err.message}`, 502);
    if (/authentication method/i.test(err.message)) throw new LlmError('没有配置 Claude API Key：在设置页填写，或写进 .env', 401);
    throw new LlmError(`无法连接 Claude：${err.message}`, 502);
  }
  return readAnthropicMessage(message);
}

async function callOpenAI(cfg, kind, input) {
  if (!cfg.apiKey) throw new LlmError('没有配置 OpenAI 兼容接口的 API Key', 401);
  let res;
  try {
    res = await fetch(`${cfg.baseUrl}/chat/completions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${cfg.apiKey}` },
      body: JSON.stringify(openaiBody(kind, input, cfg)),
    });
  } catch (err) {
    throw new LlmError(`无法连接 ${cfg.baseUrl}：${err.message}`, 502);
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new LlmError(httpErrorMessage('openai', res.status, data), res.status === 401 ? 401 : 502);
  return readOpenAIMessage(data, cfg.model);
}
