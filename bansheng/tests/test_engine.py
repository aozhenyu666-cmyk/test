"""引擎行为测试（模型用脚本代替；这里验证的是调度、记忆、取消规则，不是模型质量）。"""
import asyncio
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "neko_plugin" / "bansheng"))

from engine import Companion, PrintDeliverer, Policy, ScriptedLLM, SessionState, TurnEvent  # noqa: E402
from engine.gate import Candidate, Floor, decide  # noqa: E402

SID = "gemini:test1"


def ev(speaker, text, key, **kw):
    return {"session_id": SID, "speaker": speaker, "text": text, "turn_key": key,
            "source": "gemini_web", **kw}


def make(responses, tmp_path=None, **policy):
    llm = ScriptedLLM(responses)
    out = PrintDeliverer(echo=False)
    pol = Policy(max_reading_grace_s=0.0, **policy)
    c = Companion(llm, out, data_dir=tmp_path, policy=pol, debounce_s=0.01, context_min_gap_s=0)
    return c, llm, out


async def settle(c, rounds=6):
    for _ in range(rounds):
        await asyncio.sleep(0.03)
        await c.tick()


# ---------------------------------------------------------------- 事件去重
def test_dedupe_streaming_and_rescroll():
    s = SessionState(SID)
    assert s.accept(TurnEvent(SID, "main_ai", "部分", turn_key="m1", complete=False))[0] == "partial"
    assert s.streaming("main_ai")
    r, e = s.accept(TurnEvent(SID, "main_ai", "完整的回复内容，足够长足够长", turn_key="m1"))
    assert r == "new" and e.event_id == "e1" and not s.streaming("main_ai")
    assert s.accept(TurnEvent(SID, "main_ai", "完整的回复内容，足够长足够长", turn_key="m1"))[0] == "duplicate"
    # 页面重新渲染换了 key，同样长文本不再算新话
    assert s.accept(TurnEvent(SID, "main_ai", "完整的回复内容，  足够长足够长", turn_key="m9"))[0] == "duplicate"
    # 用户编辑了自己的提问 → revised，指向旧事件
    s.accept(TurnEvent(SID, "user", "第一版问题", turn_key="u2"))
    r, e = s.accept(TurnEvent(SID, "user", "第二版问题", turn_key="u2"))
    assert r == "revised" and e.revises == "e2"


def test_confirm_requires_user_evidence():
    s = SessionState(SID)
    s.accept(TurnEvent(SID, "user", "我怕做复杂了做不出来", turn_key="u1"))
    s.accept(TurnEvent(SID, "main_ai", "建议先做一个后台管理系统再说", turn_key="m1"))
    res = s.apply_ops([
        {"op": "confirm", "text": "先做后台系统", "evidence": ["e2"]},          # 只有主力 AI 支撑
        {"op": "add_constraint", "text": "怕复杂", "evidence": ["e1"]},
        {"op": "add_constraint", "text": "编的", "evidence": ["e99"]},         # 不存在的来源
    ])
    assert [r.reason for r in res] == ["downgraded_to_pending", "", "evidence_missing"]
    assert not s.active("confirmed")
    assert [p.text for p in s.active("pending")] == ["先做后台系统"]
    assert s.signpost()["confirmed"] == "（暂无）"


# ---------------------------------------------------------------- 验收例子：约束 → 关联 → 改口
def test_constraint_link_then_correction(tmp_path):
    def brain(system, user):
        data = json.loads(user.split("\n", 1)[1])
        trig = data["触发"]
        brief = data["当前会话理解"]
        if trig["事件"] == "e2":   # Gemini 加了后台系统
            return {"read": "主力 AI 加复杂度，和用户约束冲突",
                    "ops": [{"op": "set_topic", "text": "伴生第一版做什么", "evidence": ["e1"]},
                            {"op": "add_constraint", "text": "怕做复杂了做不出来", "evidence": ["e1"]},
                            {"op": "add_pending", "text": "先搭后台系统", "evidence": ["e2"]}],
                    "candidates": [{"text": "诶，它又加了一套后台系统。你刚才才说怕弄复杂。",
                                    "kind": "challenge", "basis": ["e2", "c2"], "score": 4.4}]}
        if trig["类型"] == "user_corrected":
            cons = [c["id"] for c in brief["constraints"]]
            return {"ops": [{"op": "correction", "text": "用户改口：这次允许复杂，先要效果",
                             "evidence": [trig["事件"]], "retire": cons}],
                    "candidates": []}
        return {"ops": [], "candidates": []}

    async def run():
        c, llm, out = make(brain, tmp_path)
        await c.ingest([ev("user", "我想要它像真的跟上我的家伙，可是做复杂了又怕最后做不出来", "u1",
                           addressee="main_ai")])
        await settle(c)
        assert llm.calls == []                       # 用户对主力 AI 说话：等它回复，不另起一轮
        await c.ingest([ev("main_ai", "可以先搭一个后台管理系统，再加记忆服务和调度服务……", "m1")])
        await settle(c)
        assert out.said == ["诶，它又加了一套后台系统。你刚才才说怕弄复杂。"]
        s = c.session(SID)
        assert [x.text for x in s.active("constraint")] == ["怕做复杂了做不出来"]
        assert s.signpost()["topic"] == "伴生第一版做什么"
        # 用户改口 → 旧约束失效
        await c.ingest([ev("user", "我刚才没说准，这次允许复杂，先要效果", "u2")])
        await settle(c)
        assert s.active("constraint") == []
        assert any(i.status == "superseded" and i.text == "怕做复杂了做不出来" for i in s.items)
        # 会话已落盘，重新打开能恢复
        c2, _, _ = make(lambda *_: {}, tmp_path)
        s2 = c2.session(SID)
        assert s2.active("constraint") == [] and s2.topic == "伴生第一版做什么"
        assert any(t.speaker == "companion" for t in s2.turns)
    asyncio.run(run())


