"use strict";

// 纯函数：只改「发给模型的那份上下文」，不碰聊天记录本身。
// 运行在 ToolPkg main 上下文（钩子）和 Node 测试里，所以不依赖任何宿主 API。
//
// 关键约束（来自 Operit 源码）：
// 1. TOOL_CALL / TOOL_RESULT 必须成对出现，原生 Tool Call 才能配对 —— 只压缩内容、保留 XML 外壳，
//    删除时只能在 USER 楼层边界整段删除。
// 2. 宿主会把钩子结果直接作为本轮的执行上下文继续使用，所以压缩必须幂等：
//    压缩后的长度 <= 上限，再压一次结果不变。
// 3. 带缓存的 API（DeepSeek / Claude 等）按前缀命中缓存。压缩边界按 step 成批推进，
//    而不是每轮都往后挪一格，前缀才能在多轮之间保持不变。

const NOTE = "精简";

const TOOL_RESULT_BLOCK =
  /<(tool_result(?:_[A-Za-z0-9_]+)?)\b([^>]*)>([\s\S]*?)<\/\1>/gi;
const CONTENT_TAG = /^([\s\S]*?<content>)([\s\S]*?)(<\/content>[\s\S]*)$/i;
const PARAM_TAG = /(<param\s+name="[^"]+">)([\s\S]*?)(<\/param>)/g;
const THINK_BLOCK = /<think(?:ing)?\b[^>]*>[\s\S]*?<\/think(?:ing)?>\s*/gi;
// 含这些标签的助手消息带协议信息（工具调用、Gemini thought signature、状态），不做截断。
const PROTECTED_ASSISTANT = /<(meta|tool|status)\b/i;
const NAME_ATTR = /name\s*=\s*"([^"]+)"/i;

const MIN_LIMIT = 80;

function isHighSurrogate(code) {
  return code >= 0xd800 && code <= 0xdbff;
}

function isLowSurrogate(code) {
  return code >= 0xdc00 && code <= 0xdfff;
}

// 截断时不能把 emoji 之类的代理对劈开，否则会产生非法字符串。
function safeHead(text, length) {
  let end = length;
  if (end > 0 && end < text.length && isHighSurrogate(text.charCodeAt(end - 1))) {
    end -= 1;
  }
  return text.slice(0, end);
}

function safeTail(text, length) {
  let start = text.length - length;
  if (start > 0 && start < text.length && isLowSurrogate(text.charCodeAt(start))) {
    start += 1;
  }
  return text.slice(start);
}

/**
 * 保留开头和结尾，中间替换成省略说明。返回值长度一定 <= max，因此可重复调用。
 */
function truncateMiddle(text, max) {
  if (typeof text !== "string" || !(max > 0) || text.length <= max) {
    return text;
  }
  const limit = Math.max(max, MIN_LIMIT);
  if (text.length <= limit) {
    return text;
  }
  const reserve = `\n…[${NOTE}: 省略 ${text.length} 字]…\n`.length;
  const room = limit - reserve;
  const headLen = Math.floor(room * 0.65);
  const tailLen = room - headLen;
  const head = safeHead(text, headLen);
  const tail = safeTail(text, tailLen);
  const omitted = text.length - head.length - tail.length;
  return `${head}\n…[${NOTE}: 省略 ${omitted} 字]…\n${tail}`;
}

function shrinkToolResultBody(body, max) {
  const parts = CONTENT_TAG.exec(body);
  if (parts) {
    return parts[1] + truncateMiddle(parts[2], max) + parts[3];
  }
  return truncateMiddle(body, max);
}

/**
 * 压缩 TOOL_RESULT：保留 <tool_result name=.. status=..> 外壳，只截内容；
 * 若同名工具返回过完全相同的长内容，用一句话代替。
 */
function compactToolResult(content, max, seen) {
  let matched = false;
  const out = content.replace(TOOL_RESULT_BLOCK, (whole, tag, attrs, body) => {
    matched = true;
    const nameMatch = NAME_ATTR.exec(attrs);
    const name = nameMatch ? nameMatch[1] : tag;
    if (seen && body.length > 200) {
      const key = `${name}\u0000${body}`;
      if (seen.has(key)) {
        return `<${tag}${attrs}><content>[${NOTE}: 与前面一次 ${name} 的结果相同，已省略]</content></${tag}>`;
      }
      seen.add(key);
    }
    return `<${tag}${attrs}>${shrinkToolResultBody(body, max)}</${tag}>`;
  });
  return matched ? out : truncateMiddle(content, max);
}

