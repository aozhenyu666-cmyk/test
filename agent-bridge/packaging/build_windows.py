"""Run on Windows after installing pyinstaller, pywebview and pywinpty."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
os.chdir(root)
if os.name != 'nt':
    sys.exit('Windows applications must be built on Windows.')
subprocess.run([sys.executable, '-m', 'PyInstaller', '--noconfirm', '--clean', 'packaging/AgentBridge.spec'], check=True)
app = root / 'dist' / 'AgentBridge'
for name in ['README.md', 'VALIDATION.md', '开始使用.txt']:
    shutil.copy2(root / name, app / name)
subprocess.run([str(app / 'AgentBridge-CLI.exe'), '--self-test'], check=True, timeout=90)
subprocess.run([str(app / 'AgentBridge-CLI.exe'), '--window-self-test'], check=True, timeout=60)
archive = root / 'dist' / 'AgentBridge-Windows-x64.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
    for file in app.rglob('*'):
        if file.is_file():
            z.write(file, 'AgentBridge/' + file.relative_to(app).as_posix())
sha = hashlib.sha256(archive.read_bytes()).hexdigest()
archive.with_suffix('.zip.sha256').write_text(sha + '  ' + archive.name + '\n', encoding='ascii')
print(f'Built {archive} ({archive.stat().st_size} bytes); SHA256 {sha}')
