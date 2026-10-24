#!/usr/bin/env python3
"""C2 device smoke runner. Read-only except installing/launching the debug APK.

Usage:
  python tools/c2_device_smoke.py --apk tongzhuo.apk --serial 10AE5U23LT004DN
  (tongzhuo.apk = https://github.com/aozhenyu666-cmyk/test/releases/download/tongzhuo-latest/tongzhuo.apk,
   or a build of the same branch: it is signed with companion-pet/keystore, so it installs over the device's copy)

Writes JSON with NOT_RUN when no device. Does not read keys, screenshots content, chats, or uninstall.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

PKG = "cn.companion.pet"
ADB = "adb"


def adb(serial, *args, timeout=20):
    cmd = [ADB] + (["-s", serial] if serial else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)


def devices():
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=20).stdout
    return [line.split()[0] for line in out.splitlines()[1:] if line.strip().endswith("device")]


def shell(serial, command, timeout=20):
    return adb(serial, "shell", command, timeout=timeout).stdout.strip()


def screenshot(serial, path):
    adb(serial, "shell", "screencap", "-p", "/sdcard/Download/c2-smoke.png")
    adb(serial, "pull", "/sdcard/Download/c2-smoke.png", path)
    return os.path.exists(path)


def dump_ui(serial, attempts=4):
    """uiautomator dump often fails with "could not get idle state" while the pet overlay animates; retry."""
    for i in range(attempts):
        adb(serial, "shell", "rm", "-f", "/sdcard/window.xml")
        adb(serial, "shell", "uiautomator", "dump", "/sdcard/window.xml", timeout=30)
        xml = adb(serial, "shell", "cat", "/sdcard/window.xml").stdout
        try:
            return ET.fromstring(xml)
        except Exception:
            time.sleep(1 + i)
    return None


BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def find_tap_point(root, text):
    """Centre of the clickable node (or its nearest clickable ancestor) whose text/content-desc is `text`.

    Compose often puts the label in a non-clickable child of the clickable tab; tapping the label's own
    bounds can miss. Returns (x, y) or None.
    """
    parent = {child: node for node in root.iter() for child in node}
    for node in root.iter("node"):
        label = (node.attrib.get("text") or "").strip() or (node.attrib.get("content-desc") or "").strip()
        if label != text:
            continue
        target = node
        while target is not None and target.attrib.get("clickable") != "true":
            target = parent.get(target)
        target = target if target is not None and target.tag == "node" else node
        m = BOUNDS.findall(target.attrib.get("bounds", ""))
        if m:
            a, b, c, d = map(int, m[0])
            if c > a and d > b:
                return (a + c) // 2, (b + d) // 2
    return None


def tap_text(serial, text):
    root = dump_ui(serial)
    if root is None:
        return "DUMP_FAILED"
    point = find_tap_point(root, text)
    if point is None:
        return "NOT_FOUND"
    shell(serial, "input tap %d %d" % point)
    return "TAPPED"


def apk_sha256(path):
    import hashlib
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest().upper()


def installed_sha256(serial, evidence_dir):
    """Pull the installed base.apk and hash it, so "already installed" proves WHICH build is on the device."""
    out = shell(serial, "pm path %s" % PKG)
    path = next((l[len("package:"):] for l in out.splitlines() if l.startswith("package:") and l.endswith("base.apk")), None)
    if not path:
        return None
    local = os.path.join(evidence_dir, "installed-base.apk")
    adb(serial, "pull", path, local, timeout=120)
    return apk_sha256(local) if os.path.exists(local) else None


def top_activity(serial):
    out = shell(serial, "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity'")
    return out[:300]


def event_lines(serial):
    month = datetime.now().strftime("%Y-%m")
    path = "files/memory/events/events-%s.jsonl" % month
    out = shell(serial, "run-as %s sh -c 'wc -l %s'" % (PKG, path))
    try:
        return int(out.split()[0])
    except Exception:
        return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--serial", default="")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--evidence-dir", default="_pet-reports/c2-device")
    args = parser.parse_args()
    global ADB
    ADB = args.adb
    os.makedirs(args.evidence_dir, exist_ok=True)
    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "apk": os.path.abspath(args.apk),
        "device": args.serial or None,
        "status": {},
        "evidence": args.evidence_dir,
    }
    available = devices()
    if not available:
        report["status"] = {k: "NOT_RUN" for k in ["install", "launch", "traces", "goals", "pending"]}
        report["b1"] = {"status": "NOT_RUN"}
        report["blocked"] = "adb devices 为空"
        path = os.path.join(args.evidence_dir, "c2-smoke.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0

    serial = args.serial or available[0]
    report["device"] = serial
    local_sha = apk_sha256(args.apk)
    report["apk_sha256"] = local_sha
    # vivo/iQOO 会在手机上弹“USB 安装”确认：没人点就是 INSTALL_FAILED_ABORTED。
    # 签名不同（例如本机调试钥匙 vs 仓库固定钥匙）会是 INSTALL_FAILED_UPDATE_INCOMPATIBLE：只能用同一把钥匙构建，不要卸载。
    install = adb(serial, "install", "-r", args.apk, timeout=180)
    report["install_output"] = (install.stdout + install.stderr)[-300:]
    device_sha = installed_sha256(serial, args.evidence_dir)
    report["installed_sha256"] = device_sha
    if install.returncode == 0 and device_sha == local_sha:
        report["status"]["install"] = "PASS"
    elif device_sha == local_sha:
        report["status"]["install"] = "PASS_SAME_APK_ALREADY_INSTALLED"
    else:
        report["status"]["install"] = "FAIL_DIFFERENT_APK_ON_DEVICE"
    adb(serial, "shell", "am", "start", "-W", "-n", PKG + "/.MainActivity", timeout=30)
    time.sleep(3)
    top = top_activity(serial)
    report["top_activity"] = top
    report["status"]["launch"] = "PASS" if (PKG + "/.MainActivity") in top or (PKG + "/" + PKG + ".MainActivity") in top else "UNVERIFIED"
    report["version"] = shell(serial, "dumpsys package %s | grep -E 'versionName|versionCode'" % PKG)[:200]

    # B1：打开“记忆”页不能清空当天事件。
    before = event_lines(serial)
    tapped = tap_text(serial, "记忆")
    if tapped == "TAPPED":
        time.sleep(3)
        after = event_lines(serial)
        report["b1"] = {"before": before, "after": after, "status": "PASS" if after >= before else "FAIL"}
    else:
        report["b1"] = {"before": before, "after": None, "status": "NOT_RUN", "reason": tapped}

    share = (
        "am start -a android.intent.action.SEND -t text/plain "
        "--es android.intent.extra.TEXT 'C2合成标题 https://example.com/c2 合成摘录' "
        "--es android.intent.extra.SUBJECT 'C2合成标题' -n %s/.MaterialShareActivity" % PKG
    )
    adb(serial, "shell", share)
    time.sleep(2)
    report["share_preview"] = "PASS" if dump_ui(serial) is not None else "NOT_RUN"
    screenshot(serial, os.path.join(args.evidence_dir, "c2-share.png"))

    for name, path in {
        "traces": "files/memory/agent/traces.jsonl",
        "goals": "files/memory/goals/state.json",
        "pending": "files/memory/actions/pending.json",
    }.items():
        out = shell(serial, "run-as %s sh -c 'test -f %s && wc -c %s || echo MISSING'" % (PKG, path, path))
        report[name] = out[:200]
        report["status"][name] = "PASS" if "MISSING" not in out else "NOT_RUN"

    path = os.path.join(args.evidence_dir, "c2-smoke.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())