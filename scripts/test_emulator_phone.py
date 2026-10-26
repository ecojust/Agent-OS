"""The phone baseline changes only the named ephemeral AVD keyboard setting."""

import contextlib
import io
from pathlib import Path
import tempfile
import unittest

import configure_emulator_phone as phone


class PhoneBaselineTests(unittest.TestCase):
    def test_inherited_and_duplicate_values_become_exactly_one_no(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / "agent-os-phone.avd"
            directory.mkdir()
            config = directory / "config.ini"
            config.write_text("hw.keyboard=yes\nhw.ramSize=2048\nhw.keyboard = yes\n")
            other = root / "other.avd"
            other.mkdir()
            (other / "config.ini").write_text("hw.keyboard=yes\n")
            with contextlib.redirect_stdout(io.StringIO()):
                phone.configure(root, "agent-os-phone")
                phone.configure(root, "agent-os-phone", check=True)
            self.assertEqual("hw.ramSize=2048\nhw.keyboard=no\n", config.read_text())
            self.assertEqual("hw.keyboard=yes\n", (other / "config.ini").read_text())

    def test_check_is_read_only_and_rejects_yes_missing_or_duplicate(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "agent-os-phone.avd"
            directory.mkdir()
            config = directory / "config.ini"
            for original in ("hw.keyboard=yes\n", "hw.ramSize=2048\n", "hw.keyboard=no\nhw.keyboard=no\n"):
                config.write_text(original)
                with self.assertRaises(ValueError):
                    phone.configure(temporary, "agent-os-phone", check=True)
                self.assertEqual(original, config.read_text())

    def test_missing_named_avd_or_environment_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(ValueError): phone.configure(temporary, "agent-os-phone")
            with self.assertRaises(ValueError): phone.configure(None, "agent-os-phone")
            with self.assertRaises(ValueError): phone.configure(temporary, "../other")

    def test_symlink_does_not_modify_another_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            outside = root / "preserve.ini"
            outside.write_text("hw.keyboard=yes\n")
            directory = root / "agent-os-phone.avd"
            directory.mkdir()
            (directory / "config.ini").symlink_to(outside)
            with self.assertRaises(ValueError): phone.configure(root, "agent-os-phone")
            self.assertEqual("hw.keyboard=yes\n", outside.read_text())


if __name__ == "__main__":
    unittest.main()
