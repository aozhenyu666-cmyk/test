"use strict";

// 用假的宿主 API（getEnv / Tools / ToolPkg / complete）跑 main.js 的钩子和子包工具。

const assert = require("assert");

const env = new Map();
const deletedChats = [];
const sent = [];
let ipcHandlers = {};
let completed = null;

const HOUR = 3600 * 1000;
const now = Date.now();
const chats = [
  { id: "cur", title: "正在聊的", messageCount: 1, updatedAt: new Date(now - 10 * HOUR).toISOString(), isCurrent: true },
  { id: "t1", title: "测试一下", messageCount: 2, updatedAt: new Date(now - 5 * HOUR).toISOString(), isCurrent: false },
  { id: "t2", title: "长对话", messageCount: 80, updatedAt: new Date(now - 5 * HOUR).toISOString(), isCurrent: false },
  { id: "t3", title: "刚刚的", messageCount: 1, updatedAt: new Date(now - 10 * 60 * 1000).toISOString(), isCurrent: false },
  { id: "t4", title: "test api", messageCount: 12, updatedAt: new Date(now - 30 * HOUR).toISOString(), isCurrent: false },
];

global.getEnv = (key) => (env.has(key) ? env.get(key) : undefined);
global.complete = (value) => {
  completed = value;
};
global.Tools = {
  SoftwareSettings: {
    writeEnvironmentVariable: async (key, value) => {
      env.set(key, value);
      return { key, value };
    },
    getFunctionModelConfig: async () => ({
      configId: "cfg1",
      configName: "DeepSeek",
      config: { contextLength: 128, enableSummary: false, summaryTokenThreshold: 0.8, enableSummaryByMessageCount: false },
    }),
    updateModelConfig: async (id, updates) => ({
      updated: true,
      config: { enableSummary: updates.enable_summary, summaryTokenThreshold: updates.summary_token_threshold },
    }),
  },
  Chat: {
    listAll: async () => ({ totalCount: chats.length, currentChatId: "cur", chats }),
    deleteChat: async (id) => {
      deletedChats.push(id);
      return { chatId: id };
    },
    createNew: async () => ({ chatId: "new1", createdAt: now }),
    updateTitle: async () => ({}),
    sendMessage: async (message, chatId) => {
      sent.push({ message, chatId });
      return { chatId };
    },
  },
};
global.ToolPkg = {
  ipc: {
    on: (channel, fn) => {
      ipcHandlers[channel] = fn;
    },
    call: async (channel, payload) => ipcHandlers[channel](payload),
  },
};

const main = require("../main.js");
const tools = require("../packages/lean_tools.js");

let passed = 0;
async function test(name, fn) {
  try {
    await fn();
    passed += 1;
    console.log(`ok - ${name}`);
  } catch (error) {
    console.error(`FAIL - ${name}`);
    throw error;
  }
}

async function callTool(fn, params) {
  completed = null;
  fn(params);
  for (let i = 0; i < 50 && completed === null; i += 1) {
    await new Promise((r) => setImmediate(r));
  }
  return completed;
}

const big = (n) => "x".repeat(n);
function history(floors) {
  const h = [{ kind: "SYSTEM", content: "SYS" }];
  for (let i = 0; i < floors; i += 1) {
    h.push({ kind: "USER", content: `q${i}` });
    h.push({ kind: "TOOL_CALL", content: `<tool name="read_file"><param name="path">/a</param></tool>` });
    h.push({ kind: "TOOL_RESULT", content: `<tool_result name="read_file" status="success"><content>${i}${big(5000)}</content></tool_result>` });
    h.push({ kind: "ASSISTANT", content: `a${i}` });
  }
  return h;
}
const finalizeEvent = (payload) => ({ event: "before_finalize_prompt", eventName: "before_finalize_prompt", eventPayload: payload });

