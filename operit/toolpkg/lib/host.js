'use strict';
// 真机适配层：只用 Operit 官方类型声明里存在的接口（examples/types/*.d.ts）。
// 电脑测试时由 tests 注入一个假的 host，接口形状与这里一致。

function parse(x) {
  if (typeof x !== 'string') return x;
  try { return JSON.parse(x); } catch (e) { return {raw: x}; }
}

function phoneHost() {
  return {
    async callTool(name, params) { return parse(await toolCall(name, params || {})); },
    async startApp(pkg) { return parse(await Tools.System.startApp(pkg)); },
    async openUri(uri) { return parse(await Tools.System.intent({action: 'android.intent.action.VIEW', uri, type: 'activity'})); },
    async notify(text, title) { return parse(await Tools.System.sendNotification(text, title)); },
    async chatSend(chatId, message) {
      return parse(await Tools.Chat.sendMessage(message, chatId, undefined, undefined,
        {persist_turn: true, notify_reply: true, hide_user_message: true}));
    },
    async startVoice(chatId) {
      const switched = chatId ? parse(await Tools.Chat.switchTo(chatId)) : null;
      const started = parse(await Tools.Chat.startService({initial_mode: 'VOICE_BALL', auto_enter_voice_chat: true, keep_if_exists: true}));
      return {switched, started};
    },
    async workflowCreate(name, description, nodes, connections, enabled) {
      return parse(await Tools.Workflow.create(name, description, nodes, connections, enabled));
    },
    async workflowUpdate(id, updates) { return parse(await Tools.Workflow.update(id, updates)); }
  };
}

module.exports = {phoneHost, parse};
