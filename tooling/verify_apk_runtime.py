#!/usr/bin/env python3
"""Proves from the APK itself whether it carries the dsh-go-proactive host plugin.

    python3 tooling/verify_apk_runtime.py app-arm64-debug.apk [--plugin runtime/proactive/dsh-go-proactive] [--json]

A green Gradle build says nothing about the runtime: the rootfs comes from runtime/out/<abi>/assets,
which only runtime/build-runtime.sh writes (on Linux). This script opens the APK and checks:

  1. assets/runtime/manifest.json exists and names dshGoProactive at /opt/dsh-go/dsh-go-proactive
  2. the launch command passes --patch /etc/dsh-app/dsh-web.patch.yml
  3. the rootfs archive is in the APK and its SHA-256 matches the manifest
  4. inside the rootfs: the plugin's package.json, lib/index.js and lib/core.js
  5. inside the rootfs: dsh-web.patch.yml inserts /opt/dsh-go/dsh-go-proactive/lib/index.js
  6. inside the rootfs: nothing from the plugin's test/ tree (mock model, fixture sessions)
  7. the APK really carries lib/<abi>/lib{proot,proot-loader,talloc,android-shmem}.so, and each
     is byte-identical to what assets/runtime/manifest.json recorded (the jniLibs the launch
     command execs; without them proot cannot start)
  8. with --plugin: the shipped plugin files are byte-identical to the repo's (this revision,
     including the tools/pre-execute guard)

Exit 0 only when every check passes. Reads the APK only; never installs anything.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
import tarfile
import zipfile

ASSET_DIR = "assets/runtime"
PLUGIN = "opt/dsh-go/dsh-go-proactive"
WEB_PATCH = "etc/dsh-app/dsh-web.patch.yml"
SHIPPED = ("package.json", "lib/index.js", "lib/core.js")
# The Termux binaries the launch argv execs by name; Gradle ships them as lib/<abi>/*.so.
NATIVE = ("libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so")
ABIS = ("arm64-v8a", "x86_64")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def verify(apk: pathlib.Path, plugin_dir: pathlib.Path | None = None) -> list[dict]:
    checks: list[dict] = []

    def check(name: str, ok: bool, detail: str = "") -> bool:
        checks.append({"check": name, "result": "PASS" if ok else "FAIL", "detail": detail})
        return ok

    try:
        z = zipfile.ZipFile(apk)
    except (OSError, zipfile.BadZipFile) as e:
        check("apk readable", False, str(e))
        return checks
    names = set(z.namelist())
    if not check("runtime manifest in APK", f"{ASSET_DIR}/manifest.json" in names,
                 f"{ASSET_DIR}/manifest.json" + ("" if f"{ASSET_DIR}/manifest.json" in names
                                                  else " missing: runtime/out was empty when the APK was built")):
        return checks
    manifest = json.loads(z.read(f"{ASSET_DIR}/manifest.json"))
    pro = manifest.get("dshGoProactive") or {}
    check("manifest names dshGoProactive", pro.get("path") == "/" + PLUGIN,
          f"dshGoProactive={json.dumps(manifest.get('dshGoProactive'))} runtimeVersion={manifest.get('runtimeVersion')}")
    launch = json.dumps(manifest.get("launch", {}))
    check("launch passes the web patch", f"--patch /{WEB_PATCH}" in launch)

    rootfs_name = f"{ASSET_DIR}/{manifest.get('rootfs', '')}"
    if not check("rootfs archive in APK", rootfs_name in names, rootfs_name):
        return checks
    blob = z.read(rootfs_name)
    check("rootfs sha256 matches manifest", sha256(blob) == manifest.get("rootfsSha256"),
          f"{sha256(blob)[:16]}… vs {str(manifest.get('rootfsSha256'))[:16]}…")

    abi = str(manifest.get("abi") or "")
    present_abis = sorted({n.split("/")[1] for n in names if n.startswith("lib/") and n.count("/") >= 2})
    check("manifest names an ABI the APK carries", abi in ABIS and present_abis == [abi],
          f"manifest abi={abi or 'missing'} apk lib/ dirs={present_abis or 'none'}")
    if abi not in ABIS:      # nothing below can be addressed; stop before reading lib/wrong/path
        return checks
    jni = manifest.get("jniLibs") or {}
    absent = [f"lib/{abi}/{n}" for n in NATIVE if f"lib/{abi}/{n}" not in names]
    check("proot binaries in the APK", not absent,
          "missing: " + ", ".join(absent) + " (Gradle ignores a sourceSets srcDir that does not "
          "exist, so an APK can be built from an empty runtime/out)" if absent
          else f"{len(NATIVE)} in lib/{abi}/")
    check("manifest lists the proot binaries", all(n in jni for n in NATIVE),
          "missing from manifest.jniLibs: " + ", ".join(n for n in NATIVE if n not in jni)
          if len(jni) < len(NATIVE) else "all present")
    differing = []
    for n in NATIVE:
        if f"lib/{abi}/{n}" not in names:
            continue
        lib = z.read(f"lib/{abi}/{n}")
        want = (jni.get(n) or {}).get("sha256")
        if sha256(lib) != want:
            differing.append(n)
        elif (jni.get(n) or {}).get("bytes") is not None and len(lib) != jni[n]["bytes"]:
            differing.append(n + " (size)")
    check("proot binaries match the manifest", not differing,
          "differs: " + ", ".join(differing) if differing else "byte-identical")

    found: dict[str, bytes] = {}
    patch_text = ""
    test_entries: list[str] = []
    mode = "r:xz" if rootfs_name.endswith(".xz") else "r:gz"
    import io
    with tarfile.open(fileobj=io.BytesIO(blob), mode=mode) as tar:
        for m in tar:
            n = m.name.lstrip("./")
            if n.startswith(PLUGIN + "/test/") or n == PLUGIN + "/test":
                test_entries.append(n)
            if not m.isfile():
                continue
            if n == WEB_PATCH:
                patch_text = tar.extractfile(m).read().decode("utf-8", "replace")
            for rel in SHIPPED:
                if n == f"{PLUGIN}/{rel}":
                    found[rel] = tar.extractfile(m).read()
    check("plugin files in rootfs", all(r in found for r in SHIPPED),
          "missing: " + ", ".join(r for r in SHIPPED if r not in found) if len(found) < len(SHIPPED) else "all present")
    check("web patch loads the plugin", f"name: /{PLUGIN}/lib/index.js" in patch_text,
          "row present" if patch_text else f"{WEB_PATCH} not in rootfs")
    check("plugin test/ not shipped", not test_entries, ", ".join(test_entries[:5]))

    if plugin_dir is not None:
        diffs = []
        for rel in SHIPPED:
            want = (plugin_dir / rel).read_bytes()
            if found.get(rel) is None or sha256(found[rel]) != sha256(want):
                diffs.append(rel)
        check("plugin matches this repo revision", not diffs, "differs: " + ", ".join(diffs) if diffs else "identical")
        check("plugin carries the tool guard", b"tools/pre-execute" in found.get("lib/index.js", b""))
    return checks


def checked_apk(raw: pathlib.Path, ap: argparse.ArgumentParser) -> pathlib.Path:
    """A real .apk file, with `..` rejected before it is resolved.

    This is a local diagnostic tool, but the argument still comes from the command line: keeping
    it a resolved, existing .apk keeps a bad argument from turning into an arbitrary read.
    """
    if any(part == ".." for part in raw.parts):
        ap.error(f"apk path must not contain '..': {raw}")
    path = raw.expanduser().resolve()
    if path.suffix.lower() != ".apk" or not path.is_file():
        ap.error(f"not an APK file: {path}")
    return path


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("apk", type=pathlib.Path)
    ap.add_argument("--plugin", type=pathlib.Path, help="repo plugin dir to compare against")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    apk_path = checked_apk(a.apk, ap)
    plugin = None
    if a.plugin is not None:
        if any(part == ".." for part in a.plugin.parts):
            ap.error(f"--plugin path must not contain '..': {a.plugin}")
        plugin = a.plugin.expanduser().resolve()
    checks = verify(apk_path, plugin)
    ok = all(c["result"] == "PASS" for c in checks)
    if a.json:
        print(json.dumps({"apk": str(apk_path), "apkSha256": sha256(apk_path.read_bytes()) if apk_path.exists() else None,
                          "ok": ok, "checks": checks}, ensure_ascii=False, indent=2))
    else:
        for c in checks:
            print(f"{c['result']}  {c['check']}" + (f"  ({c['detail']})" if c["detail"] else ""))
        print("OK" if ok else "NOT PROVEN: this APK does not demonstrably carry the plugin")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
