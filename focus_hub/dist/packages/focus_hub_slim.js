"use strict";
/* METADATA
{
  "name": "focus_hub_slim",
  "display_name": {
    "zh": "省流报告",
    "en": "Context Diet Report"
  },
  "description": {
    "zh": "只读：哪些对话、工具包、角色卡最耗上下文。",
    "en": "Read-only: which chats, packages and role cards cost the most context."
  },
  "enabled_by_default": false,
  "category": "System",
  "tools": [
    {
      "name": "get_slim_report",
      "description": {
        "zh": "读取省流报告（只读）：会话 token 账单、每轮都带的工具包清单、角色卡工具权限、自动总结设置。scan=true 时额外扫描 Operit 目录里没被工作流用到的旧文件，并写一份候选清单。",
        "en": "Read the context-cost report. scan=true also lists stale files not referenced by enabled workflows."
      },
      "parameters": [
        { "name": "scan", "description": { "zh": "是否扫描冗余文件，默认 false", "en": "Also scan for redundant files (default false)" }, "type": "boolean", "required": false }
      ]
    }
  ]
}
*/
Object.defineProperty(exports, "__esModule", { value: true });
exports.get_slim_report = get_slim_report;
const slim_js_1 = require("../shared/slim.js");
async function get_slim_report(params = {}) {
    const report = await (0, slim_js_1.collectSlimReport)();
    let text = (0, slim_js_1.slimReportToText)(report);
    if (params.scan === true || params.scan === "true") {
        const scan = await (0, slim_js_1.scanRedundancy)();
        text += `\n冗余候选 ${scan.candidates.length} 个（清单写在 ${scan.reportPath}）：${scan.candidates.slice(0, 12).map((c) => c.name).join("、") || "无"}`;
        if (scan.workflows.length)
            text += `\n可以考虑停用的工作流：${scan.workflows.map((w) => `${w.name}（${w.why}）`).join("；")}`;
    }
    return { success: true, report: text };
}
