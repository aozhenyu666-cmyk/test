"""调度器：接收事件 → 安排思考 → 管理候选 → 交付或撤销。

全部运行在一个 asyncio 事件循环里；HTTP 线程通过 run_coroutine_threadsafe 进来。
"""
from __future__ import annotations

import asyncio
import json
import re
import time
from pathlib import Path
from typing import Any, Protocol

from .events import Speaker, TurnEvent
from .gate import (Candidate, Decision, Floor, Policy, decide, NEED_PATTERNS, STOP_PATTERNS,
                   STOP_ANYWHERE, RESUME_PATTERNS, NOISY_PATTERNS)
from .ledger import Ledger
from .llm import LLM
from .mind import Trigger, build_user_prompt, parse_candidates, system_prompt
from .state import SessionState

CORRECTION_PATTERNS = re.compile(r"(没说准|说错了|不是这个意思|我改|改口|算了|其实我|收回|不对，|我更希望|我不是)")
MAIN_AI_NAMES = {"gemini": "Gemini", "chatgpt": "ChatGPT"}


class Deliverer(Protocol):
    async def say(self, cand: Candidate, session: SessionState) -> dict: ...
    async def feed_context(self, session: SessionState, digest: str) -> None: ...
    async def stop_speaking(self, reason: str) -> None: ...


class PrintDeliverer:
    """无宿主时使用：打印出来，并记录说过的话（测试和回放用）。"""

    def __init__(self, echo: bool = True):
        self.said: list[str] = []
        self.contexts: list[str] = []
        self.stops: list[str] = []
        self.echo = echo

    async def say(self, cand: Candidate, session: SessionState) -> dict:
        self.said.append(cand.text)
        if self.echo:
            print(f"[伴生] {cand.text}", flush=True)
        return {"submitted": True, "channel": "print"}

    async def feed_context(self, session: SessionState, digest: str) -> None:
        self.contexts.append(digest)

    async def stop_speaking(self, reason: str) -> None:
        self.stops.append(reason)


def _seq(event_id: str) -> int:
    try:
        return int(event_id.lstrip("e"))
    except ValueError:
        return 0


def _safe_name(session_id: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]", "_", session_id)[:120] or "session"


