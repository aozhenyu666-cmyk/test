import { memoryBlock } from "./shared/brain.js";
import { loadConfig } from "./shared/memory.js";

// 只给小满·陪伴的会话注入记忆：每一轮对话自动带上"关于 TA"、承诺、她刚说过的话和最新进展，
// 她不用调工具去查，也不多花一次模型调用。
async function onSystemPromptCompose(event: any) {
  const stage = String(event?.eventName ?? event?.event ?? "");
  if (stage !== "after_compose_system_prompt") return null;
  const chatId = String(event?.eventPayload?.chatId ?? "").trim();
  if (!chatId) return null;
  try {
    const cfg = await loadConfig();
    if (chatId !== cfg.companion_chat_id) return null;
    const current = String(event?.eventPayload?.systemPrompt ?? "");
    return { systemPrompt: `${current}\n\n${await memoryBlock()}` };
  } catch {
    return null;
  }
}

export function registerToolPkg() {
  ToolPkg.registerSystemPromptComposeHook({
    id: "companion_brain_memory",
    function: onSystemPromptCompose,
  });
  return true;
}

export { onSystemPromptCompose };
