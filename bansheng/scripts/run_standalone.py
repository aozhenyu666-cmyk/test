"""不带 N.E.K.O. 单独运行伴生引擎（只有文字：伴生说的话显示在页面浮层和终端里，没有声音）。

    # 用 Gemini 的 OpenAI 兼容接口（需要 Gemini API Key，不是网页订阅）
    set BANSHENG_LLM_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai/
    set BANSHENG_LLM_API_KEY=你的key
    set BANSHENG_LLM_MODEL=gemini-2.5-flash
    python bansheng/scripts/run_standalone.py

    # 没有 Key 时用假模型跑通管道
    python bansheng/scripts/run_standalone.py --mock
"""
from __future__ import annotations

import argparse
import asyncio
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "neko_plugin" / "bansheng"))
sys.path.insert(0, str(ROOT / "scripts"))

from engine import Companion, OpenAICompatLLM, Policy, PrintDeliverer, ScriptedLLM  # noqa: E402
from engine.httpapi import CompanionHTTP  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=int(os.environ.get("BANSHENG_PORT", 48925)))
    ap.add_argument("--data", default=str(Path.home() / ".bansheng"))
    ap.add_argument("--mock", action="store_true", help="用内置假模型（只验证管道）")
    ap.add_argument("--level", type=int, default=2)
    args = ap.parse_args()

    if args.mock:
        from mock_llm_server import answer
        llm = ScriptedLLM(lambda system, user: answer(user))
        llm.name = "mock"
    else:
        llm = OpenAICompatLLM.from_env(dict(os.environ))
        if llm is None:
            sys.exit("没有配置模型：设置 BANSHENG_LLM_BASE_URL / BANSHENG_LLM_API_KEY / BANSHENG_LLM_MODEL，或加 --mock")

    async def run():
        companion = Companion(llm, PrintDeliverer(), data_dir=args.data, policy=Policy(level=args.level))
        http = CompanionHTTP(companion, asyncio.get_running_loop(), port=args.port,
                             token=os.environ.get("BANSHENG_TOKEN", ""))
        http.start()
        print(f"伴生（独立模式）: http://127.0.0.1:{args.port}/  模型={llm.name}  数据={args.data}", flush=True)
        await companion.run_forever()

    asyncio.run(run())


if __name__ == "__main__":
    main()
