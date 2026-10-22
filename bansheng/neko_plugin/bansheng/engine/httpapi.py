"""本机 HTTP 接口：浏览器扩展把会话事件送进来，面板和页面浮层读三行路标。

只监听 127.0.0.1。普通网页不能调用：带 Origin 的请求只接受浏览器扩展和本服务自己的页面，
防止任意网站往伴生里塞话或读走对话。
"""
from __future__ import annotations

import asyncio
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

from .companion import Companion

STATIC = Path(__file__).with_name("static")
ALLOWED_ORIGIN_PREFIXES = ("chrome-extension://", "moz-extension://", "edge-extension://")


class CompanionHTTP:
    def __init__(self, companion: Companion, loop: asyncio.AbstractEventLoop,
                 host: str = "127.0.0.1", port: int = 48925, token: str = ""):
        self.companion = companion
        self.loop = loop
        self.host, self.port, self.token = host, port, token
        self._server: ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None

    def run(self, coro, timeout: float = 10.0) -> Any:
        return asyncio.run_coroutine_threadsafe(coro, self.loop).result(timeout)

    def call(self, fn, *args, **kwargs) -> Any:
        async def _wrap():
            return fn(*args, **kwargs)
        return self.run(_wrap())

    def start(self) -> None:
        api = self

        class Handler(BaseHTTPRequestHandler):
            server_version = "Bansheng/0.1"

            def log_message(self, *a):  # 安静
                pass

            def _origin_ok(self) -> bool:
                origin = self.headers.get("Origin")
                if not origin:
                    return True
                own = (f"http://127.0.0.1:{api.port}", f"http://localhost:{api.port}")
                return origin.startswith(ALLOWED_ORIGIN_PREFIXES) or origin in own

            def _auth_ok(self) -> bool:
                if not api.token:
                    return True
                q = parse_qs(urlparse(self.path).query)
                return (self.headers.get("X-Bansheng-Token") == api.token
                        or q.get("token", [""])[0] == api.token)

            def _send(self, code: int, body: Any, ctype: str = "application/json; charset=utf-8"):
                data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False, default=str).encode()
                self.send_response(code)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(data)))
                origin = self.headers.get("Origin")
                if origin and self._origin_ok():
                    self.send_header("Access-Control-Allow-Origin", origin)
                    self.send_header("Vary", "Origin")
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(data)

            def do_OPTIONS(self):
                if not self._origin_ok():
                    return self._send(403, {"error": "origin_not_allowed"})
                self.send_response(204)
                self.send_header("Access-Control-Allow-Origin", self.headers.get("Origin", "*"))
                self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Bansheng-Token")
                self.send_header("Access-Control-Max-Age", "600")
                self.end_headers()

            def _host_ok(self) -> bool:
                # 防 DNS 重绑定：只认本机地址
                host = (self.headers.get("Host") or "").rsplit(":", 1)[0].strip("[]").lower()
                return host in ("127.0.0.1", "localhost", "::1", "")

            def _guard(self) -> bool:
                if not self._host_ok():
                    self._send(403, {"error": "host_not_allowed"})
                    return False
                if not self._origin_ok():
                    self._send(403, {"error": "origin_not_allowed"})
                    return False
                if not self._auth_ok():
                    self._send(401, {"error": "bad_token"})
                    return False
                return True

            def _body(self) -> dict:
                n = int(self.headers.get("Content-Length") or 0)
                if n > 2_000_000:
                    raise ValueError("body too large")
                raw = self.rfile.read(n) if n else b"{}"
                val = json.loads(raw.decode("utf-8") or "{}")
                if not isinstance(val, dict):
                    raise ValueError("JSON object expected")
                return val

            def do_GET(self):
                url = urlparse(self.path)
                q = {k: v[0] for k, v in parse_qs(url.query).items()}
                if url.path in ("/", "/index.html"):
                    return self._send(200, (STATIC / "dashboard.html").read_bytes(), "text/html; charset=utf-8")
                if url.path == "/health":
                    return self._send(200, {"ok": True, "service": "bansheng"})
                if not self._guard():
                    return
                c = api.companion
                try:
                    if url.path == "/state":
                        return self._send(200, api.call(c.snapshot, q.get("session_id")))
                    if url.path == "/ledger":
                        n = int(q.get("n", 120))
                        return self._send(200, {"records": api.call(c.ledger.tail, n, q.get("session_id"))})
                    if url.path == "/metrics":
                        return self._send(200, api.call(c.metrics))
                    if url.path == "/sessions":
                        return self._send(200, {"sessions": api.call(c.list_sessions)})
                    if url.path == "/export":
                        return self._send(200, api.call(c.export, q["session_id"]))
                except KeyError as e:
                    return self._send(400, {"error": f"missing {e}"})
                except Exception as e:
                    return self._send(500, {"error": str(e)})
                return self._send(404, {"error": "not_found"})

            def do_POST(self):
                if not self._guard():
                    return
                url = urlparse(self.path)
                c = api.companion
                try:
                    body = self._body()
                    if url.path == "/events":
                        events = body.get("events")
                        if not isinstance(events, list):
                            events = [body]
                        return self._send(200, {"ok": True, "counts": api.run(c.ingest(events))})
                    if url.path == "/floor":
                        sid = body.pop("session_id")
                        return self._send(200, api.call(c.set_floor, sid, **body))
                    if url.path == "/control":
                        return self._send(200, api.run(c.control(body["action"], body.get("value"), "http")))
                    if url.path == "/edit":
                        return self._send(200, api.call(c.edit, body["session_id"], body.get("line"),
                                                        body.get("text", ""), body.get("drop_item")))
                    if url.path == "/host_turn":
                        allowed = {"turn_type", "text", "ts", "ts_end", "is_voice", "record_id", "conversation_id"}
                        kw = {k: v for k, v in body.items() if k in allowed}
                        return self._send(200, {"result": api.run(c.observe_host_turn(**kw))})
                    if url.path == "/import":
                        return self._send(200, {"session_id": api.call(c.import_session, body)})
                except (KeyError, ValueError, json.JSONDecodeError) as e:
                    return self._send(400, {"error": str(e)})
                except Exception as e:
                    return self._send(500, {"error": f"{type(e).__name__}: {e}"})
                return self._send(404, {"error": "not_found"})

        self._server = ThreadingHTTPServer((self.host, self.port), Handler)
        self._server.daemon_threads = True
        self._thread = threading.Thread(target=self._server.serve_forever, name="bansheng-http", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        if self._server:
            self._server.shutdown()
            self._server.server_close()
            self._server = None
