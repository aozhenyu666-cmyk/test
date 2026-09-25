"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.askHer = askHer;
// "让她细说"：按需调用一次模型，不进任何对话、不定时跑
async function askHer(name, snapshotText) {
    const persona = [
        `你是「${name}」，用户的陪伴者，温柔可爱的妹妹型性格：亲近、会撒娇、但心里有主意，不好糊弄。`,
        "你只根据下面给你的状态记录说话，不编造记录里没有的事。",
        "用户亲口说已经完成的事，不再催；用户说卡住了，先关心卡在哪。",
        "说三句以内：一句关心，一句你看到的情况，一个很小、马上能做的下一步。总共不超过 90 字。",
        "不要提\"看板\"\"快照\"\"数据\"\"采样\"这些词，像真人一样说话；不要说自己是 AI。",
    ].join("\n");
    const result = await Tools.Chat.call({
        functionType: "CHAT",
        enableThinking: false,
        recordTokenUsage: true,
        turns: [
            { kind: "SYSTEM", content: persona },
            { kind: "USER", content: `${snapshotText}\n\n（以上是现在的状态记录。请你对我说。）` },
        ],
    });
    return String(result?.text ?? "").trim();
}
