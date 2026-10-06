// 主控台 0.8.0 主机测试：node operit/hub/tests/hub.test.cjs
// 用假的 Tools / UI 跑骰子引擎、司南解析，并把每个页面渲染一遍。
const assert = require("node:assert/strict");
const fs = require("node:fs"), os = require("node:os"), path = require("node:path");

// 仓库根目录是 ESM 包；把 dist 复制到临时目录，按 CommonJS 加载（和手机上一样）
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), "hub-test-"));
fs.cpSync(path.join(__dirname, "..", "dist"), path.join(TMP, "dist"), { recursive: true });
fs.writeFileSync(path.join(TMP, "package.json"), '{"type":"commonjs"}');
const load = (rel) => require(path.join(TMP, "dist", rel));

// ---------- 假宿主 ----------
const files = new Map();
const calls = [];
let chatSeq = 0;
global.Icons = new Proxy({}, { get: (_, k) => String(k) });
global.Tools = {
    Files: {
        read: async (p) => { if (!files.has(p)) throw new Error("not found " + p); return { content: files.get(p) }; },
        write: async (p, c, append) => { files.set(p, (append ? files.get(p) || "" : "") + c); return { success: true }; },
    },
    Chat: {
        listCharacterCards: async () => ({ cards: [{ id: "card-coach", name: "骰子教练" }] }),
        createNew: async (group, setCurrent, cardId) => { chatSeq++; calls.push(["createNew", cardId]); return { chatId: "dice-" + chatSeq }; },
        updateTitle: async (id, t) => calls.push(["title", id, t]),
        deleteChat: async (id) => calls.push(["delete", id]),
        startService: async () => ({}),
        sendMessage: async (msg, id) => { calls.push(["send", id, msg]); return {}; },
        listChats: async () => ({ chats: [] }),
    },
    System: { sendNotification: async (m, t) => calls.push(["notify", t]) },
    Workflow: { getAll: async () => ({ workflows: [] }), create: async () => ({ id: "wf1" }), update: async () => ({}) },
};
const DAY0 = Date.UTC(2026, 9, 6, 16, 0); // 北京 2026-10-07 00:00
const at = (h, m) => DAY0 + (h * 60 + m) * 60000;
const seq = (vals) => { let i = 0; return () => vals[i++ % vals.length]; };

