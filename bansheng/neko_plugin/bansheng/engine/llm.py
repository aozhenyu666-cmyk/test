"""模型接口。宿主可以注入任何 OpenAI 风格客户端（N.E.K.O. 插件模型槽、Gemini 的
OpenAI 兼容端点、DeepSeek 等），引擎只要求"给 system/user，返回一个 JSON 对象"。"""
from __future__ import annotations

import asyncio
import json
import re
import time
from typing import Any, Awaitable, Callable, Protocol


class LLMError(RuntimeError):
    pass


class LLM(Protocol):
    name: str

    async def chat_json(self, system: str, user: str, max_tokens: int = 900) -> tuple[dict, dict]:
        """返回 (解析后的 JSON, 元信息{latency_ms, model, usage})。"""
        ...


def extract_json(text: str) -> dict:
    text = (text or "").strip()
    fence = re.search(r"```(?:json)?\s*(\{.*\})\s*```", text, re.S)
    if fence:
        text = fence.group(1)
    try:
        val = json.loads(text)
        if isinstance(val, dict):
            return val
    except json.JSONDecodeError:
        pass
    start, end = text.find("{"), text.rfind("}")
    if start >= 0 and end > start:
        try:
            val = json.loads(text[start:end + 1])
            if isinstance(val, dict):
                return val
        except json.JSONDecodeError:
            pass
    raise LLMError(f"model did not return JSON: {text[:200]!r}")


class OpenAICompatLLM:
    """包装一个 OpenAI 风格异步客户端。

    client_factory: 返回带 ``chat.completions.create`` 的对象（可为协程）。
    model: 真实模型名；在 N.E.K.O. 插件模型槽里则是 manifest 中的 usage id。
    """

    def __init__(self, client_factory: Callable[[], Any | Awaitable[Any]], model: str,
                 name: str = "openai_compat", temperature: float = 0.7, timeout_s: float = 30.0):
        self._factory = client_factory
        self.model = model
        self.name = name
        self.temperature = temperature
        self.timeout_s = timeout_s

    async def _client(self):
        c = self._factory()
        if asyncio.iscoroutine(c):
            c = await c
        return c

    async def chat_json(self, system: str, user: str, max_tokens: int = 900) -> tuple[dict, dict]:
        client = await self._client()
        t0 = time.monotonic()
        kwargs = dict(
            model=self.model,
            messages=[{"role": "system", "content": system}, {"role": "user", "content": user}],
            temperature=self.temperature,
        )
        try:
            resp = await asyncio.wait_for(client.chat.completions.create(
                max_completion_tokens=max_tokens, **kwargs), timeout=self.timeout_s)
        except Exception as e:  # 少数兼容端点只认 max_tokens
            if "max_completion_tokens" not in str(e):
                raise
            resp = await asyncio.wait_for(client.chat.completions.create(
                max_tokens=max_tokens, **kwargs), timeout=self.timeout_s)
        latency = int((time.monotonic() - t0) * 1000)
        content = resp.choices[0].message.content or ""
        usage = None
        if getattr(resp, "usage", None) is not None:
            try:
                usage = resp.usage.model_dump()
            except Exception:
                usage = None
        return extract_json(content), {"latency_ms": latency, "model": getattr(resp, "model", self.model),
                                       "usage": usage, "llm": self.name}

    @classmethod
    def from_env(cls, env: dict[str, str]) -> "OpenAICompatLLM | None":
        """BANSHENG_LLM_BASE_URL / BANSHENG_LLM_API_KEY / BANSHENG_LLM_MODEL。"""
        base = env.get("BANSHENG_LLM_BASE_URL")
        key = env.get("BANSHENG_LLM_API_KEY")
        model = env.get("BANSHENG_LLM_MODEL")
        if not (base and key and model):
            return None
        from openai import AsyncOpenAI  # 延迟导入
        client = AsyncOpenAI(base_url=base, api_key=key)
        return cls(lambda: client, model, name=f"env:{model}")


class ScriptedLLM:
    """测试和回放用：按顺序返回预置结果，或用函数根据输入生成。"""

    name = "scripted"

    def __init__(self, responses: list[dict] | Callable[[str, str], dict] | None = None,
                 delay_s: float = 0.0):
        self._responses = responses if responses is not None else []
        self.delay_s = delay_s
        self.calls: list[dict[str, str]] = []

    async def chat_json(self, system: str, user: str, max_tokens: int = 900) -> tuple[dict, dict]:
        self.calls.append({"system": system, "user": user})
        if self.delay_s:
            await asyncio.sleep(self.delay_s)
        if callable(self._responses):
            out = self._responses(system, user)
        elif self._responses:
            out = self._responses.pop(0)
        else:
            out = {"ops": [], "candidates": []}
        return out, {"latency_ms": int(self.delay_s * 1000), "model": "scripted", "llm": self.name}
