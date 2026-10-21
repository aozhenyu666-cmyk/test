// Parse model output as JSON, tolerating code fences or stray text around the object.
// Shared by the server and the browser's direct mode.

export class JsonParseError extends Error {}

export function parseJson(text) {
  const t = String(text || '').trim();
  try {
    return JSON.parse(t);
  } catch {
    const start = t.indexOf('{');
    const end = t.lastIndexOf('}');
    if (start >= 0 && end > start) {
      try {
        return JSON.parse(t.slice(start, end + 1));
      } catch {
        /* fall through */
      }
    }
  }
  throw new JsonParseError('模型返回的内容不是有效的 JSON，请重试');
}
