"""伴生：N.E.K.O. 插件。

    浏览器扩展（Gemini / ChatGPT 页面）──HTTP──▶ 本插件引擎 ──push_message──▶ N.E.K.O. 角色
                                                   │
                                                   └─ 面板 http://127.0.0.1:48925/

- 会话理解与候选由 engine/ 完成（不依赖 N.E.K.O.）。
- 角色发声、TTS、用户开口打断、播放闸门由 N.E.K.O. 现有链路负责：
  ai_behavior="read" 只喂上下文；ai_behavior="respond" 让角色用自己的声音说一句。
"""
from __future__ import annotations

import asyncio
import inspect
import json
import os
import secrets
import urllib.request
from pathlib import Path
import threading
import time
from collections import deque
from typing import Any

from plugin.sdk.plugin import NekoPluginBase, Ok, lifecycle, neko_plugin, plugin_entry

from .engine import Companion, OpenAICompatLLM, Policy
from .engine.gate import Candidate
from .engine.httpapi import CompanionHTTP
from .engine.state import SessionState

DEFAULT_PORT = 48925

SAY_TEMPLATE = (
    "（伴生提示：{{MASTER_NAME}}正在和 {main_ai} 讨论，你在旁边听着，内容来自网页文字采集。"
    "现在适合你插一句。请用你自己的口吻，一两句短话，自然地说出下面这个意思；"
    "不要复述或总结讨论，不要提到这条提示，也不要追加长篇建议。）\n"
    "要表达的意思：{text}"
)


HOST_TOKEN_FILE = Path.home() / ".neko" / "bansheng_host.json"


def _main_port() -> int:
    try:
        from config import MAIN_SERVER_PORT  # N.E.K.O. 的端口规划
        return int(MAIN_SERVER_PORT)
    except Exception:
        return int(os.environ.get("NEKO_MAIN_SERVER_PORT") or 48911)


class NekoHostPatch:
    """可选补丁 /api/bansheng/*（见 bansheng/neko_patch/）。没装补丁时 available=False。"""

    def __init__(self):
        self.token = secrets.token_urlsafe(24)
        self.available = False
        self.version = 0
        try:
            HOST_TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
            HOST_TOKEN_FILE.write_text(json.dumps({"token": self.token}), "utf-8")
        except OSError:
            pass

    def _request(self, path: str, body: dict | None = None, timeout: float = 5.0) -> dict:
        req = urllib.request.Request(
            f"http://127.0.0.1:{_main_port()}/api/bansheng{path}",
            data=json.dumps(body).encode() if body is not None else None,
            method="POST" if body is not None else "GET",
            headers={"Content-Type": "application/json", "X-Bansheng-Host-Token": self.token})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            try:
                return {"ok": False, "status": e.code, **json.loads(e.read() or b"{}")}
            except Exception:
                return {"ok": False, "status": e.code}
        except Exception as e:
            return {"ok": False, "reason": f"{type(e).__name__}: {e}"}

    async def call(self, path: str, body: dict | None = None, timeout: float = 5.0) -> dict:
        return await asyncio.get_running_loop().run_in_executor(None, self._request, path, body, timeout)

    async def probe(self) -> bool:
        r = await self.call("/ping", timeout=2.0)
        self.available = bool(r.get("ok"))
        self.version = int(r.get("patch_version") or 0)
        return self.available