# ---------------------------------------------------------------- 准备了但因用户继续说而取消
def test_prepared_then_cancelled_when_user_continues():
    async def run():
        c, llm, out = make([{"ops": [], "candidates": [
            {"text": "我倒想听它把第三段那个例子讲具体一点。", "kind": "curiosity", "basis": ["e1"], "score": 4.0}]}])
        await c.ingest([ev("main_ai", "很长的回复" * 50, "m1")])
        c.set_floor(SID, user_typing=True)           # 用户已经开始打字
        await settle(c)
        assert out.said == [] and c.held[SID][0].reason == "user_typing"
        await c.ingest([ev("user", "我觉得第三段的例子不太对", "u2", addressee="main_ai")])
        await settle(c)
        assert out.said == []
        assert c.decided[-1].reason == "user_continued" and c.decided[-1].status == "cancelled"
    asyncio.run(run())


def test_think_discarded_when_new_turn_arrives_during_think():
    async def run():
        c, llm, out = make([{"ops": [], "candidates": [{"text": "旧材料上的想法", "score": 5, "basis": ["e1"]}]},
                            {"ops": [], "candidates": []}])
        llm.delay_s = 0.1
        await c.ingest([ev("main_ai", "第一条回复，内容足够长了吧", "m1")])
        await asyncio.sleep(0.05)                     # 思考进行中
        await c.ingest([ev("main_ai", "第二条回复，内容足够长了吧", "m2")])
        await settle(c, rounds=12)
        assert out.said == []
        kinds = [r["kind"] for r in c.ledger.tail(50)]
        assert "candidates_discarded" in kinds
    asyncio.run(run())


# ---------------------------------------------------------------- 叫停 / 恢复 / 嫌吵
def test_stop_and_resume_and_noisy():
    async def run():
        c, llm, out = make(lambda *_: {"ops": [], "candidates": [{"text": "我想说点什么", "score": 5, "basis": []}]})
        await c.ingest([ev("user", "先别说", "u1")])
        assert c.policy.muted and out.stops == ["muted"]
        await c.ingest([ev("main_ai", "主力 AI 的一条回复内容", "m1")])
        await settle(c)
        assert out.said == []                          # 静音后不再主动说
        await c.ingest([ev("user", "伴生，回来吧", "u2")])
        assert not c.policy.muted
        await c.ingest([ev("user", "你也有点吵", "u3")])
        assert c.policy.level == 1
        # "先别说这个方案" 是对内容的讨论，不是叫停
        await c.ingest([ev("user", "先别说这个方案了，我们换个思路看看", "u4")])
        assert not c.policy.muted
    asyncio.run(run())


def test_backfill_does_not_trigger_and_reconnect_no_repeat():
    async def run():
        c, llm, out = make(lambda *_: {"ops": [], "candidates": [{"text": "一句话", "score": 5, "basis": []}]})
        hist = [ev("user", "旧问题", "u1", backfill=True), ev("main_ai", "旧回复的内容比较长", "m1", backfill=True)]
        await c.ingest(hist)
        await settle(c)
        assert llm.calls == [] and out.said == []
        counts = await c.ingest(hist)                 # 刷新页面再次补发
        assert counts == {"duplicate": 2}
    asyncio.run(run())


def test_stuck_lowers_threshold_and_repetition_suppressed():
    async def run():
        c, llm, out = make(lambda *_: {"ops": [], "candidates": [
            {"text": "我先押接话这件事，先听一小段，假就改。", "kind": "proposal", "basis": ["e1"], "score": 3.0}]})
        await c.ingest([ev("user", "我现在也不知道该抓哪一个", "u1")])
        await settle(c)
        assert out.said == ["我先押接话这件事，先听一小段，假就改。"]   # 3.0 ≥ 3.6-0.8
        await c.ingest([ev("user", "嗯……还是不知道该抓哪一个", "u2")])
        await settle(c)
        assert len(out.said) == 1 and c.decided[-1].reason == "repetitive"
    asyncio.run(run())


