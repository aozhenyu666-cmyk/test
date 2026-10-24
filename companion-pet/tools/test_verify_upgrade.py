import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

import verify_upgrade


class UpgradeGuardsTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.sdk = Path(self.folder.name)
        adb = self.sdk / "platform-tools" / ("adb.exe" if verify_upgrade.os.name == "nt" else "adb")
        adb.parent.mkdir()
        adb.touch()
        self.calls = []

    def answer(self, args, **kwargs):
        self.calls.append([str(a) for a in args])
        if args[-1] == "get-state":
            return "device"
        return ""

    def test_physical_device_is_rejected_before_any_install(self):
        with patch.object(verify_upgrade, "run", side_effect=self.answer):
            with self.assertRaisesRegex(RuntimeError, "only an Android emulator"):
                verify_upgrade.verify_device(self.sdk, "physical-test", Path("old.apk"), Path("new.apk"))
        self.assertFalse(any("install" in call or "uninstall" in call for call in self.calls))

    def test_existing_app_on_emulator_is_preserved(self):
        def answer(args, **kwargs):
            self.calls.append([str(a) for a in args])
            if args[-1] == "get-state":
                return "device"
            if args[-1] == "getprop ro.kernel.qemu":
                return "1"
            return "package:/data/app/synthetic-existing/base.apk"

        with patch.object(verify_upgrade, "run", side_effect=answer):
            with self.assertRaisesRegex(RuntimeError, "existing installation"):
                verify_upgrade.verify_device(self.sdk, "emulator-5554", Path("old.apk"), Path("new.apk"))
        self.assertFalse(any("install" in call or "uninstall" in call for call in self.calls))

    def test_failed_replace_never_falls_back_to_uninstall_or_clear(self):
        def answer(args, **kwargs):
            self.calls.append([str(a) for a in args])
            if args[-1] == "get-state":
                return "device"
            if args[-1] == "getprop ro.kernel.qemu":
                return "1"
            if "-r" in args:
                raise RuntimeError("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
            return ""

        with patch.object(verify_upgrade, "run", side_effect=answer):
            with self.assertRaisesRegex(RuntimeError, "INSTALL_FAILED_UPDATE_INCOMPATIBLE"):
                verify_upgrade.verify_device(self.sdk, "emulator-5554", Path("old.apk"), Path("new.apk"))
        self.assertFalse(any("uninstall" in call or any("pm clear" in arg for arg in call) for call in self.calls))
        self.assertFalse(any(any("am start" in arg for arg in call) for call in self.calls))


if __name__ == "__main__":
    unittest.main()
