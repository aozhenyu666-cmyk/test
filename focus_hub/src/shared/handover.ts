// 给她换个新对话：老对话太长时，每说一句都要把整段历史再发给模型。
// 一键做这几件事，并把每一步记下来，可以撤销：
// 1. 新建一个对话，绑定她现在用的角色卡，标题 "[她] 月-日"
// 2. 老对话改名 "[归档] 原标题"（不删）
// 3. 主控台以后把新对话当作她（companion/config.json）
// 4. drift/channel.txt 里如果写着老对话的 id，换成新 id（先备份原文件）
// 5. 列出还在往老对话发消息的启用中工作流（主控台不改工作流，交给流程线）
import { isArchived, listChats, type ChatEntry } from "./nav.js";
import { findCompanion, setCompanionChat } from "./companion.js";
import { fileExists, formatDateTime, readAll } from "./snapshot.js";
import { SLIM_DIR, workflowTexts } from "./slim.js";

const CHANNEL_PATH = "/sdcard/Download/Operit/drift/channel.txt";
const HANDOVER_LOG = `${SLIM_DIR}/handover.jsonl`;

function errorText(error: unknown): string {
  if (error && typeof error === "object" && "message" in error) {
    return String((error as { message: unknown }).message);
  }
  return String(error);
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

export interface HandoverRecord {
  key: string;
  ts: number;
  iso: string;
  oldId: string;
  oldTitle: string;
  oldRenamedTo: string | null;
  newId: string;
  newTitle: string;
  channelBackup: string | null;
  flowsStillOnOld: string[];
  undone?: boolean;
}

export interface HandoverResult {
  record: HandoverRecord;
  notes: string[];
}

// 优先走 ChatHistoryManager（不需要悬浮窗服务）；不行再启动服务走 create_new_chat
async function createChat(cardName: string, cardId: string): Promise<string> {
  try {
    const ChatHistoryManager = Java.com.ai.assistance.operit.data.repository.ChatHistoryManager;
    const manager = ChatHistoryManager.getInstance(Java.getApplicationContext());
    const created = (await manager.callSuspend("createNewChat", null, null, cardName || null, null, false)) as { id?: unknown } | null;
    const id = created ? String(created.id ?? "") : "";
    if (id) return id;
  } catch {
    // 落到下面的服务路径
  }
  await Tools.Chat.startService({ keep_if_exists: true });
  const result = await Tools.Chat.createNew(undefined, false, cardId || undefined);
  if (!result?.chatId) throw new Error("新对话没建成");
  return result.chatId;
}

async function readText(path: string): Promise<string | null> {
  if (!(await fileExists(path))) return null;
  const { lines } = await readAll(path, 2000);
  return lines.join("\n") + "\n";
}

export async function startFreshCompanionChat(now: number = Date.now()): Promise<HandoverResult> {
  const chats = await listChats("");
  const companion = await findCompanion(chats);
  const old: ChatEntry | null = companion.chat;
  const notes: string[] = [];

  const d = new Date(now);
  const newTitle = `[她] ${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
  const newId = await createChat(old?.characterCardName ?? "", old?.characterCardId ?? "");
  await Tools.Chat.updateTitle(newId, newTitle);
  notes.push(`新对话「${newTitle}」${old?.characterCardName ? `，角色卡 ${old.characterCardName}` : ""}`);

  let oldRenamedTo: string | null = null;
  if (old && !isArchived(old)) {
    oldRenamedTo = `[归档] ${old.title.replace(/^\s*\[[^\]]*\]\s*/, "")}`;
    try {
      await Tools.Chat.updateTitle(old.id, oldRenamedTo);
      notes.push(`老对话改名「${oldRenamedTo}」（没删，${old.messageCount} 条都还在）`);
    } catch (error) {
      oldRenamedTo = null;
      notes.push(`老对话没改上名：${errorText(error)}`);
    }
  }

  await setCompanionChat(newId);

  let channelBackup: string | null = null;
  if (old) {
    try {
      const channel = await readText(CHANNEL_PATH);
      if (channel != null && channel.includes(old.id)) {
        channelBackup = `${SLIM_DIR}/channel_backup_${now}.txt`;
        await Tools.Files.write(channelBackup, channel, false);
        await Tools.Files.write(CHANNEL_PATH, channel.split(old.id).join(newId), false);
        notes.push("提醒通道 channel.txt 已指向新对话（原文件已备份）");
      } else if (channel != null) {
        notes.push("channel.txt 里没有写她的老对话，没改");
      }
    } catch (error) {
      notes.push(`channel.txt 没改成：${errorText(error)}`);
    }
  }

  let flowsStillOnOld: string[] = [];
  if (old) {
    try {
      flowsStillOnOld = (await workflowTexts()).filter((f) => f.enabled && f.text.includes(old.id)).map((f) => f.name);
      if (flowsStillOnOld.length) notes.push(`这些工作流写死了老对话，还会往老对话发：${flowsStillOnOld.join("、")}（交给流程线改）`);
    } catch {
      // 读不到工作流不影响换对话
    }
  }

  const record: HandoverRecord = {
    key: `H-${now}-${Math.random().toString(36).slice(2, 8)}`,
    ts: Math.floor(now / 1000),
    iso: formatDateTime(now),
    oldId: old?.id ?? "",
    oldTitle: old?.title ?? "",
    oldRenamedTo,
    newId,
    newTitle,
    channelBackup,
    flowsStillOnOld,
  };
  await Tools.Files.write(HANDOVER_LOG, JSON.stringify(record) + "\n", true);
  return { record, notes };
}

async function readHandovers(): Promise<HandoverRecord[]> {
  if (!(await fileExists(HANDOVER_LOG))) return [];
  const { lines } = await readAll(HANDOVER_LOG, 200);
  const out: HandoverRecord[] = [];
  for (const line of lines) {
    try {
      out.push(JSON.parse(line) as HandoverRecord);
    } catch {
      // 跳过坏行
    }
  }
  return out;
}

// 最近一次还没撤销的换对话
export async function lastHandover(): Promise<HandoverRecord | null> {
  const all = await readHandovers();
  const undone = new Set(all.filter((r) => r.undone).map((r) => r.key));
  for (let i = all.length - 1; i >= 0; i -= 1) {
    const r = all[i];
    if (!r.undone && !undone.has(r.key)) return r;
  }
  return null;
}

export async function undoLastHandover(): Promise<string[]> {
  const r = await lastHandover();
  if (!r) throw new Error("没有可以撤销的换对话");
  const notes: string[] = [];
  if (r.oldId) {
    await setCompanionChat(r.oldId);
    notes.push("她换回老对话");
  }
  if (r.oldRenamedTo && r.oldId) {
    try {
      await Tools.Chat.updateTitle(r.oldId, r.oldTitle);
      notes.push(`老对话标题恢复为「${r.oldTitle}」`);
    } catch (error) {
      notes.push(`老对话标题没恢复：${errorText(error)}`);
    }
  }
  if (r.channelBackup) {
    const backup = await readText(r.channelBackup);
    if (backup != null) {
      await Tools.Files.write(CHANNEL_PATH, backup, false);
      notes.push("channel.txt 恢复为换之前的版本");
    }
  }
  // 新对话还是空的就顺手删掉；聊过的留着
  try {
    const fresh = (await listChats("")).find((c) => c.id === r.newId);
    if (fresh && fresh.messageCount <= 2) {
      await Tools.Chat.deleteChat(r.newId);
      notes.push("新建的空对话已删除");
    } else if (fresh) {
      notes.push(`新对话里已经有 ${fresh.messageCount} 条，留着没删`);
    }
  } catch (error) {
    notes.push(`新对话没处理：${errorText(error)}`);
  }
  await Tools.Files.write(HANDOVER_LOG, JSON.stringify({ ...r, undone: true }) + "\n", true);
  return notes;
}
