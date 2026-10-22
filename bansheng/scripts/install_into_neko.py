"""把伴生插件复制进 N.E.K.O. 源码目录。

用法（Windows PowerShell 或终端都可以）：
    python bansheng/scripts/install_into_neko.py  D:\\N.E.K.O            # 只装插件
    python bansheng/scripts/install_into_neko.py  D:\\N.E.K.O --patch    # 同时装可选补丁
    python bansheng/scripts/install_into_neko.py  D:\\N.E.K.O --unpatch  # 去掉补丁

可选补丁给 N.E.K.O. 加一个本机接口 /api/bansheng/*：让伴生能立刻打断角色说话，
以及“原样说一句”（不经角色模型改写，少一次模型调用）。只新增一个文件、在
app/main_server/web_app.py 里加两行，可随时 --unpatch。更新 N.E.K.O. 后重新执行一次即可。

装完在 N.E.K.O. 插件管理里启动「伴生」，或：
    curl -X POST http://127.0.0.1:48916/plugins/reload
    curl -X POST http://127.0.0.1:48916/plugin/bansheng/start
"""
from __future__ import annotations

import argparse
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "neko_plugin" / "bansheng"
PATCH_SRC = ROOT / "neko_patch" / "bansheng_router.py"
IMPORT_LINE = "from main_routers.bansheng_router import router as bansheng_router  # noqa  (伴生补丁)"
INCLUDE_LINE = "app.include_router(bansheng_router)  # 伴生补丁"
ANCHOR_IMPORT = "from main_routers.icebreaker_router import router as icebreaker_router"
ANCHOR_INCLUDE = "app.include_router(icebreaker_router)"


def install_plugin(neko: Path) -> None:
    dst = neko / "plugin" / "plugins" / "bansheng"
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(SRC, dst, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    print(f"插件已安装到 {dst}")


def _web_app(neko: Path) -> Path:
    return neko / "app" / "main_server" / "web_app.py"


def apply_patch(neko: Path) -> None:
    shutil.copy2(PATCH_SRC, neko / "main_routers" / "bansheng_router.py")
    web = _web_app(neko)
    lines = web.read_text("utf-8").splitlines(keepends=True)
    text = "".join(lines)
    if IMPORT_LINE in text and INCLUDE_LINE in text:
        print("补丁已存在（已更新路由文件）")
        return
    out, did_import, did_include = [], False, False
    for line in lines:
        out.append(line)
        if not did_import and line.strip().startswith(ANCHOR_IMPORT):
            out.append(IMPORT_LINE + "\n")
            did_import = True
        if not did_include and line.strip() == ANCHOR_INCLUDE:
            out.append(INCLUDE_LINE + "\n")
            did_include = True
    if not (did_import and did_include):
        raise SystemExit("没找到插入位置（N.E.K.O. 版本变化了？），补丁未应用；插件本身仍可用")
    web.write_text("".join(out), "utf-8")
    print(f"补丁已应用：{web}")


def remove_patch(neko: Path) -> None:
    web = _web_app(neko)
    lines = [l for l in web.read_text("utf-8").splitlines(keepends=True)
             if l.rstrip("\n") not in (IMPORT_LINE, INCLUDE_LINE)]
    web.write_text("".join(lines), "utf-8")
    router = neko / "main_routers" / "bansheng_router.py"
    if router.exists():
        router.unlink()
    print("补丁已移除")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("neko_dir")
    ap.add_argument("--patch", action="store_true", help="同时安装可选补丁（打断 / 原样说）")
    ap.add_argument("--unpatch", action="store_true", help="移除补丁")
    args = ap.parse_args()
    neko = Path(args.neko_dir).expanduser().resolve()
    if not (neko / "plugin" / "plugins").is_dir():
        print(f"没找到 {neko / 'plugin' / 'plugins'}，请确认传入的是 N.E.K.O. 源码根目录")
        return 1
    if args.unpatch:
        remove_patch(neko)
        return 0
    install_plugin(neko)
    if args.patch:
        apply_patch(neko)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