class NekoDeliverer:
    def __init__(self, plugin: "BanshengPlugin", voice_mode: str = "rephrase"):
        self.p = plugin
        # rephrase：交给角色模型用自己的口吻说（默认，人设一致）
        # verbatim：原样交给 TTS 说（需要补丁；少一次模型调用，更快）
        self.voice_mode = voice_mode if voice_mode in ("rephrase", "verbatim") else "rephrase"

    async def say(self, cand: Candidate, session: SessionState) -> dict:
        patch = self.p.host_patch
        if self.voice_mode == "verbatim" and patch and patch.available:
            # 后台等播放结束；前 1.5 秒内就失败的话改走 rephrase
            fut = asyncio.ensure_future(patch.call(
                "/speak", {"text": cand.text, "session_id": session.session_id, "wait": True, "timeout": 45},
                timeout=60.0))
            done, _ = await asyncio.wait({fut}, timeout=1.5)
            if not done or fut.result().get("ok"):
                fut.add_done_callback(lambda f: self._on_verbatim_done(cand, f))
                return {"submitted": True, "channel": "neko_verbatim"}
            self.p.companion.ledger.log("verbatim_failed", result=str(fut.result())[:200])
        main_ai = self.p.companion.main_ai_name(session.session_id)
        res = self.p.ctx.push_message(
            source="bansheng",
            visibility=[],
            ai_behavior="respond",
            parts=[{"type": "text", "text": SAY_TEMPLATE.format(main_ai=main_ai, text=cand.text)}],
            priority=6 if cand.explicit_need else 4,
            # 同一时刻只保留最新的一句；排队过久就丢掉，不说过时的话
            coalesce_key="bansheng_say",
            metadata={"expires_in_s": 12, "bansheng_candidate": cand.id, "session_id": session.session_id},
        )
        submitted = bool(getattr(res, "submitted", True)) if res is not None else True
        return {"submitted": submitted, "reason": getattr(res, "reason", None), "channel": "neko_respond"}

    def _on_verbatim_done(self, cand: Candidate, fut) -> None:
        try:
            r = fut.result()
        except Exception as e:
            r = {"ok": False, "reason": str(e)}
        res = r.get("result") or {}
        self.p.companion.mark_playback(cand.id, text=cand.text,
                                       ended_at=time.time() if res.get("audio_completed") else 0.0,
                                       detail={k: res.get(k) for k in ("audio_sent", "audio_completed", "speech_id")})

    async def feed_context(self, session: SessionState, digest: str) -> None:
        self.p.ctx.push_message(
            source="bansheng",
            visibility=[],
            ai_behavior="read",
            parts=[{"type": "text", "text": digest}],
            coalesce_key="bansheng_ctx",
            metadata={"session_id": session.session_id},
        )

    async def stop_speaking(self, reason: str) -> None:
        patch = self.p.host_patch
        if patch and patch.available:
            r = await patch.call("/interrupt", {})
            self.p.companion.ledger.log("stop_speaking", reason=reason, ok=bool(r.get("ok")),
                                        interrupted=r.get("interrupted"))
            return
        # 没装补丁：N.E.K.O. 没有给插件的停止接口；用户开口时由它自己的打断处理
        self.p.companion.ledger.log("stop_speaking_unsupported", reason=reason)


