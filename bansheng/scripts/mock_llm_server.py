"""开发用的假模型：一个最小的 OpenAI 兼容 /v1/chat/completions。

没有真实 API Key 时用来跑通链路。它不聪明，只按触发类型给固定回答；
用它得到的"介入"只证明管道通了，不代表伴生的接话质量。
"""
from __future__ import annotations

import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def answer(user: str) -> dict:
    try:
        data = json.loads(user.split("\n", 1)[1])
    except Exception:
        return {"ops": [], "candidates": []}
    trig = data.get("触发", {})
    turns = data.get("对话记录", [])
    users = [t for t in turns if t["speaker"] == "user"]
    ops, cands = [], []
    if users:
        ops.append({"op": "set_topic", "text": users[0]["text"][:30], "evidence": [users[0]["id"]]})
    for u in users:
        if "复杂" in u["text"] and "允许复杂" not in u["text"]:
            ops.append({"op": "add_constraint", "text": "怕做复杂", "evidence": [u["id"]]})
    if trig.get("类型") == "main_ai_reply":
        cands.append({"text": "（假模型）它这段里有个点值得追一下。", "kind": "curiosity",
                      "basis": [trig.get("事件")], "score": 4.0, "why_now": "mock", "why_not": "mock"})
    if trig.get("类型") == "user_stuck":
        cands.append({"text": "（假模型）先押一个最小的点试试。", "kind": "proposal",
                      "basis": [trig.get("事件")], "score": 4.5})
    return {"read": "mock", "ops": ops, "candidates": cands}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
        user = next((m["content"] for m in body.get("messages", []) if m["role"] == "user"), "")
        content = json.dumps(answer(user), ensure_ascii=False)
        resp = {"id": "mock", "object": "chat.completion", "model": "mock-mind",
                "choices": [{"index": 0, "finish_reason": "stop",
                             "message": {"role": "assistant", "content": content}}],
                "usage": {"prompt_tokens": len(user), "completion_tokens": len(content), "total_tokens": 0}}
        data = json.dumps(resp, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 48999
    print(f"mock LLM on http://127.0.0.1:{port}/v1", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
