"""伴生给 N.E.K.O. 加的一个小路由（可选补丁，由 bansheng/scripts/install_into_neko.py --patch 安装）。

N.E.K.O. 没有给插件"立刻停止角色说话"和"原样说一句话"的接口，这里把两个已有的内部方法露出来：
- POST /api/bansheng/interrupt  → SessionManager.interrupt_mirror_speech()（清 TTS 队列、取消实时回复、通知前端丢弃音频）
- POST /api/bansheng/speak      → SessionManager.mirror_assistant_speech(line)（不经角色模型改写，直接用项目 TTS 说）
- GET  /api/bansheng/ping

只接受本机、没有浏览器 Origin、并带有伴生插件写入的令牌的请求。
"""
from __future__ import annotations

import json
import secrets
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from main_logic.mirror_meta import build_mirror_meta
from utils.logger_config import get_module_logger

from .local_access import is_direct_loopback_request
from .shared_state import get_config_manager, get_session_manager

logger = get_module_logger(__name__, "Main")
router = APIRouter(prefix="/api/bansheng", tags=["bansheng"])
PATCH_VERSION = 2
TOKEN_FILE = Path.home() / ".neko" / "bansheng_host.json"


def _deny(reason: str, code: int = 403) -> JSONResponse:
    return JSONResponse({"ok": False, "reason": reason}, status_code=code)


def _check(request: Request) -> JSONResponse | None:
    if not is_direct_loopback_request(request):
        return _deny("not_loopback")
    if request.headers.get("origin"):
        return _deny("browser_origin_not_allowed")
    try:
        expected = json.loads(TOKEN_FILE.read_text("utf-8")).get("token", "")
    except Exception:
        return _deny("token_file_missing")
    got = request.headers.get("x-bansheng-host-token", "")
    if not expected or not secrets.compare_digest(got, expected):
        return _deny("bad_token", 401)
    return None


def _managers(lanlan_name: str | None) -> list[tuple[str, Any]]:
    sm = get_session_manager()
    if lanlan_name:
        mgr = sm.get(lanlan_name)
        return [(lanlan_name, mgr)] if mgr else []
    try:
        current = get_config_manager().load_characters().get("当前猫娘")
    except Exception:
        current = None
    if current and sm.get(current):
        return [(current, sm.get(current))]
    return list(sm.items())


@router.get("/ping")
async def bansheng_ping(request: Request):
    if (denied := _check(request)) is not None:
        return denied
    return {"ok": True, "patch_version": PATCH_VERSION}


@router.post("/interrupt")
async def bansheng_interrupt(request: Request):
    if (denied := _check(request)) is not None:
        return denied
    data: dict = {}
    try:
        data = await request.json()
    except Exception:
        pass
    done = []
    for name, mgr in _managers((data or {}).get("lanlan_name")):
        fn = getattr(mgr, "interrupt_mirror_speech", None)
        if callable(fn):
            try:
                await fn()
                done.append(name)
            except Exception as e:
                logger.warning("[bansheng] interrupt failed for %s: %s", name, e)
    return {"ok": bool(done), "interrupted": done}


@router.post("/speak")
async def bansheng_speak(request: Request):
    if (denied := _check(request)) is not None:
        return denied
    try:
        data = await request.json()
    except Exception:
        return _deny("bad_json", 400)
    line = str((data or {}).get("text") or "").strip()
    if not line:
        return _deny("missing_text", 400)
    targets = _managers(data.get("lanlan_name"))
    if not targets:
        return _deny("no_session_manager", 409)
    name, mgr = targets[0]
    speak = getattr(mgr, "mirror_assistant_speech", None)
    if not callable(speak):
        return _deny("mirror_speech_unavailable", 409)
    meta = build_mirror_meta(source="bansheng", kind="bansheng", session_id=str(data.get("session_id") or ""),
                             event={})
    # wait=true：等前端报告播放结束再返回（audio_completed），伴生用它记录真实播放时间
    result = await speak(line, metadata=meta, interrupt_audio=bool(data.get("interrupt", False)),
                         mirror_text=True, emit_turn_end_after=True,
                         wait_for_audio_completion=bool(data.get("wait", False)),
                         audio_completion_timeout=float(data.get("timeout", 45.0)))
    return {"ok": bool(isinstance(result, dict) and result.get("ok")), "lanlan_name": name, "result": result}
