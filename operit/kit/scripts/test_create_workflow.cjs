// 用假的 Tools 对象在电脑上跑 create_workflow.js：node test_create_workflow.cjs
const assert = require("node:assert/strict");
const path = require("node:path");

function fakeTools(existing) {
  const store = new Map(existing ? [[existing.id, existing]] : []);
  return {
    store,
    Files: { read: async () => ({ content: "main_chat_id=chat-123\n" }) },
    Workflow: {
      getAll: async () => ({ workflows: [...store.values()] }),
      create: async (name, description, nodes, connections, enabled) => {
        const w = { id: "wf-new", name, description, nodes, connections, enabled };
        store.set(w.id, w);
        return w;
      },
      update: async (id, u) => { const w = { ...store.get(id), ...u }; store.set(id, w); return w; },
      get: async (id) => store.get(id)
    }
  };
}

(async () => {
  global.Tools = fakeTools();
  // 仓库根目录是 ESM 包，这里直接按脚本方式加载，和手机沙盒里一样
  const src = require("node:fs").readFileSync(path.join(__dirname, "create_workflow.js"), "utf8");
  const mod = { exports: {} };
  new Function("module", src)(mod);
  const api = mod.exports;
  let r = await api.run();
  assert.equal(r.success, true); assert.equal(r.action, "created");
  assert.equal(r.node_count, 6); assert.equal(r.connection_count, 5);
  const wf = global.Tools.store.get("wf-new");
  const byId = Object.fromEntries(wf.nodes.map((n) => [n.id, n]));
  assert.equal(byId.c_go.operator, "CONTAINS"); assert.equal(byId.c_go.right, "NEED=go");
  assert.deepEqual(byId.c_go.left, { nodeId: "e_tick" });
  assert.equal(byId.e_ai.actionConfig.chat_id, "chat-123");
  assert.equal(byId.e_ai.actionConfig.character_card_name, "司南");
  assert.equal(byId.e_pop.actionConfig.chat_id, "chat-123");
  assert.equal(byId.t_tick.triggerConfig.interval_ms, "300000");
  console.log("ok   新建：6 节点 5 连线，参数正确");

  global.Tools = fakeTools({ id: "wf-old", name: "司南节拍", nodes: [], connections: [] });
  r = await api.run();
  assert.equal(r.action, "updated"); assert.equal(r.workflow_id, "wf-old"); assert.equal(r.node_count, 6);
  console.log("ok   已存在同名工作流时更新，不重复新建");

  global.Tools = fakeTools(); global.Tools.Files.read = async () => ({ content: "" });
  r = await api.run();
  assert.equal(r.success, false);
  console.log("ok   没有 chat_id 时拒绝建立");
  console.log("全部通过");
})().catch((e) => { console.error("FAIL", e); process.exit(1); });
