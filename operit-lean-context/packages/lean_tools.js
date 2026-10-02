/* METADATA
{
    "name": "lean_context_tools",
    "display_name": {
        "zh": "精简上下文工具",
        "en": "Lean Context Tools"
    },
    "description": {
        "zh": "查看/调整上下文精简模式，清理测试或一次性对话，把长对话接力到新对话，调整自动总结阈值。",
        "en": "Inspect/adjust lean-context mode, clean up throwaway chats, hand a long chat off to a fresh one, tune auto-summary."
    },
    "enabledByDefault": true,
    "env": [
        {
            "name": "LEAN_CTX_ENABLED",
            "description": { "zh": "是否启用上下文精简，true/false，默认 true", "en": "Enable lean context, default true" },
            "required": false
        },
        {
            "name": "LEAN_CTX_PRESET",
            "description": { "zh": "精简强度：light / standard / aggressive，默认 standard", "en": "Preset: light / standard / aggressive, default standard" },
            "required": false
        },
        {
            "name": "LEAN_CTX_TERSE",
            "description": { "zh": "是否注入简洁回答规则，true/false，默认跟随预设", "en": "Inject terse-answer rules, default follows preset" },
            "required": false
        },
        {
            "name": "LEAN_CTX_SLIM_TOOLS",
            "description": { "zh": "是否精简工具说明，true/false，默认跟随预设", "en": "Slim tool descriptions, default follows preset" },
            "required": false
        },
        {
            "name": "LEAN_CTX_SUMMARY",
            "description": { "zh": "是否让自动总结更短更省，true/false，默认 true", "en": "Make auto-summary shorter and cheaper, default true" },
            "required": false
        },
        {
            "name": "LEAN_CTX_OVERRIDES",
            "description": { "zh": "高级：JSON 覆盖预设参数，例如 {\"keepFloors\":4,\"toolResultMax\":500}", "en": "Advanced: JSON overrides, e.g. {\"keepFloors\":4}" },
            "required": false
        }
    ],
    "tools": [
        {
            "name": "lean_status",
            "description": {
                "zh": "查看上下文精简的当前设置和最近一次压缩效果（压缩前后字数）。",
                "en": "Show current lean-context settings and the last compaction result."
            },
            "parameters": []
        },
        {
            "name": "set_lean_mode",
            "description": {
                "zh": "调整上下文精简：开关、强度（light 轻度 / standard 标准 / aggressive 激进）、简洁回答、精简工具说明。只传需要改的参数。",
                "en": "Adjust lean context: on/off, preset, terse answers, slim tool descriptions. Pass only what changes."
            },
            "parameters": [
                { "name": "enabled", "description": { "zh": "true/false", "en": "true/false" }, "type": "boolean", "required": false },
                { "name": "preset", "description": { "zh": "light / standard / aggressive", "en": "light / standard / aggressive" }, "type": "string", "required": false },
                { "name": "terse", "description": { "zh": "简洁回答 true/false", "en": "terse answers true/false" }, "type": "boolean", "required": false },
                { "name": "slim_tools", "description": { "zh": "精简工具说明 true/false", "en": "slim tool descriptions true/false" }, "type": "boolean", "required": false }
            ]
        },
        {
            "name": "clean_trivial_chats",
            "description": {
                "zh": "找出测试对话、只聊了一两句就结束的对话。默认只列出不删除；用户确认后带 confirm=true 再调用一次才会删除。永远不删当前对话。",
                "en": "Find throwaway chats (tests, one-shot chats). Lists only by default; deletes only when called again with confirm=true after the user agrees. Never deletes the current chat."
            },
            "parameters": [
                { "name": "max_messages", "description": { "zh": "消息数不超过多少算一次性对话，默认 2（一问一答）", "en": "Max messages to count as throwaway, default 2" }, "type": "number", "required": false },
                { "name": "older_than_hours", "description": { "zh": "最后更新早于多少小时，默认 2", "en": "Last updated more than N hours ago, default 2" }, "type": "number", "required": false },
                { "name": "title_regex", "description": { "zh": "可选：标题匹配的正则，命中的对话不受消息数限制，例如 测试|test|新对话", "en": "Optional title regex; matches ignore max_messages" }, "type": "string", "required": false },
                { "name": "confirm", "description": { "zh": "true 才真正删除，默认 false 只预览", "en": "true to actually delete; default preview only" }, "type": "boolean", "required": false },
                { "name": "limit", "description": { "zh": "最多处理多少个，默认 50", "en": "Max chats to handle, default 50" }, "type": "number", "required": false }
            ]
        },
        {
            "name": "handoff_new_chat",
            "description": {
                "zh": "上下文太长时，把当前对话「接力」到一个新对话：你先写一份精炼的交接摘要（目标、已完成、关键事实/文件路径/参数、待办，建议 300~800 字），本工具新建对话并把摘要作为第一条消息发过去。旧对话保留不动。",
                "en": "When context gets long, hand the current chat off to a new chat: write a compact brief (goal, done, key facts/paths/params, todo); this tool creates a new chat and sends the brief as its first message. The old chat is kept."
            },
            "parameters": [
                { "name": "brief", "description": { "zh": "交接摘要正文", "en": "The hand-off brief" }, "type": "string", "required": true },
                { "name": "title", "description": { "zh": "新对话标题，可选", "en": "Optional new chat title" }, "type": "string", "required": false },
                { "name": "switch_to", "description": { "zh": "是否切换到新对话，默认 true", "en": "Switch to the new chat, default true" }, "type": "boolean", "required": false }
            ]
        },
        {
            "name": "tune_auto_summary",
            "description": {
                "zh": "查看或调整当前对话模型的自动总结：上下文用到多少比例就自动总结。默认只查看；apply=true 才写入设置。",
                "en": "View or adjust auto-summary of the chat model config (summary when context usage reaches a ratio). View only unless apply=true."
            },
            "parameters": [
                { "name": "threshold", "description": { "zh": "触发比例 0.1~0.95，建议 0.5", "en": "Trigger ratio 0.1-0.95, suggested 0.5" }, "type": "number", "required": false },
                { "name": "apply", "description": { "zh": "true 才写入", "en": "true to write" }, "type": "boolean", "required": false }
            ]
        }
    ]
}
*/

