"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.runSelfTest = runSelfTest;
exports.renderSelfTest = renderSelfTest;
// 自测（模拟异常）：给判断函数喂人造场景，确认它给出预期结论。
// 如果审计的判断逻辑被改坏了（比如永远返回 PASS），这里会 FAIL，暴露"体检工具自己坏了"。
const detect_js_1 = require("./detect.js");
const NOW = 1800000000000;
const MIN = 60 * 1000;
function runSelfTest() {
    const cases = [];
    const check = (name, got, want) => cases.push({ name, got: got.verdict, want });
    // 工作流新鲜度
    check("在节奏内运行 → PASS", (0, detect_js_1.workflowFreshness)(NOW, true, NOW - 10 * MIN, "SUCCESS", 15, 100), "PASS");
    check("超节奏 3 倍没跑 → FAIL", (0, detect_js_1.workflowFreshness)(NOW, true, NOW - 60 * MIN, "SUCCESS", 15, 100), "FAIL");
    check("最近失败且迟到 → FAIL", (0, detect_js_1.workflowFreshness)(NOW, true, NOW - 60 * MIN, "FAILED", 15, 100), "FAIL");
    check("已停用 → WARN", (0, detect_js_1.workflowFreshness)(NOW, false, NOW - 10 * MIN, "SUCCESS", 15, 100), "WARN");
    check("从未执行 → WARN", (0, detect_js_1.workflowFreshness)(NOW, true, null, null, 15, 0), "WARN");
    // 失败率
    check("失败率 60% → FAIL", (0, detect_js_1.failureRate)(100, 60), "FAIL");
    check("失败率 30% → WARN", (0, detect_js_1.failureRate)(100, 30), "WARN");
    check("失败率 5% → PASS", (0, detect_js_1.failureRate)(100, 5), "PASS");
    // 判断链（和真实事故同型：很久没有 SAVED，只剩 GATE_SKIP）
    check("最近有 SAVED → PASS", (0, detect_js_1.judgeHealth)(NOW, [{ ts: NOW - 20 * MIN, kind: "SAVED" }], 30), "PASS");
    check("久无 SAVED 只剩 SKIP → FAIL", (0, detect_js_1.judgeHealth)(NOW, [{ ts: NOW - 300 * MIN, kind: "SAVED" }, { ts: NOW - 10 * MIN, kind: "GATE_SKIP" }], 30), "FAIL");
    check("从没 SAVED → FAIL", (0, detect_js_1.judgeHealth)(NOW, [{ ts: NOW - 10 * MIN, kind: "GATE_SKIP" }], 30), "FAIL");
    check("连续 REJECTED → WARN", (0, detect_js_1.judgeHealth)(NOW, [{ ts: NOW - 40 * MIN, kind: "SAVED" }, { ts: NOW - 10 * MIN, kind: "REJECTED_OUTPUT" }, { ts: NOW - 5 * MIN, kind: "REJECTED_OUTPUT" }], 30), "WARN");
    // 通道配置
    check("合法通道 → PASS", (0, detect_js_1.channelHealth)({ chat_id: "52a18815-2c07-4f6d-bbae-ae5d040daffb", mode: "WINDOW" }), "PASS");
    check("chat_id 缺失 → FAIL", (0, detect_js_1.channelHealth)({ mode: "WINDOW" }), "FAIL");
    check("chat_id 非 UUID → FAIL", (0, detect_js_1.channelHealth)({ chat_id: "abc", mode: "WINDOW" }), "FAIL");
    check("mode 非法 → WARN", (0, detect_js_1.channelHealth)({ chat_id: "52a18815-2c07-4f6d-bbae-ae5d040daffb", mode: "XYZ" }), "WARN");
    // 锁队列
    check("在途 3 分钟 → PASS", (0, detect_js_1.lockQueueHealth)(NOW, { act: "lock", pkg: "x", ts: NOW - 3 * MIN }, 0), "PASS");
    check("在途卡 30 分钟 → FAIL", (0, detect_js_1.lockQueueHealth)(NOW, { act: "lock", pkg: "x", ts: NOW - 30 * MIN }, 0), "FAIL");
    check("队列积压 → WARN", (0, detect_js_1.lockQueueHealth)(NOW, null, 15), "WARN");
    check("队列空 → PASS", (0, detect_js_1.lockQueueHealth)(NOW, null, 0), "PASS");
    const failed = cases.filter((c) => c.got !== c.want);
    return { verdict: failed.length ? "FAIL" : "PASS", passed: cases.length - failed.length, failed: failed.length, cases: failed.length ? failed : cases };
}
function renderSelfTest(r) {
    if (r.verdict === "PASS")
        return `自测 ✅ PASS（${r.passed} 个场景全部符合预期，体检逻辑正常）`;
    const lines = [`自测 ❌ FAIL（${r.failed}/${r.passed + r.failed} 个场景不符预期，体检工具自己坏了）`];
    for (const c of r.cases)
        lines.push(`  ✗ ${c.name}：得到 ${c.got}，应为 ${c.want}`);
    return lines.join("\n");
}
