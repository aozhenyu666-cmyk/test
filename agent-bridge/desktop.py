"""Native desktop lifecycle; web UI and all agent processes belong to this app."""
import ctypes
import json
import os
from pathlib import Path
import sys
import threading
import traceback
import webbrowser

from bridge import Hub, Server, InstanceLock
from runtime import VERSION, data_directory


class Desktop:
    def __init__(self, directory):
        self.directory = directory
        self.instance = InstanceLock(directory)
        self.hub = Hub(directory)
        self.hub.desktop = self
        self.server = Server(0, self.hub)
        self.window = None
        self.connection = directory / "connection.json"
        self.connection.write_text(json.dumps(dict(url=self.server.origin, token=self.server.token)), encoding="utf-8")
        try:
            self.connection.chmod(0o600)
        except OSError:
            pass
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def action(self, data):
        action = data.get("action")
        if action == "choose-folder":
            import webview
            paths = self.window.create_file_dialog(webview.FOLDER_DIALOG)
            return dict(path=paths[0] if paths else None)
        if action == "open-data":
            os.startfile(str(self.directory)) if os.name == "nt" else webbrowser.open(self.directory.as_uri())
            return dict(ok=True)
        if action == "focus":
            if self.window:
                self.window.restore()
                self.window.show()
            return dict(ok=True)
        if action == "save-export":
            import webview
            kind = data.get("kind")
            if kind == "task":
                content = self.hub.export_task(str(data.get("task_id", "")))
                name = "AgentBridge-task.md"
            elif kind in {"config", "full"}:
                content = json.dumps(self.hub.backup(kind == "full"), ensure_ascii=False, indent=2)
                name = "AgentBridge-" + kind + ".json"
            else:
                raise ValueError("未知导出类型")
            paths = self.window.create_file_dialog(webview.SAVE_DIALOG, save_filename=name)
            if not paths:
                return dict(saved=False)
            target = Path(paths[0])
            target.write_text(content, encoding="utf-8")
            return dict(saved=True, path=str(target))
        raise ValueError("未知桌面操作")

    def close(self):
        self.server.shutdown()
        self.hub.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.hub.store.db.close()
        self.connection.unlink(missing_ok=True)
        self.instance.close()


def main():
    import webview
    directory = data_directory()
    directory.mkdir(parents=True, exist_ok=True)
    try:
        app = Desktop(directory)
    except ValueError:
        connection = directory / "connection.json"
        if connection.is_file():
            from urllib.request import Request, urlopen
            info=json.loads(connection.read_text(encoding="utf-8"))
            try:
                request=Request(info["url"]+"/api/app",data=b'{"action":"focus"}',headers={"Content-Type":"application/json","X-Bridge-Token":info["token"]})
                with urlopen(request,timeout=3) as response:
                    response.read()
            except Exception:
                webbrowser.open(info["url"])
        return
    try:
        app.window = webview.create_window("Agent Bridge", app.server.origin, width=1360, height=900,
                                          min_size=(900, 640), background_color="#f6f8fa")
        webview.start(gui="edgechromium" if os.name == "nt" else None, private_mode=False,
                      storage_path=str(directory / "webview"))
    finally:
        app.close()


def report_error(exc):
    directory = data_directory()
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "desktop-error.log").write_text(traceback.format_exc(), encoding="utf-8")
    message = f"Agent Bridge 无法启动：{exc}\n\n错误日志：{directory / 'desktop-error.log'}\n请确认 Microsoft Edge WebView2 Runtime 已安装。"
    if os.name == "nt":
        ctypes.windll.user32.MessageBoxW(None, message, "Agent Bridge", 0x10)
    else:
        print(message, file=sys.stderr)
