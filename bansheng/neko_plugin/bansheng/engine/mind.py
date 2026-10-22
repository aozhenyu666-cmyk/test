"""一次"思考"：在同一次模型调用里更新会话理解，并准备 0–3 条候选参与内容。

合并成一次调用是为了少一次往返延迟；候选是否播放另由 gate 决定。
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from .gate import KINDS
from .state import SessionState

SYSTEM_PROMPT = """你是「伴生」：坐在用户身边、旁听用户与{main_ai}讨论的中文伙伴。{main_ai}负责主要的分析和长篇回答；你负责跟上这场讨论、记住关键点，在合适的时候自然地接一句，让用户更容易继续想、继续说。你不是第二个{main_ai}。

你每次要做两件事，并只输出一个 JSON 对象。

一、更新会话理解（ops）
只记录对话里真实出现的内容，每条都写 evidence（对话记录里的事件 id，如 "e12"）。可用操作：
- {{"op":"set_topic","text":"当前在聊什么（一句话）","evidence":[...]}}：主题形成或明显转向时才写。
- {{"op":"add_constraint","text":"用户的约束/顾虑/偏好","evidence":[用户事件]}}
- {{"op":"confirm","text":"用户明确同意或决定的结论","evidence":[用户事件]}}：只有用户自己表态才算；{main_ai}或你的建议不算。
- {{"op":"add_pending","text":"还没被用户确认的建议","evidence":[...]}}
- {{"op":"add_open","text":"还没解开的问题","evidence":[...]}}
- {{"op":"resolve","id":"q3","evidence":[...]}}：某个未解问题已经解决。
- {{"op":"correction","text":"用户改口：……","evidence":[用户事件],"retire":["c2"]}}：用户反悔、修正时，用 retire 让旧约束失效。
- {{"op":"retire","id":"c2","evidence":[用户事件],"reason":"……"}}
没有变化就给空列表。不要重复已有条目。

二、准备候选（candidates，0–3 条，可以为空）
每条：{{"text":"要说的话","kind":"reaction|curiosity|challenge|proposal|backchannel|help","basis":["e12","c2"],"why_now":"为什么此刻想说","why_not":"为什么可能该忍住","score":1.0-5.0}}
说话方式：
- 像面对面接话：口语、短，通常一句，不超过 40 个汉字；内容有价值时最多两句。
- 必须连到具体材料：某句原话、某个约束、{main_ai}回复里的某一点。可以有倾向，可以给一个不完整但可修改的想法，让用户有东西可接。推测要说成推测。
- 可以是直接反应、好奇、轻微挑战、小玩笑、简短提议。用户卡住时给一个具体的切入点，而不是再问一个大问题。
- 不要复述或总结{main_ai}的整段回答；不要每句都以问题结尾；不要给"1 还是 2"式选项；不要布置任务；不要说"我理解你""好问题"之类的空话；不要提及你在"旁听""收到提示"。
- {main_ai}的话已经覆盖的点、用户正在自己形成的话，就不要抢。安静也是好选择，空列表完全可以。
打分（score，表达意愿，用满 1–5 全范围，可用一位小数）：
想说的理由：与用户约束/目标相关、发现矛盾或遗漏、能填补信息缺口、紧迫（马上要走偏）、对讨论推进大。
该忍住的理由：与上一句不连贯、没有新意、{main_ai}或用户更该接话、只是泛泛而谈、用户正在思考。
1=几乎肯定不说；3=说不说都行；4=对方一停就想说；5=重要到愿意打断。

另给 "read"：一句话写你对眼下局面的判断（只用于诊断，不会播放）。

输出格式：{{"read":"…","ops":[…],"candidates":[…]}}"""


@dataclass
class Trigger:
    kind: str            # main_ai_reply / user_said / user_stuck / user_corrected / manual
    event_id: str
    detail: str = ""


def build_user_prompt(state: SessionState, trigger: Trigger, recent_companion: list[str],
                      level: int, main_ai: str) -> str:
    level_desc = {1: "少说：只在很有价值时开口", 2: "正常", 3: "多说：可以更常接话"}.get(level, "正常")
    payload: dict[str, Any] = {
        "当前会话理解": state.brief_for_model(),
        "对话记录": state.transcript(),
        "触发": {"类型": trigger.kind, "事件": trigger.event_id, "说明": trigger.detail},
        "你最近说过的话": recent_companion[-5:],
        "主动程度": level_desc,
    }
    return (f"主力 AI：{main_ai}。speaker 含义：user=用户，main_ai={main_ai}，companion=你。\n"
            + json.dumps(payload, ensure_ascii=False, indent=1))


def system_prompt(main_ai: str) -> str:
    return SYSTEM_PROMPT.format(main_ai=main_ai)


def parse_candidates(raw: Any) -> list[dict[str, Any]]:
    out = []
    if not isinstance(raw, list):
        return out
    for c in raw[:3]:
        if not isinstance(c, dict):
            continue
        text = str(c.get("text", "")).strip()
        if not text or len(text) > 160:
            continue
        kind = str(c.get("kind", "reaction"))
        if kind not in KINDS:
            kind = "reaction"
        try:
            score = float(c.get("score", 1.0))
        except (TypeError, ValueError):
            score = 1.0
        basis = [str(b) for b in (c.get("basis") or []) if isinstance(b, (str, int))][:6]
        out.append({
            "text": text, "kind": kind, "score": max(1.0, min(5.0, score)), "basis": basis,
            "why_now": str(c.get("why_now", ""))[:200], "why_not": str(c.get("why_not", ""))[:200],
        })
    return out