/** 压缩 TOOL_CALL：只截过长的参数值（例如写文件的大段内容），标签不动。 */
function compactToolCall(content, max) {
  return content.replace(PARAM_TAG, (whole, open, value, close) => {
    return open + truncateMiddle(value, max) + close;
  });
}

function compactAssistant(content, max) {
  const withoutThink = content.replace(THINK_BLOCK, "");
  if (PROTECTED_ASSISTANT.test(withoutThink)) {
    return withoutThink;
  }
  return truncateMiddle(withoutThink, max);
}

function isFloorStart(turn) {
  return turn.kind === "USER" && !/^\s*<status\b/i.test(turn.content || "");
}

function totalChars(turns) {
  let sum = 0;
  for (const turn of turns) {
    sum += (turn.content || "").length;
  }
  return sum;
}

function stableCount(count, step) {
  if (count <= 0) return 0;
  const s = Math.max(1, step | 0);
  return Math.floor(count / s) * s;
}

/**
 * @param {Array<{kind:string, content:string}>} history preparedHistory
 * @param {object} opts 见 config.js 的 PRESETS
 * @returns {{history: Array, stats: object}}
 */
function compactHistory(history, opts) {
  const turns = Array.isArray(history) ? history : [];
  const beforeChars = totalChars(turns);

  const floorStarts = [];
  const toolResultIdx = [];
  turns.forEach((turn, i) => {
    if (isFloorStart(turn)) floorStarts.push(i);
    if (turn.kind === "TOOL_RESULT") toolResultIdx.push(i);
  });
  const floors = floorStarts.length;
  const lastUserIdx = floors ? floorStarts[floors - 1] : -1;

  // ---- 1. 硬上限：只保留最近 maxFloors 层（按 step 成批丢弃，保持前缀稳定） ----
  let dropBefore = -1;
  let droppedFloors = 0;
  const keepFloors = Math.max(1, opts.keepFloors | 0);
  if (opts.maxFloors > 0 && floors > opts.maxFloors) {
    const step = Math.max(1, opts.step | 0);
    const excess = floors - opts.maxFloors;
    let drop = Math.ceil(excess / step) * step;
    drop = Math.min(drop, floors - Math.min(keepFloors, opts.maxFloors));
    if (drop > 0) {
      droppedFloors = drop;
      dropBefore = floorStarts[drop];
    }
  }

  // ---- 2. 旧楼层 / 旧工具结果的边界 ----
  const oldFloors = stableCount(floors - keepFloors, opts.step);
  const floorBoundary = oldFloors > 0 ? floorStarts[oldFloors] : -1;

  const oldTools = stableCount(toolResultIdx.length - Math.max(0, opts.keepToolResults | 0), opts.step);
  let toolBoundary = -1;
  if (oldTools > 0) {
    // keepToolResults 为 0 时所有工具结果都算旧的
    toolBoundary = oldTools < toolResultIdx.length ? toolResultIdx[oldTools] : Infinity;
    // 边界退到这次工具调用的 TOOL_CALL 之前：调用和结果要么一起压缩，要么一起保留，
    // 否则边界会以和楼层边界不同的节奏推进，白白多打破一次缓存前缀。
    while (toolBoundary !== Infinity && toolBoundary > 0 && turns[toolBoundary - 1].kind === "TOOL_CALL") {
      toolBoundary -= 1;
    }
  }

  // ---- 3. 逐条压缩 ----
  const seen = opts.dedupe ? new Set() : null;
  let compressedTurns = 0;
  let result = [];
  turns.forEach((turn, i) => {
    const kind = turn.kind;
    if (kind === "SYSTEM" || kind === "SUMMARY") {
      result.push(turn);
      return;
    }
    if (i < dropBefore) {
      return;
    }
    const content = typeof turn.content === "string" ? turn.content : "";
    const floorOld = i < floorBoundary;
    const toolOld = i < toolBoundary;
    let next = content;
    if (kind === "TOOL_RESULT" && (floorOld || toolOld)) {
      next = compactToolResult(content, opts.toolResultMax, seen);
    } else if (kind === "TOOL_CALL" && (floorOld || toolOld)) {
      next = compactToolCall(content, opts.paramMax);
    } else if (kind === "ASSISTANT" && floorOld) {
      next = compactAssistant(content, opts.assistantMax);
    } else if (kind === "USER" && floorOld && i !== lastUserIdx) {
      next = truncateMiddle(content, opts.userMax);
    }
    if (next !== content) {
      compressedTurns += 1;
      result.push(Object.assign({}, turn, { content: next }));
    } else {
      result.push(turn);
    }
  });

  // ---- 4. 总字数预算：仍超出就继续从最早的楼层整层丢弃 ----
  if (opts.budgetChars > 0 && totalChars(result) > opts.budgetChars) {
    const starts = [];
    result.forEach((turn, i) => {
      if (isFloorStart(turn)) starts.push(i);
    });
    let cutFloor = 0;
    let size = totalChars(result);
    const maxCut = Math.max(0, starts.length - keepFloors);
    while (size > opts.budgetChars && cutFloor < maxCut) {
      const from = cutFloor === 0 ? 0 : starts[cutFloor];
      const to = starts[cutFloor + 1];
      for (let i = from; i < to; i += 1) {
        const t = result[i];
        if (t.kind !== "SYSTEM" && t.kind !== "SUMMARY") size -= (t.content || "").length;
      }
      cutFloor += 1;
    }
    if (cutFloor > 0) {
      const cutAt = starts[cutFloor];
      result = result.filter((t, i) => i >= cutAt || t.kind === "SYSTEM" || t.kind === "SUMMARY");
      droppedFloors += cutFloor;
    }
  }

  // ---- 5. 丢过楼层就告诉模型一声（不改最后一条用户消息，否则宿主会重复追加当前输入） ----
  if (droppedFloors > 0) {
    let firstUser = -1;
    let lastUser = -1;
    result.forEach((turn, i) => {
      if (isFloorStart(turn)) {
        if (firstUser < 0) firstUser = i;
        lastUser = i;
      }
    });
    if (firstUser >= 0 && firstUser !== lastUser) {
      const notice = `[${NOTE}: 更早的 ${droppedFloors} 轮对话没有发送给你；需要时请让用户补充或查看总结]\n`;
      const turn = result[firstUser];
      if (!turn.content.startsWith(`[${NOTE}: 更早的`)) {
        result[firstUser] = Object.assign({}, turn, { content: notice + turn.content });
      }
    }
  }

  return {
    history: result,
    stats: {
      beforeChars,
      afterChars: totalChars(result),
      beforeTurns: turns.length,
      afterTurns: result.length,
      floors,
      droppedFloors,
      compressedTurns,
    },
  };
}

