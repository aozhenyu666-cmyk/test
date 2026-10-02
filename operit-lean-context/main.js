"use strict";

const compactor = require("./lib/compactor.js");
const config = require("./lib/config.js");

const HOOK_IDS = {
  finalize: "lean_ctx_finalize",
  estimate: "lean_ctx_estimate",
  systemPrompt: "lean_ctx_system_prompt",
  toolPrompt: "lean_ctx_tool_prompt",
  summary: "lean_ctx_summary",
  menu: "lean_ctx_menu",
};

const TOGGLE_IDS = {
  master: "lean_ctx_master",
  preset: "lean_ctx_preset",
  terse: "lean_ctx_terse",
  slimTools: "lean_ctx_slim_tools",
};

const TOOL_DESC_MAX = 160;

const TERSE_PROMPT_ZH = `【精简模式】
- 直接回答，不寒暄、不复述问题、不重复前文已经说过的内容；能一句说清就不写一段。
- 不要把工具返回的原文大段贴进回答，只摘取用得上的部分。
- 读文件、网页、日志时按需读取（指定行号、关键词或分页），不要一次性读入超大内容。
- 能合并的工具调用合并做，不做重复的试探性调用；任务完成就停。
- 历史里带「[精简: …]」标记的内容已被压缩，确实需要原文时重新调用工具获取。`;

const TERSE_PROMPT_EN = `[Lean mode]
- Answer directly. No pleasantries, no restating the question, no repeating earlier content.
- Do not paste raw tool output into answers; quote only what matters.
- Read files, pages and logs selectively (line ranges, keywords, pagination), never whole huge blobs.
- Batch tool calls where possible, avoid repeated exploratory calls, stop when the task is done.
- History marked "[精简: …]" was compacted; re-run the tool if you truly need the original.`;

// main 上下文是常驻的，用来记最近一次压缩的效果，给输入菜单和 lean_status 工具看。
let lastStats = null;

function formatK(chars) {
  if (chars >= 10000) return `${(chars / 1000).toFixed(0)}k`;
  if (chars >= 1000) return `${(chars / 1000).toFixed(1)}k`;
  return String(chars);
}

function describeStats(stats) {
  if (!stats) return "还没有压缩记录";
  return `上次 ${formatK(stats.beforeChars)}→${formatK(stats.afterChars)} 字`;
}

if (typeof ToolPkg !== "undefined" && ToolPkg.ipc && typeof ToolPkg.ipc.on === "function") {
  ToolPkg.ipc.on("lean_ctx.stats", async function () {
    return { stats: lastStats };
  });
}

function isChatFunction(payload) {
  const type = payload.functionType;
  return !type || type === "CHAT";
}

function compactPayload(event, record) {
  const payload = (event && event.eventPayload) || {};
  if (!isChatFunction(payload)) return null;
  const stage = event.eventName || event.event || payload.stage;
  // 只在 before_finalize_prompt 做一次，before_send_to_model 拿到的已经是压缩后的结果。
  if (stage && stage !== "before_finalize_prompt") return null;

  const cfg = config.loadConfig();
  if (!cfg.enabled) return null;

  const history = payload.preparedHistory || [];
  if (!history.length) return null;

  const { history: next, stats } = compactor.compactHistory(history, cfg);
  if (record) {
    lastStats = Object.assign({ chatId: payload.chatId || null, at: Date.now(), preset: cfg.preset }, stats);
    console.log(
      `[lean_ctx] ${cfg.preset}: ${stats.beforeChars}->${stats.afterChars} chars, turns ${stats.beforeTurns}->${stats.afterTurns}, compressed ${stats.compressedTurns}, dropped floors ${stats.droppedFloors}`
    );
  }
  if (stats.compressedTurns === 0 && stats.afterTurns === stats.beforeTurns) return null;
  return { preparedHistory: next };
}

function onPromptFinalize(event) {
  try {
    return compactPayload(event, true);
  } catch (error) {
    console.log(`[lean_ctx] finalize failed: ${error && error.stack ? error.stack : error}`);
    return null;
  }
}

// 估算用的钩子也压一遍：输入框上方的 token 数和自动总结的触发判断才能和实际发送的一致。
function onPromptEstimateFinalize(event) {
  try {
    return compactPayload(event, false);
  } catch (error) {
    console.log(`[lean_ctx] estimate failed: ${error && error.stack ? error.stack : error}`);
    return null;
  }
}

function onSystemPromptCompose(event) {
  const stage = (event && (event.eventName || event.event)) || "";
  if (stage !== "after_compose_system_prompt") return null;
  const cfg = config.loadConfig();
  if (!cfg.enabled || !cfg.terse) return null;
  const payload = event.eventPayload || {};
  const current = payload.systemPrompt || "";
  if (current.indexOf("【精简模式】") >= 0 || current.indexOf("[Lean mode]") >= 0) return null;
  const extra = payload.useEnglish ? TERSE_PROMPT_EN : TERSE_PROMPT_ZH;
  return { systemPrompt: `${current}\n\n${extra}` };
}

