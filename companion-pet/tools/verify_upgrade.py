#!/usr/bin/env python3
"""Check an APK pair offline, or verify install -r with synthetic data on a clean emulator.

No credentials are read or created. Device mode refuses physical devices and any emulator
where cn.companion.pet is already installed. It never uninstalls or clears an existing app.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys

PACKAGE = "cn.companion.pet"


def run(args, *, data=None, allow_failure=False):
    result = subprocess.run([str(a) for a in args], input=data, capture_output=True)
    if result.returncode and not allow_failure:
        raise RuntimeError(result.stderr.decode("utf-8", "replace").strip() or
                           result.stdout.decode("utf-8", "replace").strip() or
                           f"Command failed: {args[0]}")
    return result.stdout.decode("utf-8", "replace").strip()


def sdk_tools(sdk):
    folders = sorted((sdk / "build-tools").glob("*"),
                     key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)), reverse=True)
    for folder in folders:
        aapt = folder / ("aapt.exe" if os.name == "nt" else "aapt")
        signer = folder / "lib/apksigner.jar"
        if aapt.is_file() and signer.is_file():
            return aapt, signer
    raise RuntimeError("Android SDK build-tools with aapt and apksigner.jar are required")


def apk_info(apk, aapt, signer):
    if not apk.is_file():
        raise RuntimeError(f"APK does not exist: {apk}")
    badging = run([aapt, "dump", "badging", apk])
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
    if not match or match[1] != PACKAGE:
        raise RuntimeError("Both APKs must belong to cn.companion.pet")
    java = Path(os.environ.get("JAVA_HOME", "")) / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not java.is_file():
        java = shutil.which("java")
    if not java:
        raise RuntimeError("Java is required for certificate verification")
    certificates = run([java, "-jar", signer, "verify", "--print-certs", apk])
    digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", certificates)
    if not digests:
        raise RuntimeError("APK signature verification did not return a signer")
    return {"package": match[1], "version_code": int(match[2]), "version_name": match[3],
            "signers_sha256": sorted(d.lower() for d in digests),
            "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest()}


def verify_device(sdk, serial, old_apk, new_apk):
    adb = sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    if not adb.is_file():
        raise RuntimeError("SDK platform-tools/adb is required")
    prefix = [adb, "-s", serial]
    if run(prefix + ["get-state"]) != "device":
        raise RuntimeError("Selected emulator is not online")
    if run(prefix + ["shell", "getprop ro.kernel.qemu"]) != "1":
        raise RuntimeError("Refusing device mode: only an Android emulator is supported")
    existing = run(prefix + ["shell", f"pm path {PACKAGE}"], allow_failure=True)
    if existing:
        raise RuntimeError("Refusing to overwrite an existing installation; use a clean emulator")

    run(prefix + ["install", "-t", old_apk])
    fixtures = {
        "files/memory/persona.md": "合成升级测试人设\n",
        "files/memory/profile.md": "[他说过] 合成升级偏好\n",
        "files/memory/facts.json": '[{"id":"upgrade-fact","factKey":"upgrade-key","text":"合成升级偏好","confidence":"said","createdAt":1792756800000,"sourceEventIds":[],"version":1}]',
        "files/memory/today.md": "# 2026-10-23\n- 合成升级交流\n",
        "shared_prefs/settings.xml": '<?xml version="1.0" encoding="utf-8"?><map><boolean name="paused" value="true"/><boolean name="user_stopped" value="true"/><int name="daily_limit" value="37"/></map>',
    }
    run(prefix + ["shell", f"run-as {PACKAGE} mkdir -p files/memory shared_prefs"])
    for relative, text in fixtures.items():
        command = f"run-as {PACKAGE} sh -c " + shlex.quote("cat > " + relative)
        run(prefix + ["shell", "-T", command], data=text.encode("utf-8"))
    run(prefix + ["install", "-r", "-t", new_apk])
    launch = run(prefix + ["shell", f"am start -W -n {PACKAGE}/.MainActivity"])
    if "Error:" in launch or "Status: ok" not in launch:
        raise RuntimeError("Upgraded activity failed to start")
    # Read checksums only, and only of files this invocation created on a fresh emulator.
    for relative, text in fixtures.items():
        expected = hashlib.sha256(text.encode("utf-8")).hexdigest()
        actual = run(prefix + ["shell", f"run-as {PACKAGE} sha256sum {relative}"]).split()[0]
        if expected != actual:
            raise RuntimeError(f"Upgrade changed synthetic fixture: {relative}")
    expected_backup = hashlib.sha256(fixtures["files/memory/today.md"].encode("utf-8")).hexdigest()
    backup = run(prefix + ["shell", f"run-as {PACKAGE} sha256sum files/memory/migrations/legacy-today-2026-10-23.md"]).split()[0]
    if backup != expected_backup:
        raise RuntimeError("Legacy today migration backup does not match")
    # A second launch must not import legacy events again.
    event_path = "files/memory/events/events-2026-10.jsonl"
    first = run(prefix + ["shell", f"run-as {PACKAGE} sha256sum {event_path}"]).split()[0]
    run(prefix + ["shell", f"am force-stop {PACKAGE}"])
    run(prefix + ["shell", f"am start -W -n {PACKAGE}/.MainActivity"])
    second = run(prefix + ["shell", f"run-as {PACKAGE} sha256sum {event_path}"]).split()[0]
    if first != second:
        raise RuntimeError("Second launch changed the imported legacy events")
    return {"install_replace": "passed", "synthetic_files": len(fixtures),
            "legacy_backup": "passed", "second_launch_import_idempotent": "passed"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME"))
    parser.add_argument("--old-apk", type=Path, required=True)
    parser.add_argument("--new-apk", type=Path, required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check-apks", action="store_true", help="Offline package/version/signature checks only")
    mode.add_argument("--serial", help="Install/upgrade on this CLEAN emulator; refuses physical devices")
    parser.add_argument("--report", type=Path, help="Write a JSON report")
    args = parser.parse_args()
    if not args.sdk or not args.sdk.is_dir():
        parser.error("Set --sdk or ANDROID_SDK_ROOT to an installed Android SDK")
    result = {"apk_pair": "not_checked", "device_upgrade": "not_executed"}
    try:
        aapt, signer = sdk_tools(args.sdk)
        old = apk_info(args.old_apk, aapt, signer)
        new = apk_info(args.new_apk, aapt, signer)
        if new["version_code"] <= old["version_code"]:
            raise RuntimeError("New versionCode must be greater than the old versionCode")
        if old["signers_sha256"] != new["signers_sha256"]:
            raise RuntimeError("APK signing certificates differ; install -r cannot upgrade this pair")
        result.update(apk_pair="passed", old=old, new=new)
        if args.serial:
            result["device_upgrade"] = verify_device(args.sdk, args.serial, args.old_apk, args.new_apk)
    except (OSError, RuntimeError) as error:
        result["error"] = str(error)
        code = 1
    else:
        code = 0
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return code


if __name__ == "__main__":
    sys.exit(main())
