import { listChats, type ChatEntry } from "./nav.js";

// 主界面只有一张脸。判定顺序：用户在会话页"设为她"选过的 → 秘书会话（S3 投递目标）→ 标题含"秘书" → 标题含"陪伴窗"
const CONFIG_PATH = "/sdcard/Download/Operit/companion/config.json";
const SECRETARY_CHAT_ID = "d20b4e22-f6ab-4766-bc83-54e96c99bb44";
const TITLE_FALLBACKS = ["秘书", "陪伴窗"];
const DEFAULT_NAME = "小妹";
const LINE_NUMBER_PREFIX = /^\s*\d+\| ?/;

export interface Companion {
  name: string;
  chat: ChatEntry | null;
  source: "chosen" | "secretary" | "title" | "none";
}

async function readChosenChatId(): Promise<string> {
  try {
    const exists = await Tools.Files.exists(CONFIG_PATH);
    if (!exists?.exists) return "";
    const part = await Tools.Files.readPart(CONFIG_PATH, 1, 50);
    const json = part.content
      .split("\n")
      .filter((line) => LINE_NUMBER_PREFIX.test(line))
      .map((line) => line.replace(LINE_NUMBER_PREFIX, ""))
      .join("\n");
    return String(JSON.parse(json)?.chat_id ?? "").trim();
  } catch {
    return "";
  }
}

export async function setCompanionChat(chatId: string): Promise<void> {
  const body = JSON.stringify({ chat_id: chatId, chosen_at: new Date().toISOString() });
  await Tools.Files.write(CONFIG_PATH, `${body}\n`, false);
}

export async function findCompanion(allChats?: ChatEntry[]): Promise<Companion> {
  const chats = allChats ?? (await listChats(""));
  const byId = (id: string) => (id ? chats.find((c) => c.id === id) ?? null : null);
  const pick = (chat: ChatEntry | null, source: Companion["source"]): Companion | null =>
    chat ? { name: chat.characterCardName || DEFAULT_NAME, chat, source } : null;

  return (
    pick(byId(await readChosenChatId()), "chosen") ??
    pick(byId(SECRETARY_CHAT_ID), "secretary") ??
    TITLE_FALLBACKS.map((keyword) => pick(chats.find((c) => c.title.includes(keyword)) ?? null, "title")).find(Boolean) ??
    { name: DEFAULT_NAME, chat: null, source: "none" }
  );
}
