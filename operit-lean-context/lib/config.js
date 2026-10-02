"use strict";

// 配置全部存在 Operit 的「环境变量」里：钩子(main)、工具(sandbox)、设置页都能读写同一份。

const ENV = {
  enabled: "LEAN_CTX_ENABLED",
  preset: "LEAN_CTX_PRESET",
  terse: "LEAN_CTX_TERSE",
  slimTools: "LEAN_CTX_SLIM_TOOLS",
  summary: "LEAN_CTX_SUMMARY",
  overrides: "LEAN_CTX_OVERRIDES",
};

const PRESET_ORDER = ["light", "standard", "aggressive"];

const PRESET_LABEL = {
  light: "轻度",
  standard: "标准",
  aggressive: "激进",
};

// 字段含义：
// keepFloors       最近几轮（以用户发言为一轮）原样保留
// keepToolResults  最近几个工具结果原样保留（覆盖同一轮里的长工具链）
// toolResultMax    旧工具结果保留的最大字数
// paramMax         旧工具调用里单个参数的最大字数
// assistantMax     旧 AI 回复的最大字数
// userMax          旧用户消息的最大字数
// step             压缩边界每次推进几格（越大越利于缓存命中）
// maxFloors        最多发送多少轮，0 = 不限（交给自动总结）
// budgetChars      发送给模型的历史总字数上限，0 = 不限
// summaryMax       自动总结的目标字数
const PRESETS = {
  light: {
    keepFloors: 6,
    keepToolResults: 10,
    toolResultMax: 2000,
    paramMax: 800,
    assistantMax: 4000,
    userMax: 4000,
    step: 4,
    maxFloors: 0,
    budgetChars: 0,
    dedupe: true,
    terse: true,
    slimTools: false,
    summaryMax: 2000,
  },
  standard: {
    keepFloors: 3,
    keepToolResults: 6,
    toolResultMax: 800,
    paramMax: 300,
    assistantMax: 1500,
    userMax: 1500,
    step: 4,
    maxFloors: 30,
    budgetChars: 0,
    dedupe: true,
    terse: true,
    slimTools: false,
    summaryMax: 1200,
  },
  aggressive: {
    keepFloors: 2,
    keepToolResults: 3,
    toolResultMax: 300,
    paramMax: 160,
    assistantMax: 600,
    userMax: 800,
    step: 2,
    maxFloors: 10,
    budgetChars: 40000,
    dedupe: true,
    terse: true,
    slimTools: true,
    summaryMax: 800,
  },
};

const TRUE_VALUES = ["1", "true", "yes", "on"];
const FALSE_VALUES = ["0", "false", "no", "off"];

function defaultReader(key) {
  if (typeof getEnv !== "function") return "";
  const value = getEnv(key);
  return value == null ? "" : String(value).trim();
}

function parseBool(raw, fallback) {
  const v = String(raw || "").trim().toLowerCase();
  if (TRUE_VALUES.includes(v)) return true;
  if (FALSE_VALUES.includes(v)) return false;
  return fallback;
}

function normalizePreset(raw) {
  const v = String(raw || "").trim().toLowerCase();
  if (PRESET_ORDER.includes(v)) return v;
  for (const key of PRESET_ORDER) {
    if (PRESET_LABEL[key] === String(raw || "").trim()) return key;
  }
  return "";
}

/**
 * 读取完整配置。read 默认用宿主的 getEnv，测试里可以传一个 Map 的 get。
 */
function loadConfig(read) {
  const get = read || defaultReader;
  const preset = normalizePreset(get(ENV.preset)) || "standard";
  const base = Object.assign({}, PRESETS[preset]);

  const overridesRaw = get(ENV.overrides);
  if (overridesRaw) {
    try {
      const parsed = JSON.parse(overridesRaw);
      for (const key of Object.keys(parsed)) {
        if (key in base && typeof parsed[key] === typeof base[key]) {
          base[key] = parsed[key];
        }
      }
    } catch (error) {
      console.log(`[lean_ctx] ${ENV.overrides} 不是合法 JSON，已忽略: ${error && error.message}`);
    }
  }

  return Object.assign(base, {
    preset,
    presetLabel: PRESET_LABEL[preset],
    enabled: parseBool(get(ENV.enabled), true),
    terse: parseBool(get(ENV.terse), base.terse),
    slimTools: parseBool(get(ENV.slimTools), base.slimTools),
    summary: parseBool(get(ENV.summary), true),
  });
}

function nextPreset(current) {
  const i = PRESET_ORDER.indexOf(current);
  return PRESET_ORDER[(i + 1) % PRESET_ORDER.length];
}

async function writeEnv(key, value) {
  await Tools.SoftwareSettings.writeEnvironmentVariable(key, value);
}

module.exports = {
  ENV,
  PRESETS,
  PRESET_ORDER,
  PRESET_LABEL,
  loadConfig,
  nextPreset,
  normalizePreset,
  parseBool,
  writeEnv,
};
