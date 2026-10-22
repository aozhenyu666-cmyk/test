"""对正在运行的伴生服务做一次三方交流回放（模拟浏览器扩展发来的事件）。

    python bansheng/scripts/smoke_http.py [http://127.0.0.1:48925]

注意：这是回放，不是真实 Gemini 页面采集。
"""
from __future__ import annotations

import json
import sys
import time
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:48925"
SID = f"gemini:smoke{int(time.time())}"


def call(path: str, body: dict | None = None) -> dict:
    req = urllib.request.Request(BASE + path, method="POST" if body is not None else "GET",
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read())


def ev(speaker, text, key, **kw):
    return {"events": [{"session_id": SID, "speaker": speaker, "text": text, "turn_key": key,
                        "source": "replay", **kw}]}


def show(title):
    s = call(f"/state?session_id={SID}")
    print(f"\n== {title}")
    print("路标:", json.dumps(s["signpost"], ensure_ascii=False))
    for c in s["held"]:
        print("  准备中:", c["text"], "|", c["reason"])
    for c in s["recent_decisions"][-3:]:
        print(f"  {c['status']}: {c['text']} | {c['reason']}")


print(call("/health"))
print(call("/events", ev("user", "我想要它像真的跟上我的家伙，可是做复杂了又怕做不出来", "u1", addressee="main_ai")))
print(call("/events", ev("main_ai", "可以先搭一个后台管理系统……", "m1", complete=False)))
print(call("/events", ev("main_ai", "可以先搭一个后台管理系统，再加记忆服务和调度服务，最后做桌面端。", "m1")))
time.sleep(3.5)
show("主力 AI 回复之后")
print(call("/events", ev("main_ai", "可以先搭一个后台管理系统，再加记忆服务和调度服务，最后做桌面端。", "m1", backfill=True)))
print(call("/events", ev("user", "我现在也不知道该抓哪一个", "u2")))
time.sleep(3.5)
show("用户说卡住之后")
print(call("/control", {"action": "mute"}))
show("静音后")
print(call("/control", {"action": "unmute"}))
print("ledger kinds:", [r["kind"] for r in call(f"/ledger?n=40&session_id={SID}")["records"]])
