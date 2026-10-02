#!/usr/bin/env python3
"""备用构建用 dx 而不是 D8，dx 不会把 invokedynamic（lambda / LambdaMetafactory）脱糖，
这类代码在 Android 上会在运行时崩溃。这里做两项检查：
1. app 和 core 自己的字节码里不能有 invokedynamic；
2. 不能调用 Kotlin 标准库里内部用了 invokedynamic 的方法（例如 vararg 版本的 compareBy）。

用法：check-indy.py <stdlib.jar> <要检查的目录或 jar>...
"""
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


def class_files(target: str, tmp: Path):
    p = Path(target)
    if p.is_dir():
        return sorted(p.rglob("*.class"))
    out = tmp / p.name
    with zipfile.ZipFile(p) as z:
        z.extractall(out)
    return sorted(f for f in out.rglob("*.class") if "META-INF" not in f.parts)


def javap(files):
    out = []
    for i in range(0, len(files), 200):
        r = subprocess.run(["javap", "-c", "-p", "-s"] + [str(f) for f in files[i:i + 200]],
                           capture_output=True, text=True)
        out.append(r.stdout)
    return "\n".join(out)


HEADER = re.compile(r"^  \S.*?(\w+|<init>|<clinit>)\(.*\)(?: throws .*)?;$")


def methods(text):
    """yield (package, name, descriptor, body)"""
    pkg = ""
    name = desc = None
    body = []
    for line in text.splitlines():
        m = re.match(r"^(?:Compiled from|.*(?:class|interface) ([\w.$]+))", line)
        if line.startswith(("public ", "final ", "abstract ", "class ", "interface ")) and "{" in line:
            if name:
                yield pkg, name, desc, "\n".join(body)
            name = None
            cls = re.search(r"(?:class|interface) ([\w.$]+)", line)
            if cls:
                pkg = cls.group(1).rsplit(".", 1)[0].replace(".", "/")
            continue
        h = HEADER.match(line)
        if h:
            if name:
                yield pkg, name, desc, "\n".join(body)
            name, desc, body = h.group(1), None, []
            continue
        if name and desc is None and line.strip().startswith("descriptor:"):
            desc = line.split(":", 1)[1].strip()
            continue
        if name:
            body.append(line)
    if name:
        yield pkg, name, desc, "\n".join(body)


def main():
    stdlib, targets = sys.argv[1], sys.argv[2:]
    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        bad_std = set()
        for pkg, name, desc, body in methods(javap(class_files(stdlib, tmp))):
            if "invokedynamic" in body:
                bad_std.add((pkg, name, desc))
        problems = []
        for target in targets:
            for pkg, name, desc, body in methods(javap(class_files(target, tmp))):
                if "invokedynamic" in body:
                    problems.append(f"{pkg}.{name}{desc}: 使用了 invokedynamic")
                for call in re.findall(r"// (?:Interface)?Method ([\w/$]+)\.([\w$<>]+):(\S+)", body):
                    owner, mname, mdesc = call
                    opkg = owner.rsplit("/", 1)[0]
                    if (opkg, mname, mdesc) in bad_std:
                        problems.append(f"{pkg}.{name}: 调用了标准库中使用 invokedynamic 的 {owner}.{mname}{mdesc}")
        if problems:
            print("dx 无法安全处理以下代码（请改写，或改用 Android Gradle 插件构建）：")
            print("\n".join(sorted(set(problems))))
            sys.exit(1)
        print(f"invokedynamic 检查通过（标准库中有 {len(bad_std)} 个此类方法，均未被调用）")


if __name__ == "__main__":
    main()
