"""对照实验：同一段三方讨论，用两种风格各回放一遍，生成并排对比页（产品说明 §8、执行方案阶段 E）。

    python bansheng/scripts/replay_compare.py bansheng/tests/fixtures/sample_discussion.json
    python bansheng/scripts/replay_compare.py 导出的会话.json --out 对比.html
    python bansheng/scripts/replay_compare.py 材料.json --mock        # 不调模型，只看流程

材料格式二选一：
- 伴生面板导出的会话（GET /export?session_id=...），只取用户和主力 AI 的轮次
- {"main_ai": "Gemini", "turns": [{"speaker": "user"|"main_ai", "text": "..."}, ...]}

模型用 BANSHENG_LLM_BASE_URL / BANSHENG_LLM_API_KEY / BANSHENG_LLM_MODEL。两种风格用同一个模型、同一份材料。
回放不模拟真实时间间隔（冷却关闭、没有阅读等待），也没有声音；它比较的是"想说什么、什么时候忍住"。
"""
from __future__ import annotations

import argparse
import asyncio
import html
import json
import os
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "neko_plugin" / "bansheng"))
sys.path.insert(0, str(ROOT / "scripts"))

from engine import Companion, OpenAICompatLLM, Policy, PrintDeliverer, ScriptedLLM  # noqa: E402

STYLE_NAMES = {"react": "自然反应（默认）", "summarize": "总结与安排"}


def load_material(path: Path) -> tuple[str, list[dict]]:
    d = json.loads(path.read_text("utf-8"))
    turns = [t for t in d.get("turns", []) if t.get("speaker") in ("user", "main_ai") and t.get("text")]
    sid = d.get("session_id", "")
    main_ai = d.get("main_ai") or ("ChatGPT" if sid.startswith("chatgpt") else "Gemini")
    return main_ai, turns


