// Tools.Files.readPart 返回的每行都带 "  12| " 行号前缀，这里统一剥掉。
const LINE_NUMBER_PREFIX = /^\s*\d+\| ?/;
const CHUNK = 300;

function strip(content: string): string[] {
  return content
    .split("\n")
    .filter((line) => LINE_NUMBER_PREFIX.test(line))
    .map((line) => line.replace(LINE_NUMBER_PREFIX, ""));
}

export async function exists(path: string): Promise<boolean> {
  try {
    const r = await Tools.Files.exists(path);
    return !!r?.exists;
  } catch {
    return false;
  }
}

async function totalLines(path: string): Promise<number> {
  const probe = await Tools.Files.readPart(path, 1, 1);
  return probe.totalLines || 0;
}

export async function readLines(path: string): Promise<string[]> {
  if (!(await exists(path))) return [];
  try {
    const total = await totalLines(path);
    const out: string[] = [];
    for (let s = 1; s <= total; s += CHUNK) {
      const part = await Tools.Files.readPart(path, s, Math.min(total, s + CHUNK - 1));
      out.push(...strip(part.content));
    }
    return out;
  } catch {
    return [];
  }
}

export async function tailLines(path: string, n: number): Promise<string[]> {
  if (!(await exists(path))) return [];
  try {
    const total = await totalLines(path);
    if (total <= 0) return [];
    const start = Math.max(1, total - n + 1);
    const out: string[] = [];
    for (let s = start; s <= total; s += CHUNK) {
      const part = await Tools.Files.readPart(path, s, Math.min(total, s + CHUNK - 1));
      out.push(...strip(part.content));
    }
    return out.filter((l) => l.trim());
  } catch {
    return [];
  }
}

export async function readText(path: string): Promise<string> {
  return (await readLines(path)).join("\n");
}

export async function readJson<T>(path: string): Promise<T | null> {
  try {
    const text = await readText(path);
    return text.trim() ? (JSON.parse(text) as T) : null;
  } catch {
    return null;
  }
}

export function parseJsonl<T>(lines: string[]): T[] {
  const out: T[] = [];
  for (const line of lines) {
    const t = line.trim();
    if (!t.startsWith("{")) continue;
    try {
      out.push(JSON.parse(t) as T);
    } catch {
      // 跳过损坏的行
    }
  }
  return out;
}

export async function writeText(path: string, content: string): Promise<void> {
  await Tools.Files.write(path, content, false);
}

export async function appendLine(path: string, line: string): Promise<void> {
  await Tools.Files.write(path, line.endsWith("\n") ? line : `${line}\n`, true);
}

export async function appendJsonl(path: string, record: unknown): Promise<void> {
  await appendLine(path, JSON.stringify(record));
}

export async function listNames(dir: string): Promise<string[]> {
  try {
    const r = await Tools.Files.list(dir);
    return (r?.entries ?? []).filter((e) => !e.isDirectory).map((e) => e.name).sort();
  } catch {
    return [];
  }
}

export function errorText(error: unknown): string {
  if (error && typeof error === "object" && "message" in error) {
    return String((error as { message: unknown }).message);
  }
  return String(error);
}
