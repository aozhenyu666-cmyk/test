// LLM router: Claude via the official SDK, or any OpenAI-compatible endpoint
// (OpenAI, DeepSeek, Kimi, Qwen...) via its chat/completions API.

import Anthropic from '@anthropic-ai/sdk';
import { SYSTEM_PROMPTS, SCHEMAS, buildUserMessage } from '../web/js/core/prompts.js';

const DEFAULT_ANTHROPIC_MODEL = 'claude-opus-5-5';
// Models that accept server-side refusal fallbacks (`fallbacks: "default"`).
const FALLBACK_MODELS = new Set(['claude-opus-5-5', 'claude-opus-5', 'claude-sonnet-5-5', 'claude-fable-5-1']);

export class LlmError extends Error {
  constructor(message, status = 502) {
    super(message);
    this.status = status;
  }
}

/** Resolve provider settings: per-request overrides from the UI win over env vars. */
export function resolveConfig(override = {}) {
  const provider = override.provider || process.env.LLM_PROVIDER || 'anthropic';
  if (provider === 'openai') {
    return {
      provider,
      apiKey: override.apiKey || process.env.OPENAI_API_KEY || '',
      baseUrl: (override.baseUrl || process.env.OPENAI_BASE_URL || 'https://api.openai.com/v1').replace(/\/+$/, ''),
      model: override.model || process.env.OPENAI_MODEL || 'gpt-4o-mini',
    };
  }
  return {
    provider: 'anthropic',
    apiKey: override.apiKey || process.env.ANTHROPIC_API_KEY || '',
    model: override.model || process.env.ANTHROPIC_MODEL || DEFAULT_ANTHROPIC_MODEL,
    effort: override.effort || process.env.ANTHROPIC_EFFORT || 'medium',
  };
}

/** Run one of the three engines and return parsed JSON. */
export async function runEngine(kind, input, override) {
  if (!SYSTEM_PROMPTS[kind]) throw new LlmError(`未知的分析类型：${kind}`, 400);
  const cfg = resolveConfig(override);
  const system = SYSTEM_PROMPTS[kind];
  const user = buildUserMessage(kind, input);
  const schema = SCHEMAS[kind];

  const result = cfg.provider === 'openai'
    ? await callOpenAI(cfg, system, user, schema)
    : await callAnthropic(cfg, system, user, schema);
  return { result, model: result.__model || cfg.model, provider: cfg.provider };
}

async function callAnthropic(cfg, system, user, schema) {
  // With no key in env or settings, the SDK still tries ANTHROPIC_AUTH_TOKEN / `ant auth login`.
  const client = new Anthropic(cfg.apiKey ? { apiKey: cfg.apiKey } : {});
  const params = {
    model: cfg.model,
    max_tokens: 16000,
    system,
    messages: [{ role: 'user', content: user }],
    output_config: {
      effort: cfg.effort,
      format: { type: 'json_schema', schema },
    },
  };

  let response;
  try {
    response = FALLBACK_MODELS.has(cfg.model)
      ? await client.beta.messages.create({ ...params, betas: ['server-side-fallback-2026-07-01'], fallbacks: 'default' })
      : await client.messages.create(params);
  } catch (err) {
    if (err instanceof Anthropic.AuthenticationError) throw new LlmError('Claude API Key 无效或未配置', 401);
    if (err instanceof Anthropic.RateLimitError) throw new LlmError('Claude 请求太频繁或额度不足，请稍后再试', 429);
    if (err instanceof Anthropic.BadRequestError) throw new LlmError(`请求被拒绝：${err.message}`, 400);
    if (err instanceof Anthropic.APIError) throw new LlmError(`Claude API 错误 ${err.status}：${err.message}`, 502);
    if (/authentication method/i.test(err.message)) throw new LlmError('没有配置 Claude API Key：在设置页填写，或写进 .env', 401);
    throw new LlmError(`无法连接 Claude：${err.message}`, 502);
  }

  if (response.stop_reason === 'refusal') {
    throw new LlmError('模型拒绝回答这个请求。可以换一种描述方式再试。', 422);
  }
  if (response.stop_reason === 'max_tokens') {
    throw new LlmError('回答太长被截断了，请把情况描述得更简洁一些再试。', 502);
  }
  const text = response.content.filter((b) => b.type === 'text').map((b) => b.text).join('');
  const parsed = parseJson(text);
  parsed.__model = response.model;
  return parsed;
}

async function callOpenAI(cfg, system, user, schema) {
  if (!cfg.apiKey) throw new LlmError('没有配置 OpenAI 兼容接口的 API Key', 401);
  // json_object mode is the most widely supported across OpenAI-compatible vendors,
  // so the schema goes into the system prompt instead of response_format.
  const body = {
    model: cfg.model,
    messages: [
      {
        role: 'system',
        content: `${system}\n\n## 输出格式\n只输出一个 JSON 对象，不要输出其他任何文字。JSON 必须符合下面的 JSON Schema（所有字段都必须出现）：\n${JSON.stringify(schema)}`,
      },
      { role: 'user', content: user },
    ],
    response_format: { type: 'json_object' },
    temperature: 0.4,
  };
  let res;
  try {
    res = await fetch(`${cfg.baseUrl}/chat/completions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${cfg.apiKey}` },
      body: JSON.stringify(body),
    });
  } catch (err) {
    throw new LlmError(`无法连接 ${cfg.baseUrl}：${err.message}`, 502);
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new LlmError(`接口返回 ${res.status}：${data?.error?.message || '未知错误'}`, res.status === 401 ? 401 : 502);
  const parsed = parseJson(data.choices?.[0]?.message?.content || '');
  parsed.__model = data.model || cfg.model;
  return parsed;
}

/** Parse model output as JSON, tolerating code fences or stray text around the object. */
export function parseJson(text) {
  const t = String(text || '').trim();
  try {
    return JSON.parse(t);
  } catch {
    const start = t.indexOf('{');
    const end = t.lastIndexOf('}');
    if (start >= 0 && end > start) {
      try {
        return JSON.parse(t.slice(start, end + 1));
      } catch {
        /* fall through */
      }
    }
  }
  throw new LlmError('模型返回的内容不是有效的 JSON，请重试', 502);
}