class Companion:
    def __init__(self, llm: LLM | None, deliverer: Deliverer, data_dir: Path | str | None = None,
                 policy: Policy | None = None, debounce_s: float = 0.6,
                 context_min_gap_s: float = 15.0):
        self.llm = llm
        self.deliverer = deliverer
        self.data_dir = Path(data_dir) if data_dir else None
        self.policy = policy or Policy()
        self.debounce_s = debounce_s
        self.context_min_gap_s = context_min_gap_s
        self.ledger = Ledger(self.data_dir / "ledger" if self.data_dir else None)
        self.sessions: dict[str, SessionState] = {}
        self.floors: dict[str, Floor] = {}
        self.held: dict[str, list[Candidate]] = {}
        self.decided: list[Candidate] = []
        self.delivered_texts: dict[str, list[str]] = {}
        self.last_delivery_at = 0.0
        self.active_session = ""
        self._think_tasks: dict[str, asyncio.Task] = {}
        self._pending_trigger: dict[str, Trigger] = {}
        self._last_context_at: dict[str, float] = {}
        self._cand_seq = 0
        self._tick_lock = asyncio.Lock()
        if self.data_dir:
            (self.data_dir / "sessions").mkdir(parents=True, exist_ok=True)
            self._load_settings()

    # ------------------------------------------------------------------ 会话
    def session(self, sid: str) -> SessionState:
        s = self.sessions.get(sid)
        if s is None:
            s = self._load_session(sid) or SessionState(sid)
            self.sessions[sid] = s
            self.floors.setdefault(sid, Floor())
            self.ledger.log("session_open", session_id=sid, restored=bool(s.turns), revision=s.revision)
        return s

    def main_ai_name(self, sid: str) -> str:
        return MAIN_AI_NAMES.get(sid.split(":", 1)[0].lower(), "主力 AI")

    def _session_path(self, sid: str) -> Path | None:
        return self.data_dir / "sessions" / f"{_safe_name(sid)}.json" if self.data_dir else None

    def _load_session(self, sid: str) -> SessionState | None:
        p = self._session_path(sid)
        if p and p.exists():
            try:
                return SessionState.from_dict(json.loads(p.read_text("utf-8")))
            except Exception as e:  # 损坏的文件不阻止新会话
                self.ledger.log("session_load_error", session_id=sid, error=str(e))
        return None

    def save(self, sid: str) -> None:
        p = self._session_path(sid)
        if p and sid in self.sessions:
            tmp = p.with_suffix(".tmp")
            tmp.write_text(json.dumps(self.sessions[sid].to_dict(), ensure_ascii=False), "utf-8")
            tmp.replace(p)

    def list_sessions(self) -> list[dict]:
        out = {sid: s.updated_at for sid, s in self.sessions.items()}
        if self.data_dir:
            for p in (self.data_dir / "sessions").glob("*.json"):
                try:
                    d = json.loads(p.read_text("utf-8"))
                    out.setdefault(d["session_id"], d.get("updated_at", 0))
                except Exception:
                    continue
        return [{"session_id": k, "updated_at": v} for k, v in sorted(out.items(), key=lambda x: -x[1])]

    # ------------------------------------------------------------------ 设置
    def _settings_path(self) -> Path | None:
        return self.data_dir / "settings.json" if self.data_dir else None

    def _load_settings(self) -> None:
        p = self._settings_path()
        if p and p.exists():
            try:
                d = json.loads(p.read_text("utf-8"))
                self.policy.level = int(d.get("level", self.policy.level))
                self.policy.muted = bool(d.get("muted", False))
            except Exception:
                pass

    def _save_settings(self) -> None:
        p = self._settings_path()
        if p:
            p.write_text(json.dumps({"level": self.policy.level, "muted": self.policy.muted}), "utf-8")

    # ------------------------------------------------------------------ 输入
    async def ingest(self, raw_events: list[dict]) -> dict:
        counts: dict[str, int] = {}
        touched: set[str] = set()
        for raw in raw_events:
            try:
                ev = TurnEvent.from_dict(raw)
            except (ValueError, TypeError) as e:
                counts["invalid"] = counts.get("invalid", 0) + 1
                self.ledger.log("event_invalid", error=str(e))
                continue
            s = self.session(ev.session_id)
            floor = self.floors[ev.session_id]
            result, accepted = s.accept(ev)
            counts[result] = counts.get(result, 0) + 1
            if ev.speaker == Speaker.MAIN_AI:
                floor.main_ai_streaming = s.streaming(Speaker.MAIN_AI)
            if result not in ("new", "revised"):
                continue
            touched.add(ev.session_id)
            if not ev.backfill:
                self.active_session = ev.session_id
            self.ledger.log("event", session_id=ev.session_id, event_id=accepted.event_id,
                            speaker=ev.speaker, source=ev.source, to=ev.addressee or None,
                            backfill=ev.backfill, result=result, chars=len(ev.text),
                            preview=ev.text[:80])
            if ev.backfill or ev.speaker == Speaker.COMPANION:
                continue
            await self._on_new_turn(s, accepted)
        for sid in touched:
            self.save(sid)
        return counts

    async def _on_new_turn(self, s: SessionState, ev: TurnEvent) -> None:
        sid = s.session_id
        if ev.speaker == Speaker.USER:
            # 用户开口了：之前准备的话都不再适用；正在播的也停
            await self._cancel_held(sid, "user_continued")
            text = ev.text.strip()
            if STOP_PATTERNS.search(text) or STOP_ANYWHERE.search(text):
                await self.control("mute", source=f"user:{ev.event_id}")
                return
            if RESUME_PATTERNS.search(text):
                await self.control("unmute", source=f"user:{ev.event_id}")
            if NOISY_PATTERNS.search(text) and self.policy.level > 1:
                await self.control("level", self.policy.level - 1, source=f"user:{ev.event_id}")
            if NEED_PATTERNS.search(text):
                self._schedule_think(sid, Trigger("user_stuck", ev.event_id, "用户明确表示卡住/不知道怎么继续"))
            elif CORRECTION_PATTERNS.search(text) or ev.revises:
                self._schedule_think(sid, Trigger("user_corrected", ev.event_id, "用户在修正或改口"))
            elif ev.addressee == "main_ai":
                # 主力 AI 马上会回答，不另起一轮；等它的回复一起想
                self.ledger.log("think_deferred", session_id=sid, event_id=ev.event_id,
                                reason="waiting_for_main_ai_reply")
            else:
                self._schedule_think(sid, Trigger("user_said", ev.event_id, "用户说了一句（对象不确定）"))
        elif ev.speaker == Speaker.MAIN_AI:
            await self._cancel_held(sid, "main_ai_moved_on")
            detail = f"{self.main_ai_name(sid)} 刚完成一条回复，约 {len(ev.text)} 字"
            last_user = s.last_event(Speaker.USER, include_backfill=False)
            kind = "main_ai_reply"
            if last_user and NEED_PATTERNS.search(last_user.text) and _seq(last_user.event_id) > _seq(self._last_spoken_seq(sid)):
                kind, detail = "user_stuck", detail + "；用户在这之前说了自己卡住"
            self._schedule_think(sid, Trigger(kind, ev.event_id, detail))

    def _last_spoken_seq(self, sid: str) -> str:
        s = self.sessions[sid]
        last = s.last_event(Speaker.COMPANION)
        return last.event_id if last else "e0"

    def set_floor(self, sid: str, **flags: Any) -> dict:
        self.session(sid)
        f = self.floors[sid]
        now = time.monotonic()
        if flags.get("user_typing"):
            f.user_typing_at = now
        for name in ("user_speaking", "main_ai_streaming", "main_ai_speaking", "companion_speaking"):
            if name in flags and flags[name] is not None:
                setattr(f, name, bool(flags[name]))
        if flags.get("user_speaking"):
            # 用户开始说话：伴生立刻让出话权
            asyncio.ensure_future(self.deliverer.stop_speaking("user_speaking"))
        return {"busy": f.busy(self.policy, now) or None}

    # ------------------------------------------------------------------ 思考
    def _schedule_think(self, sid: str, trigger: Trigger) -> None:
        if self.llm is None:
            self.ledger.log("think_skipped", session_id=sid, reason="no_llm_configured")
            return
        task = self._think_tasks.get(sid)
        if task and not task.done():
            prev = self._pending_trigger.get(sid)
            # 保留更强的触发理由
            if not prev or trigger.kind in ("user_stuck", "user_corrected") or prev.kind not in ("user_stuck",):
                self._pending_trigger[sid] = trigger
            return
        self._think_tasks[sid] = asyncio.ensure_future(self._think_loop(sid, trigger))

    async def _think_loop(self, sid: str, trigger: Trigger) -> None:
        while trigger is not None:
            await asyncio.sleep(self.debounce_s)
            # 防抖期间来了更新的触发，就用更新的
            trigger = self._pending_trigger.pop(sid, trigger)
            try:
                await self.think(sid, trigger)
            except Exception as e:
                self.ledger.log("think_error", session_id=sid, error=f"{type(e).__name__}: {e}")
            trigger = self._pending_trigger.pop(sid, None)

    async def think(self, sid: str, trigger: Trigger) -> list[Candidate]:
        s = self.session(sid)
        main_ai = self.main_ai_name(sid)
        rev0, seq0 = s.revision, s.material_seq()
        recent = self.delivered_texts.get(sid, [])
        self.ledger.log("think_start", session_id=sid, trigger=trigger.kind, event_id=trigger.event_id,
                        revision=rev0)
        out, meta = await self.llm.chat_json(system_prompt(main_ai),
                                             build_user_prompt(s, trigger, recent, self.policy.level, main_ai))
        results = s.apply_ops(out.get("ops") or [])
        self.ledger.log("think_done", session_id=sid, trigger=trigger.kind, read=str(out.get("read", ""))[:200],
                        latency_ms=meta.get("latency_ms"), model=meta.get("model"),
                        ops=[{"op": r.op.get("op"), "ok": r.ok, "reason": r.reason, "text": r.op.get("text")}
                             for r in results],
                        revision=s.revision)
        if s.material_seq() != seq0:
            # 思考期间又来了新话：这批候选基于旧材料，不再使用（新的思考已排队）
            self.ledger.log("candidates_discarded", session_id=sid, reason="new_turns_during_think")
            self.save(sid)
            return []
        explicit = trigger.kind == "user_stuck"
        not_before = 0.0
        trig_ev = s.event(trigger.event_id)
        if trig_ev and trig_ev.speaker == Speaker.MAIN_AI:
            not_before = time.monotonic() + self.policy.reading_grace(len(trig_ev.text))
        new: list[Candidate] = []
        for c in parse_candidates(out.get("candidates")):
            self._cand_seq += 1
            cand = Candidate(id=f"k{self._cand_seq}", session_id=sid, text=c["text"], kind=c["kind"],
                             score=c["score"], basis=c["basis"], trigger_event=trigger.event_id,
                             trigger_seq=seq0, state_rev=s.revision, why_now=c["why_now"],
                             why_not=c["why_not"], explicit_need=explicit, not_before=not_before)
            new.append(cand)
            self.ledger.log("candidate", session_id=sid, candidate=cand.id, text=cand.text,
                            style=cand.kind, score=cand.score, basis=cand.basis, why_now=cand.why_now,
                            why_not=cand.why_not, explicit_need=explicit, state_rev=cand.state_rev)
        await self._cancel_held(sid, "superseded_by_new_thought")
        self.held[sid] = sorted(new, key=lambda c: -c.score)
        self.save(sid)
        await self._maybe_feed_context(s, force=s.revision != rev0)
        await self.tick()
        return new

    async def _maybe_feed_context(self, s: SessionState, force: bool = False) -> None:
        now = time.monotonic()
        last = self._last_context_at.get(s.session_id, 0.0)
        if not force and now - last < self.context_min_gap_s:
            return
        self._last_context_at[s.session_id] = now
        sp = s.signpost()
        main_ai = self.main_ai_name(s.session_id)
        lines = [f"当前在聊：{sp['topic']}", f"已确认：{sp['confirmed']}", f"还没解开：{sp['open']}"]
        cons = [c.text for c in s.active("constraint")][-3:]
        if cons:
            lines.append("用户的约束：" + "；".join(cons))
        lu, lm = s.last_event(Speaker.USER), s.last_event(Speaker.MAIN_AI)
        if lu:
            lines.append(f"用户最近说：「{lu.text[:120]}」")
        if lm:
            lines.append(f"{main_ai} 最近回复开头：「{lm.text[:160]}」")
        digest = f"（伴生旁听：{{MASTER_NAME}}正在和 {main_ai} 讨论，以下来自网页文字采集）\n" + "\n".join(lines)
        try:
            await self.deliverer.feed_context(s, digest)
            self.ledger.log("context_fed", session_id=s.session_id, revision=s.revision)
        except Exception as e:
            self.ledger.log("context_feed_error", session_id=s.session_id, error=str(e))

    # ------------------------------------------------------------------ 交付
    async def _cancel_held(self, sid: str, reason: str) -> None:
        for c in self.held.pop(sid, []):
            self._close(c, "cancelled", reason)

    def _close(self, c: Candidate, status: str, reason: str) -> None:
        c.status, c.reason, c.decided_at = status, reason, time.monotonic()
        self.decided.append(c)
        self.decided = self.decided[-200:]
        self.ledger.log("decision", session_id=c.session_id, candidate=c.id, status=status, reason=reason,
                        text=c.text, score=c.score)

    async def tick(self) -> None:
        async with self._tick_lock:
            for sid in list(self.held):
                cands = self.held.get(sid) or []
                if not cands:
                    continue
                s = self.sessions[sid]
                lu = s.last_event(Speaker.USER, include_backfill=False)
                lm = s.last_event(Speaker.MAIN_AI, include_backfill=False)
                retired = {i.id for i in s.items if i.status != "active"}
                keep: list[Candidate] = []
                chosen: Candidate | None = None
                for c in cands:
                    d: Decision = decide(
                        c, policy=self.policy, floor=self.floors[sid],
                        latest_user_seq=_seq(lu.event_id) if lu else 0,
                        latest_main_seq=_seq(lm.event_id) if lm else 0,
                        recent_delivered=self.delivered_texts.get(sid, []),
                        last_delivery_at=self.last_delivery_at, retired_ids=retired)
                    if d.action == "cancel":
                        self._close(c, "cancelled", d.reason)
                    elif d.action == "deliver" and chosen is None:
                        chosen = c
                    else:
                        if c.reason != d.reason:
                            c.reason = d.reason
                            self.ledger.log("hold", session_id=sid, candidate=c.id, reason=d.reason)
                        keep.append(c)
                if chosen is not None:
                    for c in keep:
                        self._close(c, "cancelled", "sibling_delivered")
                    keep = []
                    await self._deliver(s, chosen)
                self.held[sid] = keep

    async def _deliver(self, s: SessionState, c: Candidate) -> None:
        self.last_delivery_at = time.monotonic()
        self.delivered_texts.setdefault(s.session_id, []).append(c.text)
        try:
            res = await self.deliverer.say(c, s)
        except Exception as e:
            res = {"submitted": False, "error": str(e)}
        self._close(c, "delivered" if res.get("submitted") else "cancelled",
                    "ok" if res.get("submitted") else f"deliver_failed:{res.get('error') or res.get('reason')}")
        if res.get("submitted"):
            s.accept(TurnEvent(session_id=s.session_id, speaker=Speaker.COMPANION, text=c.text,
                               source="bansheng", turn_key=f"c:{c.id}", addressee="user"))
            self.save(s.session_id)

    async def run_forever(self, interval: float = 0.4) -> None:
        while True:
            try:
                await self.tick()
            except Exception as e:
                self.ledger.log("tick_error", error=str(e))
            await asyncio.sleep(interval)

    # ------------------------------------------------------------------ 控制
    async def control(self, action: str, value: Any = None, source: str = "ui") -> dict:
        if action == "mute":
            self.policy.muted = True
            for sid in list(self.held):
                await self._cancel_held(sid, "muted")
            await self.deliverer.stop_speaking("muted")
        elif action == "unmute":
            self.policy.muted = False
        elif action == "level":
            self.policy.level = max(0, min(3, int(value)))
        elif action == "stop_speaking":
            await self.deliverer.stop_speaking("ui")
        elif action == "think":
            sid = value or self.active_session
            if sid:
                last = self.session(sid).last_event()
                self._schedule_think(sid, Trigger("manual", last.event_id if last else "", "用户手动要求伴生想一想"))
        else:
            raise ValueError(f"unknown action {action}")
        self._save_settings()
        self.ledger.log("control", action=action, value=value, source=source,
                        level=self.policy.level, muted=self.policy.muted)
        return {"level": self.policy.level, "muted": self.policy.muted}

    def edit(self, sid: str, line: str | None = None, text: str = "", drop_item: str | None = None) -> dict:
        s = self.session(sid)
        if drop_item:
            ok = s.drop_item(drop_item)
        else:
            s.edit_line(line or "", text)
            ok = True
        self.ledger.log("manual_edit", session_id=sid, line=line, drop_item=drop_item, text=text, ok=ok)
        self.save(sid)
        return {"ok": ok, "signpost": s.signpost()}

    # ------------------------------------------------------------------ 查看
    def snapshot(self, sid: str | None = None) -> dict:
        sid = sid or self.active_session
        base = {"policy": {"level": self.policy.level, "muted": self.policy.muted},
                "llm": getattr(self.llm, "name", None), "active_session": self.active_session}
        if not sid:
            return {**base, "session_id": None}
        s = self.session(sid)
        f = self.floors[sid]
        return {
            **base,
            "session_id": sid,
            "main_ai": self.main_ai_name(sid),
            "signpost": s.signpost(),
            "state": s.brief_for_model(),
            "floor": {"busy": f.busy(self.policy, time.monotonic()) or None,
                      "main_ai_streaming": f.main_ai_streaming},
            "held": [c.to_dict() for c in self.held.get(sid, [])],
            "recent_decisions": [c.to_dict() for c in self.decided if c.session_id == sid][-12:],
            "turns": s.transcript(n=8, max_chars=240),
            "thinking": bool(self._think_tasks.get(sid) and not self._think_tasks[sid].done()),
        }

    def export(self, sid: str) -> dict:
        return self.session(sid).to_dict()

    def import_session(self, data: dict) -> str:
        s = SessionState.from_dict(data)
        self.sessions[s.session_id] = s
        self.floors.setdefault(s.session_id, Floor())
        self.save(s.session_id)
        return s.session_id
