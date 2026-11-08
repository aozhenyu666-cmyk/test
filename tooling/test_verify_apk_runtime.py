"""Tests for tooling/verify_apk_runtime.py on synthetic APKs built the way rtbuild.py lays them out.

    python3 -m unittest discover -s tooling -p 'test_*.py'
"""

import hashlib
import io
import json
import pathlib
import tarfile
import tempfile
import unittest
import zipfile

from verify_apk_runtime import NATIVE, verify

ROOT = pathlib.Path(__file__).resolve().parent.parent
PLUGIN_DIR = ROOT / "runtime" / "proactive" / "dsh-go-proactive"
ROW = "- insert:\n    - id: dsh-go-proactive\n      name: /opt/dsh-go/dsh-go-proactive/lib/index.js\n"


def rootfs(files: dict) -> bytes:
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:xz") as tar:
        for name, data in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    return buf.getvalue()


def native(abi: str = "arm64-v8a") -> dict:
    """The lib/<abi>/lib*.so Gradle ships, with what the manifest records for them."""
    return {n: ("%s for %s" % (n, abi)).encode() for n in NATIVE}


def apk(dirpath: pathlib.Path, files: dict | None, manifest_extra: dict | None = None, runtime: bool = True,
        abi: str = "arm64-v8a", with_native: bool = True, native_override: dict | None = None) -> pathlib.Path:
    p = dirpath / "app.apk"
    fresh = native(abi)                                  # what the manifest describes
    libs = fresh if with_native else {}                  # what Gradle actually packaged
    if native_override is not None:
        libs = native_override
    with zipfile.ZipFile(p, "w") as z:
        z.writestr("classes.dex", b"dex")
        if runtime:
            blob = rootfs(files or {})
            m = {"rootfs": "rootfs-%s.tar.xz" % abi, "rootfsSha256": hashlib.sha256(blob).hexdigest(),
                 "abi": abi, "runtimeVersion": "test",
                 "launch": {"argv": ["/bin/sh", "-c", "exec dsh web --patch /etc/dsh-app/dsh-web.patch.yml"]},
                 "jniLibs": {n: {"sha256": hashlib.sha256(b).hexdigest(), "bytes": len(fresh[n])} for n, b in fresh.items()},
                 "dshGoProactive": {"version": "0.1.0", "path": "/opt/dsh-go/dsh-go-proactive"}}
            m.update(manifest_extra or {})
            z.writestr("assets/runtime/manifest.json", json.dumps(m))
            z.writestr("assets/runtime/rootfs-%s.tar.xz" % abi, blob)
            for n, b in libs.items():
                z.writestr("lib/%s/%s" % (abi, n), b)
    return p


def plugin_files(prefix="opt/dsh-go/dsh-go-proactive/") -> dict:
    return {prefix + rel: (PLUGIN_DIR / rel).read_bytes() for rel in ("package.json", "lib/index.js", "lib/core.js")}


class VerifyApkRuntimeTest(unittest.TestCase):
    def run_verify(self, files, **kw):
        with tempfile.TemporaryDirectory() as d:
            res = verify(apk(pathlib.Path(d), files, **kw), PLUGIN_DIR)
        return {c["check"]: c["result"] for c in res}

    def test_a_complete_runtime_passes_every_check(self):
        r = self.run_verify({**plugin_files(), "etc/dsh-app/dsh-web.patch.yml": ROW.encode()})
        self.assertTrue(all(v == "PASS" for v in r.values()), r)
        self.assertEqual(r["plugin carries the tool guard"], "PASS")

    def test_an_apk_built_without_runtime_out_is_not_proven(self):
        r = self.run_verify(None, runtime=False)
        self.assertEqual(r, {"runtime manifest in APK": "FAIL"})

    def test_an_upstream_rootfs_without_the_plugin_fails(self):
        r = self.run_verify({"etc/dsh-app/dsh-web.patch.yml": b"- insert:\n    - id: dsh-go-bridge\n"},
                            manifest_extra={"dshGoProactive": None})
        self.assertEqual(r["manifest names dshGoProactive"], "FAIL")
        self.assertEqual(r["plugin files in rootfs"], "FAIL")
        self.assertEqual(r["web patch loads the plugin"], "FAIL")

    def test_shipping_test_files_or_a_stale_plugin_fails(self):
        files = {**plugin_files(), "etc/dsh-app/dsh-web.patch.yml": ROW.encode(),
                 "opt/dsh-go/dsh-go-proactive/test/mock-messages.mjs": b"mock"}
        files["opt/dsh-go/dsh-go-proactive/lib/index.js"] = b"// an older plugin without the guard"
        r = self.run_verify(files)
        self.assertEqual(r["plugin test/ not shipped"], "FAIL")
        self.assertEqual(r["plugin matches this repo revision"], "FAIL")
        self.assertEqual(r["plugin carries the tool guard"], "FAIL")

    def test_an_apk_whose_assets_came_from_an_empty_runtime_out_is_not_proven(self):
        # Gradle ignores a sourceSets srcDir that does not exist: assets can be present while the
        # jniLibs (and, in the real case, the rootfs) are not. The manifest still looks right.
        r = self.run_verify({**plugin_files(), "etc/dsh-app/dsh-web.patch.yml": ROW.encode()},
                            with_native=False)
        self.assertEqual(r["proot binaries in the APK"], "FAIL")
        self.assertEqual(r["manifest names an ABI the APK carries"], "FAIL")

    def test_native_libs_that_differ_from_the_manifest_fail(self):
        stale = {**native(), "libproot.so": b"a rebuilt proot the manifest never saw"}
        r = self.run_verify({**plugin_files(), "etc/dsh-app/dsh-web.patch.yml": ROW.encode()},
                            native_override=stale)
        self.assertEqual(r["proot binaries match the manifest"], "FAIL")

    def test_an_x86_64_apk_is_accepted_and_a_mismatched_abi_is_not(self):
        r = self.run_verify({**plugin_files(), "etc/dsh-app/dsh-web.patch.yml": ROW.encode()}, abi="x86_64")
        self.assertEqual(r["manifest names an ABI the APK carries"], "PASS")
        self.assertEqual(r["proot binaries in the APK"], "PASS")
        # A manifest claiming x86_64 while the ABI dirs say arm64-v8a must not pass.
        r = self.run_verify({**plugin_files(), "etc/dsh-app/dsh-web.patch.yml": ROW.encode()},
                            abi="x86_64", manifest_extra={"abi": "riscv64"})
        self.assertEqual(r["manifest names an ABI the APK carries"], "FAIL")

    def test_a_tampered_rootfs_fails_the_checksum(self):
        r = self.run_verify({**plugin_files(), "etc/dsh-app/dsh-web.patch.yml": ROW.encode()},
                            manifest_extra={"rootfsSha256": "0" * 64})
        self.assertEqual(r["rootfs sha256 matches manifest"], "FAIL")


if __name__ == "__main__":
    unittest.main()
