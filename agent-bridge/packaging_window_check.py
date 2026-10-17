"""Exercise the packaged native WebView2 window on a Windows runner."""
import tempfile
import time
from pathlib import Path
from desktop import Desktop


def main():
    import webview
    failures=[]
    with tempfile.TemporaryDirectory() as temp:
        app=Desktop(Path(temp))
        app.window=webview.create_window('Agent Bridge desktop check', app.server.origin, width=1100,height=800)
        def inspect():
            try:
                deadline=time.monotonic()+25
                while time.monotonic()<deadline:
                    try:
                        ready=app.window.evaluate_js("document.getElementById('connection-status').textContent")
                        if ready=='本地服务已连接': break
                    except Exception: pass
                    time.sleep(.3)
                else: raise AssertionError('Desktop UI did not connect to its local server')
                assert app.window.evaluate_js("typeof Terminal")=='function'
                assert app.window.evaluate_js("document.getElementById('page-settings') !== null")
            except Exception as exc: failures.append(str(exc))
            finally: app.window.destroy()
        try:
            webview.start(inspect,gui='edgechromium')
        finally:
            app.close()
        if failures:raise AssertionError(failures)
    print('NATIVE_WINDOW_OK: WebView2, local UI, API connection, offline terminal assets')
