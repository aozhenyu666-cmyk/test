"""当前会话理解：主题、约束、已确认、未解开、待考虑建议、改口。

规则（产品说明 F1/F5/F6）：
- 每一条都带来源事件；"已确认"必须能回到用户自己的表达，否则只能进"待考虑"。
- 用户改口时，旧约束标为 superseded，不再支配后续发言。
- 每次变更 revision+1，候选内容会带上生成时的 revision。
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field, asdict
from typing import Any, Iterable

from .events import Speaker, TurnEvent, normalize, text_hash

KINDS = ("constraint", "confirmed", "open", "pending", "correction")
MAX_TURNS = 300


@dataclass
class Item:
    id: str
    kind: str
    text: str
    evidence: list[str]
    status: str = "active"   # active / superseded / resolved / dropped
    closed_by: list[str] = field(default_factory=list)
    note: str = ""
    rev: int = 0


@dataclass
class OpResult:
    op: dict
    ok: bool
    reason: str = ""


class SessionState:
    def __init__(self, session_id: str):
        self.session_id = session_id
        self.revision = 0
        self.topic = ""
        self.topic_evidence: list[str] = []
        self.items: list[Item] = []
        self.turns: list[TurnEvent] = []          # 已接收的完整轮次（按顺序）
        self._by_key: dict[str, TurnEvent] = {}   # turn key → 最新事件
        self._partials: dict[str, TurnEvent] = {}
        self._seq = 0
        self._item_seq = 0
        self.manual_lines: dict[str, str] = {}     # 用户在路标上的手动修正
        self.updated_at = time.time()

    # ------------------------------------------------------------------ 事件
    def accept(self, ev: TurnEvent) -> tuple[str, TurnEvent | None]:
        """返回 (结果, 事件)。结果：new / revised / partial / duplicate / empty。"""
        if not normalize(ev.text):
            return "empty", None
        key = ev.key
        if not ev.complete:
            self._partials[key] = ev
            return "partial", ev
        self._partials.pop(key, None)
        old = self._by_key.get(key)
        if old is not None:
            if text_hash(old.text) == text_hash(ev.text):
                return "duplicate", None
            ev.revises = old.event_id
        elif len(normalize(ev.text)) >= 12:
            # 同一段长文本换了 key（页面重新渲染/滚动），视作重复
            h = text_hash(ev.text)
            for t in reversed(self.turns[-60:]):
                if t.speaker == ev.speaker and text_hash(t.text) == h:
                    self._by_key[key] = t
                    return "duplicate", None
        self._seq += 1
        ev.event_id = f"e{self._seq}"
        self.turns.append(ev)
        if len(self.turns) > MAX_TURNS:
            self.turns = self.turns[-MAX_TURNS:]
        self._by_key[key] = ev
        self.updated_at = time.time()
        return ("revised" if ev.revises else "new"), ev

    def event(self, event_id: str) -> TurnEvent | None:
        for t in reversed(self.turns):
            if t.event_id == event_id:
                return t
        return None

    def material_seq(self) -> int:
        """最近一条用户或主力 AI 发言的序号（不算伴生自己）。"""
        for t in reversed(self.turns):
            if t.speaker != Speaker.COMPANION:
                return int(t.event_id.lstrip("e") or 0)
        return 0

    def streaming(self, speaker: str) -> bool:
        return any(p.speaker == speaker for p in self._partials.values())

    def last_event(self, speaker: str | None = None, include_backfill: bool = True) -> TurnEvent | None:
        for t in reversed(self.turns):
            if (speaker is None or t.speaker == speaker) and (include_backfill or not t.backfill):
                return t
        return None

    # ------------------------------------------------------------------ 条目
    def active(self, kind: str) -> list[Item]:
        return [i for i in self.items if i.kind == kind and i.status == "active"]

    def item(self, item_id: str) -> Item | None:
        return next((i for i in self.items if i.id == item_id), None)

    def _new_item(self, kind: str, text: str, evidence: list[str], note: str = "") -> Item:
        self._item_seq += 1
        prefix = {"constraint": "c", "confirmed": "k", "open": "q", "pending": "p", "correction": "r"}[kind]
        it = Item(id=f"{prefix}{self._item_seq}", kind=kind, text=text.strip()[:200],
                  evidence=evidence, note=note[:200], rev=self.revision + 1)
        self.items.append(it)
        return it

    def _evidence_ok(self, ids: Iterable[str], need_user: bool) -> tuple[list[str], str]:
        ids = [str(i) for i in (ids or []) if isinstance(i, (str, int))]
        found = [i for i in ids if self.event(i) is not None]
        if not found:
            return [], "evidence_missing"
        if need_user and not any(self.event(i).speaker == Speaker.USER for i in found):
            return found, "needs_user_evidence"
        return found, ""

    def apply_ops(self, ops: list[dict]) -> list[OpResult]:
        """应用模型给出的更新。非法项拒绝并给出原因，不会抛异常。"""
        results: list[OpResult] = []
        changed = False
        for op in ops or []:
            if not isinstance(op, dict):
                continue
            r = self._apply_one(op)
            results.append(r)
            changed = changed or r.ok
        if changed:
            self.revision += 1
            self.updated_at = time.time()
        return results

    def _apply_one(self, op: dict) -> OpResult:
        name = str(op.get("op", ""))
        text = normalize(str(op.get("text", "")))
        ev = op.get("evidence") or []
        if name == "set_topic":
            found, why = self._evidence_ok(ev, need_user=False)
            if why or not text:
                return OpResult(op, False, why or "empty_text")
            self.topic, self.topic_evidence = text[:120], found
            self.manual_lines.pop("topic", None)
            return OpResult(op, True)
        if name in ("add_constraint", "confirm", "correction"):
            found, why = self._evidence_ok(ev, need_user=True)
            if why == "needs_user_evidence" and name == "confirm":
                # 非用户表达的"结论"只能进待考虑
                self._new_item("pending", text, found, note="模型想确认，但没有用户原话支撑")
                return OpResult(op, True, "downgraded_to_pending")
            if why or not text:
                return OpResult(op, False, why or "empty_text")
            kind = {"add_constraint": "constraint", "confirm": "confirmed", "correction": "correction"}[name]
            it = self._new_item(kind, text, found, note=str(op.get("note", "")))
            if kind == "confirmed":
                self.manual_lines.pop("confirmed", None)
                for p in self.active("pending"):
                    if _similar(p.text, text) > 0.5:
                        p.status, p.closed_by = "resolved", found
            for rid in op.get("retire", []) or []:
                old = self.item(str(rid))
                if old and old.status == "active" and old.id != it.id:
                    old.status, old.closed_by = "superseded", found
            return OpResult(op, True)
        if name in ("add_open", "add_pending"):
            found, why = self._evidence_ok(ev, need_user=False)
            if why or not text:
                return OpResult(op, False, why or "empty_text")
            kind = "open" if name == "add_open" else "pending"
            if any(_similar(i.text, text) > 0.7 for i in self.active(kind)):
                return OpResult(op, False, "near_duplicate")
            self._new_item(kind, text, found)
            if kind == "open":
                self.manual_lines.pop("open", None)
            return OpResult(op, True)
        if name in ("retire", "resolve"):
            target = self.item(str(op.get("id", "")))
            if target is None or target.status != "active":
                return OpResult(op, False, "unknown_or_closed_item")
            need_user = target.kind in ("constraint", "confirmed")
            found, why = self._evidence_ok(ev, need_user=need_user)
            if why:
                return OpResult(op, False, why)
            target.status = "resolved" if name == "resolve" else "superseded"
            target.closed_by = found
            target.note = str(op.get("reason", ""))[:200]
            return OpResult(op, True)
        return OpResult(op, False, "unknown_op")

    # ------------------------------------------------------------------ 手动修正
    def edit_line(self, line: str, text: str) -> None:
        """用户直接在路标上改字。用户的修正优先于模型。"""
        if line not in ("topic", "confirmed", "open"):
            raise ValueError("line must be topic/confirmed/open")
        text = normalize(text)[:120]
        if text:
            self.manual_lines[line] = text
        else:
            self.manual_lines.pop(line, None)
        if line == "confirmed" and text:
            self._new_item("confirmed", text, ["manual"], note="用户在路标上手动写入")
        self.revision += 1

    def drop_item(self, item_id: str) -> bool:
        it = self.item(item_id)
        if not it or it.status != "active":
            return False
        it.status, it.note = "dropped", "用户在路标上删除"
        self.revision += 1
        return True

    # ------------------------------------------------------------------ 展示
    def signpost(self) -> dict[str, str]:
        confirmed = self.active("confirmed")
        opens = self.active("open")
        return {
            "topic": self.manual_lines.get("topic") or self.topic or "（还在形成）",
            "confirmed": self.manual_lines.get("confirmed")
            or ("；".join(i.text for i in confirmed[-2:]) if confirmed else "（暂无）"),
            "open": self.manual_lines.get("open")
            or ("；".join(i.text for i in opens[-2:]) if opens else "（暂无）"),
        }

    def brief_for_model(self) -> dict[str, Any]:
        """给模型的会话理解摘要（只含 active 条目和最近改口）。"""
        def pack(kind):
            return [{"id": i.id, "text": i.text, "evidence": i.evidence} for i in self.active(kind)]
        retired = [{"id": i.id, "text": i.text, "closed_by": i.closed_by}
                   for i in self.items if i.kind == "constraint" and i.status == "superseded"][-4:]
        return {
            "revision": self.revision,
            "topic": self.manual_lines.get("topic") or self.topic,
            "constraints": pack("constraint"),
            "retired_constraints": retired,
            "confirmed": pack("confirmed"),
            "open": pack("open"),
            "pending_suggestions": pack("pending"),
            "corrections": pack("correction")[-4:],
            "user_manual_edits": dict(self.manual_lines),
        }

    def transcript(self, n: int = 14, max_chars: int = 1600) -> list[dict[str, Any]]:
        out = []
        for t in self.turns[-n:]:
            text = t.text
            if len(text) > max_chars:
                half = max_chars // 2
                text = text[:half] + f"\n…（中间省略 {len(text) - max_chars} 字）…\n" + text[-half:]
            out.append({"id": t.event_id, "speaker": t.speaker, "to": t.addressee or None,
                        "text": text, "backfill": t.backfill or None})
        return out

    # ------------------------------------------------------------------ 持久化
    def to_dict(self) -> dict[str, Any]:
        return {
            "session_id": self.session_id,
            "revision": self.revision,
            "topic": self.topic,
            "topic_evidence": self.topic_evidence,
            "items": [asdict(i) for i in self.items],
            "turns": [t.to_dict() for t in self.turns[-MAX_TURNS:]],
            "seq": self._seq,
            "item_seq": self._item_seq,
            "manual_lines": self.manual_lines,
            "updated_at": self.updated_at,
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "SessionState":
        s = cls(d["session_id"])
        s.revision = int(d.get("revision", 0))
        s.topic = d.get("topic", "")
        s.topic_evidence = list(d.get("topic_evidence", []))
        s.items = [Item(**i) for i in d.get("items", [])]
        for t in d.get("turns", []):
            ev = TurnEvent(**t)
            s.turns.append(ev)
            s._by_key[ev.key] = ev
        s._seq = int(d.get("seq", len(s.turns)))
        s._item_seq = int(d.get("item_seq", len(s.items)))
        s.manual_lines = dict(d.get("manual_lines", {}))
        s.updated_at = float(d.get("updated_at", time.time()))
        return s


def _bigrams(s: str) -> set[str]:
    s = normalize(s).lower()
    return {s[i:i + 2] for i in range(max(len(s) - 1, 1))}


def _similar(a: str, b: str) -> float:
    A, B = _bigrams(a), _bigrams(b)
    if not A or not B:
        return 0.0
    return len(A & B) / len(A | B)


similar = _similar
