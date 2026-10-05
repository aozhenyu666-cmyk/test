'use strict';
// 测试用的假手机：记录所有调用；cognitive_core 用其 v0.2.0 的真实状态机（内存存储）。
const {makeService} = require('../toolpkg/lib/service.js');
const {memoryStore} = require('../toolpkg/lib/store.js');
const CC = require('./vendor/cognitive-continuity-0.2.0/runtime.js');

function ccRuntime(clock) {
  let doc = CC.fresh();
  const store = {read: () => JSON.parse(JSON.stringify(doc)), transact(fn) { const d = JSON.parse(JSON.stringify(doc)); const r = fn(d); doc = d; return r; }};
  return CC.makeRuntime(store, {}, clock);
}

function fakeHost({clock, withThread = true, routes = {}} = {}) {
  const calls = [];
  const cc = withThread ? ccRuntime(clock) : null;
  const host = {
    calls, cc,
    async callTool(name, params) {
      calls.push({fn: 'callTool', name, params});
      if (name === 'cognitive_core:status') {
        if (!cc) throw new Error('Tool not found: cognitive_core:status');
        return {success: true, data: {state: cc.read()}};
      }
      if (name === 'cognitive_core:apply') {
        if (!cc) throw new Error('Tool not found');
        try { return {success: true, data: {result: cc.event(JSON.parse(params.event_json))}}; }
        catch (e) { return {success: false, message: e.message, data: {code: e.code}}; }
      }
      if (routes[name]) return routes[name](params);
      throw new Error('Tool not found: ' + name);
    },
    async startApp(pkg) { calls.push({fn: 'startApp', pkg}); return {success: true, packageName: pkg}; },
    async openUri(uri) { calls.push({fn: 'openUri', uri}); return {success: true}; },
    async notify(text, title) { calls.push({fn: 'notify', text, title}); return 'ok'; },
    async chatSend(chatId, message) { calls.push({fn: 'chatSend', chatId, message}); return {success: true}; },
    async startVoice(chatId) { calls.push({fn: 'startVoice', chatId}); return {success: true}; },
    files: {},
    chats: [{id: '52a18815-xiaoman', title: '小满'}, {id: 'core-chat-1', title: '核心对话'}],
    current_chat: null,
    async readFile(path) {
      calls.push({fn: 'readFile', path});
      if (!(path in host.files)) throw new Error('File or directory does not exist: ' + path);
      return {path, content: host.files[path]};
    },
    async chatSwitch(chatId) { calls.push({fn: 'chatSwitch', chatId}); host.current_chat = chatId; return {success: true, chatId}; },
    async findChat(query) {
      calls.push({fn: 'findChat', query});
      const c = host.chats.find(x => x.title.includes(query));
      if (!c) throw new Error('Chat not found by query: ' + query);
      return {chat: c};
    },
    async workflowCreate(name, description, nodes, connections, enabled) {
      calls.push({fn: 'workflowCreate', name, nodes, connections, enabled}); return {id: 'wf-' + calls.length, name, enabled};
    },
    async workflowUpdate(id, updates) { calls.push({fn: 'workflowUpdate', id, updates}); return {id, ...updates}; }
  };
  return host;
}

function setup(opts = {}) {
  let now = opts.start || Date.UTC(2026, 9, 5, 2, 0, 0); // 北京时间 10:00
  const clock = () => now;
  const host = fakeHost({clock, ...opts});
  const store = memoryStore();
  const svc = makeService({store, host, clock});
  return {svc, host, store, clock, advance: ms => { now += ms; }, at: () => now};
}

module.exports = {setup, fakeHost};
