import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import types
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bridge import Hub, Server, InstanceLock
from mcp_server import Client, handle
from windows_launcher import command_line

FIXTURE = '''import sys, time, json, argparse
p=argparse.ArgumentParser()
p.add_argument('--name'); p.add_argument('--capture'); p.add_argument('--delay',type=float,default=.02)
p.add_argument('--fail',action='store_true'); p.add_argument('--output')
a=p.parse_args(); prompt=sys.stdin.read()
with open(a.capture, 'a', encoding='utf-8') as f: f.write(json.dumps({'prompt':prompt,'name':a.name})+'\\n')
print('START '+a.name, flush=True)
time.sleep(a.delay)
if a.fail: print('FAIL',flush=True); sys.exit(7)
result='RESULT '+a.name+' 上下文 '+str(len(prompt))
if a.output:
 with open(a.output,'w',encoding='utf-8') as f: f.write(result)
else: print(result)
'''


class HubTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.fixture = self.root / "fixture.py"
        self.fixture.write_text(FIXTURE, encoding="utf-8")
        self.hub = Hub(self.root / "data")

    def tearDown(self):
        self.hub.shutdown()
        deadline = time.monotonic() + 5
        while self.hub.cancellations and time.monotonic() < deadline:
            time.sleep(.02)
        self.hub.store.db.close()
        self.temp.cleanup()

    def agent(self, name, *extra):
        return self.hub.agent_save(dict(name=name, cwd=str(self.root), timeout=10,
                    argv=[sys.executable, "-u", str(self.fixture), "--name", name,
                          "--capture", str(self.root / (name + ".jsonl")), *extra]))

    def task(self, agents, mode="partners", turns=2):
        return self.hub.task_create(dict(mode=mode, agent_ids=[a["id"] for a in agents],
                                        prompt="检查中文任务传递", max_turns=turns))

    def wait(self, task, status=None):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            value = self.hub.store.get("tasks", task["id"])
            if value["status"] in {"completed", "failed", "cancelled"}:
                if status:
                    self.assertEqual(value["status"], status, value)
                return value
            time.sleep(.02)
        self.fail("Task did not finish")

    def captured(self, name):
        return [json.loads(line) for line in (self.root / (name + ".jsonl")).read_text(encoding="utf-8").splitlines()]

    def test_partners_forward_results_and_unicode(self):
        a, b = self.agent("A"), self.agent("B")
        task = self.task([a, b], turns=4)
        done = self.wait(task, "completed")
        self.assertEqual(done["current_turn"], 4)
        self.assertIn("RESULT B", done["result"])
        self.assertIn("RESULT A", self.captured("B")[0]["prompt"])
        self.assertIn("RESULT B", self.captured("A")[1]["prompt"])
        self.assertIn("检查中文任务传递", self.captured("A")[0]["prompt"])

    def test_hierarchy_dispatch_execute_review(self):
        a, b = self.agent("Boss"), self.agent("Worker")
        done = self.wait(self.task([a, b], "hierarchy"), "completed")
        self.assertEqual(done["max_turns"], 3)
        self.assertIn("你是执行者", self.captured("Worker")[0]["prompt"])
        self.assertIn("验收", self.captured("Boss")[1]["prompt"])
        self.assertIn("RESULT Worker", self.captured("Boss")[1]["prompt"])

    def test_output_file_not_progress_logs(self):
        a = self.agent("File", "--output", "{output}")
        done = self.wait(self.task([a], "single"), "completed")
        self.assertTrue(done["result"].startswith("RESULT File"))
        self.assertNotIn("START", done["result"])

    def test_failure_stops_later_turns(self):
        a, b = self.agent("Bad", "--fail"), self.agent("Next")
        done = self.wait(self.task([a, b]), "failed")
        self.assertIn("7", done["error"])
        self.assertFalse((self.root / "Next.jsonl").exists())

    def test_timeout_stops_process(self):
        a = self.agent("Slow", "--delay", "10")
        a["timeout"] = .15
        self.hub.store.put("agents", a)
        done = self.wait(self.task([a], "single"), "failed")
        self.assertIn("超过", done["error"])
        self.assertFalse(self.hub.processes)

    def test_cancel_running_process(self):
        a = self.agent("Slow", "--delay", "10")
        task = self.task([a], "single")
        deadline = time.monotonic()+3
        while not self.hub.processes and time.monotonic()<deadline:
            time.sleep(.01)
        self.assertTrue(self.hub.processes)
        self.hub.cancel(task["id"])
        self.wait(task, "cancelled")
        self.assertFalse(self.hub.processes)

    def test_cancel_queued_task(self):
        a = self.agent("Queued")
        gate = threading.Lock(); gate.acquire()
        self.hub.agent_locks[a["id"]] = gate
        try:
            task = self.task([a], "single")
            self.hub.cancel(task["id"])
            self.wait(task, "cancelled")
            self.assertFalse(self.hub.store.all("runs"))
        finally:
            gate.release()

    def test_same_agent_is_serialized(self):
        a = self.agent("Serial", "--delay", ".25")
        t1 = self.task([a], "single")
        t2 = self.task([a], "single")
        self.wait(t1, "completed"); self.wait(t2, "completed")
        runs = sorted(self.hub.store.all("runs"), key=lambda r:r["started"])
        self.assertGreaterEqual(runs[1]["started"], runs[0]["ended"])

    def test_different_agents_can_run_concurrently(self):
        a, b = self.agent("ParallelA", "--delay", ".3"), self.agent("ParallelB", "--delay", ".3")
        t1 = self.task([a], "single"); t2 = self.task([b], "single")
        self.wait(t1, "completed"); self.wait(t2, "completed")
        runs = self.hub.store.all("runs")
        self.assertLess(max(r["started"] for r in runs), min(r["ended"] for r in runs))

    def test_restart_marks_interrupted(self):
        self.hub.store.put("tasks", dict(id="stale", status="running"))
        other = Hub(self.root / "data")
        self.assertEqual(other.store.get("tasks", "stale")["status"], "interrupted")
        other.store.db.close()

    def test_invalid_and_duplicate_agents(self):
        a = self.agent("A")
        with self.assertRaises(ValueError):
            self.task([a, a])
        with self.assertRaises(ValueError):
            self.hub.agent_save(dict(name="Bad", cwd=str(self.root), argv="cmd"))

    def test_missing_executable_fails_cleanly(self):
        a = self.hub.agent_save(dict(name="Missing",cwd=str(self.root),argv=["agent-bridge-no-such-command-879"] ))
        done = self.wait(self.task([a], "single"), "failed")
        self.assertIn("找不到命令", done["error"])

    def test_instance_lock(self):
        lock = InstanceLock(self.root / "lock")
        try:
            with self.assertRaises(ValueError):
                InstanceLock(self.root / "lock")
        finally:
            lock.close()

    def test_windows_batch_wrapper_quoting(self):
        raw = command_line([r"C:\Program Files\tools\codex.cmd", "exec", r"C:\Users\A&B\out.txt"], "cmd.exe")
        self.assertEqual(raw, 'cmd.exe /d /s /c ""C:\\Program Files\\tools\\codex.cmd" "exec" "C:\\Users\\A&B\\out.txt""')

    def test_terminal_input_guard_with_mock_windows_backend(self):
        # Contract test only: this is NOT a real Windows ConPTY test.
        a = self.agent("Terminal")
        class FakeProcess:
            alive = True
            writes = []
            @classmethod
            def spawn(cls, *args, **kwargs): return cls()
            def isalive(self): return self.alive
            def read(self, size=1024):
                if not self.alive: raise EOFError()
                return ""
            def write(self, text): self.writes.append(text)
            def terminate(self, force=False): self.alive=False
        fake = types.SimpleNamespace(PtyProcess=FakeProcess)
        with patch.dict(sys.modules, {"winpty": fake}), patch("bridge.os.name", "nt"), patch("bridge.command_for", return_value=["codex"]):
            t = self.hub.terminal_start({"agent_id":a["id"], "argv":["codex"]})
            tid = t["id"]
            with self.assertRaises(ValueError):
                self.hub.terminal_action(tid, {"action":"input", "text":"hello"})
            self.hub.terminal_action(tid, {"action":"ready"})
            self.hub.terminal_action(tid, {"action":"input", "text":"中文消息", "enter":True})
            self.assertEqual(FakeProcess.writes, ["中文消息\r"])
            self.assertTrue(self.hub.terminals[tid]["busy"])
            self.hub.terminal_action(tid, {"action":"ready"})
            with self.assertRaises(ValueError):
                self.hub.terminal_action(tid, {"action":"input", "text":"a\nb"})
            self.hub.terminal_action(tid, {"action":"stop"})


class HTTPTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.hub = Hub(Path(cls.temp.name))
        cls.server = Server(0, cls.hub)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.connection = Path(cls.temp.name) / "connection.json"
        cls.connection.write_text(json.dumps(dict(url=cls.server.origin,token=cls.server.token)),encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.hub.shutdown()
        cls.hub.store.db.close(); cls.temp.cleanup()

    def request(self, path, data=None, headers=None, authorized=True):
        h = {"X-Bridge-Token":self.server.token} if authorized else {}
        h.update(headers or {})
        raw = None if data is None else json.dumps(data).encode()
        if raw is not None: h["Content-Type"]="application/json"
        return urlopen(Request(self.server.origin+path, data=raw, headers=h), timeout=3)

    def test_ui_and_bootstrap(self):
        with self.request("/") as r:
            self.assertIn("Agent Bridge",r.read().decode())
            self.assertIn("frame-ancestors 'none'", r.headers["Content-Security-Policy"])
        with self.request("/api/bootstrap", authorized=False) as r:
            self.assertEqual(json.load(r)["token"], self.server.token)

    def test_token_required(self):
        with self.assertRaises(HTTPError) as ctx:
            self.request("/api/state",authorized=False)
        self.assertEqual(ctx.exception.code,403)

    def test_cross_origin_and_rebinding_blocked(self):
        for headers in [{"Origin":"https://evil.example"},{"Host":"evil.example"},{"Sec-Fetch-Site":"cross-site"}]:
            with self.assertRaises(HTTPError) as ctx:
                self.request("/api/bootstrap",headers=headers,authorized=False)
            self.assertEqual(ctx.exception.code,403)

    def test_invalid_body(self):
        with self.assertRaises(HTTPError) as ctx:
            self.request("/api/agents",data=[])
        self.assertEqual(ctx.exception.code,400)

    def test_mcp_real_stdio_to_http(self):
        requests = [
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}},
            {"jsonrpc":"2.0","method":"notifications/initialized"},
            {"jsonrpc":"2.0","id":2,"method":"tools/list"},
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_agents","arguments":{}}},
        ]
        result = subprocess.run([sys.executable,str(Path(__file__).resolve().parents[1]/"mcp_server.py"),"--connection",str(self.connection)],
                    input="\n".join(json.dumps(r) for r in requests)+"\n",text=True,capture_output=True,encoding="utf-8",timeout=5)
        self.assertEqual(result.returncode,0,result.stderr)
        responses=[json.loads(line) for line in result.stdout.splitlines()]
        self.assertEqual(len(responses),3)
        self.assertEqual(responses[0]["result"]["serverInfo"]["name"],"agent-bridge")
        self.assertEqual(len(responses[1]["result"]["tools"]),6)
        self.assertFalse(responses[2]["result"].get("isError"))

    def test_message_roundtrip_via_mcp(self):
        client=Client(self.connection)
        a=self.hub.agent_save(dict(name="Inbox",argv=[sys.executable],cwd=self.temp.name))
        result=client.call("send_message",dict(to=a["id"],body="你好，伙伴"))
        inbox=client.call("read_inbox",dict(agent_id=a["id"]))
        self.assertEqual(inbox[-1]["id"],result["id"])
        self.assertEqual(inbox[-1]["body"],"你好，伙伴")


if __name__ == "__main__":
    unittest.main()
