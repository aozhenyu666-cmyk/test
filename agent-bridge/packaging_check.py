"""Real executable smoke check, invoked in the packaged Windows runtime."""
import json
from pathlib import Path
import subprocess
import tempfile
import time
from bridge import Hub, Server
from runtime import ROOT, helper


def main():
    assert (ROOT / 'web' / 'vendor' / 'xterm.js').is_file()
    import winpty
    import webview
    with tempfile.TemporaryDirectory() as temp:
        hub = Hub(Path(temp))
        agent = hub.agent_save(dict(name='Packaged demo', cwd=temp, argv=[*helper('demo'),'--delay','.01']))
        task = hub.task_create(dict(mode='single',prompt='Executable smoke test',agent_ids=[agent['id']]))
        deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            done=hub.store.get('tasks',task['id'])
            if done['status'] not in {'queued','running'}: break
            time.sleep(.05)
        assert done['status']=='completed',done
        connection=Path(temp)/'connection.json'
        server=Server(0,hub)
        import threading
        threading.Thread(target=server.serve_forever,daemon=True).start()
        connection.write_text(json.dumps(dict(url=server.origin,token=server.token)),encoding='utf-8')
        request=json.dumps(dict(jsonrpc='2.0',id=1,method='tools/call',params=dict(name='list_agents',arguments={})))+'\n'
        result=subprocess.run([*helper('mcp'),'--connection',str(connection)],input=request,text=True,encoding='utf-8',capture_output=True,timeout=15)
        response=json.loads(result.stdout)
        assert not response['result'].get('isError'),response
        server.shutdown();server.server_close();hub.shutdown();hub.store.db.close()
    print('PACKAGED_SMOKE_OK: embedded runtime, resources, demo subprocess, SQLite, MCP, pywebview, winpty')
