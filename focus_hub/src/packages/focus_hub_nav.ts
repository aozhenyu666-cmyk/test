/* METADATA
{
  "name": "focus_hub_nav",
  "display_name": {
    "zh": "主控台跳转",
    "en": "Focus Hub Navigation"
  },
  "description": {
    "zh": "把界面带到主控台，或在主界面打开指定对话。只做页面跳转，不发消息、不改工作流、不冻结应用。",
    "en": "Bring up the Focus Hub page, or open a specific chat in the main screen. Navigation only."
  },
  "enabled_by_default": true,
  "category": "System",
  "tools": [
    {
      "name": "open_focus_hub",
      "description": {
        "zh": "打开主控台页面（看板 + 会话导航）。适合放在工作流末尾，代替新建一段临时对话来提醒用户。",
        "en": "Open the Focus Hub page. Useful at the end of a workflow instead of starting a throwaway chat."
      },
      "parameters": []
    },
    {
      "name": "open_chat",
      "description": {
        "zh": "把主界面切换到指定对话并打开聊天页。",
        "en": "Switch the main screen to the given chat and open the chat page."
      },
      "parameters": [
        {
          "name": "chat_id",
          "description": {
            "zh": "对话 ID",
            "en": "Chat ID"
          },
          "type": "string",
          "required": true
        }
      ]
    }
  ]
}
*/

import { FOCUS_HUB_ROUTE, NATIVE_CHAT_ROUTE, openRouteViaIntent, setMainChat } from "../shared/nav.js";

function errorText(error: unknown): string {
  if (error && typeof error === "object" && "message" in error) {
    return String((error as { message: unknown }).message);
  }
  return String(error);
}

export async function open_focus_hub(): Promise<{ success: boolean; message: string }> {
  try {
    await openRouteViaIntent(FOCUS_HUB_ROUTE);
    // 只能确认跳转请求已发出；后台启动 Activity 可能被系统拦截
    return { success: true, message: "已请求打开主控台（ACCEPTED，是否真的显示需以屏幕为准）" };
  } catch (error) {
    return { success: false, message: `打开主控台失败：${errorText(error)}` };
  }
}

export async function open_chat(params: { chat_id?: string }): Promise<{ success: boolean; message: string }> {
  const chatId = String(params?.chat_id ?? "").trim();
  if (!chatId) return { success: false, message: "缺少 chat_id" };
  try {
    await setMainChat(chatId);
    await openRouteViaIntent(NATIVE_CHAT_ROUTE);
    return { success: true, message: `已把主界面切到 ${chatId} 并请求打开聊天页（ACCEPTED）` };
  } catch (error) {
    return { success: false, message: `打开对话失败：${errorText(error)}` };
  }
}