(async () => {
    const dice = load("shared/dice.js");
    const D = "/sdcard/Download/Operit/dice/";

    // 组卡：步骤、降级、元认知、教练提示
    const card = dice.compose(4, { text: "观点：“坚持就是胜利。”" }, { ai_example: true });
    assert.match(card.text, /🎲4·提问/); assert.match(card.text, /卡住了？降级/); assert.match(card.text, /我现在这一步/); assert.match(card.text, /给教练/);
    assert.match(dice.compose(2, { text: "x" }, { fusion: true }).text, /① 我现在在干嘛/);
    console.log("ok   训练卡包含步骤、卡住降级、元认知、教练提示");

    // 用户素材解析
    const pool = dice.parsePool("# 注释\n[1] 第一条\n[预判推演] 第二条\n没写模式\n");
    assert.deepEqual(pool.map((p) => p.mode), [1, 2, 0]);
    console.log("ok   素材库支持“[数字]”“[名字]”和不写模式");

    // 默认关闭；打开后按间隔推送
    assert.equal((await dice.tick(at(9, 0))).reason, "disabled");
    await dice.saveConfig({ enabled: true, interval_min: 10, delivery: "both" });
    let r = await dice.tick(at(9, 0), { rng: seq([0.5]) });
    assert.equal(r.sent, true); assert.deepEqual(r.delivered, ["notify", "chat"]);
    assert.ok(calls.some((c) => c[0] === "createNew" && c[1] === "card-coach"), "用骰子教练角色卡建对话");
    assert.equal((await dice.tick(at(9, 5))).reason, "not_due");
    assert.equal((await dice.tick(at(9, 10), { rng: seq([0.1]) })).sent, true);
    assert.equal((await dice.tick(at(7, 0))).reason, "outside_hours");
    console.log("ok   默认关闭；打开后按间隔推送，时段外不推");

    // 司南工作段里你在做事时让路；休息时照常
    files.set("/sdcard/Download/Operit/core/today_windows.txt", "date=2026-10-07\nw1|09:00|12:00|改简历\n");
    files.set("/sdcard/Download/Operit/core/thread.json", JSON.stringify({ task: "改简历", status: "acting", last_step: "第二段", question: "第三段先写什么" }));
    assert.equal((await dice.tick(at(9, 30))).reason, "yield_to_sinan");
    files.set("/sdcard/Download/Operit/core/thread.json", JSON.stringify({ task: "改简历", status: "resting" }));
    assert.equal((await dice.tick(at(9, 40), { rng: seq([0.3]) })).sent, true);
    console.log("ok   司南工作段里在做事时让路，休息时照常推送");

    // 融合卡：素材取自手头的事
    files.set("/sdcard/Download/Operit/core/thread.json", JSON.stringify({ task: "改简历", status: "resting", last_step: "第二段" }));
    r = await dice.tick(at(14, 0), { force: true, fusion: true, rng: seq([0.2]) });
    assert.equal(r.card.fusion, true); assert.match(r.card.material, /改简历/);
    console.log("ok   融合卡用手头的事做素材，并先问元认知两问");

    // 记录结果与统计
    await dice.record(at(14, 1), "down", "只做了一层");
    const s = await dice.summary(at(14, 2));
    assert.equal(s.card.result, "down"); assert.ok(s.done >= 1);
    assert.match(files.get(D + "log/2026-10-07.jsonl"), /"result":"down"/);
    console.log("ok   记录完成/降级/跳过，写进日志和今日统计");

    // 每 10 张换新对话，删掉骰子自己建的旧对话
    const before = (await dice.loadState(at(15, 0))).chat_id;
    for (let i = 0; i < 12; i++) await dice.tick(at(15, i * 10), { force: true, rng: seq([0.6]) });
    const after = (await dice.loadState(at(17, 0))).chat_id;
    assert.notEqual(before, after);
    assert.ok(calls.some((c) => c[0] === "delete" && c[1] === before), "旧对话被删除");
    console.log("ok   每 10 张卡换新对话（清掉上下文），只删骰子自己建的旧对话");

    // 司南解析
    const { parseAll } = load("shared/sinan-ui.js");
    const p = parseAll({
        thread: JSON.stringify({ task: "改简历", step: 2, status: "acting", question: "第三段先写什么" }),
        windows: "date=2026-10-07\nw1|09:00|10:50|改简历\nw2|11:00|12:00|投递\n",
        today: JSON.stringify({ rules: ["上午只做求职"] }),
        outputs: "| 2026-10-07 | 改简历 | 第二段 | 截图 |\n| 2026-10-06 | x | y | z |\n",
        log: '{"ts":"09:10","who":"user"}\n{"ts":"09:12","who":"ai"}\n{"ts":"09:30","who":"user"}\n',
        profile: "## 使用说明书（司南维护）\n- 早上第一句被点名就能启动\n",
        config: "main_chat_id=chat-9\n",
    }, at(9, 40));
    assert.equal(p.windows[0].now, true); assert.equal(p.outputs, 1); assert.equal(p.replies, 2); assert.equal(p.lastUserTs, "09:30");
    assert.equal(p.rules[0], "上午只做求职"); assert.equal(p.chatId, "chat-9"); assert.match(p.manualLast, /点名/);
    console.log("ok   司南页解析：当前段、规则、三个数、使用说明书");

    // ---------- 渲染每个页面 ----------
    const state = new Map();
    const node = (type) => (props, children) => ({ type, props: props || {}, children: Array.isArray(children) ? children : children ? [children] : [] });
    const UI = new Proxy({}, { get: (_, k) => node(String(k)) });
    const ctx = {
        UI, MaterialTheme: { colorScheme: new Proxy({}, { get: () => "#000000" }) },
        useState: (k, init) => { if (!state.has(k)) state.set(k, init); return [state.get(k), (v) => state.set(k, v)]; },
        useRef: (k, init) => { const key = "ref:" + k; if (!state.has(key)) state.set(key, { current: init }); return state.get(key); },
        navigate: async () => { }, showToast: async () => { },
    };
    const Screen = load("ui/focus_hub/index.ui.js").default;
    const texts = (t, out = []) => { if (!t) return out; if (typeof t.props?.text === "string") out.push(t.props.text); (t.children || []).forEach((c) => texts(c, out)); return out; };
    let tree = Screen(ctx);
    await tree.props.onLoad();
    const pages = ["today", "sinan", "dice", "chats", "sys", "slim", "warden"];
    for (const pg of pages) {
        state.set("page", pg);
        tree = Screen(ctx);
        const all = texts(tree).join(" ");
        assert.ok(all.includes("今天") && all.includes("司南") && all.includes("骰子") && all.includes("设置"), "底栏五项：" + pg);
        if (["sys", "slim", "warden"].includes(pg)) assert.ok(all.includes("系统") && all.includes("省流") && all.includes("督促"), "设置页顶部三个分页");
        if (pg === "dice") assert.ok(all.includes("今天") && all.includes("定时推送"), "骰子页有统计和推送设置");
        if (pg === "sinan") assert.ok(all.includes("改简历") && all.includes("找司南"), "司南页显示当前进度与找她按钮");
    }
    console.log("ok   七个页面都能渲染；底栏为 今天/司南/骰子/会话/设置，系统·省流·督促收进设置");

    fs.rmSync(TMP, { recursive: true, force: true });
    console.log("全部通过");
})().catch((e) => { console.error("FAIL", e); process.exit(1); });
