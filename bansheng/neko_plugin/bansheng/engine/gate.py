"""是否交付：播放前再确认一次候选仍然相关、不重复、现在可以说。

借鉴 Thoughtful Agents 的"生成候选 → 评价 → 选择是否说"：模型给每条候选打
1–5 的表达意愿分，这里再叠加确定性的取消条件和话权判断。
"""
from __future__ import annotations

import re
import time
from dataclasses import dataclass, field, asdict
from typing import Any

from .state import similar

# 用户明确卡住 / 求助 —— 降低门槛、跳过冷却
NEED_PATTERNS = re.compile(
    r"(卡住|卡在|不知道(该|从|怎么|要)|说不清|说不上来|没思路|抓哪|从哪(里)?开始|怎么办|想不出|理不清|乱了)"
)
# 让伴生安静（只认短句，避免把"先别说这个方案"当成叫停）
STOP_PATTERNS = re.compile(r"^(伴生)?[，,\s]*(先?别说了?|安静|先安静|闭嘴|停一?下?|先停|别插话|别吵|嘘)[。！!，,\s]*$")
STOP_ANYWHERE = re.compile(r"(伴生|你)[，,\s]*(先?别说了?|先?安静|别插话|闭嘴)")
RESUME_PATTERNS = re.compile(r"(伴生[，,\s]*(回来|继续|可以说了|说吧)|你可以说话了|恢复陪伴)")
NOISY_PATTERNS = re.compile(r"(伴生|你)(也)?(有点|太|好)?(吵|烦|话多|啰嗦)")

KINDS = ("reaction", "curiosity", "challenge", "proposal", "backchannel", "help")


@dataclass
class Candidate:
    id: str
    session_id: str
    text: str
    kind: str
    score: float                  # 1–5 表达意愿
    basis: list[str]              # 依据的事件 id / 条目 id
    trigger_event: str            # 触发这次思考的事件 id
    trigger_seq: int              # 触发时会话里最新事件序号
    state_rev: int
    why_now: str = ""
    why_not: str = ""
    explicit_need: bool = False   # 用户明确卡住/求助
    created_at: float = field(default_factory=time.monotonic)
    not_before: float = 0.0       # 读完主力 AI 长回复前不说
    status: str = "held"          # held / delivered / cancelled
    reason: str = ""
    decided_at: float = 0.0
    # 延迟记录（墙钟时间，秒）
    material_ts: float = 0.0      # 触发材料出现的时间（用户发出 / 主力 AI 回复完成）
    think_start_ts: float = 0.0
    think_done_ts: float = 0.0
    delivered_ts: float = 0.0     # 交给宿主
    received_ts: float = 0.0      # 宿主模型确认收到（N.E.K.O. proactive_instruction）
    spoken_ts: float = 0.0        # 角色开始说
    spoken_end_ts: float = 0.0    # 角色说完
    spoken_text: str = ""         # 角色实际说出的原话
    channel: str = ""             # 交付通道（例如 neko_respond / neko_verbatim / print）

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["age_s"] = round(time.monotonic() - self.created_at, 1)
        return d


@dataclass
class Policy:
    level: int = 2                 # 0 关闭主动 / 1 少 / 2 正常 / 3 多
    muted: bool = False            # 用户叫停：声音和主动发言都停
    ttl_s: float = 25.0            # 候选最长保留
    typing_hold_s: float = 3.0     # 用户最后一次打字后多久内不插话
    max_reading_grace_s: float = 6.0
    style: str = "react"           # react（自然反应，默认）/ summarize（总结与安排，用于对照）
    cooldown_override: float | None = None   # 回放时不模拟时间间隔，用它关掉冷却

    THRESHOLDS = {1: 4.2, 2: 3.6, 3: 3.0}
    COOLDOWN_S = {1: 60.0, 2: 25.0, 3: 10.0}

    def threshold(self, explicit_need: bool) -> float:
        base = self.THRESHOLDS.get(self.level, 3.6)
        return base - 0.8 if explicit_need else base

    def cooldown(self) -> float:
        if self.cooldown_override is not None:
            return self.cooldown_override
        return self.COOLDOWN_S.get(self.level, 25.0)

    def reading_grace(self, reply_len: int) -> float:
        # 长回复给一点阅读时间；不是固定计时器插话，只是"不早于"
        return min(1.0 + reply_len / 500.0, self.max_reading_grace_s)


@dataclass
class Floor:
    """话权相关的实时信号。"""
    user_typing_at: float = 0.0      # monotonic
    user_speaking: bool = False      # 来自宿主（例如 N.E.K.O. 的 VAD）
    main_ai_streaming: bool = False
    main_ai_speaking: bool = False   # 主力 AI 语音播放中（原生语音共存时）
    companion_speaking: bool = False

    def busy(self, policy: Policy, now: float) -> str:
        if self.user_speaking:
            return "user_speaking"
        if now - self.user_typing_at < policy.typing_hold_s:
            return "user_typing"
        if self.main_ai_streaming:
            return "main_ai_streaming"
        if self.main_ai_speaking:
            return "main_ai_speaking"
        if self.companion_speaking:
            return "companion_speaking"
        return ""


@dataclass
class Decision:
    action: str   # deliver / hold / cancel
    reason: str


def decide(c: Candidate, *, policy: Policy, floor: Floor, latest_user_seq: int,
           latest_main_seq: int, recent_delivered: list[str], last_delivery_at: float,
           retired_ids: set[str], now: float | None = None) -> Decision:
    now = time.monotonic() if now is None else now
    if policy.muted:
        return Decision("cancel", "muted")
    if policy.level <= 0:
        return Decision("cancel", "proactive_off")
    if latest_user_seq > c.trigger_seq:
        return Decision("cancel", "user_continued")
    if latest_main_seq > c.trigger_seq:
        return Decision("cancel", "main_ai_moved_on")
    if any(b in retired_ids for b in c.basis):
        return Decision("cancel", "basis_retired")
    if now - c.created_at > policy.ttl_s:
        return Decision("cancel", "expired")
    if c.score < policy.threshold(c.explicit_need):
        return Decision("cancel", f"below_threshold({c.score:.1f}<{policy.threshold(c.explicit_need):.1f})")
    if any(similar(c.text, d) > 0.55 for d in recent_delivered[-6:]):
        return Decision("cancel", "repetitive")
    busy = floor.busy(policy, now)
    if busy:
        return Decision("hold", busy)
    if now < c.not_before:
        return Decision("hold", "reading_grace")
    if not c.explicit_need and last_delivery_at and now - last_delivery_at < policy.cooldown():
        return Decision("hold", "cooldown")
    return Decision("deliver", "ok")
