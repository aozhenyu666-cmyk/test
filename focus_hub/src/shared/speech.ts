// 让她开口：优先用用户自己的 voice_bar（MiniMax 等）合成音频文件再播放；不行再退回 Operit 自带 TTS。
// voice_bar:say 只合成、不播放，返回 <voice> 标签，所以这里要自己找出音频路径并播放。

export type SpeakStatus = "ACCEPTED" | "FAILED";

export interface SpeakResult {
  status: SpeakStatus;
  via: "voice_bar" | "tts" | "none";
  audioPath?: string;
  error?: string;
}

const AUDIO_EXT = /\.(mp3|wav|ogg|m4a|aac|opus|flac|amr)(\?.*)?$/i;
const ATTR = /\b(?:src|path|file|audio|audio_path|url)\s*=\s*["']([^"']+)["']/gi;
const MAX_PLAY_MS = 90 * 1000;

function errorText(error: unknown): string {
  if (error && typeof error === "object" && "message" in error) {
    return String((error as { message: unknown }).message);
  }
  return String(error);
}

function asAudio(candidate: string): string | null {
  const s = candidate.trim().replace(/^file:\/\//, "");
  if (!AUDIO_EXT.test(s)) return null;
  return s.startsWith("/") || /^https?:\/\//.test(s) ? s : null;
}

// 不假设 voice_bar 的返回结构：在任意字段、JSON 字符串或 <voice ...> 标签属性里找音频路径
export function findAudioPath(value: unknown, depth = 0): string | null {
  if (value == null || depth > 6) return null;
  if (typeof value === "string") {
    const direct = asAudio(value);
    if (direct) return direct;
    for (const match of value.matchAll(ATTR)) {
      const hit = asAudio(match[1]);
      if (hit) return hit;
    }
    const trimmed = value.trim();
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      try {
        return findAudioPath(JSON.parse(trimmed), depth + 1);
      } catch {
        return null;
      }
    }
    return null;
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      const hit = findAudioPath(item, depth + 1);
      if (hit) return hit;
    }
    return null;
  }
  if (typeof value === "object") {
    const record = value as Record<string, unknown>;
    const preferred = ["audio_path", "audioPath", "path", "file", "voice_tag", "voiceTag", "data", "result"];
    const keys = [...preferred.filter((k) => k in record), ...Object.keys(record).filter((k) => !preferred.includes(k))];
    for (const key of keys) {
      const hit = findAudioPath(record[key], depth + 1);
      if (hit) return hit;
    }
  }
  return null;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// 播放完再返回：脚本上下文结束后播放器可能被回收，提前返回会把声音截断
export async function playAudio(path: string): Promise<void> {
  const MediaPlayer = Java.type("android.media.MediaPlayer");
  const player = new MediaPlayer();
  try {
    player.setDataSource(path);
    player.prepare();
    player.start();
    const duration = Number(player.getDuration()) || 0;
    await sleep(Math.min(Math.max(duration, 1000) + 400, MAX_PLAY_MS));
  } finally {
    try {
      player.release();
    } catch {
      // 已释放
    }
  }
}

export async function speak(text: string, title: string): Promise<SpeakResult> {
  const errors: string[] = [];
  try {
    const result = await toolCall("voice_bar:say", { text, title });
    const failed = result && typeof result === "object" && (result as { success?: unknown }).success === false;
    const audioPath = failed ? null : findAudioPath(result);
    if (audioPath) {
      await playAudio(audioPath);
      return { status: "ACCEPTED", via: "voice_bar", audioPath };
    }
    errors.push(`voice_bar：${failed ? String((result as { error?: unknown; message?: unknown }).error ?? (result as { message?: unknown }).message ?? "返回失败") : "返回里没找到音频文件"}`);
  } catch (error) {
    errors.push(`voice_bar：${errorText(error)}`);
  }
  try {
    const result = await Tools.SoftwareSettings.testTtsPlayback(text, { interrupt: false });
    if (result?.playbackTriggered) return { status: "ACCEPTED", via: "tts" };
    errors.push(`系统 TTS：${String(result?.errorMessage ?? result?.errorType ?? "playbackTriggered=false")}`);
  } catch (error) {
    errors.push(`系统 TTS：${errorText(error)}`);
  }
  return { status: "FAILED", via: "none", error: errors.join("；") };
}
