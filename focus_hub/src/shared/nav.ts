export const FOCUS_HUB_ROUTE = "toolpkg:local.focus_hub:ui:focus_hub";
export const NATIVE_CHAT_ROUTE = "native.ai_chat";

// 与宿主 ToolPkgDesktopWidgetHost.EXTRA_OPEN_ROUTE_ID 一致，MainActivity.handleIntent 读取它打开路由
const EXTRA_OPEN_ROUTE_ID = "com.ai.assistance.operit.extra.OPEN_ROUTE_ID";
const MAIN_ACTIVITY = "com.ai.assistance.operit.ui.main.MainActivity";

// 主界面对话有专门的入口，按标题关键词把它们排在最前面
export const PINNED_TITLE_KEYWORDS = ["陪伴窗", "温柔巡检", "判断官", "复盘者", "解锁审讯官", "跑偏提醒", "秘书"];

// 超过这个消息数就提示上下文偏长
export const LONG_CHAT_MESSAGES = 300;

export interface ChatEntry {
  id: string;
  title: string;
  messageCount: number;
  updatedAt: string;
  isCurrent: boolean;
  characterCardName: string;
}

function toEntry(chat: {
  id: string;
  title?: string;
  messageCount?: number;
  updatedAt?: string;
  isCurrent?: boolean;
  characterCardName?: string | null;
}): ChatEntry {
  return {
    id: chat.id,
    title: String(chat.title ?? "").trim() || "（无标题）",
    messageCount: chat.messageCount ?? 0,
    updatedAt: String(chat.updatedAt ?? ""),
    isCurrent: Boolean(chat.isCurrent),
    characterCardName: String(chat.characterCardName ?? ""),
  };
}

// list_chats 读聊天记录库，不依赖悬浮窗服务
export async function listChats(query: string): Promise<ChatEntry[]> {
  const params: Record<string, unknown> = { sort_by: "updatedAt", sort_order: "desc", limit: 200 };
  if (query.trim()) {
    params.query = query.trim();
    params.match = "contains";
  }
  const result = await Tools.Chat.listChats(params as Parameters<typeof Tools.Chat.listChats>[0]);
  return (result.chats ?? []).map(toEntry);
}

export function isPinned(entry: ChatEntry): boolean {
  return PINNED_TITLE_KEYWORDS.some((keyword) => entry.title.includes(keyword));
}

// Tools.Chat.switchTo 只切换悬浮窗里的对话（syncToGlobal=false），而且悬浮窗服务没运行时直接报
// "Service not connected"。主界面跟随 ChatHistoryManager 的全局当前对话，所以直接写这个值。
export async function setMainChat(chatId: string): Promise<void> {
  const ChatHistoryManager = Java.com.ai.assistance.operit.data.repository.ChatHistoryManager;
  const manager = ChatHistoryManager.getInstance(Java.getApplicationContext());
  const exists = await manager.callSuspend("chatExists", chatId);
  if (exists !== true) {
    throw new Error(`对话不存在：${chatId}`);
  }
  await manager.callSuspend("setCurrentChatId", chatId);
}

export async function openRouteViaIntent(routeId: string): Promise<void> {
  const packageName = String(Java.getApplicationContext().getPackageName());
  // 传 "包名/完整类名"：setComponent 在 debug 包名下会把类名拼错
  const intent = new Intent("android.intent.action.MAIN")
    .setComponent(packageName, `${packageName}/${MAIN_ACTIVITY}`)
    .addFlag(IntentFlag.ACTIVITY_NEW_TASK)
    .addFlag(IntentFlag.ACTIVITY_SINGLE_TOP)
    .putExtra(EXTRA_OPEN_ROUTE_ID, routeId);
  await intent.start();
}
