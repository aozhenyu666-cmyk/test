#!/usr/bin/env python3
"""C2 device smoke runner. Read-only except installing/launching the debug APK.

Usage:
  python tools/c2_device_smoke.py --apk _pet-reports/c1-diagnostics/app-debug.apk --serial 10AE5U23LT004DN

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
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def devices():
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True, timeout=20).stdout
    return [line.split()[0] for line in out.splitlines()[1:] if line.strip().endswith("device")]


def shell(serial, command, timeout=20):
    return adb(serial, "shell", command, timeout=timeout).stdout.strip()


def screenshot(serial, path):
    adb(serial, "shell", "screencap", "-p", "/sdcard/Download/c2-smoke.png")
    adb(serial, "pull", "/sdcard/Download/c2-smoke.png", path)
    return os.path.exists(path)


def dump_ui(serial):
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/window.xml")
    xml = adb(serial, "shell", "cat", "/sdcard/window.xml").stdout
    try:
        return ET.fromstring(xml)
    except Exception:
        return None


def tap_text(serial, text):
    root = dump_ui(serial)
    if root is None:
        return False
    for node in root.iter("node"):
        if node.attrib.get("text") == text:
            m = re.findall(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
            if m:
                a, b, c, d = map(int, m[0])
                shell(serial, "input tap %d %d" % ((a + c) // 2, (b + d) // 2))
                return True
    return False


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
        report["status"] = {k: "NOT_RUN" for k in ["install", "launch", "b1", "share", "traces", "goals", "pending"]}
        report["blocked"] = "adb devices 为空"
        path = os.path.join(args.evidence_dir, "c2-smoke.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0

    serial = args.serial or available[0]
    report["device"] = serial
    if adb(serial, "install", "-r", "-g", args.apk, timeout=120).returncode == 0:
        report["status"]["install"] = "PASS"
    else:
        report["status"]["install"] = "FAIL"
    adb(serial, "shell", "am", "start", "-W", "-n", PKG + "/.MainActivity")
    time.sleep(3)
    report["status"]["launch"] = "PASS" if PKG in shell(serial, "dumpsys window | grep mCurrentFocus") else "UNVERIFIED"
    report["version"] = shell(serial, "dumpsys package %s | grep versionName" % PKG)[:120]

    before = event_lines(serial)
    if tap_text(serial, "记忆"):
        time.sleep(3)
        after = event_lines(serial)
        report["b1"] = {"before": before, "after": after, "status": "PASS" if before == after else "FAIL"}
    else:
        report["b1"] = {"before": before, "after": None, "status": "NOT_RUN"}

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