"use strict";

const config = require("../lib/config.js");

const DEFAULT_HANDOFF_TITLE_PREFIX = "接力·";

function bool(value, fallback) {
  if (value === undefined || value === null || value === "") return fallback;
  if (typeof value === "boolean") return value;
  return config.parseBool(String(value), fallback);
}

function num(value, fallback) {
  if (value === undefined || value === null || value === "") return fallback;
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

function toMillis(value) {
  if (typeof value === "number") return value;
  const parsed = Date.parse(String(value || ""));
  return Number.isFinite(parsed) ? parsed : 0;
}

function errorMessage(error) {
  return error instanceof Error ? error.message : String(error);
}

async function run(fn, params) {
  try {
    complete(await fn(params || {}));
  } catch (error) {
    console.log(`[lean_ctx] tool failed: ${error && error.stack ? error.stack : error}`);
    complete({ success: false, error: errorMessage(error) });
  }
}

async function leanStatus() {
  const cfg = config.loadConfig();
  let stats = null;
  try {
    const reply = await ToolPkg.ipc.call("lean_ctx.stats", {});
    stats = reply && reply.stats ? reply.stats : null;
  } catch (error) {
    console.log(`[lean_ctx] read stats failed: ${errorMessage(error)}`);
  }
  const lines = [
    `精简上下文：${cfg.enabled ? "开启" : "关闭"}，强度：${cfg.presetLabel}`,
    `原样保留最近 ${cfg.keepFloors} 轮、最近 ${cfg.keepToolResults} 个工具结果；更早的工具结果截到 ${cfg.toolResultMax} 字`,
    `最多发送 ${cfg.maxFloors > 0 ? cfg.maxFloors + " 轮" : "不限轮数"}，总字数上限 ${cfg.budgetChars > 0 ? cfg.budgetChars : "不限"}`,
    `简洁回答：${cfg.terse ? "开" : "关"}，精简工具说明：${cfg.slimTools ? "开" : "关"}，精简自动总结：${cfg.summary ? "开" : "关"}`,
  ];
  if (stats) {
    const saved = stats.beforeChars > 0 ? Math.round((1 - stats.afterChars / stats.beforeChars) * 100) : 0;
    lines.push(
      `最近一次：${stats.beforeChars} → ${stats.afterChars} 字（省 ${saved}%），消息 ${stats.beforeTurns} → ${stats.afterTurns}，压缩 ${stats.compressedTurns} 条，丢弃 ${stats.droppedFloors} 轮`
    );
  } else {
    lines.push("最近一次：还没有压缩记录（发一条消息后再看）");
  }
  return { success: true, config: cfg, last: stats, message: lines.join("\n") };
}

async function setLeanMode(params) {
  const changed = [];
  if (params.enabled !== undefined && params.enabled !== "") {
    const v = bool(params.enabled, true);
    await config.writeEnv(config.ENV.enabled, v ? "true" : "false");
    changed.push(`开关=${v ? "开" : "关"}`);
  }
  if (params.preset !== undefined && params.preset !== "") {
    const preset = config.normalizePreset(params.preset);
    if (!preset) {
      return { success: false, error: "preset 只能是 light / standard / aggressive（轻度/标准/激进）" };
    }
    await config.writeEnv(config.ENV.preset, preset);
    changed.push(`强度=${config.PRESET_LABEL[preset]}`);
  }
  if (params.terse !== undefined && params.terse !== "") {
    const v = bool(params.terse, true);
    await config.writeEnv(config.ENV.terse, v ? "true" : "false");
    changed.push(`简洁回答=${v ? "开" : "关"}`);
  }
  if (params.slim_tools !== undefined && params.slim_tools !== "") {
    const v = bool(params.slim_tools, false);
    await config.writeEnv(config.ENV.slimTools, v ? "true" : "false");
    changed.push(`精简工具说明=${v ? "开" : "关"}`);
  }
  if (!changed.length) {
    return { success: false, error: "没有要修改的参数" };
  }
  return { success: true, message: `已更新：${changed.join("，")}。下一条消息生效。` };
}

async function cleanTrivialChats(params) {
  const maxMessages = Math.max(0, num(params.max_messages, 2));
  const olderThanHours = Math.max(0, num(params.older_than_hours, 2));
  const limit = Math.max(1, num(params.limit, 50));
  const confirm = bool(params.confirm, false);
  let titleRe = null;
  if (params.title_regex) {
    try {
      titleRe = new RegExp(String(params.title_regex), "i");
    } catch (error) {
      return { success: false, error: `title_regex 不是合法正则：${errorMessage(error)}` };
    }
  }

  const list = await Tools.Chat.listAll();
  const now = Date.now();
  const cutoff = now - olderThanHours * 3600 * 1000;
  const candidates = [];
  for (const chat of list.chats || []) {
    if (chat.isCurrent || chat.id === list.currentChatId) continue;
    const updated = toMillis(chat.updatedAt) || toMillis(chat.createdAt);
    if (updated && updated > cutoff) continue;
    const fewMessages = chat.messageCount <= maxMessages;
    const titleHit = titleRe ? titleRe.test(chat.title || "") : false;
    if (!fewMessages && !titleHit) continue;
    candidates.push({
      id: chat.id,
      title: chat.title || "(无标题)",
      messageCount: chat.messageCount,
      updatedAt: chat.updatedAt,
      reason: titleHit && !fewMessages ? "标题匹配" : `仅 ${chat.messageCount} 条消息`,
    });
    if (candidates.length >= limit) break;
  }

  if (!candidates.length) {
    return { success: true, deleted: 0, candidates: [], message: "没有找到符合条件的一次性/测试对话。" };
  }

  const preview = candidates.map((c, i) => `${i + 1}. ${c.title}（${c.reason}）`).join("\n");
  if (!confirm) {
    return {
      success: true,
      dryRun: true,
      deleted: 0,
      candidates,
      message: `找到 ${candidates.length} 个可清理的对话（还没删除）：\n${preview}\n\n请向用户确认后，用相同参数加 confirm=true 再调用一次才会删除。`,
    };
  }

  const deleted = [];
  const failed = [];
  for (const c of candidates) {
    try {
      await Tools.Chat.deleteChat(c.id);
      deleted.push(c);
    } catch (error) {
      failed.push({ id: c.id, title: c.title, error: errorMessage(error) });
    }
  }
  return {
    success: failed.length === 0,
    deleted: deleted.length,
    failed,
    message: `已删除 ${deleted.length} 个对话${failed.length ? `，${failed.length} 个失败` : ""}。`,
  };
}

async function handoffNewChat(params) {
  const brief = String(params.brief || "").trim();
  if (brief.length < 20) {
    return { success: false, error: "brief 太短：请先写一份交接摘要（目标、已完成、关键事实、待办）。" };
  }
  const switchTo = bool(params.switch_to, true);

  const list = await Tools.Chat.listAll();
  const current = (list.chats || []).find((c) => c.id === list.currentChatId);
  const oldTitle = current && current.title ? current.title : "上一个对话";
  const title = String(params.title || "").trim() || `${DEFAULT_HANDOFF_TITLE_PREFIX}${oldTitle}`.slice(0, 40);

  const created = await Tools.Chat.createNew(undefined, switchTo);
  const chatId = created.chatId;
  await Tools.Chat.updateTitle(chatId, title);

  const message =
    `【接力摘要】这是从「${oldTitle}」接力过来的新对话，下面是之前的要点：\n\n${brief}\n\n` +
    `请先用一两句话确认你理解的当前目标和下一步，然后等我继续。`;
  // sendMessage 会等新对话里的 AI 回完；摘要要求它只回一两句，给 2 分钟足够。
  let sendError = "";
  try {
    await Tools.Chat.sendMessage(message, chatId, undefined, undefined, { timeout_ms: 120000 });
  } catch (error) {
    sendError = errorMessage(error);
    console.log(`[lean_ctx] handoff send failed: ${sendError}`);
  }

  return {
    success: !sendError,
    chatId,
    title,
    switched: switchTo,
    error: sendError || undefined,
    message: sendError
      ? `已新建对话「${title}」，但发送交接摘要失败：${sendError}。可以把摘要手动粘贴过去。`
      : `已新建对话「${title}」并发送交接摘要（${brief.length} 字）。之后请在新对话里继续，旧对话保留不动。`,
  };
}

async function tuneAutoSummary(params) {
  const binding = await Tools.SoftwareSettings.getFunctionModelConfig("CHAT");
  const cfg = binding.config || {};
  const current = {
    configId: binding.configId,
    configName: binding.configName,
    contextLength: cfg.contextLength,
    enableSummary: cfg.enableSummary,
    summaryTokenThreshold: cfg.summaryTokenThreshold,
    enableSummaryByMessageCount: cfg.enableSummaryByMessageCount,
    summaryMessageCountThreshold: cfg.summaryMessageCountThreshold,
  };
  const describe = (c) =>
    `模型配置「${c.configName}」：上下文 ${c.contextLength}，自动总结 ${c.enableSummary ? "开" : "关"}，` +
    `用到 ${Math.round((c.summaryTokenThreshold || 0) * 100)}% 时总结` +
    (c.enableSummaryByMessageCount ? `，或每 ${c.summaryMessageCountThreshold} 条消息总结` : "");

  const apply = bool(params.apply, false);
  if (!apply) {
    return {
      success: true,
      current,
      message: `${describe(current)}。\n建议：开启自动总结，阈值 0.5 左右（上下文用到一半就总结，单轮工具链也不会无限涨）。用 apply=true 写入。`,
    };
  }

  const threshold = num(params.threshold, 0.5);
  if (!(threshold >= 0.1 && threshold <= 0.95)) {
    return { success: false, error: "threshold 需要在 0.1 ~ 0.95 之间" };
  }
  const updated = await Tools.SoftwareSettings.updateModelConfig(binding.configId, {
    enable_summary: true,
    summary_token_threshold: threshold,
  });
  const after = Object.assign({}, current, {
    enableSummary: updated.config ? updated.config.enableSummary : true,
    summaryTokenThreshold: updated.config ? updated.config.summaryTokenThreshold : threshold,
  });
  return { success: true, before: current, after, message: `已更新。${describe(after)}。` };
}

exports.lean_status = (params) => run(leanStatus, params);
exports.set_lean_mode = (params) => run(setLeanMode, params);
exports.clean_trivial_chats = (params) => run(cleanTrivialChats, params);
exports.handoff_new_chat = (params) => run(handoffNewChat, params);
exports.tune_auto_summary = (params) => run(tuneAutoSummary, params);
