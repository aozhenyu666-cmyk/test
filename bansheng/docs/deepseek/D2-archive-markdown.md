# D2：把本场讨论导出成 Markdown 笔记

## 背景

「伴生」会为每场讨论（例如一个 Gemini 对话）维护一份会话状态，`SessionState.to_dict()`（`bansheng/neko_plugin/bansheng/engine/state.py`）导出的字典结构如下：

```python
{
  "session_id": "gemini:abc123",
  "revision": 7,
  "topic": "伴生第一版做什么",
  "topic_evidence": ["e1"],
  "items": [   # 条目
    {"id": "c2", "kind": "constraint", "text": "怕做复杂了做不出来", "evidence": ["e1"],
     "status": "superseded", "closed_by": ["e5"], "note": "", "rev": 2},
    # kind: constraint 约束 / confirmed 已确认 / open 未解开 / pending 待考虑建议 / correction 改口
    # status: active / superseded（被改口取代）/ resolved（已解决）/ dropped（用户删除）
  ],
  "turns": [   # 对话轮次
    {"session_id": "...", "speaker": "user", "text": "...", "source": "gemini_web", "turn_key": "...",
     "complete": True, "addressee": "main_ai", "backfill": False, "ts": 1792650000.0,
     "event_id": "e1", "revises": ""},
    # speaker: user 用户 / main_ai 主力 AI / companion 伴生
  ],
  "manual_lines": {"topic": "..."},   # 用户在路标上手动改过的行，优先于自动内容
  "updated_at": 1792650000.0,
  ...
}
```

`evidence` 和 `closed_by` 里是 `turns[].event_id`；`"manual"` 表示用户手动写入。

产品要求：讨论结束后保留主题、已确认结论和未解决点，方便下次接续和归档到笔记软件；**已确认的结论要能回到用户的原话**；建议（待考虑）和用户的决定要分开。

## 你要做的

1. 新建 `bansheng/neko_plugin/bansheng/engine/archive.py`，提供：

   ```python
   def session_to_markdown(data: dict, main_ai: str = "Gemini") -> str: ...
   ```

   只用标准库，不导入引擎其他模块（输入就是上面的字典）。输出的 Markdown 包括：
   - 标题：`# {主题}`（优先 `manual_lines["topic"]`，没有主题写“未命名讨论”），下一行写会话 id、更新时间（本地时间 `YYYY-MM-DD HH:MM`）
   - `## 三行路标`：在聊 / 已确认 / 没解开（规则同 `SessionState.signpost()`：手动修改优先；已确认、没解开取最近 2 条 active）
   - `## 已确认`：每条 active 的 confirmed，下面用引用块 `> 你：「原话」` 列出它 evidence 指向的用户原话（原话超过 120 字截断加“…”）；evidence 是 `manual` 时写 `> （你在路标上手动写入）`
   - `## 还没解开`：active 的 open
   - `## 待考虑的建议（还没确认）`：active 的 pending，标出来自谁（主力 AI / 伴生 / 你）
   - `## 约束与改口`：active 的约束；已失效的约束用 `~~删除线~~` 并写“（已被改口取代：「改口原话」）”；correction 条目列在后面
   - `## 伴生说过的话`：speaker 为 companion 的轮次，按时间
   - `## 完整对话`：所有 `backfill` 为 False 的轮次，格式 `**你**：…` / `**{main_ai}**：…` / `**伴生**：…`，主力 AI 的长回复保留全文
   - 某一节没有内容就写“（暂无）”，不要省略这一节

2. 在 `bansheng/neko_plugin/bansheng/engine/httpapi.py` 的 `do_GET` 里，紧挨着现有的 `if url.path == "/export":` 加一个分支：
   `GET /export.md?session_id=...` → 返回 `session_to_markdown(c.export(sid), c.main_ai_name(sid))`，`Content-Type: text/markdown; charset=utf-8`，并加响应头 `Content-Disposition: attachment; filename="bansheng-<日期>.md"`（文件名只用 ASCII）。用现有的 `api.call(...)` 在事件循环里取数据。只加这一个分支，不改其他逻辑。

3. 在 `bansheng/neko_plugin/bansheng/engine/static/dashboard.html` 顶部工具栏加一个“导出本场笔记”链接，指向 `/export.md?session_id=<当前会话>`；没有会话时隐藏。

4. 新建 `bansheng/tests/test_archive.py`（pytest），至少覆盖：
   - 已确认条目带出用户原话
   - 已失效约束显示删除线和改口原话
   - 待考虑建议标出来源
   - backfill 轮次不出现在“完整对话”
   - 空会话每节都写“（暂无）”
   - 手动修改的主题优先

   测试里可以直接构造字典，或用 `SessionState` 生成（参考 `bansheng/tests/test_engine.py` 开头的 `sys.path` 写法）。

## 注意

- 注释和输出文字用中文。不新增依赖。
- 不要修改 `companion.py`、`state.py`、`gate.py`、`mind.py`、`events.py`。

## 验收命令

```bash
python -m pytest bansheng/tests        # 全部通过（原有 12 项 + 你的新测试）
python bansheng/scripts/run_standalone.py --mock --data /tmp/bs-d2 &
python bansheng/scripts/smoke_http.py
curl "http://127.0.0.1:48925/export.md?session_id=$(curl -s http://127.0.0.1:48925/state | python -c 'import sys,json;print(json.load(sys.stdin)["session_id"])')"
```