async def run_style(style: str, main_ai: str, turns: list[dict], llm) -> dict:
    sid = f"{main_ai.lower()}:replay-{style}"
    with tempfile.TemporaryDirectory() as tmp:
        c = Companion(llm, PrintDeliverer(echo=False), data_dir=tmp, debounce_s=0.05,
                      policy=Policy(level=2, style=style, max_reading_grace_s=0.0, cooldown_override=0.0))
        steps = []
        for i, t in enumerate(turns):
            seen = len(c.decided)
            await c.ingest([{"session_id": sid, "speaker": t["speaker"], "text": t["text"], "turn_key": f"r{i}",
                             "source": "replay", "addressee": "main_ai" if t["speaker"] == "user" else "user"}])
            nxt = turns[i + 1]["speaker"] if i + 1 < len(turns) else ""
            if t["speaker"] == "user" and nxt == "main_ai":
                # 真实场景里主力 AI 一两秒内就开始回复：期间伴生应该等着
                c.floors[sid].main_ai_streaming = True
            # 等这一步的思考做完（防抖 + 模型调用），再让闸门判断一次
            await asyncio.sleep(0.1)
            while c._think_tasks.get(sid) and not c._think_tasks[sid].done():
                await asyncio.sleep(0.05)
            await c.tick()
            new = c.decided[seen:]
            steps.append({
                "said": [x.text for x in new if x.status == "delivered"],
                "held_back": [{"text": x.text, "reason": x.reason, "score": x.score} for x in new
                              if x.status == "cancelled"] + [{"text": x.text, "reason": "（还在等）", "score": x.score}
                                                             for x in c.held.get(sid, [])],
                "read": next((r.get("read") for r in reversed(c.ledger.tail(20)) if r["kind"] == "think_done"), ""),
            })
        s = c.session(sid)
        latencies = [r["latency_ms"] for r in c.ledger.tail(800) if r["kind"] == "think_done" and r.get("latency_ms") is not None]
        return {"style": style, "steps": steps, "signpost": s.signpost(), "state": s.brief_for_model(),
                "said_count": sum(len(x["said"]) for x in steps),
                "model_latency_ms": sorted(latencies)[len(latencies) // 2] if latencies else None}


def render(main_ai: str, turns: list[dict], results: list[dict], meta: dict) -> str:
    who = {"user": "你", "main_ai": main_ai}
    head = "".join(f"<th>{html.escape(STYLE_NAMES.get(r['style'], r['style']))}<br><span class=m>说了 {r['said_count']} 句"
                   f" · 模型中位 {r['model_latency_ms'] if r['model_latency_ms'] is not None else '—'} ms</span></th>" for r in results)
    rows = []
    for i, t in enumerate(turns):
        text = html.escape(t["text"] if len(t["text"]) < 400 else t["text"][:380] + "……")
        cells = []
        for r in results:
            st = r["steps"][i]
            parts = [f"<div class=said data-k='{r['style']}-{i}-{j}'>“{html.escape(x)}”"
                     f"<span class=mark><button>想接</button><button>烦</button><button>假</button></span></div>"
                     for j, x in enumerate(st["said"])]
            parts += [f"<div class=held>忍住：{html.escape(h['text'])} <span class=m>{html.escape(h['reason'])} · {h['score']}</span></div>"
                      for h in st["held_back"]]
            cells.append(f"<td>{''.join(parts) or '<span class=m>（安静）</span>'}</td>")
        rows.append(f"<tr><td class='turn {t['speaker']}'><b>{who[t['speaker']]}</b>：{text}</td>{''.join(cells)}</tr>")
    sign = "".join(f"<td><b>在聊</b> {html.escape(r['signpost']['topic'])}<br><b>已确认</b> {html.escape(r['signpost']['confirmed'])}"
                   f"<br><b>没解开</b> {html.escape(r['signpost']['open'])}</td>" for r in results)
    return f"""<!doctype html><html lang=zh-CN><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>伴生风格对照</title><style>
:root{{--bg:#f7f6f3;--card:#fff;--ink:#1f2328;--mute:#6b7280;--line:#e5e2dc;--ok:#2f7d4f;--acc:#3b6ea8}}
@media (prefers-color-scheme:dark){{:root{{--bg:#16181b;--card:#1f2226;--ink:#e8e6e1;--mute:#9aa0a6;--line:#2e3237;--ok:#6cbf8c;--acc:#7aa7dc}}}}
body{{margin:0;background:var(--bg);color:var(--ink);font:14px/1.6 system-ui,"PingFang SC","Microsoft YaHei",sans-serif}}
main{{max-width:1200px;margin:0 auto;padding:16px}} .m{{color:var(--mute);font-size:12px}}
.wrap{{overflow-x:auto}} table{{border-collapse:collapse;width:100%;min-width:720px;background:var(--card)}}
td,th{{border:1px solid var(--line);padding:8px 10px;vertical-align:top;text-align:left}} th{{position:sticky;top:0;background:var(--card)}}
td.turn{{width:40%}} td.user b{{color:var(--acc)}} .said{{color:var(--ok);margin-bottom:4px}} .held{{color:var(--mute);font-size:12px}}
.mark button{{font-size:11px;margin-left:4px;border:1px solid var(--line);background:transparent;color:var(--mute);border-radius:8px;cursor:pointer}}
.mark button.on{{background:var(--acc);color:#fff;border-color:var(--acc)}}
</style></head><body><main>
<h1 style="font-size:18px">伴生风格对照</h1>
<p class=m>同一段材料、同一个模型（{html.escape(meta['llm'])}），{meta['generated']} 生成。回放不模拟时间间隔，也没有声音。
看完后在想接话的句子上点“想接”，觉得烦或假的点“烦”“假”，最后点下面的按钮把标记复制出来发给开发。</p>
<div class=wrap><table><tr><th>对话</th>{head}</tr>{''.join(rows)}<tr><td><b>结束时的三行路标</b></td>{sign}</tr></table></div>
<p><button id=copy>复制我的标记</button> <span id=msg class=m></span></p>
</main><script>
const KEY="bansheng-compare-marks"; let marks={{}};
try{{marks=JSON.parse(localStorage.getItem(KEY)||"{{}}")}}catch(e){{}}
document.querySelectorAll(".said").forEach(el=>{{const k=el.dataset.k;
 el.querySelectorAll("button").forEach(b=>{{ if(marks[k]===b.textContent)b.classList.add("on");
  b.onclick=()=>{{marks[k]=marks[k]===b.textContent?undefined:b.textContent;
   el.querySelectorAll("button").forEach(x=>x.classList.toggle("on",marks[k]===x.textContent));
   try{{localStorage.setItem(KEY,JSON.stringify(marks))}}catch(e){{}} }}; }}); }});
document.getElementById("copy").onclick=async()=>{{const out=[...document.querySelectorAll(".said")].filter(e=>marks[e.dataset.k])
 .map(e=>({{key:e.dataset.k,mark:marks[e.dataset.k],text:e.firstChild.textContent}}));
 try{{await navigator.clipboard.writeText(JSON.stringify(out,null,1));document.getElementById("msg").textContent="已复制 "+out.length+" 条"}}
 catch(e){{document.getElementById("msg").textContent=JSON.stringify(out)}} }};
</script></body></html>"""


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("material")
    ap.add_argument("--styles", default="react,summarize")
    ap.add_argument("--out", default="")
    ap.add_argument("--mock", action="store_true")
    args = ap.parse_args()
    main_ai, turns = load_material(Path(args.material))
    if args.mock:
        from mock_llm_server import answer
        llm = ScriptedLLM(lambda system, user: answer(user))
        llm.name = "mock"
    else:
        llm = OpenAICompatLLM.from_env(dict(os.environ))
        if llm is None:
            sys.exit("没有配置模型：设置 BANSHENG_LLM_BASE_URL / BANSHENG_LLM_API_KEY / BANSHENG_LLM_MODEL，或加 --mock")
    styles = [s.strip() for s in args.styles.split(",") if s.strip()]
    results = asyncio.run(_run_all(styles, main_ai, turns, llm))
    out = Path(args.out or Path(args.material).with_suffix(".compare.html"))
    meta = {"llm": llm.name, "generated": time.strftime("%Y-%m-%d %H:%M")}
    out.write_text(render(main_ai, turns, results, meta), "utf-8")
    out.with_suffix(".json").write_text(json.dumps({"meta": meta, "results": results}, ensure_ascii=False, indent=1), "utf-8")
    for r in results:
        print(f"{STYLE_NAMES.get(r['style'], r['style'])}: 说了 {r['said_count']} 句，路标 {r['signpost']}")
    print(f"对比页：{out}")


async def _run_all(styles, main_ai, turns, llm):
    return await asyncio.gather(*(run_style(s, main_ai, turns, llm) for s in styles))


if __name__ == "__main__":
    main()
