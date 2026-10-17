# PyInstaller: GUI + CLI helper share one runtime, no external Python required.
from pathlib import Path
project = Path(SPECPATH).parent
a = Analysis([str(project / 'app_entry.py')], pathex=[str(project)],
             binaries=[], datas=[(str(project / 'web'), 'web')],
             hiddenimports=['webview.platforms.edgechromium', 'winpty'],
             hookspath=[], hooksconfig={}, runtime_hooks=[], excludes=[])
pyz = PYZ(a.pure)
gui = EXE(pyz, a.scripts, [], exclude_binaries=True, name='AgentBridge',
          debug=False, strip=False, upx=False, console=False, icon=str(project / 'packaging' / 'icon.ico'))
cli = EXE(pyz, a.scripts, [], exclude_binaries=True, name='AgentBridge-CLI',
          debug=False, strip=False, upx=False, console=True, icon=str(project / 'packaging' / 'icon.ico'))
coll = COLLECT(gui, cli, a.binaries, a.datas, strip=False, upx=False, name='AgentBridge')
