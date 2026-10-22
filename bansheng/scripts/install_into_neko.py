"""把伴生插件复制进 N.E.K.O. 源码目录。

用法（Windows PowerShell 或终端都可以）：
    python bansheng/scripts/install_into_neko.py  D:\\N.E.K.O
之后在 N.E.K.O. 插件管理里启动「伴生」，或：
    curl -X POST http://127.0.0.1:48916/plugins/reload
    curl -X POST http://127.0.0.1:48916/plugin/bansheng/start
"""
from __future__ import annotations

import shutil
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parents[1] / "neko_plugin" / "bansheng"


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    neko = Path(sys.argv[1]).expanduser().resolve()
    plugins = neko / "plugin" / "plugins"
    if not plugins.is_dir():
        print(f"没找到 {plugins}，请确认传入的是 N.E.K.O. 源码根目录")
        return 1
    dst = plugins / "bansheng"
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(SRC, dst, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    print(f"已安装到 {dst}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