// before_compose_tool_prompt 阶段宿主还没渲染工具文本，改 availableTools 会被重新渲染；
// filter_tool_call_tools 是原生 Tool Call 模式下发给 API 的工具定义。
function onToolPromptCompose(event) {
  const stage = (event && (event.eventName || event.event)) || "";
  if (stage !== "before_compose_tool_prompt" && stage !== "filter_tool_call_tools") return null;
  const cfg = config.loadConfig();
  if (!cfg.enabled || !cfg.slimTools) return null;
  const payload = event.eventPayload || {};
  const slim = compactor.slimToolItems(payload.availableTools, TOOL_DESC_MAX);
  return slim ? { availableTools: slim } : null;
}

function onSummaryGenerate(event) {
  const stage = (event && (event.eventName || event.event)) || "";
  if (stage !== "before_prepare_summary_prompt") return null;
  const cfg = config.loadConfig();
  if (!cfg.enabled || !cfg.summary) return null;
  const payload = event.eventPayload || {};
  const result = {};

  const { history, changed } = compactor.compactForSummary(payload.chatHistory || [], {
    toolResultMax: Math.max(cfg.toolResultMax, 1200),
    paramMax: Math.max(cfg.paramMax, 300),
    textMax: 3000,
  });
  if (changed) result.chatHistory = history;

  const basePrompt = payload.summaryPrompt || "";
  const budget = payload.useEnglish
    ? `\n\nKeep the summary within about ${cfg.summaryMax} words. Keep only what is needed to continue: goal, decisions, key facts/paths/parameters, done vs. pending. Drop greetings, retries and raw tool output.`
    : `\n\n请把总结控制在约 ${cfg.summaryMax} 字以内，只保留继续工作必需的信息：目标、已做的决定、关键事实/路径/参数、已完成与待办。去掉寒暄、试错过程和工具原文。`;
  if (basePrompt.indexOf(budget.trim()) < 0) result.summaryPrompt = basePrompt + budget;

  return Object.keys(result).length ? result : null;
}

async function onInputMenuToggle(event) {
  const payload = (event && event.eventPayload) || {};
  const cfg = config.loadConfig();

  if (payload.action === "create") {
    return {
      toggles: [
        {
          id: TOGGLE_IDS.master,
          title: "精简上下文",
          description: cfg.enabled ? `已开启 · ${cfg.presetLabel} · ${describeStats(lastStats)}` : "已关闭，发送完整上下文",
          isChecked: cfg.enabled,
        },
        {
          id: TOGGLE_IDS.preset,
          title: `精简强度 ▶ ${cfg.presetLabel}`,
          description: "点击切换：轻度 / 标准 / 激进",
          isChecked: cfg.enabled,
        },
        {
          id: TOGGLE_IDS.terse,
          title: "简洁回答",
          description: "让 AI 少说废话、按需读取文件",
          isChecked: cfg.enabled && cfg.terse,
        },
        {
          id: TOGGLE_IDS.slimTools,
          title: "精简工具说明",
          description: "去掉工具的长说明，省下每轮固定开销",
          isChecked: cfg.enabled && cfg.slimTools,
        },
      ],
    };
  }

  if (payload.action === "toggle") {
    switch (payload.toggleId) {
      case TOGGLE_IDS.master:
        await config.writeEnv(config.ENV.enabled, cfg.enabled ? "false" : "true");
        return { ok: true };
      case TOGGLE_IDS.preset:
        await config.writeEnv(config.ENV.preset, config.nextPreset(cfg.preset));
        return { ok: true };
      case TOGGLE_IDS.terse:
        await config.writeEnv(config.ENV.terse, cfg.terse ? "false" : "true");
        return { ok: true };
      case TOGGLE_IDS.slimTools:
        await config.writeEnv(config.ENV.slimTools, cfg.slimTools ? "false" : "true");
        return { ok: true };
      default:
        break;
    }
  }
  return { ok: false };
}

function registerToolPkg() {
  ToolPkg.registerPromptFinalizeHook({ id: HOOK_IDS.finalize, function: onPromptFinalize });
  ToolPkg.registerPromptEstimateFinalizeHook({ id: HOOK_IDS.estimate, function: onPromptEstimateFinalize });
  ToolPkg.registerSystemPromptComposeHook({ id: HOOK_IDS.systemPrompt, function: onSystemPromptCompose });
  ToolPkg.registerToolPromptComposeHook({ id: HOOK_IDS.toolPrompt, function: onToolPromptCompose });
  ToolPkg.registerSummaryGenerateHook({ id: HOOK_IDS.summary, function: onSummaryGenerate });
  ToolPkg.registerInputMenuTogglePlugin({ id: HOOK_IDS.menu, function: onInputMenuToggle });
  return true;
}

exports.registerToolPkg = registerToolPkg;
exports.onPromptFinalize = onPromptFinalize;
exports.onPromptEstimateFinalize = onPromptEstimateFinalize;
exports.onSystemPromptCompose = onSystemPromptCompose;
exports.onToolPromptCompose = onToolPromptCompose;
exports.onSummaryGenerate = onSummaryGenerate;
exports.onInputMenuToggle = onInputMenuToggle;
