/* METADATA
{
  "name": "focus_hub_data",
  "display_name": {
    "zh": "主控台数据",
    "en": "Focus Hub Data"
  },
  "description": {
    "zh": "只读汇总当前任务、工作流运行情况、App 使用记录、最近事件和动作审计。不执行任何动作。",
    "en": "Read-only summary of the current task, workflow runs, app usage, recent events and action audit. Performs no actions."
  },
  "enabled_by_default": true,
  "category": "System",
  "tools": [
    {
      "name": "get_dashboard_snapshot",
      "description": {
        "zh": "读取主控台看板的当前快照（只读）。需要了解用户当前任务、工作流是否正常、今天用了哪些 App、最近发生了什么事件时调用。返回的是带数据来源和口径说明的文本。",
        "en": "Read the current Focus Hub dashboard snapshot (read-only): current task, workflow health, app usage, recent events and action audit, with sources."
      },
      "parameters": []
    }
  ]
}
*/

import { collectSnapshot } from "../shared/snapshot.js";
import { snapshotToText } from "../shared/format.js";

export async function get_dashboard_snapshot(): Promise<{ success: boolean; snapshot: string }> {
  const snap = await collectSnapshot();
  return { success: true, snapshot: snapshotToText(snap) };
}
