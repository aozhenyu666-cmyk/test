"use strict";
// 主控台“骰子”页：画骰子、显示训练卡、记录结果、看统计、改推送设置。
Object.defineProperty(exports, "__esModule", { value: true });
exports.use = use;
exports.PIPS = void 0;
const dice = require("./dice.js");
const nav_js_1 = require("./nav.js");

// 3×3 格子里哪些位置有点
exports.PIPS = { 1: [4], 2: [0, 8], 3: [0, 4, 8], 4: [0, 2, 6, 8], 5: [0, 2, 4, 6, 8], 6: [0, 2, 3, 5, 6, 8] };
const WORKFLOW_NAME = "骰子节拍";

function use(ctx, kit) {
    const { UI } = ctx;
    const colors = kit.colors;
    const [sum, setSum] = ctx.useState("dice.sum", null);
    const [cfg, setCfg] = ctx.useState("dice.cfg", null);
    const [busy, setBusy] = ctx.useState("dice.busy", "");
    const [note, setNote] = ctx.useState("dice.note", null);
    const [showDown, setShowDown] = ctx.useState("dice.showDown", false);
    const [showSet, setShowSet] = ctx.useState("dice.showSet", false);
    const [reply, setReply] = ctx.useState("dice.reply", "");
    const [wfId, setWfId] = ctx.useState("dice.wf", "");
    const [poolCount, setPoolCount] = ctx.useState("dice.pool", 0);

    async function refresh() {
        try {
            const now = Date.now();
            const [s, c] = await Promise.all([dice.summary(now), dice.loadConfig()]);
            setSum(s); setCfg(c);
            try { const r = await Tools.Files.read(dice.PATHS.pool); setPoolCount(dice.parsePool(r.content).length); } catch (e) { setPoolCount(0); }
            try { const all = await Tools.Workflow.getAll(); const w = (all.workflows || []).find((x) => x.name === WORKFLOW_NAME); setWfId(w ? w.id : ""); } catch (e) { }
        } catch (e) { setNote({ ok: false, text: "读取失败：" + String((e && e.message) || e) }); }
    }
    async function run(key, fn, okText) {
        if (busy) return;
        setBusy(key);
        try { await fn(); if (okText) setNote({ ok: true, text: okText }); await refresh(); }
        catch (e) { setNote({ ok: false, text: String((e && e.message) || e) }); }
        finally { setBusy(""); }
    }
    const rollNow = (mode, fusion) => run("roll", () => dice.tick(Date.now(), { force: true, forceMode: mode || 0, fusion: !!fusion }), "已掷出并推送");
    const mark = (result) => run(result, () => dice.record(Date.now(), result, reply).then(() => setReply("")), result === "skip" ? "已记为跳过" : "已记下，回响时会用到");
    const setOpt = (patch) => run("cfg", () => dice.saveConfig(patch), "设置已保存");
    async function openChat() {
        if (!sum || !sum.chat_id) { setNote({ ok: false, text: "还没有骰子对话：先掷一次（推送方式含“对话”）" }); return; }
        try { await nav_js_1.setMainChat(sum.chat_id); await ctx.navigate(nav_js_1.NATIVE_CHAT_ROUTE); } catch (e) { setNote({ ok: false, text: "打开失败：" + String(e.message || e) }); }
    }
    async function createWorkflow() {
        await run("wf", async () => {
            const nodes = [
                { id: "t_tick", type: "trigger", name: "每5分钟", triggerType: "schedule", triggerConfig: { enabled: "true", repeat: "true", schedule_type: "interval", interval_ms: "300000" }, position: { x: 100, y: 80 } },
                { id: "t_manual", type: "trigger", name: "手动", triggerType: "manual", triggerConfig: { enabled: "true" }, position: { x: 100, y: 260 } },
                { id: "e_tick", type: "execute", name: "掷骰节拍", actionType: "focus_hub_dice:tick", actionConfig: {}, position: { x: 380, y: 170 } },
            ];
            const conns = [{ sourceNodeId: "t_tick", targetNodeId: "e_tick" }, { sourceNodeId: "t_manual", targetNodeId: "e_tick" }];
            if (wfId) await Tools.Workflow.update(wfId, { nodes, connections: conns, enabled: true });
            else await Tools.Workflow.create(WORKFLOW_NAME, "每5分钟检查一次；按骰子页的间隔与时段掷骰推送。", nodes, conns, true);
        }, wfId ? "骰子节拍已更新" : "骰子节拍已建立");
    }

    // ---------- 画骰子 ----------
    function face(mode, size) {
        const M = dice.MODES[mode] || dice.MODES[1];
        const pip = Math.round(size / 6.5);
        const cells = [0, 1, 2, 3, 4, 5, 6, 7, 8].map((i) => (exports.PIPS[mode] || []).includes(i)
            ? UI.Box({ width: pip, height: pip, background: M.color, backgroundShape: { type: "circle" } })
            : UI.Box({ width: pip, height: pip }));
        const row = (a) => UI.Row({ fillMaxWidth: true, horizontalArrangement: "spaceEvenly" }, a);
        return UI.Card({ width: size, height: size, containerColor: "#FFFFFF", shape: { cornerRadius: Math.round(size / 4.5) }, border: { width: 2, color: M.color }, elevation: 0 },
            UI.Column({ fillMaxSize: true, padding: Math.round(size / 9), verticalArrangement: "spaceEvenly" }, [row(cells.slice(0, 3)), row(cells.slice(3, 6)), row(cells.slice(6, 9))]));
    }
    function chip(textValue, on, onClick, color) {
        return UI.Surface({ containerColor: on ? (color || colors.primary) : colors.surfaceVariant, shape: { type: "pill" }, onClick },
            kit.text(textValue, "labelMedium", on ? "#FFFFFF" : colors.onSurface, { paddingHorizontal: 12, paddingVertical: 6 }));
    }

    // ---------- 卡片 ----------
    function heroCard() {
        const c = sum && sum.card;
        if (!c) {
            return kit.card([
                UI.Row({ fillMaxWidth: true, spacing: 14, verticalAlignment: "center" }, [face(5, 72), UI.Column({ weight: 1, spacing: 4 }, [
                    kit.text("还没有掷过", "titleLarge", colors.onSurface), kit.muted("掷一次，或在下面打开定时推送"),
                ])]),
                UI.Button({ fillMaxWidth: true, text: busy === "roll" ? "掷骰中…" : "🎲 掷一次", enabled: !busy, onClick: () => rollNow(0, false) }),
            ]);
        }
        const M = dice.MODES[c.mode];
        const done = !!c.result;
        return kit.card([
            UI.Row({ fillMaxWidth: true, spacing: 14, verticalAlignment: "center" }, [
                face(c.mode, 84),
                UI.Column({ weight: 1, spacing: 4 }, [
                    kit.text(`${M.icon} ${M.name}`, "titleLarge", M.color),
                    kit.muted(M.one + (c.fusion ? " · 融合卡" : "") + ` · ${c.at || ""}`),
                    ...(done ? [kit.pill(c.result === "done" ? "已完成" : c.result === "down" ? "降级完成" : "已跳过", M.tint, M.color)] : []),
                ]),
            ]),
            UI.Surface({ fillMaxWidth: true, containerColor: M.tint, shape: { cornerRadius: 14 } },
                UI.Column({ fillMaxWidth: true, padding: 12, spacing: 4 }, [kit.label("素材"), kit.text(c.material, "bodyLarge", colors.onSurface)])),
            kit.text(c.fusion ? "先回答：① 我现在在干嘛？② 下一个动作是什么？" : "先问自己：我现在这一步，要弄清什么或做出什么？", "bodyMedium", colors.onSurface),
            ...c.steps.map((s, i) => UI.Row({ fillMaxWidth: true, spacing: 8 }, [
                kit.text(String(i + 1), "labelLarge", M.color), kit.text(s, "bodyMedium", colors.onSurface, { weight: 1 }),
            ])),
            UI.TextButton({ onClick: () => setShowDown(!showDown) }, kit.text(showDown ? "收起降级" : "卡住了？看降级路径", "labelLarge", M.color)),
            ...(showDown ? [kit.muted(c.down)] : []),
            UI.TextField({ fillMaxWidth: true, value: reply, onValueChange: setReply, placeholder: "写一句你的回答（可不写，记录用）" }),
            UI.Row({ fillMaxWidth: true, spacing: 8 }, [
                UI.Button({ weight: 1, text: busy === "done" ? "记录中…" : "✓ 完成", enabled: !busy && !done, onClick: () => mark("done") }),
                UI.FilledTonalButton({ weight: 1, enabled: !busy && !done, onClick: () => mark("down") }, kit.text("↓ 降级完成", "labelLarge", colors.onSurface)),
                UI.TextButton({ enabled: !busy && !done, onClick: () => mark("skip") }, kit.text("跳过", "labelLarge", colors.onSurfaceVariant)),
            ]),
            UI.Row({ fillMaxWidth: true, spacing: 8 }, [
                UI.FilledTonalButton({ weight: 1, enabled: !busy, onClick: () => rollNow(0, false) }, kit.text(busy === "roll" ? "掷骰中…" : "🎲 再掷一次", "labelLarge", colors.onSurface)),
                UI.FilledTonalButton({ weight: 1, enabled: !busy, onClick: () => rollNow(0, true) }, kit.text("🧭 用在手头的事", "labelLarge", colors.onSurface)),
                UI.FilledTonalButton({ weight: 1, enabled: !!(sum && sum.chat_id), onClick: openChat }, kit.text("💬 去对话答", "labelLarge", colors.onSurface)),
            ]),
            ...(note ? [kit.text(note.text, "bodySmall", note.ok ? colors.primary : colors.error)] : []),
        ]);
    }
    function statsCard() {
        const rows = (sum && sum.rows) || [];
        const tile = (r) => {
            const M = dice.MODES[r.mode];
            const fin = r.done + r.down;
            const ratio = r.sent > 0 ? Math.min(fin / r.sent, 1) : 0;
            return UI.Surface({ weight: 1, containerColor: M.tint, shape: { cornerRadius: 14 }, onClick: () => rollNow(r.mode, false) },
                UI.Column({ fillMaxWidth: true, padding: 10, spacing: 4 }, [
                    kit.text(`${M.icon} ${r.mode}`, "labelLarge", M.color),
                    kit.text(M.name, "labelSmall", colors.onSurface, { maxLines: 1 }),
                    kit.text(`${fin}/${r.sent}`, "titleMedium", colors.onSurface),
                    UI.Row({ fillMaxWidth: true }, [
                        UI.Box({ weight: Math.max(ratio, 0.001), height: 5, background: M.color, backgroundShape: { type: "pill" } }),
                        UI.Box({ weight: Math.max(1 - ratio, 0.001), height: 5 }),
                    ]),
                ]));
        };
        return kit.card([
            UI.Row({ fillMaxWidth: true, verticalAlignment: "center" }, [
                kit.label("今天"), UI.Box({ weight: 1 }),
                kit.text(`推送 ${sum ? sum.sent : 0} · 完成 ${sum ? sum.done : 0}`, "labelMedium", colors.onSurfaceVariant),
            ]),
            UI.Row({ fillMaxWidth: true, spacing: 6 }, rows.slice(0, 3).map(tile)),
            UI.Row({ fillMaxWidth: true, spacing: 6 }, rows.slice(3, 6).map(tile)),
            kit.muted("点任意一面，立刻用这一面练一次。降级完成也算完成：降级是策略，不是失败。"),
        ]);
    }
    function settingsCard() {
        if (!cfg) return null;
        const on = (v) => (v ? "开" : "关");
        return kit.card([
            UI.Row({ fillMaxWidth: true, verticalAlignment: "center" }, [
                kit.label("定时推送"), UI.Box({ weight: 1 }),
                chip(cfg.enabled ? "已开启" : "已关闭", cfg.enabled, () => setOpt({ enabled: !cfg.enabled }), "#2E9E8F"),
            ]),
            kit.muted(`${cfg.active_start}–${cfg.active_end} 之间，每 ${cfg.interval_min} 分钟一张。${cfg.yield_to_sinan ? "司南工作段里你在做事时自动让路。" : ""}`),
            UI.TextButton({ onClick: () => setShowSet(!showSet) }, kit.text(showSet ? "收起设置" : "调整设置", "labelLarge", colors.primary)),
            ...(showSet ? [
                kit.label("间隔"),
                UI.Row({ spacing: 6 }, [5, 10, 15, 30].map((m) => chip(m + " 分钟", cfg.interval_min === m, () => setOpt({ interval_min: m })))),
                kit.label("推送到"),
                UI.Row({ spacing: 6 }, [["both", "对话+通知"], ["chat", "只对话"], ["notify", "只通知"]].map(([k, l]) => chip(l, cfg.delivery === k, () => setOpt({ delivery: k })))),
                UI.Row({ spacing: 6 }, [
                    chip("AI 示范 " + on(cfg.ai_example), cfg.ai_example, () => setOpt({ ai_example: !cfg.ai_example })),
                    chip("让路司南 " + on(cfg.yield_to_sinan), cfg.yield_to_sinan, () => setOpt({ yield_to_sinan: !cfg.yield_to_sinan })),
                ]),
                kit.muted(`对话里每 ${cfg.rotate_after} 张卡换一个新对话，旧的${cfg.delete_old_chats ? "自动删除" : "保留"}（只动骰子自己建的对话）；每 ${cfg.fusion_every} 张有一张融合卡。`),
                UI.Button({ fillMaxWidth: true, text: busy === "wf" ? "处理中…" : wfId ? "重建「骰子节拍」工作流" : "建立「骰子节拍」工作流", enabled: !busy, onClick: createWorkflow }),
                kit.muted(wfId ? "工作流已存在：每 5 分钟检查一次，按上面的间隔决定是否掷骰。" : "还没有工作流：不建立的话只能手动掷。"),
            ] : []),
        ]);
    }
    function materialCard() {
        return kit.card([
            kit.label("素材"),
            kit.text(`内置 72 条 · 我的 ${poolCount} 条`, "titleMedium", colors.onSurface),
            kit.muted("我的素材放在 " + dice.PATHS.pool + "，每行一条，写成“[模式] 内容”，模式写 1-6 或名字；不写模式的话，哪一面都可以用。也可以让任何 AI 按这个格式批量生成，再粘贴进去。"),
            kit.muted("融合卡会把你手头的事（司南的当前进度）拿来当素材。"),
        ]);
    }
    function page() {
        const items = [UI.Row({ fillMaxWidth: true, paddingStart: 4, paddingTop: 8, paddingBottom: 4, verticalAlignment: "center" }, [
            kit.text("骰子", "headlineSmall", colors.onSurface, { weight: 1 }),
            UI.IconButton({ icon: Icons.Refresh, onClick: refresh }),
        ])];
        if (!sum) items.push(kit.spinner());
        else items.push(heroCard(), statsCard(), settingsCard(), materialCard());
        return kit.pageColumn(items.filter(Boolean));
    }
    function miniCard() {
        const c = sum && sum.card;
        const M = c ? dice.MODES[c.mode] : null;
        return kit.card([
            UI.Row({ fillMaxWidth: true, spacing: 12, verticalAlignment: "center" }, [
                face(c ? c.mode : 5, 48),
                UI.Column({ weight: 1, spacing: 2 }, [
                    kit.text(c ? `${M.name}${c.result ? " · 已处理" : ""}` : "六骰子思维训练", "titleMedium", M ? M.color : colors.onSurface),
                    kit.muted(c ? c.material : "掷一次，练一面", 2),
                ]),
                UI.FilledTonalButton({ enabled: !busy, onClick: () => rollNow(0, false) }, kit.text("🎲", "titleMedium", colors.onSurface)),
            ]),
        ]);
    }
    return { refresh, page, miniCard };
}
