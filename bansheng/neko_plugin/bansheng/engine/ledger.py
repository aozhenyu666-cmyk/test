"""诊断记录（F8）：每次介入依据了哪段内容、为何播放或取消、模型耗时。

写 JSONL 文件，同时保留内存环形缓冲供面板读取。
"""
from __future__ import annotations

import json
import threading
import time
from collections import deque
from pathlib import Path
from typing import Any


class Ledger:
    def __init__(self, directory: Path | None = None, keep: int = 800):
        self.directory = Path(directory) if directory else None
        if self.directory:
            self.directory.mkdir(parents=True, exist_ok=True)
        self._buf: deque[dict[str, Any]] = deque(maxlen=keep)
        self._lock = threading.Lock()
        self._seq = 0

    def log(self, kind: str, **fields: Any) -> dict[str, Any]:
        with self._lock:
            self._seq += 1
            rec = {"n": self._seq, "t": round(time.time(), 3), "kind": kind, **fields}
            self._buf.append(rec)
            if self.directory:
                path = self.directory / time.strftime("ledger-%Y%m%d.jsonl")
                try:
                    with path.open("a", encoding="utf-8") as f:
                        f.write(json.dumps(rec, ensure_ascii=False, default=str) + "\n")
                except OSError:
                    pass
            return rec

    def tail(self, n: int = 100, session_id: str | None = None, kinds: set[str] | None = None) -> list[dict]:
        with self._lock:
            items = list(self._buf)
        if session_id:
            items = [r for r in items if r.get("session_id") in (None, session_id)]
        if kinds:
            items = [r for r in items if r["kind"] in kinds]
        return items[-n:]
