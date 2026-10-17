"""Optional Playwright integration check: python tests/browser_smoke.py."""
import contextlib
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
from pathlib import Path
import time
from playwright.sync_api import sync_playwright
from playwright.sync_api import expect

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT.parent / "scratch" / "agent-bridge-checks"
OUT.mkdir(parents=True, exist_ok=True)

@contextlib.contextmanager
def local_server():
    with tempfile.TemporaryDirectory() as temp:
        proc = subprocess.Popen([sys.executable,str(ROOT/'bridge.py'),'--no-browser','--port','0','--data-dir',temp],
                                stdout=subprocess.DEVNULL,
                                **({'creationflags':subprocess.CREATE_NEW_PROCESS_GROUP} if os.name=='nt' else {}))
        try:
            connection=Path(temp)/'connection.json'
            for _ in range(100):
                if connection.exists(): break
                if proc.poll() is not None: raise RuntimeError('Preview server failed to start')
                time.sleep(.05)
            yield json.loads(connection.read_text(encoding='utf-8'))['url']
        finally:
            if proc.poll() is None:
                proc.send_signal(signal.CTRL_BREAK_EVENT if os.name=='nt' else signal.SIGINT)
                try: proc.wait(timeout=5)
                except subprocess.TimeoutExpired: proc.kill(); proc.wait()


with local_server() as url, sync_playwright() as p:
    browser = p.chromium.launch(headless=True, executable_path=shutil.which("chromium") or p.chromium.executable_path, args=["--no-sandbox"])
    page = browser.new_page(viewport={"width":1440,"height":1080}, device_scale_factor=1)
    errors = []
    requests = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.on('request',lambda request: requests.append((request.url,request.post_data)) if request.method=='POST' else None)
    page.goto(url)
    page.get_by_text("本地服务已连接", exact=True).wait_for()
    page.get_by_role("button", name="接入指南", exact=False).click()
    page.get_by_role("button", name="添加演示伙伴").click()
    expect(page.locator('#stat-agents')).to_have_text('2')
    page.get_by_label("任务内容").fill("请协作检查自动消息传递流程，并用中文汇总结果。")
    page.get_by_role("button", name="开始协作").click()
    expect(page.locator('#stat-complete')).to_have_text('1', timeout=15000)
    page.locator("#task-detail .run").nth(3).wait_for()
    assert page.locator("#task-detail .run").count() == 4
    assert "离线演示回执" in page.locator("#task-detail").inner_text()
    page.screenshot(path=str(OUT/"workspace-desktop.png"),full_page=True)

    page.get_by_role("button", name="上下级派发", exact=True).click()
    page.get_by_label("任务内容").fill("演示主管安排、伙伴执行、主管验收。")
    page.get_by_role("button", name="开始协作").click()
    expect(page.locator('#stat-complete')).to_have_text('2', timeout=15000)
    assert page.locator("#task-detail .run").count() == 3

    page.locator("#message-body").fill("<script>window.injected=true</script> 你好伙伴")
    page.get_by_role("button",name="发送消息",exact=True).click()
    expect(page.locator('#message-list')).to_contain_text('你好伙伴')
    assert page.evaluate("window.injected") is None
    page.get_by_role("button",name="Agent 管理",exact=False).click()
    assert page.locator(".agent-card").count() == 2
    page.locator("[data-edit]").first.click()
    page.get_by_label("显示名称").fill("演示 · 规划伙伴（已编辑）")
    page.get_by_role("button",name="保存 Agent",exact=True).click()
    page.get_by_role("heading",name="演示 · 规划伙伴（已编辑）",exact=True).wait_for()
    page.screenshot(path=str(OUT/"agents-desktop.png"),full_page=True)

    page.locator('[data-check]').first.click()
    expect(page.locator('#check-output')).to_contain_text('Python',timeout=15000)
    page.get_by_role('button',name='关闭检查').click()
    page.get_by_role('button',name='对话历史',exact=False).click()
    page.locator('[data-session]').first.click()
    page.get_by_role('button',name='继续这段对话').click()
    page.get_by_label('任务内容').fill('继续上次讨论，补充测试结论。')
    page.get_by_role('button',name='开始协作').click()
    expect(page.locator('#stat-complete')).to_have_text('3',timeout=15000)
    expect(page.locator('#session-banner')).to_be_visible()
    with page.expect_download() as exported:
        page.get_by_role('button',name='导出 Markdown',exact=True).click()
    exported.value.save_as(OUT/'task-export.md')
    assert '离线演示' in (OUT/'task-export.md').read_text()

    page.get_by_role('button',name='设置与备份',exact=False).click()
    expect(page.locator('#mcp-json')).to_contain_text('')
    expect(page.locator('#mcp-json')).not_to_have_value('')
    config=json.loads(page.locator('#mcp-json').input_value())
    assert config['mcpServers']['agent_bridge']['command']
    with page.expect_download() as backup:
        page.get_by_role('button',name='导出配置',exact=True).click()
    backup.value.save_as(OUT/'config-backup.json')
    config=json.loads((OUT/'config-backup.json').read_text())
    assert len(config['agents'])==2
    config['agents']=config['agents'][:1]
    config['agents'][0]['name']='演示 · 第三位伙伴'
    page.locator('#import-file').set_input_files({'name':'config.json','mimeType':'application/json','buffer':json.dumps(config).encode()})
    expect(page.locator('#stat-agents')).to_have_text('3')
    page.get_by_role('button',name='协作工作台',exact=False).click()
    page.get_by_role('button',name='改为新对话',exact=True).click()
    page.locator('#extra-team-field summary').click()
    third=page.locator('#task-extra-agents option').filter(has_text='第三位伙伴').get_attribute('value')
    page.locator('#task-extra-agents').select_option(third)
    page.get_by_label('任务内容').fill('三位 Agent 完成主管与多位执行者的协作。')
    page.get_by_role('button',name='开始协作').click()
    expect(page.locator('#stat-complete')).to_have_text('4',timeout=15000)
    expect(page.locator('#task-detail .run')).to_have_count(4)
    page.locator('#message-body').fill('请自动处理这条消息。')
    page.locator('#message-trigger').check()
    page.get_by_role('button',name='发送消息',exact=True).click()
    try:
        expect(page.locator('#stat-complete')).to_have_text('5',timeout=15000)
    except AssertionError:
        print('POST requests:',requests)
        raise

    page.get_by_role("button",name="交互终端",exact=False).click()
    if os.name != 'nt':
        page.get_by_role("button",name="启动终端",exact=True).click()
        page.get_by_role("status").get_by_text("交互终端功能需要在 Windows",exact=False).wait_for()

    page.set_viewport_size({"width":390,"height":844})
    page.get_by_role("button",name="协作工作台",exact=False).click()
    page.wait_for_timeout(200)
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
    page.screenshot(path=str(OUT/"workspace-mobile.png"),full_page=True)
    assert not errors, errors
    print(json.dumps({"browser":"Chromium","flows":["partners-4-turn","hierarchy-3-turn","message-XSS-escaping","agent-edit","agent-check","session-continuation","markdown-export","MCP-config-generation","config-backup-import","multiple-workers","message-wakeup","unsupported-terminal-feedback","mobile-no-overflow"],"page_errors":errors,"screenshots":str(OUT)},ensure_ascii=False))
    browser.close()
