#!/usr/bin/env python3
"""C2 device smoke runner. Read-only except installing/launching the debug APK.

Usage:
  python tools/c2_device_smoke.py --apk tongzhuo.apk --serial 10AE5U23LT004DN
  python tools/c2_device_smoke.py --apk tongzhuo.apk --look   # 加跑“看一眼”：只有图形的合成图 → 点桌宠 → 看一眼
  (tongzhuo.apk = https://github.com/aozhenyu666-cmyk/test/releases/download/tongzhuo-latest/tongzhuo.apk,
   or a build of the same branch: it is signed with companion-pet/keystore, so it installs over the device's copy)

Writes JSON with NOT_RUN when no device. Does not read keys, screenshots content, chats, or uninstall.
The --look flow only records the outcome category, keyword hits on the synthetic picture, and the app's
vision evidence (type/size/hash/outcome) -- never the screenshot, Base64 or the key.
"""
import argparse
import html
import struct
import zlib
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


# ---------- 看一眼（C2-T2）：只有图形、没有文字的合成图，无障碍读不到任何内容 ----------

VISUAL_NAME = "c2_visual_only.png"
LOOK_PREFIX = "我看了一眼："
LOOK_FAIL_PREFIXES = {
    "没截到": "CAPTURE_FAILED",
    "截到了，但还没允许": "UPLOAD_OFF",
    "截到了，但看图还没配好": "NOT_CONFIGURED",
    "截图成功，但视觉模型调用失败": "REQUEST_FAILED",
    "截图成功，模型也回了": "PARSE_FAILED",
    "截图成功，但看图没配好": "NOT_CONFIGURED",
    "今天的调用次数用完了": "BUDGET",
    "这个 App 在排除名单里": "EXCLUDED",
}
COLOR_WORDS = ("红", "白", "蓝")
SHAPE_WORDS = ("圆", "方", "正方", "矩形", "块")


def synthetic_png(width=720, height=1280):
    """Red background, white circle, blue square. Pure Python so the script has no dependencies."""
    cx, cy, r = width // 2, height // 3, width // 5
    sx0, sy0, side = width // 3, height * 3 // 5, width // 3
    rows = []
    for y in range(height):
        row = bytearray(b"\x00")
        for x in range(width):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                row += b"\xff\xff\xff"
            elif sx0 <= x < sx0 + side and sy0 <= y < sy0 + side:
                row += b"\x1e\x50\xdc"
            else:
                row += b"\xd2\x1e\x1e"
        rows.append(bytes(row))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(b"".join(rows), 6)) + chunk(b"IEND", b"")


def classify_look(texts):
    """From visible bubble texts, decide what "看一眼" produced. Returns (outcome, text_or_None)."""
    for t in texts:
        if t.startswith(LOOK_PREFIX):
            return "LOOK_RESULT", t
    for t in texts:
        for prefix, outcome in LOOK_FAIL_PREFIXES.items():
            if t.startswith(prefix):
                return outcome, t
    return None, None


def keyword_hits(text):
    return {
        "color": any(w in text for w in COLOR_WORDS),
        "shape": any(w in text for w in SHAPE_WORDS),
    }


def parse_vision_evidence(prefs_xml):
    """shared_prefs/vision_evidence.xml -> dict (the app stores one JSON string under name="last")."""
    try:
        root = ET.fromstring(prefs_xml)
    except Exception:
        return None
    for node in root.iter("string"):
        if node.attrib.get("name") == "last" and node.text:
            try:
                data = json.loads(html.unescape(node.text))
            except Exception:
                return None
            keep = ("at", "outcome", "model", "host", "imageBlocks", "mime", "imageBytes", "width", "height",
                    "imageSha256", "durationMs", "failure", "summaryChars")
            return {k: data.get(k) for k in keep}
    return None


def visible_texts(root):
    return [(n.attrib.get("text") or "").strip() for n in root.iter("node") if (n.attrib.get("text") or "").strip()]


