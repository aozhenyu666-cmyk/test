#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

rm -rf dist build
tsc -p .
node test/harness.js > /dev/null

python3 - <<'EOF'
import json, os, zipfile, hashlib
m = json.load(open("manifest.json", encoding="utf-8"))
entries = [m["main"]] + [s["entry"] for s in m.get("subpackages", [])]
missing = [e for e in entries if not os.path.isfile(e)]
if missing:
    raise SystemExit(f"manifest entries missing: {missing}")

os.makedirs("build", exist_ok=True)
out = "build/focus_hub.toolpkg"
files = ["manifest.json"] + sorted(
    os.path.join(root, f) for root, _, fs in os.walk("dist") for f in fs
)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for f in files:
        info = zipfile.ZipInfo(f, date_time=(2026, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, open(f, "rb").read())
digest = hashlib.sha256(open(out, "rb").read()).hexdigest()
print(f"{out}  {os.path.getsize(out)} bytes")
for f in files:
    print("  " + f)
print(f"sha256 {digest}")
open(out + ".sha256", "w").write(f"{digest}  focus_hub.toolpkg\n")
EOF