def test_decide_rules_unit():
    pol, fl = Policy(), Floor()
    base = dict(id="k1", session_id=SID, text="一句", kind="reaction", score=4.0, basis=["c1"],
                trigger_event="e3", trigger_seq=3, state_rev=1)
    kw = dict(policy=pol, floor=fl, latest_user_seq=1, latest_main_seq=3, recent_delivered=[],
              last_delivery_at=0.0, retired_ids=set())
    assert decide(Candidate(**base), **kw).action == "deliver"
    assert decide(Candidate(**base), **{**kw, "retired_ids": {"c1"}}).reason == "basis_retired"
    assert decide(Candidate(**{**base, "score": 3.0}), **kw).reason.startswith("below_threshold")
    fl.main_ai_streaming = True
    assert decide(Candidate(**base), **kw).reason == "main_ai_streaming"


# ---------------------------------------------------------------- 宿主回报：用户对角色说话、角色实际说的话、延迟
def test_voice_to_companion_mutes_and_only_updates_understanding():
    async def run():
        c, llm, out = make(lambda *_: {"ops": [], "candidates": [{"text": "我也想说一句", "score": 5, "basis": []}]})
        await c.ingest([ev("main_ai", "主力 AI 的一条回复内容", "m1")])
        await settle(c)
        assert out.said == ["我也想说一句"]
        # 用户对着桌面角色说了一段话：伴生只更新理解，不另准备一句（角色自己会回答）
        r = await c.observe_host_turn(turn_type="user_message", text="其实我更在意它能不能听懂我", is_voice=True,
                                      record_id="r1")
        await settle(c)
        assert r == "user_to_companion"
        user_turn = c.session(SID).last_event("user")
        assert user_turn.addressee == "companion" and user_turn.source == "neko_voice"
        assert "只更新会话理解" in llm.calls[-1]["user"] and len(out.said) == 1
        # 语音叫停也生效
        await c.observe_host_turn(turn_type="user_message", text="别说了", is_voice=True, record_id="r2")
        assert c.policy.muted
    asyncio.run(run())


def test_spoken_text_paired_with_candidate_and_metrics():
    async def run():
        c, llm, out = make([{"ops": [], "candidates": [{"text": "它又加了后台系统", "score": 4.5, "basis": ["e1"]}]}])
        await c.ingest([ev("main_ai", "建议先做后台系统再做别的东西", "m1")])
        await settle(c)
        cand = c.decided[-1]
        assert cand.status == "delivered" and cand.material_ts > 0 and cand.think_done_ts >= cand.think_start_ts
        r1 = await c.observe_host_turn(turn_type="proactive_instruction", conversation_id="conv9",
                                       text="（伴生提示……）要表达的意思：它又加了后台系统")
        r2 = await c.observe_host_turn(turn_type="proactive_reply", conversation_id="conv9",
                                       text="诶，它怎么又塞了个后台系统进来？",
                                       ts=cand.delivered_ts + 1.2, ts_end=cand.delivered_ts + 3.0)
        assert (r1, r2) == ("received", "spoken")
        assert cand.spoken_text == "诶，它怎么又塞了个后台系统进来？"
        turn = [t for t in c.session(SID).turns if t.speaker == "companion"][-1]
        assert turn.text == cand.spoken_text          # 会话里记的是角色实际说的原话
        m = c.metrics()
        assert m["stages"]["交给角色→角色开口"]["median"] == 1.2
        assert m["stages"]["角色说话时长"]["median"] == 1.8
        # 和伴生无关的角色发言记成普通伴生轮次
        assert await c.observe_host_turn(turn_type="assistant_message", text="今天天气不错") == "companion_turn"
    asyncio.run(run())


def test_verbatim_delivery_not_paired_with_unrelated_reply():
    class VerbatimOut(PrintDeliverer):
        async def say(self, cand, session):
            await super().say(cand, session)
            return {"submitted": True, "channel": "neko_verbatim"}

    async def run():
        llm = ScriptedLLM([{"ops": [], "candidates": [{"text": "原样说的一句", "score": 4.5, "basis": []}]}])
        c = Companion(llm, VerbatimOut(echo=False), policy=Policy(max_reading_grace_s=0.0), debounce_s=0.01)
        await c.ingest([ev("main_ai", "主力 AI 的一条回复内容", "m1")])
        await settle(c)
        assert c.decided[-1].channel == "neko_verbatim"
        r = await c.observe_host_turn(turn_type="proactive_reply", text="角色自己的另一个主动话题")
        assert r == "companion_turn" and not c.decided[-1].spoken_text
        c.mark_playback(c.decided[-1].id, text="原样说的一句", ended_at=c.decided[-1].delivered_ts + 2.0)
        assert c.metrics()["stages"]["交给角色→播放结束"]["median"] == 2.0
    asyncio.run(run())
