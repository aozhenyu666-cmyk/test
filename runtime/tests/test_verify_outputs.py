#!/usr/bin/env python3
"""Tests for rtbuild.py's verify-outputs, the check behind the G1' criterion.

    python3 -I runtime/tests/test_verify_outputs.py

Gradle silently ignores a sourceSets srcDir that does not exist, so an APK build can
say BUILD SUCCESSFUL while carrying no runtime at all. verify-outputs is what proves
runtime/out really holds both ABIs; these tests pin its verdict on hand-built trees.
"""
import hashlib
import json
import os
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True   # runtime/lib/__pycache__ is tracked; do not rewrite it
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "lib"))
import rtbuild  # noqa: E402

ABIS = ("arm64-v8a", "x86_64")
JNI = ("libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so")


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(data if isinstance(data, bytes) else data.encode())


def dump(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(obj, fh)


def build_tree(root, abis=ABIS, index_abis=None, break_=""):
    """Writes a runtime/out tree shaped exactly like build-runtime.sh's output."""
    index = {"schema": 1, "abis": {}}
    for abi in abis:
        assets = os.path.join(root, abi, "assets", "runtime")
        blob = ("rootfs for " + abi).encode()
        name = "rootfs-%s.tar.xz" % abi
        if not break_ == "no-rootfs-" + abi:
            write(os.path.join(assets, name), blob)
        jni_out = os.path.join(root, abi, "jniLibs", abi)
        os.makedirs(jni_out, exist_ok=True)
        jni = {}
        for lib in JNI:
            path = os.path.join(jni_out, lib)
            write(path, "%s %s" % (lib, abi))
            jni[lib] = {"sha256": hashlib.sha256(("%s %s" % (lib, abi)).encode()).hexdigest(),
                        "bytes": os.path.getsize(path),
                        "machine": rtbuild.ABI[abi]["machine"]}
        manifest = {
            "schema": 1, "abi": abi, "runtimeVersion": "r14-test-" + abi,
            "rootfs": name, "rootfsSha256": hashlib.sha256(blob).hexdigest(),
            "rootfsBytes": len(blob), "jniLibs": jni,
            "dshGoProactive": {"version": "0.1.0", "path": "/opt/dsh-go/dsh-go-proactive"},
            "launch": {"argv": ["/bin/sh", "-c", "exec dsh web --patch /etc/dsh-app/dsh-web.patch.yml"]},
        }
        if break_ == "bad-sha-" + abi:
            manifest["rootfsSha256"] = "0" * 64
        if break_ == "abi-field-" + abi:
            manifest["abi"] = "x86_64" if abi == "arm64-v8a" else "arm64-v8a"
        if break_ == "no-jni-" + abi:
            os.remove(os.path.join(jni_out, "libproot.so"))
        if not break_ == "skip-index-" + abi:
            index["abis"][abi] = {"runtimeVersion": manifest["runtimeVersion"]}
        dump(os.path.join(assets, "manifest.json"), manifest)
    if index_abis is not None:
        index["abis"] = {a: v for a, v in index["abis"].items() if a in index_abis}
    dump(os.path.join(root, "manifest.json"), index)


class VerifyOutputsTest(unittest.TestCase):
    def check(self, abis=ABIS, **kw):
        with tempfile.TemporaryDirectory() as d:
            build_tree(d, **kw)
            return rtbuild.verify_outputs(d, list(abis))

    def test_both_abis_complete_pass_the_g1_criterion(self):
        problems, lines = self.check()
        self.assertEqual(problems, [], problems)
        self.assertEqual(len(lines), 3)          # two ABIs + the index
        self.assertTrue(lines[-1].startswith("PASS") and "arm64-v8a" in lines[-1])

    def test_an_empty_out_tree_fails_both_abis(self):
        with tempfile.TemporaryDirectory() as d:
            problems, lines = rtbuild.verify_outputs(d, list(ABIS))
        self.assertEqual(lines, [])
        self.assertEqual(len(problems), 3)       # 2 ABIs + the index manifest
        self.assertTrue(all("missing" in p for p in problems), problems)

    def test_only_one_abi_built_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as d:
            build_tree(d, abis=("arm64-v8a",))
            problems, _ = rtbuild.verify_outputs(d, list(ABIS))
        self.assertTrue(any(p.startswith("x86_64:") and "missing" in p for p in problems), problems)

    def test_a_missing_jnilib_fails_even_though_the_manifest_is_present(self):
        problems, _ = self.check(break_="no-jni-x86_64")
        self.assertTrue(any("libproot.so" in p and "missing" in p for p in problems), problems)

    def test_a_tampered_rootfs_fails(self):
        problems, _ = self.check(break_="bad-sha-arm64-v8a")
        self.assertTrue(any("sha256 does not match" in p for p in problems), problems)

    def test_a_manifest_naming_the_wrong_abi_fails(self):
        problems, _ = self.check(break_="abi-field-arm64-v8a")
        self.assertTrue(any("manifest says abi=" in p for p in problems), problems)

    def test_an_index_that_omits_an_abi_fails(self):
        problems, _ = self.check(index_abis=("arm64-v8a",))
        self.assertTrue(any("lacks" in p and "x86_64" in p for p in problems), problems)

    def test_a_missing_rootfs_archive_fails(self):
        problems, _ = self.check(break_="no-rootfs-arm64-v8a")
        self.assertTrue(any(p.startswith("arm64-v8a") and "rootfs" in p for p in problems), problems)

    def test_the_cli_exit_code_reports_the_verdict(self):
        with tempfile.TemporaryDirectory() as d:
            build_tree(d)
            self.assertEqual(rtbuild.verify_outputs(d, list(ABIS))[0], [])
            empty = os.path.join(d, "nowhere")
            os.makedirs(empty)
            self.assertNotEqual(rtbuild.verify_outputs(empty, list(ABIS))[0], [])


if __name__ == "__main__":
    unittest.main()
