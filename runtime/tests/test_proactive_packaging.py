#!/usr/bin/env python3
"""Unit tests for how rtbuild.py ships dsh-go-proactive (review round 3, D).

    python3 -I runtime/tests/test_proactive_packaging.py

The full rootfs build needs Linux inputs; this checks the recipe pieces that decide whether the
plugin reaches the phone and is loaded: the dsh-web.patch.yml row, the manifest entry, and the
drop rule that keeps test/ (mock model, fixture sessions) off the device.
"""
import os
import sys
import unittest

sys.dont_write_bytecode = True   # runtime/lib/__pycache__ is tracked; do not rewrite it
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "lib"))
import rtbuild  # noqa: E402


def patch_text(**kw):
    e = rtbuild.overlay_entries("{}", **kw)["etc/dsh-app/dsh-web.patch.yml"]
    return e.data.decode() if isinstance(e.data, bytes) else e.data


class ProactivePackagingTest(unittest.TestCase):
    def test_the_web_patch_loads_the_plugin_by_absolute_path_once(self):
        text = patch_text(with_bridge=True, with_proactive=True)
        row = "- id: dsh-go-proactive\n      name: /opt/dsh-go/dsh-go-proactive/lib/index.js\n"
        self.assertEqual(text.count(row), 1)
        self.assertIn("name: /opt/dsh-go/dsh-go-bridge/lib/index.js", text)

    def test_without_the_flag_nothing_mentions_the_plugin(self):
        self.assertNotIn("dsh-go-proactive", patch_text(with_bridge=True))

    def test_only_code_ships(self):
        drop = rtbuild.proactive_drop_reason
        for rel in ("test/mock-messages.mjs", "test/fixture-session/index.js", "test/e2e-live.mjs", "test/core.test.mjs"):
            self.assertEqual(drop(rel, "arm64-v8a", True), "test-only", rel)
        for rel in ("lib/index.js", "lib/core.js", "package.json"):
            self.assertIsNone(drop(rel, "arm64-v8a", True), rel)

    def test_the_launch_command_passes_the_web_patch(self):
        self.assertIn("--patch " + rtbuild.DSH_WEB_PATCH, " ".join(rtbuild.launch_template()["argv"]))


if __name__ == "__main__":
    unittest.main()
