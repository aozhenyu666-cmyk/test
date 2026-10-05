"use strict";
/* METADATA
{
  "name": "focus_hub_warden",
  "display_name": {
    "zh": "督促演练",
    "en": "Warden (dry-run)"
  },
  "description": {
    "zh": "第一阶段只演练：按契约和时间判断本来会不会锁，写进演练日志，绝不调用 app_suspender。工作流每 15 分钟调一次 tick。",
    "en": "Phase-1 dry run: decides what WOULD be locked and logs it. Never calls app_suspender."
  },
  "enabled_by_default": false,
  "category": "System",
  "tools": [
    {
      "name": "tick",
      "description": {
        "zh": "推进一次督促判定（演练）。读契约、当前派活、交差和用户进展，算出这一拍处于哪个阶段、本来会锁什么，只写日志不冻结。工作流每 15 分钟调用。",
        "en": "Advance the warden one step in dry-run. Logs what would be locked; never freezes."
      },
      "parameters": []
    },
    {
      "name": "get_warden_status",
      "description": {
        "zh": "只读：当前督促状态、距截止时间、演练本来会锁什么、还没拍板的契约项。判断或提醒前先读。",
        "en": "Read the current warden status (dry-run)."
      },
      "parameters": []
    },
    {
      "name": "record_delivery",
      "description": {
        "zh": "记一次交差：用户对当前被督促任务交出的东西。quote 是用户亲口说的，file_path 可选（产物型会核对文件是否在派活后修改过）。",
        "en": "Record a delivery against the current assignment."
      },
      "parameters": [
        { "name": "quote", "description": { "zh": "用户交的话/说明", "en": "what the user submits" }, "type": "string", "required": true },
        { "name": "file_path", "description": { "zh": "产物文件路径，可选", "en": "product file path, optional" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
Object.defineProperty(exports, "__esModule", { value: true });
exports.tick = tick;
exports.get_warden_status = get_warden_status;
exports.record_delivery = record_delivery;
const warden_js_1 = require("../shared/warden.js");
async function tick() {
    const r = await (0, warden_js_1.tick)();
    return { success: true, result: `${r.note}｜阶段 ${r.stage}｜本来会锁：${r.wouldLock.join("、") || "无"}` };
}
async function get_warden_status() {
    const report = await (0, warden_js_1.collectWarden)();
    return { success: true, status: (0, warden_js_1.wardenReportToText)(report) };
}
async function record_delivery(params) {
    const { delivery, reason } = await (0, warden_js_1.recordDelivery)(params.quote, params.file_path ?? "");
    if (!delivery)
        return { success: false, result: reason ?? "没记上" };
    const verdict = delivery.autoCheck === "PRODUCT_OK"
        ? "产物已核对通过，这次督促收工"
        : delivery.autoCheck === "PRODUCT_MISSING"
            ? "没找到产物或它还是派活前的，先不算数"
            : "已收下，够不够要核对模型看（后续阶段）";
    return { success: true, result: `已记交差：${verdict}` };
}
