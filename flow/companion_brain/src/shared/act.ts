// 行动：全部由代码执行，模型不直接碰手机。
// 锁不另起炉灶：只往现有锁队列 events/lock_queue.txt 投一行，由 S4_Lock_Queue_Worker 调 app_suspender 并回读。
import { appendLine, errorText, writeText } from "./fsx.js";
import { ROOT } from "./memory.js";

async function call(name: string, params: Record<string, string | number | boolean | object>): Promise<any> {
  return await toolCall(name, params);
}

function outputOf(result: any): string {
  if (result == null) return "";
  if (typeof result === "string") return result;
  if (typeof result.output === "string") return result.output;
  if (result.result && typeof result.result.output === "string") return result.result.output;
  return JSON.stringify(result);
}

export async function shell(command: string): Promise<string> {
  return outputOf(await call("super_admin:shell", { command }));
}

export interface Delivery {
  via: string[];
  errors: string[];
}

// 开口：优先走主控台的 check_in（念 + 弹主控台 + 记一条待回应）；不行就退回系统 TTS。再发一条通知，保证看得到字。
export async function speak(line: string, title: string, popup: boolean): Promise<Delivery> {
  const out: Delivery = { via: [], errors: [] };
  let spoken = false;
  try {
    const r = await call("focus_hub_nav:check_in", { message: line, force: true, speak: true, popup });
    const status = String(r?.status ?? r?.result?.status ?? "");
    if (status === "CHECKED_IN") {
      out.via.push(`check_in(speak=${r?.speak ?? r?.result?.speak ?? "?"},popup=${r?.popup ?? r?.result?.popup ?? "?"})`);
      spoken = String(r?.speak ?? r?.result?.speak ?? "") === "ACCEPTED";
    } else {
      out.errors.push(`check_in:${status || "无状态"}`);
    }
  } catch (error) {
    out.errors.push(`check_in:${errorText(error)}`);
  }
  if (!spoken) {
    try {
      const r = await Tools.SoftwareSettings.testTtsPlayback(line, { interrupt: true });
      if (r?.playbackTriggered) out.via.push("tts");
      else out.errors.push("tts:playbackTriggered=false");
    } catch (error) {
      out.errors.push(`tts:${errorText(error)}`);
    }
  }
  try {
    await Tools.System.sendNotification(line, title);
    out.via.push("notification");
  } catch (error) {
    out.errors.push(`notification:${errorText(error)}`);
  }
  return out;
}

// 记一条 ALERT 事件：判断官证据包里的"上次提醒"、规则提醒的节流都认它
export async function logAlertEvent(stage: string, ts: number): Promise<void> {
  try {
    await shell(`sh ${ROOT}/events/eventd.sh add ALERT src=xiaoman_brain stage=${stage} --dedup "xmbr-${Math.floor(ts / 1000)}"`);
  } catch {
    // 记账失败不影响开口
  }
}

export async function openApp(pkg: string): Promise<string> {
  try {
    const r = await Tools.System.startApp(pkg);
    return r?.success === false ? `FAILED ${r?.details ?? ""}` : "ACCEPTED";
  } catch (error) {
    return `FAILED ${errorText(error)}`;
  }
}

export async function enqueueLock(pkg: string, protectedPkgs: string[], lockable: string[]): Promise<string> {
  if (!/^[A-Za-z0-9._]+$/.test(pkg)) return "REJECTED bad_pkg";
  if (protectedPkgs.includes(pkg)) return "REJECTED protected";
  if (!lockable.includes(pkg)) return "REJECTED not_in_ent_list";
  await appendLine(`${ROOT}/events/lock_queue.txt`, `lock ${pkg}`);
  return "QUEUED";
}

export async function setAssignment(task: string, minutes: number, now: number): Promise<{ id: string; deadline: number }> {
  const id = `A-${now}-${Math.random().toString(36).slice(2, 8)}`;
  const deadline = now + minutes * 60 * 1000;
  // 与主控台"督促"的 assignment.json 同格式，主控台能直接显示和交差
  await writeText(
    `${ROOT}/companion/warden/assignment.json`,
    JSON.stringify({ id, task, createdAt: now, deadline, standard: { type: "statement", detail: "由小满派活", filePath: "" }, active: true }),
  );
  return { id, deadline };
}

// 看一眼屏幕：复用现有截屏取证脚本（截图 → OCR → 一行 FACT）
export async function lookAtScreen(): Promise<string> {
  try {
    const out = await shell(`sh ${ROOT}/judge/shot_facts.sh`);
    const m = /FACT_OK (FACT\|[^\s]+)/.exec(out);
    return m ? m[1] : "";
  } catch {
    return "";
  }
}