/** 交给总结模型前先瘦身：大段工具输出对写总结几乎没用，却占了总结请求的大部分输入。 */
function compactForSummary(history, opts) {
  const seen = new Set();
  let changed = false;
  const out = (Array.isArray(history) ? history : []).map((turn) => {
    const content = typeof turn.content === "string" ? turn.content : "";
    let next = content;
    if (turn.kind === "TOOL_RESULT") next = compactToolResult(content, opts.toolResultMax, seen);
    else if (turn.kind === "TOOL_CALL") next = compactToolCall(content, opts.paramMax);
    else if (turn.kind === "ASSISTANT") next = compactAssistant(content, opts.textMax);
    else if (turn.kind === "USER") next = truncateMiddle(content, opts.textMax);
    if (next === content) return turn;
    changed = true;
    return Object.assign({}, turn, { content: next });
  });
  return { history: out, changed };
}

/** 工具说明瘦身：去掉 details / notes，过长的描述截短。工具本身一个不少。 */
function slimToolItems(items, descMax) {
  if (!Array.isArray(items)) return null;
  let changed = false;
  const out = items.map((item) => {
    if (!item || typeof item !== "object") return item;
    const next = Object.assign({}, item);
    if (next.details) {
      next.details = "";
      changed = true;
    }
    if (next.notes) {
      next.notes = "";
      changed = true;
    }
    if (typeof next.description === "string" && next.description.length > descMax) {
      next.description = safeHead(next.description, descMax - 1) + "…";
      changed = true;
    }
    return next;
  });
  return changed ? out : null;
}

module.exports = {
  NOTE,
  truncateMiddle,
  compactToolResult,
  compactToolCall,
  compactAssistant,
  compactHistory,
  compactForSummary,
  slimToolItems,
  totalChars,
};
