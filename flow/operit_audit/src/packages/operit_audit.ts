/* METADATA
{
  "name": "operit_audit",
  "display_name": { "zh": "系统体检", "en": "Operit Audit" },
  "description": {
    "zh": "只读体检：检查工作流开关与调度新鲜度、关键状态文件、判断链和锁队列，再用人造场景自测判断逻辑本身，输出 PASS/WARN/FAIL。不改任何文件、不触发任何工作流。",
    "en": "Read-only health check of workflows, state files, judge chain and lock queue, plus a self-test of the detection logic. Changes nothing."
  },
  "enabled_by_default": true,
  "category": "System",
  "tools": [
    {
      "name": "audit",
      "description": { "zh": "跑一次完整体检，输出每项 PASS/WARN/FAIL 和总评。只读。", "en": "Run a full read-only health check." },
      "parameters": [
        { "name": "self_test", "description": { "zh": "可选：true 时同时跑自测（模拟异常，验证判断逻辑本身没坏）", "en": "Also run the self-test" }, "type": "boolean", "required": false }
      ]
    },
    {
      "name": "self_test",
      "description": { "zh": "只跑自测：用人造的正常/异常场景验证体检的判断逻辑是否还准。只读，不碰真实数据。", "en": "Run only the self-test of the detection logic." },
      "parameters": []
    }
  ]
}
*/

import { auditSummary } from "../shared/audit.js";
import { errorText } from "../shared/fsx.js";
import { renderSelfTest, runSelfTest } from "../shared/selftest.js";

function flag(value: unknown): boolean {
  return value === true || String(value).toLowerCase() === "true";
}

export async function audit(params: { self_test?: unknown }) {
  try {
    const { report, text } = await auditSummary();
    let message = text;
    let selfTest: ReturnType<typeof runSelfTest> | undefined;
    if (flag(params?.self_test)) {
      selfTest = runSelfTest();
      message = `${renderSelfTest(selfTest)}\n\n${text}`;
    }
    return { success: true, overall: report.overall, criticalFail: report.criticalFail, summary: report.summary, selfTest: selfTest?.verdict, message, report };
  } catch (error) {
    return { success: false, message: errorText(error) };
  }
}

export async function self_test() {
  try {
    const r = runSelfTest();
    return { success: true, verdict: r.verdict, message: renderSelfTest(r), report: r };
  } catch (error) {
    return { success: false, message: errorText(error) };
  }
}