(async () => {
  await test("finalize compacts CHAT and records stats", async () => {
    const out = main.onPromptFinalize(finalizeEvent({ chatId: "c", functionType: "CHAT", preparedHistory: history(10) }));
    assert.ok(out && out.preparedHistory);
    const stats = (await ToolPkg.ipc.call("lean_ctx.stats")).stats;
    assert.strictEqual(stats.chatId, "c");
    assert.ok(stats.afterChars < stats.beforeChars);
  });

  await test("finalize skips non-chat function types and before_send stage", async () => {
    assert.strictEqual(main.onPromptFinalize(finalizeEvent({ functionType: "SUMMARY", preparedHistory: history(10) })), null);
    const ev = { event: "before_send_to_model", eventName: "before_send_to_model", eventPayload: { preparedHistory: history(10) } };
    assert.strictEqual(main.onPromptFinalize(ev), null);
  });

  await test("estimate hook compacts too but does not overwrite stats", async () => {
    const before = (await ToolPkg.ipc.call("lean_ctx.stats")).stats;
    const out = main.onPromptEstimateFinalize(finalizeEvent({ chatId: "other", preparedHistory: history(10) }));
    assert.ok(out && out.preparedHistory);
    assert.strictEqual((await ToolPkg.ipc.call("lean_ctx.stats")).stats.chatId, before.chatId);
  });

  await test("tool prompt slimming is off by default, on via env", async () => {
    const ev = {
      eventName: "before_compose_tool_prompt",
      eventPayload: { availableTools: [{ name: "a", description: big(400), details: "d", notes: "n" }] },
    };
    assert.strictEqual(main.onToolPromptCompose(ev), null);
    env.set("LEAN_CTX_SLIM_TOOLS", "true");
    const out = main.onToolPromptCompose(ev);
    assert.strictEqual(out.availableTools[0].details, "");
    assert.ok(out.availableTools[0].description.length <= 160);
    assert.strictEqual(main.onToolPromptCompose(Object.assign({}, ev, { eventName: "filter_tool_prompt_items" })), null);
    env.delete("LEAN_CTX_SLIM_TOOLS");
  });

  await test("terse prompt appended once, English variant", async () => {
    const out = main.onSystemPromptCompose({ eventName: "after_compose_system_prompt", eventPayload: { systemPrompt: "S", useEnglish: true } });
    assert.ok(out.systemPrompt.includes("[Lean mode]"));
    assert.strictEqual(main.onSystemPromptCompose({ eventName: "after_compose_system_prompt", eventPayload: { systemPrompt: out.systemPrompt } }), null);
  });

  await test("master switch disables every hook", async () => {
    env.set("LEAN_CTX_ENABLED", "false");
    assert.strictEqual(main.onPromptFinalize(finalizeEvent({ preparedHistory: history(10) })), null);
    assert.strictEqual(main.onSystemPromptCompose({ eventName: "after_compose_system_prompt", eventPayload: { systemPrompt: "S" } }), null);
    assert.strictEqual(main.onSummaryGenerate({ eventName: "before_prepare_summary_prompt", eventPayload: { summaryPrompt: "x" } }), null);
    env.delete("LEAN_CTX_ENABLED");
  });

  await test("input menu: create lists toggles, toggles write env", async () => {
    const created = await main.onInputMenuToggle({ eventPayload: { action: "create" } });
    assert.strictEqual(created.toggles.length, 4);
    assert.ok(created.toggles[0].description.includes("上次"));
    await main.onInputMenuToggle({ eventPayload: { action: "toggle", toggleId: "lean_ctx_preset" } });
    assert.strictEqual(env.get("LEAN_CTX_PRESET"), "aggressive");
    await main.onInputMenuToggle({ eventPayload: { action: "toggle", toggleId: "lean_ctx_preset" } });
    assert.strictEqual(env.get("LEAN_CTX_PRESET"), "light");
    await main.onInputMenuToggle({ eventPayload: { action: "toggle", toggleId: "lean_ctx_master" } });
    assert.strictEqual(env.get("LEAN_CTX_ENABLED"), "false");
    env.clear();
  });

  await test("overrides JSON tweaks a preset, bad JSON ignored", async () => {
    const { loadConfig } = require("../lib/config.js");
    env.set("LEAN_CTX_OVERRIDES", '{"keepFloors":7,"bogus":1,"toolResultMax":"x"}');
    const cfg = loadConfig();
    assert.strictEqual(cfg.keepFloors, 7);
    assert.strictEqual(cfg.toolResultMax, 800);
    assert.ok(!("bogus" in cfg));
    env.set("LEAN_CTX_OVERRIDES", "{oops");
    assert.strictEqual(loadConfig().keepFloors, 3);
    env.clear();
  });

  await test("lean_status reports config and last stats", async () => {
    const r = await callTool(tools.lean_status, {});
    assert.ok(r.success);
    assert.ok(r.message.includes("最近一次"));
    assert.ok(r.last);
  });

  await test("set_lean_mode validates and writes", async () => {
    const bad = await callTool(tools.set_lean_mode, { preset: "ultra" });
    assert.strictEqual(bad.success, false);
    const ok = await callTool(tools.set_lean_mode, { preset: "激进", terse: "false" });
    assert.ok(ok.success);
    assert.strictEqual(env.get("LEAN_CTX_PRESET"), "aggressive");
    assert.strictEqual(env.get("LEAN_CTX_TERSE"), "false");
    env.clear();
  });

  await test("clean_trivial_chats previews first, never touches current/recent", async () => {
    const preview = await callTool(tools.clean_trivial_chats, { title_regex: "测试|test" });
    assert.ok(preview.dryRun);
    assert.deepStrictEqual(preview.candidates.map((c) => c.id).sort(), ["t1", "t4"]);
    assert.strictEqual(deletedChats.length, 0);
    const done = await callTool(tools.clean_trivial_chats, { title_regex: "测试|test", confirm: "true" });
    assert.strictEqual(done.deleted, 2);
    assert.deepStrictEqual(deletedChats.sort(), ["t1", "t4"]);
    const bad = await callTool(tools.clean_trivial_chats, { title_regex: "(" });
    assert.strictEqual(bad.success, false);
  });

  await test("handoff_new_chat needs a real brief, then creates and sends", async () => {
    const short = await callTool(tools.handoff_new_chat, { brief: "hi" });
    assert.strictEqual(short.success, false);
    const r = await callTool(tools.handoff_new_chat, { brief: "目标：重构登录模块。已完成：拆分 service。待办：补测试，路径 app/src/login。" });
    assert.ok(r.success);
    assert.strictEqual(r.chatId, "new1");
    assert.ok(r.title.startsWith("接力·正在聊的"));
    assert.ok(sent[0].message.includes("【接力摘要】"));
  });

  await test("tune_auto_summary views by default, applies when asked", async () => {
    const view = await callTool(tools.tune_auto_summary, {});
    assert.ok(view.success && view.current.summaryTokenThreshold === 0.8);
    const bad = await callTool(tools.tune_auto_summary, { apply: true, threshold: 2 });
    assert.strictEqual(bad.success, false);
    const applied = await callTool(tools.tune_auto_summary, { apply: "true", threshold: "0.5" });
    assert.ok(applied.success);
    assert.strictEqual(applied.after.summaryTokenThreshold, 0.5);
  });

  console.log(`\n${passed} hook/tool tests passed`);
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
