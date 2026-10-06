// 建立（或更新）“司南节拍”工作流。按 Operit 官方 Tools.Workflow 接口编写。
// 运行方式：operit_editor:debug_run_sandbox_script，source_path 指向本文件。
// 依赖：/sdcard/Download/Operit/core/config.txt 中已有 main_chat_id=<司南对话ID>。
const CORE = "/sdcard/Download/Operit/core/";
const NAME = "司南节拍";

function buildGraph(chatId) {
  const nodes = [
    { id: "t_tick", type: "trigger", name: "每5分钟", triggerType: "schedule",
      triggerConfig: { enabled: "true", repeat: "true", schedule_type: "interval", interval_ms: "300000" },
      position: { x: 100, y: 80 } },
    { id: "t_manual", type: "trigger", name: "手动", triggerType: "manual",
      triggerConfig: { enabled: "true" }, position: { x: 100, y: 260 } },
    { id: "e_tick", type: "execute", name: "节拍脚本", actionType: "super_admin:terminal",
      actionConfig: { command: "sh " + CORE + "scripts/tick.sh", timeoutMs: "30000" },
      position: { x: 360, y: 170 } },
    { id: "c_go", type: "condition", name: "有事才叫醒",
      left: { nodeId: "e_tick" }, operator: "CONTAINS", right: "NEED=go",
      position: { x: 620, y: 170 } },
    { id: "e_ai", type: "execute", name: "叫醒司南", actionType: "extended_chat:chat_with_agent",
      actionConfig: {
        message: "节拍：有新事件，请读 " + CORE + "tick_last.txt 并按你的规则处理。",
        character_card_name: "司南",
        chat_id: chatId,
        timeout: "180",
        notify_reply: "true",
        hide_user_message: "true"
      },
      position: { x: 880, y: 170 } },
    { id: "e_pop", type: "execute", name: "弹出司南对话", actionType: "focus_hub_nav:open_chat",
      actionConfig: { chat_id: chatId }, position: { x: 1140, y: 170 } }
  ];
  const connections = [
    { sourceNodeId: "t_tick", targetNodeId: "e_tick" },
    { sourceNodeId: "t_manual", targetNodeId: "e_tick" },
    { sourceNodeId: "e_tick", targetNodeId: "c_go", condition: "on_success" },
    { sourceNodeId: "c_go", targetNodeId: "e_ai", condition: "true" },
    { sourceNodeId: "e_ai", targetNodeId: "e_pop", condition: "on_success" }
  ];
  return { nodes, connections };
}

async function run() {
  const cfg = await Tools.Files.read(CORE + "config.txt");
  const m = /main_chat_id=([^\s]+)/.exec(String(cfg.content || ""));
  if (!m) return { success: false, message: "core/config.txt 里没有 main_chat_id，请先完成命令 1B" };
  const { nodes, connections } = buildGraph(m[1]);
  const desc = "每5分钟跑 core/scripts/tick.sh；输出含 NEED=go 时叫醒司南并弹出司南对话。";
  const all = await Tools.Workflow.getAll();
  const old = (all.workflows || []).find((w) => w.name === NAME);
  const saved = old
    ? await Tools.Workflow.update(old.id, { description: desc, nodes, connections, enabled: true })
    : await Tools.Workflow.create(NAME, desc, nodes, connections, true);
  const id = (saved && saved.id) || (old && old.id);
  const back = await Tools.Workflow.get(id);
  return {
    success: true,
    action: old ? "updated" : "created",
    workflow_id: id,
    node_count: (back.nodes || []).length,
    connection_count: (back.connections || []).length
  };
}

if (typeof module !== "undefined" && module.exports) module.exports = { buildGraph, run };
if (typeof complete === "function") {
  run().then(complete).catch((e) => complete({ success: false, message: String((e && e.message) || e) }));
}
