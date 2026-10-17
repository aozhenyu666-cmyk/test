"""Agent Bridge: local-only agent collaboration server (Python 3.10+)."""
from __future__ import annotations

import argparse
import json
import os
import secrets
import signal
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit, parse_qs
from runtime import VERSION, helper, default_workspace, data_directory

ROOT = Path(__file__).resolve().parent
LIMIT = 2_000_000


def ident():
    return uuid.uuid4().hex[:16]


class Store:
    def __init__(self, directory):
        directory.mkdir(parents=True, exist_ok=True)
        self.directory = directory
        self.lock = threading.RLock()
        self.db = sqlite3.connect(directory / "bridge.sqlite3", check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS agents(id TEXT PRIMARY KEY, data TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY, data TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS runs(id TEXT PRIMARY KEY, data TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY, data TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY, data TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS terminal_history(id TEXT PRIMARY KEY, data TEXT NOT NULL);
        """)
        self.db.commit()

    def put(self, table, value):
        assert table in {"agents", "tasks", "runs", "messages", "sessions", "terminal_history"}
        with self.lock:
            self.db.execute(f"INSERT OR REPLACE INTO {table} VALUES (?, ?)",
                            (value["id"], json.dumps(value, ensure_ascii=False)))
            self.db.commit()
        return value

    def get(self, table, key):
        with self.lock:
            row = self.db.execute(f"SELECT data FROM {table} WHERE id = ?", (key,)).fetchone()
        if not row:
            raise ValueError("记录不存在")
        return json.loads(row[0])

    def all(self, table):
        with self.lock:
            return [json.loads(r[0]) for r in self.db.execute(f"SELECT data FROM {table} ORDER BY rowid")]

    def remove(self, table, key):
        with self.lock:
            self.db.execute(f"DELETE FROM {table} WHERE id = ?", (key,))
            self.db.commit()


class InstanceLock:
    """Keep two processes from modifying the same task database."""
    def __init__(self, directory):
        directory.mkdir(parents=True, exist_ok=True)
        self.file = (directory / ".instance.lock").open("a+b")
        self.file.seek(0, 2)
        if self.file.tell() == 0:
            self.file.write(b"0")
            self.file.flush()
        self.file.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.file.close()
            raise ValueError("这个数据目录已有 Agent Bridge 正在运行，请使用已有窗口")

    def close(self):
        self.file.close()


def kill_tree(proc):
    if proc.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10,
                       creationflags=subprocess.CREATE_NO_WINDOW)
    else:
        try:
            os.killpg(proc.pid, signal.SIGTERM)
            proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass


def stop_terminal(proc):
    if proc is None:
        return
    # A .cmd Agent may have a Python helper, cmd.exe and Node below the PTY
    # parent. Closing only that parent leaves the actual Agent running.
    if os.name == "nt" and getattr(proc, "pid", None) and proc.isalive():
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       timeout=10, creationflags=subprocess.CREATE_NO_WINDOW)
    if proc.isalive():
        proc.terminate(force=True)


def command_for(agent, output):
    argv = [x.replace("{output}", str(output)) for x in agent["argv"]]
    executable = shutil.which(argv[0])
    if not executable:
        raise ValueError(f"找不到命令 {argv[0]}，请先安装工具并确认它在 PATH 中")
    argv[0] = executable
    # Windows npm CLIs are often .cmd shims. Prompts ONLY go through stdin.
    if os.name == "nt" and Path(executable).suffix.lower() in {".cmd", ".bat"}:
        # A Python wrapper supplies the raw cmd.exe command line. Passing the /c
        # payload as a Popen list item escapes quotes using CRT rules, which
        # cmd.exe does not follow, especially under C:\Program Files.
        argv = [*helper("batch-launch"), *argv]
    return argv


class Hub:
    def __init__(self, directory):
        self.store = Store(directory)
        self.lock = threading.RLock()
        self.agent_locks = {}
        self.cancellations = {}
        self.processes = {}
        self.terminals = {}
        self.threads = set()
        self.stopping = False
        for task in self.store.all("tasks"):
            if task["status"] in {"queued", "running"}:
                task.update(status="interrupted", error="服务重启，任务已中断。请重新提交。")
                self.store.put("tasks", task)
        for run in self.store.all("runs"):
            if run["status"] == "running":
                run.update(status="interrupted", ended=time.time())
                self.store.put("runs", run)
        for terminal in self.store.all("terminal_history"):
            terminal.update(alive=False, busy=True, proc=None,
                            error="历史会话：进程已结束。可查看日志，或新建终端。")
            self.terminals[terminal["id"]] = terminal

    def agent_save(self, data):
        name = str(data.get("name", "")).strip()
        argv = data.get("argv")
        if not name or len(name) > 80:
            raise ValueError("请输入 1–80 字的 Agent 名称")
        if not isinstance(argv, list) or not argv or not all(isinstance(x, str) and x and len(x) < 4096 for x in argv):
            raise ValueError('命令必须是非空 JSON 字符串数组，例如 ["codex", "exec", "-"]')
        cwd = str(Path(data.get("cwd") or ROOT).expanduser().resolve())
        if not Path(cwd).is_dir():
            raise ValueError("工作目录不存在；请填写运行服务的这台电脑上的目录")
        timeout = int(data.get("timeout", 600))
        if not 10 <= timeout <= 7200:
            raise ValueError("超时必须在 10–7200 秒之间")
        agent_id = data.get("id") or ident()
        if data.get("id"):
            self.store.get("agents", agent_id)
        if len(self.store.all("agents")) >= 100 and not data.get("id"):
            raise ValueError("最多支持 100 个 Agent")
        interactive = data.get("interactive_argv", [argv[0]])
        if not isinstance(interactive, list) or not interactive or not all(isinstance(x, str) and x for x in interactive):
            raise ValueError("交互命令必须是非空字符串数组")
        return self.store.put("agents", dict(id=agent_id, name=name, argv=argv, cwd=cwd,
                              interactive_argv=interactive, timeout=timeout, preset=str(data.get("preset", "custom"))))

    def task_create(self, data):
        prompt = str(data.get("prompt", "")).strip()
        mode = data.get("mode", "partners")
        ids = data.get("agent_ids", [])
        if not prompt or len(prompt) > 64000:
            raise ValueError("任务内容必须在 1–64000 字之间")
        if mode not in {"single", "partners", "hierarchy"}:
            raise ValueError("未知协作模式")
        if not isinstance(ids, list) or not (len(ids) == 1 if mode == "single" else 2 <= len(ids) <= 8):
            raise ValueError("请选择所需的 Agent")
        if len(set(ids)) != len(ids):
            raise ValueError("协作需要两个不同的 Agent")
        agents = [self.store.get("agents", i) for i in ids]
        turns = 1 if mode == "single" else len(ids) + 1 if mode == "hierarchy" else int(data.get("max_turns", 4))
        if not 1 <= turns <= 24:
            raise ValueError("轮数必须在 1–24 之间")
        if mode == "partners" and turns < len(ids):
            raise ValueError("伙伴协作轮数不能少于参与 Agent 数量")
        with self.lock:
            if self.stopping:
                raise ValueError("服务正在停止")
            active = [t for t in self.store.all("tasks") if t["status"] in {"queued", "running"}]
            if len(active) >= 32:
                raise ValueError("队列已满，请等待现有任务完成")
            session_id = data.get("session_id")
            context = ""
            if session_id:
                session = self.store.get("sessions", session_id)
                previous = [t for t in self.store.all("tasks") if t.get("session_id") == session_id]
                if any(t["status"] in {"running", "queued"} for t in previous):
                    raise ValueError("该对话还有任务执行中，请完成或停止后继续")
                completed = [t for t in previous if t["status"] == "completed"][-6:]
                context = "\n\n".join("用户：" + t["prompt"] + "\n答复：" + t["result"] for t in completed)[-120000:]
            else:
                session = dict(id=ident(), title=prompt[:60], created=time.time())
                session_id = session["id"]
            session["updated"] = time.time()
            self.store.put("sessions", session)
            task = dict(id=ident(), mode=mode, prompt=prompt, agent_ids=ids, agents=agents,
                        session_id=session_id, context=context, max_turns=turns, status="queued",
                        current_turn=0, result="", error="", created=time.time())
            self._launch(task)
        return task

    def _launch(self, task):
        self.store.put("tasks", task)
        cancel = threading.Event()
        self.cancellations[task["id"]] = cancel
        thread = threading.Thread(target=self._task_run, args=(task, cancel), daemon=True)
        self.threads.add(thread)
        thread.start()

    def retry(self, task_id, resume=True):
        with self.lock:
            original = self.store.get("tasks", task_id)
            if original["status"] in {"running", "queued"}:
                raise ValueError("请先停止任务")
            if self.stopping or len(self.cancellations) >= 32:
                raise ValueError("服务正在停止或队列已满")
            if original.get("session_id") and any(t.get("session_id") == original["session_id"] and t["status"] in {"running", "queued"} for t in self.store.all("tasks")):
                raise ValueError("该对话还有任务执行中")
            task = {**original, "id": ident(), "parent_task_id": task_id, "status": "queued",
                    "current_turn": 0, "result": "", "error": "", "created": time.time()}
            task.pop("ended", None)
            history = list(original.get("prior_history", [])) if resume else []
            if resume:
                runs = sorted([r for r in self.store.all("runs") if r["task_id"] == task_id], key=lambda r:r["turn"])
                for run in runs:
                    if run["status"] != "completed":
                        break
                    history.append([run["agent_name"], run["result"]])
            if len(history) >= task["max_turns"]:
                raise ValueError("任务已经全部完成，请选择重新运行或继续对话")
            task["prior_history"] = history
            task["start_turn"] = len(history)
            task["current_turn"] = len(history)
            self._launch(task)
            return task

    def _task_run(self, task, cancel):
        history = list(task.get("prior_history", []))
        try:
            for turn in range(task.get("start_turn", 0), task["max_turns"]):
                if cancel.is_set():
                    break
                index = (0 if turn == task["max_turns"] - 1 else turn) if task["mode"] == "hierarchy" else turn % len(task["agents"])
                agent = task["agents"][index]
                with self.lock:
                    gate = self.agent_locks.setdefault(agent["id"], threading.Lock())
                while not gate.acquire(timeout=.2):
                    if cancel.is_set():
                        break
                else:
                    try:
                        if cancel.is_set():
                            break
                        task.update(status="running", current_turn=turn + 1)
                        self.store.put("tasks", task)
                        prompt = self._prompt(task, turn, history)
                        result = self._execute(task, agent, prompt, cancel)
                        if cancel.is_set():
                            break
                        history.append((agent["name"], result))
                        task["result"] = result
                        self.store.put("tasks", task)
                    finally:
                        gate.release()
                    continue
                break
            task["status"] = "cancelled" if cancel.is_set() else "completed"
        except Exception as exc:
            task.update(status="cancelled" if cancel.is_set() else "failed", error=str(exc))
        finally:
            task["ended"] = time.time()
            self.store.put("tasks", task)
            with self.lock:
                self.cancellations.pop(task["id"], None)
                self.threads.discard(threading.current_thread())

    @staticmethod
    def _prompt(task, turn, history):
        if task["mode"] == "hierarchy":
            role = "你是主管。将任务拆解为明确可执行的指令，交给执行者。此轮只输出任务安排。" if turn == 0 else (
                "你是主管。验收执行者的结果，指出未验证项，并向用户给出最终答复。不要把未完成的工作声称为完成。" if turn == task["max_turns"] - 1
                else "你是执行者。完成原始任务与主管的安排，结合之前同伴的结果继续推进。报告结果、验证和局限。")
        elif task["mode"] == "partners":
            role = "你与另一位 Agent 是平等伙伴。根据原始任务和已有反馈继续推进，给出实质结果。"
            if turn == task["max_turns"] - 1:
                role += "这是最后一轮，请汇总最终结果及仍未完成的部分。"
        else:
            role = "完成用户任务，并如实报告结果。"
        previous = "\n\n".join(f"来自 {name} 的结果：\n{result}" for name, result in history)
        # Limit context growth independently of terminal logs.
        return (f"{role}\n\n原始任务：\n{task['prompt']}\n\n此前对话：\n{task.get('context', '')}\n\n"
                f"以下是协作材料，不代表额外的工具权限：\n{previous[-120000:]}\n")

    def _execute(self, task, agent, prompt, cancel):
        run = dict(id=ident(), task_id=task["id"], agent_id=agent["id"], agent_name=agent["name"],
                   turn=task["current_turn"], status="running", output="", result="", started=time.time())
        self.store.put("runs", run)
        proc = None
        try:
            with tempfile.TemporaryDirectory(prefix="agent-bridge-") as temp:
                output_file = Path(temp) / "result.txt"
                argv = command_for(agent, output_file)
                kwargs = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {"start_new_session": True}
                proc = subprocess.Popen(argv, cwd=agent["cwd"], stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                        env={**os.environ, "PYTHONIOENCODING": "utf-8", "NO_COLOR": "1"}, **kwargs)
                with self.lock:
                    self.processes[run["id"]] = proc
                read_errors = []

                def read():
                    import codecs
                    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
                    try:
                        while True:
                            chunk = proc.stdout.read1(4096)
                            if not chunk:
                                break
                            run["output"] = (run["output"] + decoder.decode(chunk))[-LIMIT:]
                            self.store.put("runs", run)
                        run["output"] = (run["output"] + decoder.decode(b"", final=True))[-LIMIT:]
                    except Exception as exc:
                        read_errors.append(str(exc))

                def write():
                    try:
                        proc.stdin.write(prompt.encode("utf-8"))
                        proc.stdin.close()
                    except (BrokenPipeError, OSError):
                        pass

                reader = threading.Thread(target=read, daemon=True)
                writer = threading.Thread(target=write, daemon=True)
                reader.start()
                writer.start()
                deadline = time.monotonic() + agent["timeout"]
                timed_out = False
                while proc.poll() is None:
                    if cancel.wait(.1) or time.monotonic() > deadline:
                        timed_out = not cancel.is_set()
                        kill_tree(proc)
                        break
                proc.wait(timeout=10)
                reader.join(timeout=3)
                if reader.is_alive():
                    raise RuntimeError("输出管道仍被子进程占用，任务已停止。请检查 CLI 配置。")
                if cancel.is_set():
                    run["status"] = "cancelled"
                    return ""
                if timed_out:
                    raise RuntimeError(f"超过 {agent['timeout']} 秒，已终止进程")
                if read_errors:
                    raise RuntimeError("读取输出失败：" + read_errors[0])
                if proc.returncode:
                    raise RuntimeError(f"{agent['name']} 退出码 {proc.returncode}；请查看运行日志。")
                if "{output}" in " ".join(agent["argv"]):
                    if not output_file.is_file():
                        raise RuntimeError("CLI 未写入结果文件，请查看日志并检查命令参数")
                    if output_file.stat().st_size > LIMIT:
                        raise RuntimeError("结果超过 2 MB，请缩小任务范围")
                    result = output_file.read_text(encoding="utf-8", errors="replace")
                else:
                    result = run["output"].replace("\r\n", "\n")
                if not result.strip():
                    raise RuntimeError("Agent 未返回结果，请检查命令是否支持从标准输入读取提示词")
                run.update(status="completed", result=result)
                return result
        except Exception as exc:
            run.update(status="cancelled" if cancel.is_set() else "failed", error=str(exc))
            raise
        finally:
            if proc:
                if proc.poll() is None:
                    kill_tree(proc)
                for stream in (proc.stdin, proc.stdout):
                    try:
                        stream.close()
                    except (OSError, ValueError, AttributeError):
                        pass
            run["ended"] = time.time()
            self.store.put("runs", run)
            with self.lock:
                self.processes.pop(run["id"], None)

    def cancel(self, task_id):
        self.store.get("tasks", task_id)
        with self.lock:
            event = self.cancellations.get(task_id)
            if event:
                event.set()
        return {"ok": True}

    def message(self, data):
        sender, recipient = str(data.get("from", "user")), str(data.get("to", ""))
        self.store.get("agents", recipient)
        if sender != "user":
            self.store.get("agents", sender)
        body = str(data.get("body", "")).strip()
        if not body or len(body) > 64000:
            raise ValueError("消息内容必须在 1–64000 字之间")
        message = dict(id=ident(), sender=sender, recipient=recipient, body=body, created=time.time(), read=False)
        if data.get("trigger"):
            task = self.task_create(dict(mode="single", prompt=f"来自 {sender} 的消息，请处理并回复：\n{body}", agent_ids=[recipient]))
            message["task_id"] = task["id"]
        return self.store.put("messages", message)

    def agent_check(self, aid):
        agent = self.store.get("agents", aid)
        command = shutil.which(agent["argv"][0])
        result = dict(available=bool(command), path=command, cwd_exists=Path(agent["cwd"]).is_dir(),
                      checked=time.time(), note="检查命令是否安装；不代表已登录模型服务。")
        if command:
            args = command_for({**agent, "argv": [agent["argv"][0], "--version"]}, "")
            kwargs = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {"start_new_session": True}
            proc = subprocess.Popen(args, cwd=agent["cwd"], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **kwargs)
            try:
                output, _ = proc.communicate(timeout=10)
                result.update(version_output=output.decode("utf-8", errors="replace")[:8000], returncode=proc.returncode)
            except subprocess.TimeoutExpired:
                kill_tree(proc)
                proc.communicate(timeout=3)
                result["version_output"] = "版本检查超时；请确认工具支持 --version。"
        return result

    def export_task(self, tid):
        task = self.store.get("tasks", tid)
        lines = ["# " + task["prompt"][:80], "", "状态：" + task["status"], "", task["prompt"], ""]
        for index, (name, result) in enumerate(task.get("prior_history", []), 1):
            lines += [f"## 第 {index} 轮 · {name}（此前已完成）", "", result, ""]
        runs = [r for r in self.store.all("runs") if r["task_id"] == tid]
        for run in runs:
            lines += [f"## 第 {run['turn']} 轮 · {run['agent_name']}", "", run.get("result") or run.get("error") or run["output"], ""]
        if task.get("error"):
            lines += ["## 未完成原因", "", task["error"]]
        return "\n".join(lines)

    def backup(self, include_history=False):
        data = dict(version=VERSION, exported=time.time(), agents=self.store.all("agents"))
        if include_history:
            data.update(tasks=self.store.all("tasks"), runs=self.store.all("runs"), sessions=self.store.all("sessions"), messages=self.store.all("messages"))
        return data

    def import_agents(self, data):
        agents = data.get("agents")
        if not isinstance(agents, list) or len(agents) > 100:
            raise ValueError("备份须包含最多 100 个 Agent 配置")
        # Validate in a separate temporary store so bad imports do not partially apply.
        with tempfile.TemporaryDirectory() as temp:
            validation = Hub(Path(temp))
            try:
                values = [validation.agent_save({**a, "id": None}) for a in agents]
            finally:
                validation.store.db.close()
        if len(values) + len(self.store.all("agents")) > 100:
            raise ValueError("导入后会超过 100 个 Agent")
        for value in values:
            self.store.put("agents", value)
        return dict(imported=len(values))

    def terminal_start(self, data):
        if os.name != "nt":
            raise ValueError("交互终端功能需要在 Windows 上运行本服务；自动任务功能可跨平台运行")
        try:
            from winpty import PtyProcess
        except ImportError:
            raise ValueError("缺少 pywinpty，请运行 install.ps1 安装 Windows 终端依赖")
        agent = self.store.get("agents", str(data.get("agent_id", "")))
        argv = data.get("argv") or agent.get("interactive_argv", [agent["argv"][0]])
        if not isinstance(argv, list) or not argv or not all(isinstance(a, str) and a for a in argv):
            raise ValueError('请提供交互启动命令数组，例如 ["codex"]')
        terminal = dict(id=ident(), agent_id=agent["id"], name=agent["name"], busy=True,
                        alive=True, chunks=[], seq=0, error="", created=time.time())
        with self.lock:
            if len([t for t in self.terminals.values() if t["alive"]]) >= 8:
                raise ValueError("最多同时打开 8 个终端")
            resolved = command_for({**agent, "argv": argv}, "")
            proc = PtyProcess.spawn(resolved, cwd=agent["cwd"], dimensions=(32, 120))
            terminal["proc"] = proc
            self.terminals[terminal["id"]] = terminal
            self._terminal_save(terminal)

        def read():
            checkpoint = time.monotonic()
            try:
                while True:
                    try:
                        chunk = proc.read(4096)
                    except EOFError:
                        break
                    if chunk:
                        with self.lock:
                            terminal["seq"] += 1
                            terminal["chunks"].append(dict(seq=terminal["seq"], text=chunk))
                            terminal["chunks"] = terminal["chunks"][-512:]
                            if time.monotonic() - checkpoint > 1:
                                self._terminal_save(terminal)
                                checkpoint = time.monotonic()
                    else:
                        time.sleep(.05)
            except Exception as exc:
                terminal["error"] = str(exc)
            finally:
                terminal["alive"] = False
                self._terminal_save(terminal)
                if hasattr(proc, "close"):
                    proc.close(force=True)
                terminal["proc"] = None
                with self.lock:
                    self.threads.discard(threading.current_thread())

        thread = threading.Thread(target=read, daemon=True)
        with self.lock:
            self.threads.add(thread)
        thread.start()
        return {k: v for k, v in terminal.items() if k not in {"proc", "chunks"}}

    def _terminal_save(self, terminal):
        self.store.put("terminal_history", {k:v for k,v in terminal.items() if k != "proc"})

    def terminal_action(self, tid, data):
        with self.lock:
            terminal = self.terminals.get(tid)
            if not terminal:
                raise ValueError("终端不存在")
            action = data.get("action")
            if action == "stop":
                if terminal["alive"]:
                    stop_terminal(terminal["proc"])
                terminal["alive"] = False
                self._terminal_save(terminal)
            elif action == "resize":
                if terminal["alive"]:
                    rows, cols = int(data.get("rows", 32)), int(data.get("cols", 120))
                    terminal["proc"].setwinsize(max(8, min(300, rows)), max(20, min(400, cols)))
            elif action == "raw":
                text = data.get("text")
                if not terminal["alive"] or not isinstance(text, str) or len(text) > 64000:
                    raise ValueError("终端已退出或输入太长")
                # Raw keys are explicit human input, including arrows/Ctrl+C.
                terminal["proc"].write(text)
            elif action == "ready":
                if not terminal["alive"]:
                    raise ValueError("终端已退出")
                terminal["busy"] = False
            elif action == "input":
                if not terminal["alive"] or terminal["busy"]:
                    raise ValueError("请先确认终端正在等待输入，并标记为可输入")
                text = str(data.get("text", ""))
                if not text or len(text) > 32000 or any(ord(c) < 32 and c not in "\n\t" for c in text):
                    raise ValueError("请输入 1–32000 字的文本，不允许终端控制字符")
                if "\n" in text:
                    if not data.get("bracketed_paste"):
                        raise ValueError("多行输入需要开启括号粘贴；请确认目标工具支持")
                    text = "\x1b[200~" + text + "\x1b[201~"
                terminal["proc"].write(text + ("\r" if data.get("enter", True) else ""))
                terminal["busy"] = True
            else:
                raise ValueError("未知终端操作")
        return {"ok": True}

    def snapshot(self):
        agents = self.store.all("agents")
        tasks = self.store.all("tasks")
        with self.lock:
            for agent in agents:
                gate = self.agent_locks.get(agent["id"])
                agent["busy"] = bool(gate and gate.locked())
                agent["available"] = bool(shutil.which(agent["argv"][0]))
            terminals = [{k: v for k, v in t.items() if k not in {"proc", "chunks"}} for t in self.terminals.values()]
        summaries = [{**{k: v for k, v in t.items() if k not in {"result", "agents"}},
                      "prompt": t["prompt"][:200]} for t in tasks[-100:][::-1]]
        return dict(agents=agents, tasks=summaries, sessions=self.store.all("sessions")[::-1][:100], version=VERSION,
                    counts=dict(total=len(tasks), completed=sum(t["status"] == "completed" for t in tasks),
                                active=sum(t["status"] in {"running", "queued"} for t in tasks)),
                    messages=self.store.all("messages")[-100:][::-1], terminals=terminals,
                    windows=os.name == "nt", cwd=str(default_workspace()), data_dir=str(self.store.directory),
                    desktop=bool(getattr(self, "desktop", None)))

    def shutdown(self):
        with self.lock:
            self.stopping = True
            for event in self.cancellations.values():
                event.set()
            procs = list(self.processes.values())
            terminals = list(self.terminals.values())
        for proc in procs:
            kill_tree(proc)
        for terminal in terminals:
            if terminal["alive"]:
                stop_terminal(terminal["proc"])
                terminal["alive"] = False
                self._terminal_save(terminal)
        for thread in list(self.threads):
            thread.join(timeout=5)


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, port, hub):
        super().__init__(("127.0.0.1", port), Handler)
        self.hub = hub
        self.token = secrets.token_urlsafe(32)
        self.allowed_hosts = {f"127.0.0.1:{self.server_port}", f"localhost:{self.server_port}"}
        self.origin = f"http://127.0.0.1:{self.server_port}"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def send(self, status, data, content_type="application/json; charset=utf-8"):
        raw = json.dumps(data, ensure_ascii=False).encode("utf-8") if content_type.startswith("application/json") else data
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'")
        self.end_headers()
        self.wfile.write(raw)

    def valid_request(self):
        if self.headers.get("Host") not in self.server.allowed_hosts:
            self.send(403, {"error": "只允许通过本机地址访问"})
            return False
        origin = self.headers.get("Origin")
        if origin and origin not in {"http://" + h for h in self.server.allowed_hosts}:
            self.send(403, {"error": "不允许跨站访问"})
            return False
        if self.headers.get("Sec-Fetch-Site") == "cross-site":
            self.send(403, {"error": "不允许跨站访问"})
            return False
        return True

    def do_GET(self):
        if not self.valid_request():
            return
        path = urlsplit(self.path).path
        hub = self.server.hub
        try:
            if path == "/api/bootstrap":
                self.send(200, {"token": self.server.token, "version": VERSION})
                return
            if path.startswith("/api/"):
                if not secrets.compare_digest(self.headers.get("X-Bridge-Token", ""), self.server.token):
                    self.send(403, {"error": "访问令牌无效，请刷新页面"})
                    return
                if path == "/api/state":
                    self.send(200, hub.snapshot())
                elif path == "/api/integrations":
                    command = helper("mcp")
                    self.send(200, dict(mcp={"command": command[0], "args": command[1:]+["--connection", str(hub.store.directory / "connection.json")]},
                                        version=VERSION, data_dir=str(hub.store.directory)))
                elif path == "/api/backup":
                    include = parse_qs(urlsplit(self.path).query).get("history", ["0"])[0] == "1"
                    self.send(200, hub.backup(include))
                elif path.startswith("/api/sessions/"):
                    sid = path.split("/")[-1]
                    self.send(200, dict(session=hub.store.get("sessions", sid), tasks=[t for t in hub.store.all("tasks") if t.get("session_id")==sid]))
                elif path == "/api/messages":
                    recipient = parse_qs(urlsplit(self.path).query).get("to", [""])[0]
                    hub.store.get("agents", recipient)
                    self.send(200, [m for m in hub.store.all("messages") if m["recipient"] == recipient][-100:])
                elif path.startswith("/api/tasks/") and path.endswith("/export"):
                    self.send(200, {"markdown": hub.export_task(path.split("/")[-2])})
                elif path.startswith("/api/tasks/"):
                    tid = path.split("/")[-1]
                    self.send(200, {"task": hub.store.get("tasks", tid),
                                    "runs": [r for r in hub.store.all("runs") if r["task_id"] == tid]})
                elif path.startswith("/api/terminals/"):
                    tid = path.split("/")[-1]
                    after = int(parse_qs(urlsplit(self.path).query).get("after", [0])[0])
                    with hub.lock:
                        t = hub.terminals.get(tid)
                        if not t:
                            raise ValueError("终端不存在")
                        self.send(200, {"alive": t["alive"], "busy": t["busy"], "error": t["error"],
                                        "chunks": [c for c in t["chunks"] if c["seq"] > after]})
                else:
                    self.send(404, {"error": "接口不存在"})
                return
            static = {"/": ("index.html", "text/html; charset=utf-8"),
                      "/app.js": ("app.js", "text/javascript; charset=utf-8"),
                      "/style.css": ("style.css", "text/css; charset=utf-8"),
                      "/vendor/xterm.js": ("vendor/xterm.js", "text/javascript; charset=utf-8"),
                      "/vendor/xterm.css": ("vendor/xterm.css", "text/css; charset=utf-8"),
                      "/vendor/addon-fit.js": ("vendor/addon-fit.js", "text/javascript; charset=utf-8")}
            if path not in static:
                self.send(404, {"error": "页面不存在"})
                return
            file, mime = static[path]
            self.send(200, (ROOT / "web" / file).read_bytes(), mime)
        except (ValueError, KeyError) as exc:
            self.send(400, {"error": str(exc)})
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_POST(self):
        if not self.valid_request():
            return
        if not secrets.compare_digest(self.headers.get("X-Bridge-Token", ""), self.server.token):
            self.send(403, {"error": "访问令牌无效，请刷新页面"})
            return
        try:
            size = int(self.headers.get("Content-Length", 0))
            if not 0 < size <= 2_000_000 or self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                raise ValueError("请求必须是小于 2 MB 的 JSON")
            data = json.loads(self.rfile.read(size))
            if not isinstance(data, dict):
                raise ValueError("请求必须是 JSON 对象")
            path = urlsplit(self.path).path
            hub = self.server.hub
            if path == "/api/agents":
                result = hub.agent_save(data)
            elif path == "/api/agents/check":
                result = hub.agent_check(str(data.get("id", "")))
            elif path == "/api/agents/discover":
                result = [dict(preset=name, path=shutil.which(command)) for name, command in [("codex", "codex"), ("claude", "claude")] if shutil.which(command)]
            elif path == "/api/backup/import":
                result = hub.import_agents(data)
            elif path == "/api/app":
                if not getattr(hub, "desktop", None):
                    raise ValueError("此操作需在桌面应用中使用")
                result = hub.desktop.action(data)
            elif path == "/api/agents/delete":
                aid = str(data.get("id", ""))
                with hub.lock:
                    if any(aid in t["agent_ids"] and t["status"] in {"queued", "running"} for t in hub.store.all("tasks")):
                        raise ValueError("Agent 仍有任务，请先停止任务")
                    if any(t["agent_id"] == aid and t["alive"] for t in hub.terminals.values()):
                        raise ValueError("请先停止这个 Agent 的交互终端")
                    hub.store.remove("agents", aid)
                result = {"ok": True}
            elif path == "/api/tasks":
                result = hub.task_create(data)
            elif path.startswith("/api/tasks/") and path.endswith("/cancel"):
                result = hub.cancel(path.split("/")[-2])
            elif path.startswith("/api/tasks/") and path.endswith("/retry"):
                result = hub.retry(path.split("/")[-2], bool(data.get("resume", True)))
            elif path == "/api/messages":
                result = hub.message(data)
            elif path == "/api/demo":
                result = [hub.agent_save(dict(name=name, preset="demo", cwd=str(ROOT),
                          argv=[*helper("demo"), "--name", name]))
                          for name in ("演示 · 规划伙伴", "演示 · 评审伙伴")]
            elif path == "/api/terminals":
                result = hub.terminal_start(data)
            elif path.startswith("/api/terminals/"):
                result = hub.terminal_action(path.split("/")[-1], data)
            else:
                self.send(404, {"error": "接口不存在"})
                return
            self.send(200, result)
        except (ValueError, KeyError, TypeError, OSError) as exc:
            self.send(400, {"error": str(exc)})
        except Exception as exc:
            self.send(500, {"error": str(exc)})


def main():
    parser = argparse.ArgumentParser(description="Agent Bridge 本地多 Agent 协作平台")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--data-dir", type=Path, default=ROOT / "data")
    parser.add_argument("--no-browser", action="store_true")
    args = parser.parse_args()
    try:
        instance = InstanceLock(args.data_dir.resolve())
        server = Server(args.port, None)
    except (OSError, ValueError) as exc:
        sys.exit(f"无法启动本地服务（端口可能被占用）：{exc}")
    hub = Hub(args.data_dir.resolve())
    server.hub = hub
    connection = args.data_dir.resolve() / "connection.json"
    connection.write_text(json.dumps({"url": server.origin, "token": server.token}), encoding="utf-8")
    try:
        connection.chmod(0o600)
    except OSError:
        pass
    print(f"Agent Bridge 已启动：{server.origin}\n按 Ctrl+C 停止服务。", flush=True)
    if not args.no_browser:
        threading.Timer(.6, lambda: webbrowser.open(server.origin)).start()
    try:
        server.serve_forever(poll_interval=.2)
    except KeyboardInterrupt:
        pass
    finally:
        hub.shutdown()
        server.server_close()
        connection.unlink(missing_ok=True)
        instance.close()


if __name__ == "__main__":
    main()
