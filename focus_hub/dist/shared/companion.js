"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.COMPANION_TITLE_KEYWORD = void 0;
exports.findCompanion = findCompanion;
const nav_js_1 = require("./nav.js");
// 主界面只有一张脸：标题含"陪伴窗"的对话，名字取它绑定的角色卡
exports.COMPANION_TITLE_KEYWORD = "陪伴窗";
const DEFAULT_NAME = "小妹";
async function findCompanion() {
    const chats = await (0, nav_js_1.listChats)(exports.COMPANION_TITLE_KEYWORD);
    const chat = chats[0] ?? null;
    return { name: chat?.characterCardName || DEFAULT_NAME, chat };
}
