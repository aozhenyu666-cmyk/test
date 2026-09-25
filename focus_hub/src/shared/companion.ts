import { listChats, type ChatEntry } from "./nav.js";

// 主界面只有一张脸：标题含"陪伴窗"的对话，名字取它绑定的角色卡
export const COMPANION_TITLE_KEYWORD = "陪伴窗";
const DEFAULT_NAME = "小妹";

export interface Companion {
  name: string;
  chat: ChatEntry | null;
}

export async function findCompanion(): Promise<Companion> {
  const chats = await listChats(COMPANION_TITLE_KEYWORD);
  const chat = chats[0] ?? null;
  return { name: chat?.characterCardName || DEFAULT_NAME, chat };
}
