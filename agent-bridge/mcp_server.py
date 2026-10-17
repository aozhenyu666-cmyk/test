"""Dependency-free stdio MCP adapter for the running local Agent Bridge."""
import argparse
import json
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen
from runtime import VERSION, data_directory

ROOT = Path(__file__).resolve().parent


def schema(properties=None, required=None):
    return dict(type="object", properties=properties or {}, required=required or [], additionalProperties=False)


STRING = {"type": "string"}
TOOLS = [
    dict(name="list_agents", description="列出本机已接入的 Agent、运行状态和 ID。", inputSchema=schema()),
    dict(name="send_message", description="向 Agent 信箱写入消息。仅留档，不会唤醒 Agent。需要执行时使用 create_task。",
         inputSchema=schema({"to": STRING, "from": STRING, "body": STRING, "trigger": {"type":"boolean"}}, ["to", "body"])),
    dict(name="read_inbox", description="读取指定 Agent 的最近 100 条信箱消息（不删除）。",
         inputSchema=schema({"agent_id": STRING}, ["agent_id"])),
    dict(name="create_task", description="异步派发本地自动任务。返回 task ID；使用 get_task 查询结果。不要在自己的执行轮次中创建包含自己的任务并等待它完成，这会使后续任务等待当前轮次退出。",
         inputSchema=schema({"prompt": STRING, "mode": {"type": "string", "enum": ["single", "partners", "hierarchy"]},
                             "agent_ids": {"type": "array", "items": STRING, "minItems": 1, "maxItems": 8},
                             "session_id":STRING,
                             "max_turns": {"type": "integer", "minimum": 1, "maximum": 24}}, ["prompt", "mode", "agent_ids"])),
    dict(name="get_task", description="读取任务状态、逐轮结果与日志。任务是异步的，请合理间隔查询。",
         inputSchema=schema({"task_id": STRING}, ["task_id"])),
    dict(name="cancel_task", description="取消一个本地任务及其运行进程。",
         inputSchema=schema({"task_id": STRING}, ["task_id"])),
    dict(name="list_sessions", description="列出保存的对话，可以在 create_task 中传入 session_id 续接。", inputSchema=schema()),
    dict(name="get_session", description="读取一段对话的任务和结果。", inputSchema=schema({"session_id":STRING},["session_id"])),
    dict(name="retry_task", description="从失败或中断的轮次续跑任务；resume=false 则从头重新执行。已完成的外部操作不会回滚。",
         inputSchema=schema({"task_id":STRING,"resume":{"type":"boolean"}},["task_id"]))
]


class Client:
    def __init__(self, connection):
        self.connection = connection

    def request(self, path, data=None):
        try:
            config = json.loads(self.connection.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            raise ValueError("请先启动 Agent Bridge，或用 --connection 指定连接文件") from exc
        url = config["url"]
        # The adapter deliberately only supports local transports in this version.
        if not url.startswith("http://127.0.0.1:") or not url.rsplit(":", 1)[-1].isdigit():
            raise ValueError("连接地址必须是本地 http://127.0.0.1:端口")
        raw = None if data is None else json.dumps(data, ensure_ascii=False).encode("utf-8")
        req = Request(url + "/api/" + path, data=raw,
                      headers={"X-Bridge-Token": config["token"], "Content-Type": "application/json"})
        try:
            with urlopen(req, timeout=15) as response:
                return json.load(response)
        except HTTPError as exc:
            raise ValueError(json.load(exc).get("error", str(exc))) from exc
        except URLError as exc:
            raise ValueError("无法连接本地服务，请检查 Agent Bridge 是否运行") from exc

    def call(self, name, args):
        tool = next((t for t in TOOLS if t["name"] == name), None)
        if not tool:
            raise ValueError("未知工具")
        if not isinstance(args, dict):
            raise ValueError("工具参数必须为对象")
        definition = tool["inputSchema"]
        if set(args) - set(definition["properties"]) or any(k not in args for k in definition["required"]):
            raise ValueError("缺少必要参数或包含未知参数")
        if name == "list_agents":
            return self.request("state")["agents"]
        if name == "list_sessions":
            return self.request("state")["sessions"]
        if name == "get_session":
            return self.request("sessions/"+quote(str(args["session_id"]),safe=""))
        if name == "retry_task":
            return self.request("tasks/"+quote(str(args["task_id"]),safe="")+"/retry",dict(resume=args.get("resume",True)))
        if name == "send_message":
            return self.request("messages", args)
        if name == "read_inbox":
            return self.request("messages?to=" + quote(str(args["agent_id"]), safe=""))
        if name == "create_task":
            return self.request("tasks", args)
        if name == "get_task":
            return self.request("tasks/" + quote(str(args["task_id"]), safe=""))
        if name == "cancel_task":
            return self.request("tasks/" + quote(str(args["task_id"]), safe="") + "/cancel", {})


def handle(message, client):
    request_id = message.get("id")
    method = message.get("method")
    if request_id is None:
        return None
    if method == "initialize":
        result = {"protocolVersion": "2024-11-05", "capabilities": {"tools": {"listChanged": False}},
                  "serverInfo": {"name": "agent-bridge", "version": VERSION}}
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": TOOLS}
    elif method == "tools/call":
        params = message.get("params", {})
        try:
            value = client.call(params.get("name"), params.get("arguments", {}))
            result = {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False)}]}
        except Exception as exc:
            result = {"content": [{"type": "text", "text": str(exc)}], "isError": True}
    else:
        return {"jsonrpc": "2.0", "id": request_id, "error": {"code": -32601, "message": "Method not found"}}
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--connection", type=Path, default=(data_directory() if getattr(sys,"frozen",False) else ROOT / "data") / "connection.json")
    args = parser.parse_args()
    client = Client(args.connection)
    if hasattr(sys.stdin, "reconfigure"):
        sys.stdin.reconfigure(encoding="utf-8")
        sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
    for line in sys.stdin:
        try:
            message = json.loads(line)
            if not isinstance(message, dict):
                raise ValueError("expected an object")
            response = handle(message, client)
        except (ValueError, TypeError):
            response = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}}
        if response:
            print(json.dumps(response, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
