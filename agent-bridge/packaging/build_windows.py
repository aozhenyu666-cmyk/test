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
compiler = Path(os.environ.get('ProgramFiles(x86)', r'C:\Program Files (x86)')) / 'Inno Setup 6' / 'ISCC.exe'
if not compiler.is_file():
    sys.exit('Inno Setup 6 is required to build the installer.')
subprocess.run([str(compiler), str(root / 'packaging' / 'installer.iss')], check=True)
installer = root / 'dist' / 'AgentBridge-Setup-0.2.0-x64.exe'
assert installer.is_file()
sums = '\n'.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name for p in [archive,installer])+'\n'
(root / 'dist' / 'SHA256SUMS.txt').write_text(sums,encoding='ascii')
