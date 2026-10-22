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
import os
import threading
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


class NekoDeliverer:
    def __init__(self, plugin: "BanshengPlugin"):
        self.p = plugin

    async def say(self, cand: Candidate, session: SessionState) -> dict:
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

    async def feed_context(self, session: SessionState, digest: str) -> None:
        self.p.ctx.push_message(
            source="bansheng",
            visibility=[],
            ai_behavior="read",
            parts=[{"type": "text", "text": digest}],
            coalesce_key=f"bansheng_ctx",
            metadata={"session_id": session.session_id},
        )

    async def stop_speaking(self, reason: str) -> None:
        # N.E.K.O. 目前没有给插件的"立即停止角色语音"接口；用户开口时由它自己的打断处理。
        self.p.companion.ledger.log("stop_speaking_unsupported", reason=reason)


@neko_plugin
class BanshengPlugin(NekoPluginBase):
    def __init__(self, ctx: Any):
        super().__init__(ctx)
        self.companion: Companion | None = None
        self.http: CompanionHTTP | None = None
        self._ticker = None
        self._loop: asyncio.AbstractEventLoop | None = None

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
        self.companion = Companion(self._make_llm(), NekoDeliverer(self), data_dir=data_dir,
                                   policy=Policy(level=level))
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
        self.logger.info("伴生已启动：http://127.0.0.1:{}/  模型={}", port, self.companion.llm.name)
        return Ok({"status": "running", "port": port, "llm": self.companion.llm.name,
                   "dashboard": f"http://127.0.0.1:{port}/", "token_required": bool(token)})

    @lifecycle(id="shutdown")
    def shutdown(self, **_):
        if self._ticker:
            self._ticker.cancel()
        if self.http:
            self.http.stop()
        if getattr(self, "_loop", None):
            self._loop.call_soon_threadsafe(self._loop.stop)
        return Ok({"status": "stopped"})

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