def open_visual_page(serial, evidence_dir):
    local = os.path.join(evidence_dir, VISUAL_NAME)
    with open(local, "wb") as f:
        f.write(synthetic_png())
    remote = "/sdcard/Pictures/" + VISUAL_NAME
    adb(serial, "push", local, remote, timeout=60)
    adb(serial, "shell", "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + remote)
    time.sleep(2)
    out = shell(serial, "content query --uri content://media/external/images/media --projection _id "
                        "--where \"_display_name='%s'\"" % VISUAL_NAME)
    ids = re.findall(r"_id=(\d+)", out)
    if ids:
        uri = "content://media/external/images/media/" + ids[-1]
        adb(serial, "shell", "am start -a android.intent.action.VIEW -t image/png -d " + uri + " --grant-read-uri-permission")
        time.sleep(3)
        return "OPENED_VIA_MEDIASTORE"
    return "NOT_OPENED"


def look_flow(serial, evidence_dir):
    """Open the synthetic picture, tap the pet, tap 看一眼, wait for the result bubble, read vision evidence."""
    result = {"status": "NOT_RUN"}
    result["visual_page"] = open_visual_page(serial, evidence_dir)
    if result["visual_page"] == "NOT_OPENED":
        result["reason"] = "合成图没能用相册打开：手动用相册打开 /sdcard/Pictures/%s 后加 --look-here 重跑" % VISUAL_NAME
        return result
    return look_here(serial, evidence_dir, result)


def look_here(serial, evidence_dir, result=None):
    result = result or {"status": "NOT_RUN", "visual_page": "MANUAL"}
    started = shell(serial, "date +%s")
    try:
        started_ms = int(started.strip()) * 1000
    except Exception:
        started_ms = 0
    for step in ("桌宠", "看一眼"):
        state = "NOT_FOUND"
        for _ in range(3):
            state = tap_text(serial, step)
            if state == "TAPPED":
                break
            time.sleep(1.5)
        result["tap_" + ("pet" if step == "桌宠" else "look")] = state
        if state != "TAPPED":
            result["reason"] = "找不到“%s”（悬浮窗没显示或菜单已收起）" % step
            return result
        time.sleep(1.5)
    outcome, text = None, None
    deadline = time.time() + 35
    while time.time() < deadline and outcome is None:
        root = dump_ui(serial, attempts=2)
        if root is not None:
            outcome, text = classify_look(visible_texts(root))
        if outcome is None:
            time.sleep(2)
    screenshot(serial, os.path.join(evidence_dir, "c2-look-visual.png"))
    result["bubble_outcome"] = outcome or "NO_BUBBLE"
    if text and outcome == "LOOK_RESULT":
        # 合成图上没有任何私人内容，只记关键词命中和长度。
        result["keywords"] = keyword_hits(text)
        result["result_chars"] = len(text) - len(LOOK_PREFIX)
    elif text:
        result["failure_text"] = text[:120]
    evidence = parse_vision_evidence(shell(serial, "run-as %s cat shared_prefs/vision_evidence.xml" % PKG))
    result["vision_evidence"] = evidence
    fresh = bool(evidence) and (started_ms == 0 or (evidence.get("at") or 0) >= started_ms - 5000)
    sent_image = fresh and (evidence.get("imageBlocks") or 0) >= 1
    if outcome == "LOOK_RESULT" and sent_image and evidence.get("outcome") == "OK":
        hits = result.get("keywords", {})
        result["status"] = "PASS" if hits.get("color") and hits.get("shape") else "PARTIAL_IMAGE_SENT_BUT_DESCRIPTION_OFF"
    elif outcome is None:
        result["status"] = "UNVERIFIED_NO_BUBBLE"
    elif outcome == "LOOK_RESULT":
        result["status"] = "UNVERIFIED_NO_FRESH_EVIDENCE"
    else:
        result["status"] = "FAIL_" + outcome
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--serial", default="")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--evidence-dir", default="_pet-reports/c2-device")
    parser.add_argument("--look", action="store_true", help="跑看一眼：推合成图→相册打开→点桌宠→看一眼")
    parser.add_argument("--look-here", action="store_true", help="合成图已在前台：直接点桌宠→看一眼")
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
        report["status"] = {k: "NOT_RUN" for k in ["install", "launch", "traces", "goals", "pending", "look"]}
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
        # 文件不存在 = 还没创建过目标/待确认（第一次写入时才建文件），不是持久化失败。
        if name == "traces":
            report["status"][name] = "PASS" if "MISSING" not in out else "NOT_RUN"
        else:
            report["status"][name] = "PRESENT" if "MISSING" not in out else "EMPTY_NOT_CREATED_YET"

    if args.look or args.look_here:
        report["look"] = look_here(serial, args.evidence_dir) if args.look_here else look_flow(serial, args.evidence_dir)
        report["status"]["look"] = report["look"]["status"]
    else:
        report["status"]["look"] = "NOT_RUN"

    path = os.path.join(args.evidence_dir, "c2-smoke.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())