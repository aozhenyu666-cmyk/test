"""伴生核心引擎。

不依赖 N.E.K.O.，只用标准库。概念边界（见执行方案 §6）：

    输入事件 → 当前会话理解 → 候选参与内容 → 是否交付 → 声音和桌面表现

对应模块：events → state / mind → gate → Deliverer（由宿主实现）。
"""
from .events import TurnEvent, Speaker
from .state import SessionState
from .gate import Policy, Candidate
from .companion import Companion, Deliverer, PrintDeliverer
from .llm import LLM, OpenAICompatLLM, ScriptedLLM

__all__ = [
    "TurnEvent", "Speaker", "SessionState", "Policy", "Candidate",
    "Companion", "Deliverer", "PrintDeliverer",
    "LLM", "OpenAICompatLLM", "ScriptedLLM",
]