@neko_plugin
class BanshengPlugin(NekoPluginBase):
    def __init__(self, ctx: Any):
        super().__init__(ctx)
        self.companion: Companion | None = None
        self.http: CompanionHTTP | None = None
        self._ticker = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._bus_watch = None
        self.host_patch: NekoHostPatch | None = None

    def _make_llm(self):
        env_llm = OpenAICompatLLM.from_env(dict(os.environ))
        if env_llm is not None:
            return env_llm
        # 默认：用 N.E.K.O. 的「插件 API」模型槽，绑定 usage "mind"
        return OpenAICompatLLM(self.ctx.models.get_client, "mind", name="neko_slot:mind")

    @lifecycle(id="startup")
    async def startup(self, **_):
        try:
            cfg = await self.config.dump(timeout=5.0)
        except Exception:
            cfg = {}
        bcfg = (cfg or {}).get("bansheng") or {}
        port = int(bcfg.get("port") or os.environ.get("BANSHENG_PORT") or DEFAULT_PORT)
        token = str(bcfg.get("token") or os.environ.get("BANSHENG_TOKEN") or "")
        level = int(bcfg.get("level", 2))

        data_dir = self.data_path("bansheng")
        self.host_patch = NekoHostPatch()
        self.companion = Companion(self._make_llm(),
                                   NekoDeliverer(self, os.environ.get("BANSHENG_VOICE_MODE") or str(bcfg.get("voice_mode") or "rephrase")),
                                   data_dir=data_dir, policy=Policy(level=level))
        # 宿主的 startup 循环在返回后会关闭，所以伴生用自己的常驻循环线程
        self._loop = asyncio.new_event_loop()
        threading.Thread(target=self._loop.run_forever, name="bansheng-loop", daemon=True).start()
        self.http = CompanionHTTP(self.companion, self._loop, port=port, token=token)
        try:
            self.http.start()
        except OSError as e:
            self.logger.error("伴生 HTTP 端口 {} 无法监听: {}", port, e)
            return Ok({"status": "port_unavailable", "port": port})
        self._ticker = asyncio.run_coroutine_threadsafe(self.companion.run_forever(), self._loop)
        # 读 N.E.K.O. 的会话流：用户直接对角色说的话、角色实际说出的话
        self._bus_watch = asyncio.run_coroutine_threadsafe(self._watch_host_conversations(), self._loop)
        try:
            await asyncio.wrap_future(asyncio.run_coroutine_threadsafe(self.host_patch.probe(), self._loop))
        except Exception:
            pass
        self.companion.host_status.update(patch=self.host_patch.available,
                                          voice_mode=self.companion.deliverer.voice_mode)
        self.logger.info("伴生已启动：http://127.0.0.1:{}/  模型={}", port, self.companion.llm.name)
        return Ok({"status": "running", "port": port, "llm": self.companion.llm.name,
                   "host_patch": self.host_patch.available,
                   "dashboard": f"http://127.0.0.1:{port}/", "token_required": bool(token)})

    @lifecycle(id="shutdown")
    def shutdown(self, **_):
        if self._ticker:
            self._ticker.cancel()
        if getattr(self, "_bus_watch", None):
            self._bus_watch.cancel()
        if self.http:
            self.http.stop()
        if getattr(self, "_loop", None):
            self._loop.call_soon_threadsafe(self._loop.stop)
        return Ok({"status": "stopped"})

    async def _watch_host_conversations(self, interval: float = 0.7) -> None:
        """轮询 bus.conversations（只读）。记录形状见 N.E.K.O.
        main_logic/agent_event_bus.py::publish_conversation_turn_observed_best_effort。"""
        since = time.time()
        seen: deque[str] = deque(maxlen=600)
        log = self.companion.ledger
        failures = 0
        hs = self.companion.host_status
        hs.update(bus="starting", polls=0, records=0, last_error="")
        while True:
            await asyncio.sleep(interval)
            try:
                res = self.bus.conversations.get(since_ts=since - 2.0, max_count=50, timeout=3.0)
                if inspect.isawaitable(res):
                    res = await asyncio.wait_for(res, 6.0)
                records = list(res or [])
                failures = 0
                hs.update(bus="ok", polls=hs["polls"] + 1, records=hs["records"] + len(records),
                          raw_type=type(res).__name__)
            except Exception as e:
                failures += 1
                hs.update(bus="error", last_error=f"{type(e).__name__}: {e}"[:300])
                if failures in (1, 20):
                    log.log("host_bus_error", error=hs["last_error"])
                continue
            for r in sorted(records, key=lambda r: (r.metadata or {}).get("ts") or r.timestamp or 0):
                md = r.metadata or {}
                turn_type = r.turn_type or md.get("turn_type") or ""
                ts = md.get("ts") or r.timestamp
                key = f"{turn_type}|{r.conversation_id}|{ts}|{hash(r.content)}"
                if key in seen:
                    continue
                seen.append(key)
                if r.timestamp:
                    since = max(since, float(r.timestamp))
                try:
                    result = await self.companion.observe_host_turn(
                        turn_type=turn_type, text=r.content or "", ts=ts, ts_end=md.get("ts_end"),
                        is_voice=bool(md.get("is_voice")), record_id=key, conversation_id=r.conversation_id or "")
                    log.log("host_turn", turn_type=turn_type, result=result, preview=(r.content or "")[:60])
                except Exception as e:
                    log.log("host_turn_error", error=f"{type(e).__name__}: {e}")

    async def _on_loop(self, coro):
        return await asyncio.wrap_future(asyncio.run_coroutine_threadsafe(coro, self._loop))

    async def _snapshot(self):
        return self.companion.snapshot()

    # ---------------------------------------------------------------- 入口（角色和管理界面都能调）
    @plugin_entry(id="get_signpost", name="伴生三行路标",
                  description="读取当前讨论的三行路标：在聊什么 / 已确认什么 / 还有什么没解开",
                  input_schema={"type": "object", "properties": {}})
    async def get_signpost(self, **_):
        snap = await self._on_loop(self._snapshot())
        return Ok({"session_id": snap.get("session_id"), "signpost": snap.get("signpost")})

    @plugin_entry(id="set_level", name="设置主动程度", description="0 关闭主动、1 少、2 正常、3 多",
                  input_schema={"type": "object", "properties": {"level": {"type": "integer"}},
                                "required": ["level"]})
    async def set_level(self, level: int = 2, **_):
        return Ok(await self._on_loop(self.companion.control("level", level, source="entry")))

    @plugin_entry(id="mute", name="伴生安静", description="停止主动发言，直到恢复",
                  input_schema={"type": "object", "properties": {}})
    async def mute(self, **_):
        return Ok(await self._on_loop(self.companion.control("mute", source="entry")))

    @plugin_entry(id="unmute", name="恢复伴生", description="恢复主动陪伴",
                  input_schema={"type": "object", "properties": {}})
    async def unmute(self, **_):
        return Ok(await self._on_loop(self.companion.control("unmute", source="entry")))

    @plugin_entry(id="status", name="伴生状态", description="端口、模型、当前会话",
                  input_schema={"type": "object", "properties": {}})
    async def status(self, **_):
        snap = await self._on_loop(self._snapshot())
        return Ok({"port": self.http.port if self.http else None, "llm": snap.get("llm"),
                   "policy": snap.get("policy"), "session_id": snap.get("session_id")})
