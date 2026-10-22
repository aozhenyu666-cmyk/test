"""输入事件：谁、在哪个会话、什么时候、从哪来、内容是否完整。"""
from __future__ import annotations

import hashlib
import re
import time
from dataclasses import dataclass, field, asdict
from typing import Any


class Speaker:
    USER = "user"
    MAIN_AI = "main_ai"      # Gemini / ChatGPT
    COMPANION = "companion"  # 伴生自己
    ALL = (USER, MAIN_AI, COMPANION)


_WS = re.compile(r"\s+")


def normalize(text: str) -> str:
    return _WS.sub(" ", (text or "")).strip()


def text_hash(text: str) -> str:
    compact = re.sub(r"\s+", "", text or "")
    return hashlib.sha1(compact.encode("utf-8")).hexdigest()[:12]


@dataclass
class TurnEvent:
    session_id: str               # 例如 "gemini:abc123"
    speaker: str                  # Speaker.*
    text: str
    source: str = "manual"        # gemini_web / chatgpt_web / neko / manual / replay
    turn_key: str = ""            # 适配器给出的稳定轮次标识，例如 "u3"、"m3"
    complete: bool = True         # False = 流式中途片段
    addressee: str = ""           # main_ai / companion / ""（未知）
    backfill: bool = False        # 页面加载或重连时补发的旧历史，不触发介入
    ts: float = field(default_factory=time.time)
    event_id: str = ""            # 接收后由会话分配
    revises: str = ""             # 若是对旧轮次的编辑，指向旧 event_id

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "TurnEvent":
        speaker = str(d.get("speaker", "")).strip()
        if speaker not in Speaker.ALL:
            raise ValueError(f"unknown speaker: {speaker!r}")
        session_id = str(d.get("session_id", "")).strip()
        if not session_id:
            raise ValueError("session_id is required")
        ts = d.get("ts")
        return cls(
            session_id=session_id[:200],
            speaker=speaker,
            text=str(d.get("text", ""))[:20000],
            source=str(d.get("source", "manual"))[:40],
            turn_key=str(d.get("turn_key", ""))[:120],
            complete=bool(d.get("complete", True)),
            addressee=str(d.get("addressee", ""))[:20],
            backfill=bool(d.get("backfill", False)),
            ts=float(ts) if isinstance(ts, (int, float)) else time.time(),
        )

    @property
    def key(self) -> str:
        return self.turn_key or f"{self.speaker}:{text_hash(self.text)}"

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)
