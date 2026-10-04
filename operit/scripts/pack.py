#!/usr/bin/env python3
"""打包 ToolPkg 与 Skill，并生成 SHA256SUMS。产物写入 operit/dist/。"""
import hashlib
import json
import pathlib
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"
FIXED_TIME = (2026, 10, 4, 0, 0, 0)  # 固定时间戳，保证重复打包产物一致


def add_tree(zf, base, prefix=""):
    for path in sorted(p for p in base.rglob("*") if p.is_file()):
        info = zipfile.ZipInfo(prefix + path.relative_to(base).as_posix(), FIXED_TIME)
        info.compress_type = zipfile.ZIP_DEFLATED
        zf.writestr(info, path.read_bytes())


def main():
    manifest = json.loads((ROOT / "toolpkg" / "manifest.json").read_text(encoding="utf-8"))
    version = manifest["version"]
    DIST.mkdir(exist_ok=True)
    outputs = []
    pkg = DIST / f"zhukong-{version}.toolpkg"
    with zipfile.ZipFile(pkg, "w") as zf:
        add_tree(zf, ROOT / "toolpkg")
    outputs.append(pkg)
    skill = DIST / f"zhukong-skill-{version}.zip"
    with zipfile.ZipFile(skill, "w") as zf:
        add_tree(zf, ROOT / "skill", "")
    outputs.append(skill)
    lines = [f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}" for p in outputs]
    (DIST / "SHA256SUMS").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
