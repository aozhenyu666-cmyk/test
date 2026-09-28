"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.CLASS_LABEL = exports.LONG_MIN_TOKENS = exports.LONG_MIN_MESSAGES = exports.SHORT_MAX_MESSAGES = void 0;
exports.titleKey = titleKey;
exports.proposeTitle = proposeTitle;
exports.scanChats = scanChats;
exports.reportToMarkdown = reportToMarkdown;
exports.deleteShortChats = deleteShortChats;
exports.applyRenames = applyRenames;
exports.lastRenameBatch = lastRenameBatch;
exports.undoLastRenames = undoLastRenames;
exports.distillPrompt = distillPrompt;
exports.writeDistillPrompt = writeDistillPrompt;
// 对话整理：用 list_chats 返回的元数据盘点所有对话，不读内容、不花模型 token。
// 能自动做的只有两件，而且都要点两次确认：
// - 删除空对话（0–2 条），删之前把内容备份到 slim/deleted_chats.jsonl
// - 按"[类别] 原标题"改名，记下旧标题，可以整批撤销
// 长对话的提炼需要读内容，交给 Operit AI：这里只生成一份现成的指令文件。
const nav_js_1 = require("./nav.js");
const companion_js_1 = require("./companion.js");
const snapshot_js_1 = require("./snapshot.js");
const slim_js_1 = require("./slim.js");
const DELETED_LOG = `${slim_js_1.SLIM_DIR}/deleted_chats.jsonl`;
const RENAME_LOG = `${slim_js_1.SLIM_DIR}/renames.jsonl`;
exports.SHORT_MAX_MESSAGES = 2;
exports.LONG_MIN_MESSAGES = 100;
exports.LONG_MIN_TOKENS = 1000000;
exports.CLASS_LABEL = {
    A: "空的或 1–2 句",
    B: "标题重复",
    C: "超长",
    D: "正常",
};
function dayKey(ms) {
    const d = new Date(ms);
    return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, "0")}${String(d.getDate()).padStart(2, "0")}`;
}
function errorText(error) {
    if (error && typeof error === "object" && "message" in error) {
        return String(error.message);
    }
    return String(error);
}
// list_chats 单次最多 200 个、没有翻页参数；按三种排序各取一次再去重，最多能盖到约 600 个
async function listEverything() {
    const seen = new Map();
    let totalCount = 0;
    const orders = [
        ["messageCount", "desc"],
        ["messageCount", "asc"],
        ["updatedAt", "desc"],
    ];
    for (const [sortBy, order] of orders) {
        const result = await Tools.Chat.listChats({ sort_by: sortBy, sort_order: order, limit: 200 });
        totalCount = Math.max(totalCount, Number(result.totalCount) || 0);
        for (const c of result.chats ?? []) {
            if (seen.has(c.id))
                continue;
            seen.set(c.id, {
                id: c.id,
                title: String(c.title ?? "").trim() || "（无标题）",
                messageCount: c.messageCount ?? 0,
                updatedAt: String(c.updatedAt ?? ""),
                isCurrent: Boolean(c.isCurrent),
                characterCardName: String(c.characterCardName ?? ""),
                inputTokens: Number(c.inputTokens) || 0,
                outputTokens: Number(c.outputTokens) || 0,
            });
        }
        // 一次就拿全了，后面两次不用再查
        if (totalCount > 0 && seen.size >= totalCount)
            break;
    }
    return { chats: [...seen.values()], totalCount: Math.max(totalCount, seen.size) };
}
// 标题归一：去掉数字、日期、标点和"新对话"之类的套话，用来找重复
function titleKey(title) {
    return title
        .toLowerCase()
        .replace(/新(的)?对话|new chat|untitled|（无标题）/g, "")
        .replace(/[0-9０-９]+/g, "")
        .replace(/[\s\-_:：,，.。!！?？()（）\[\]【】「」"'“”#·/\\|]+/g, "")
        .trim();
}
const TEST_PATTERN = /测试|试试|试一下|test|probe|探针|demo|调试|debug/i;
function categoryFor(c, herId) {
    if (c.id === herId)
        return "她";
    if (c.guard.startsWith("工作流"))
        return "工作流";
    if ((0, nav_js_1.isPinned)(c))
        return "角色";
    if (TEST_PATTERN.test(c.title))
        return "测试";
    if (c.cls === "C")
        return "长对话";
    return "日常";
}
function proposeTitle(c, herId) {
    if (c.cls === "A")
        return null; // 空对话是删除候选，不改名
    if (/^\s*[\[【]/.test(c.title))
        return null; // 已经有类别前缀
    const cat = categoryFor(c, herId);
    if (!cat)
        return null;
    const topic = c.title.length > 24 ? `${c.title.slice(0, 24)}…` : c.title;
    return `[${cat}] ${topic}`;
}
async function scanChats(now = Date.now()) {
    const { chats, totalCount } = await listEverything();
    const companion = await (0, companion_js_1.findCompanion)(chats).catch(() => null);
    const herId = companion?.chat?.id ?? "";
    // 被启用中的工作流写死 chat_id 的对话不能删
    const usedByFlow = new Map();
    try {
        for (const f of await (0, slim_js_1.workflowTexts)()) {
            if (!f.enabled)
                continue;
            for (const c of chats)
                if (!usedByFlow.has(c.id) && f.text.includes(c.id))
                    usedByFlow.set(c.id, f.name);
        }
    }
    catch {
        // 读不到工作流就只靠标题和"她"来保护
    }
    const groups = new Map();
    const rows = chats.map((c) => {
        const guard = c.id === herId
            ? "她的对话"
            : usedByFlow.has(c.id)
                ? `工作流在用：${usedByFlow.get(c.id)}`
                : (0, nav_js_1.isPinned)(c)
                    ? "后台角色"
                    : c.isCurrent
                        ? "当前对话"
                        : "";
        const tokens = c.inputTokens + c.outputTokens;
        let cls = "D";
        if (c.messageCount <= exports.SHORT_MAX_MESSAGES)
            cls = "A";
        else if (c.messageCount > exports.LONG_MIN_MESSAGES || c.inputTokens > exports.LONG_MIN_TOKENS)
            cls = "C";
        return { ...c, cls, guard, tokens, proposedTitle: null };
    });
    for (const r of rows) {
        const key = titleKey(r.title);
        if (!key)
            continue;
        const list = groups.get(key) ?? [];
        list.push(r);
        groups.set(key, list);
    }
    const duplicates = [];
    for (const [key, list] of groups) {
        if (list.length < 2)
            continue;
        duplicates.push({ key, titles: list.map((x) => x.title), ids: list.map((x) => x.id) });
        for (const r of list)
            if (r.cls === "D")
                r.cls = "B";
    }
    duplicates.sort((a, b) => b.ids.length - a.ids.length);
    for (const r of rows)
        r.proposedTitle = proposeTitle(r, herId);
    rows.sort((a, b) => b.messageCount - a.messageCount);
    const counts = { A: 0, B: 0, C: 0, D: 0 };
    for (const r of rows)
        counts[r.cls] += 1;
    const totalTokens = rows.reduce((a, r) => a + r.tokens, 0);
    const topTokens = rows
        .slice()
        .sort((a, b) => b.tokens - a.tokens)
        .slice(0, 5)
        .map((r) => ({ title: r.title, tokens: r.tokens, share: totalTokens > 0 ? r.tokens / totalTokens : 0 }));
    const reportPath = `${slim_js_1.SLIM_DIR}/chats_${dayKey(now)}.md`;
    const report = {
        scannedAt: now,
        totalCount,
        listed: rows.length,
        complete: rows.length >= totalCount,
        chats: rows,
        counts,
        duplicates,
        totalTokens,
        topTokens,
        reportPath,
    };
    try {
        await Tools.Files.write(reportPath, reportToMarkdown(report), false);
    }
    catch {
        // 写不了只影响文件，页面照常显示
    }
    return report;
}
function tokenText(n) {
    if (n >= 1e8)
        return `${(n / 1e8).toFixed(1)}亿`;
    if (n >= 1e4)
        return `${(n / 1e4).toFixed(n >= 1e6 ? 0 : 1)}万`;
    return String(n);
}
function updatedText(value) {
    const t = /^\d+$/.test(value) ? Number(value) : Date.parse(value);
    return Number.isFinite(t) && t > 0 ? (0, snapshot_js_1.formatDateTime)(t) : value || "—";
}
function reportToMarkdown(r) {
    const lines = [
        `# 对话盘点（${(0, snapshot_js_1.formatDateTime)(r.scannedAt)}，只读 list_chats，未读内容）`,
        "",
        `共 ${r.totalCount} 个对话，本次列出 ${r.listed} 个${r.complete ? "" : "（超过单次可列上限，有遗漏）"}。`,
        `A ${exports.CLASS_LABEL.A} ${r.counts.A} · B ${exports.CLASS_LABEL.B} ${r.counts.B} · C ${exports.CLASS_LABEL.C} ${r.counts.C} · D ${exports.CLASS_LABEL.D} ${r.counts.D}`,
        "",
        "## token 花在哪",
        ...r.topTokens.map((t) => `- ${t.title}：${tokenText(t.tokens)}（${Math.round(t.share * 100)}%）`),
        "",
        "## 全部对话",
        "",
        "| 类 | 标题 | 条数 | 输入/输出 token | 最后更新 | 角色卡 | 保护 |",
        "|---|---|---|---|---|---|---|",
        ...r.chats.map((c) => `| ${c.cls} | ${c.title.replace(/\|/g, "／")} | ${c.messageCount} | ${tokenText(c.inputTokens)}/${tokenText(c.outputTokens)} | ${updatedText(c.updatedAt)} | ${c.characterCardName || "—"} | ${c.guard || ""} |`),
        "",
        "## 标题重复",
        ...(r.duplicates.length ? r.duplicates.map((g) => `- ${g.titles.join(" / ")}`) : ["- 没有"]),
        "",
    ];
    return lines.join("\n");
}
async function deleteShortChats(targets) {
    const out = { deleted: [], skipped: [] };
    for (const c of targets) {
        // 硬保险：只删 A 类，受保护的一律跳过
        if (c.cls !== "A" || c.messageCount > exports.SHORT_MAX_MESSAGES) {
            out.skipped.push({ title: c.title, reason: "不是空对话" });
            continue;
        }
        if (c.guard) {
            out.skipped.push({ title: c.title, reason: c.guard });
            continue;
        }
        try {
            let messages = [];
            try {
                const m = await Tools.Chat.getMessages(c.id, { order: "asc", limit: 10 });
                messages = (m.messages ?? []).map((x) => ({ sender: x.sender, content: x.content, timestamp: x.timestamp }));
            }
            catch {
                // 读不到内容也照样备份元数据
            }
            await Tools.Files.write(DELETED_LOG, JSON.stringify({ ts: Math.floor(Date.now() / 1000), id: c.id, title: c.title, messageCount: c.messageCount, card: c.characterCardName, messages }) + "\n", true);
            await Tools.Chat.deleteChat(c.id);
            out.deleted.push(c.title);
        }
        catch (error) {
            out.skipped.push({ title: c.title, reason: errorText(error) });
        }
    }
    return out;
}
async function applyRenames(targets) {
    const batch = `R-${Date.now()}`;
    let renamed = 0;
    const failed = [];
    for (const c of targets) {
        if (!c.proposedTitle || c.proposedTitle === c.title)
            continue;
        try {
            await Tools.Chat.updateTitle(c.id, c.proposedTitle);
            const rec = { batch, ts: Math.floor(Date.now() / 1000), id: c.id, from: c.title, to: c.proposedTitle };
            await Tools.Files.write(RENAME_LOG, JSON.stringify(rec) + "\n", true);
            renamed += 1;
        }
        catch (error) {
            failed.push(`${c.title}：${errorText(error)}`);
        }
    }
    return { renamed, failed };
}
async function readRenames() {
    if (!(await (0, snapshot_js_1.fileExists)(RENAME_LOG)))
        return [];
    const { lines } = await (0, snapshot_js_1.readAll)(RENAME_LOG, 2000);
    const out = [];
    for (const line of lines) {
        try {
            out.push(JSON.parse(line));
        }
        catch {
            // 跳过坏行
        }
    }
    return out;
}
async function lastRenameBatch() {
    const all = await readRenames();
    const undone = new Set(all.filter((r) => r.undone).map((r) => `${r.batch}:${r.id}`));
    const live = all.filter((r) => !r.undone && !undone.has(`${r.batch}:${r.id}`));
    const last = live[live.length - 1];
    if (!last)
        return null;
    return { batch: last.batch, count: live.filter((r) => r.batch === last.batch).length };
}
async function undoLastRenames() {
    const all = await readRenames();
    const undone = new Set(all.filter((r) => r.undone).map((r) => `${r.batch}:${r.id}`));
    const live = all.filter((r) => !r.undone && !undone.has(`${r.batch}:${r.id}`));
    const last = live[live.length - 1];
    if (!last)
        return { restored: 0, failed: [] };
    let restored = 0;
    const failed = [];
    for (const r of live.filter((x) => x.batch === last.batch)) {
        try {
            await Tools.Chat.updateTitle(r.id, r.from);
            await Tools.Files.write(RENAME_LOG, JSON.stringify({ ...r, ts: Math.floor(Date.now() / 1000), undone: true }) + "\n", true);
            restored += 1;
        }
        catch (error) {
            failed.push(`${r.to}：${errorText(error)}`);
        }
    }
    return { restored, failed };
}
// ---------- 长对话提炼：生成给 Operit AI 的指令 ----------
function distillPrompt(c) {
    const segments = Math.max(1, Math.ceil(c.messageCount / 40));
    return [
        `请提炼对话「${c.title}」（chat_id: ${c.id}，共 ${c.messageCount} 条消息）。只读，不要删除或修改任何对话。`,
        "",
        `1. 用 read_messages_range 分段读取，每次 40 条（start=0,end=40；start=40,end=80……），共约 ${segments} 段。`,
        "   每读完一段先写 3–5 条笔记（这段在做什么、哪一步成功了、哪一步失败了），再读下一段。不要一次全部读完。",
        "2. 读完后写一份精简的「工作流说明」：",
        "   - 目标是什么",
        "   - 最终能用的步骤、参数和提示词（只保留最后成功的版本，失败的尝试不要）",
        "   - 注意事项",
        `3. 把说明保存到 /sdcard/Download/Operit/companion/slim/distilled_${c.id.slice(0, 8)}.md。`,
        "4. 然后问我要不要：① 存成记忆 ② 新建一个干净的对话来继续用这个工作流 ③ 删除原来的长对话。",
        "   我明确回复之前，什么都不要做。",
        "",
    ].join("\n");
}
async function writeDistillPrompt(c) {
    const path = `${slim_js_1.SLIM_DIR}/distill_prompt_${c.id.slice(0, 8)}.md`;
    await Tools.Files.write(path, distillPrompt(c), false);
    return path;
}
