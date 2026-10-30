"""Capture failures retain evidence and cannot signal an unrelated process."""

import gzip
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import run_app_experience_trace as capture


class ExperienceCaptureTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.output = Path(temporary.name)
        self.calls = []
        self.payload = b"synthetic trace bytes for boundary tests" * 20
        self.state = dict(schemaVersion=4, configVersion=3, kind="agent-os-experience-trace-capture", status="recording",
                          pid=123, stopped=False, startMode="--background", ready=True,
                          config="synthetic config", trace=None, compressed=None, error=None)
        capture.save(self.output, self.state)

    def adb(self, *args, **kwargs):
        self.calls.append(args)
        if args[:2] == ("shell", "cat"):
            return subprocess.CompletedProcess(args, 0, "perfetto\n", "")
        if args[:3] == ("shell", "kill", "-0"):
            return subprocess.CompletedProcess(args, 1, "", "")
        return subprocess.CompletedProcess(args, 0, "", "")

    def pull(self, args, **kwargs):
        self.assertEqual(args, ["adb", "exec-out", "cat", capture.REMOTE_TRACE])
        kwargs["stdout"].write(self.payload)
        return subprocess.CompletedProcess(args, 0)

    def run_stop(self, adb=None):
        with mock.patch.object(capture, "adb", side_effect=adb or self.adb), \
                mock.patch.object(capture.subprocess, "run", side_effect=self.pull):
            result = capture.stop(self.output)
        return result, json.loads((self.output / capture.STATE_NAME).read_text())

    def test_stopped_trace_keeps_exact_uncompressed_and_compressed_hashes(self):
        result, state = self.run_stop()
        self.assertTrue(result)
        self.assertEqual(state["status"], "collected")
        self.assertTrue(state["stopped"])
        zipped = (self.output / "trace.pftrace.gz").read_bytes()
        self.assertEqual(gzip.decompress(zipped), self.payload)
        self.assertEqual(state["trace"]["sha256"], hashlib.sha256(self.payload).hexdigest())
        self.assertEqual(state["compressed"]["sha256"], hashlib.sha256(zipped).hexdigest())

    def test_exited_or_reused_pid_is_never_signalled_but_readable_partial_is_preserved(self):
        def other(*args, **kwargs):
            self.calls.append(args)
            return subprocess.CompletedProcess(args, 0, "unrelated-process\n", "")
        result, state = self.run_stop(other)
        self.assertFalse(result)
        self.assertEqual(state["status"], "failed")
        self.assertFalse(state["stopped"])
        self.assertIn("exited_before_stop", state["error"])
        self.assertTrue((self.output / "trace.pftrace.gz").is_file())
        self.assertFalse(any("kill" in call for call in self.calls))

    def test_exact_capacity_retains_bytes_but_is_not_complete(self):
        with mock.patch.object(capture, "RAW_LIMIT", len(self.payload)):
            result, state = self.run_stop()
        self.assertFalse(result)
        self.assertEqual(state["error"], "trace_reached_size_limit")
        self.assertEqual(state["trace"]["bytes"], len(self.payload))

    def test_legacy_capture_keeps_its_original_capacity(self):
        for version in (1, 2, 3):
            with self.subTest(version=version):
                state = dict(self.state, schemaVersion=version)
                state.pop("configVersion")
                if version == 3:
                    state["configVersion"] = 2
                capture.save(self.output, state)
                with mock.patch.object(capture, "LEGACY_RAW_LIMIT", len(self.payload)):
                    result, actual = self.run_stop()
                self.assertFalse(result)
                self.assertEqual(actual["error"], "trace_reached_size_limit")
                self.assertEqual(actual["trace"]["bytes"], len(self.payload))

    def test_current_capture_uses_new_limit_without_changing_legacy_limits(self):
        with mock.patch.object(capture, "LEGACY_RAW_LIMIT", len(self.payload) - 1), \
                mock.patch.object(capture, "LEGACY_GZIP_LIMIT", 5):
            result, state = self.run_stop()
        self.assertTrue(result)
        self.assertEqual(state["status"], "collected")

    def test_wrong_capacity_version_is_rejected_before_device_access(self):
        self.state["configVersion"] = 2
        capture.save(self.output, self.state)
        with self.assertRaisesRegex(ValueError, "unsupported_trace_config_version"):
            self.run_stop()
        self.assertEqual(self.calls, [])

    def test_oversized_compressed_file_is_removed_and_failure_is_recorded(self):
        with mock.patch.object(capture, "GZIP_LIMIT", 5):
            result, state = self.run_stop()
        self.assertFalse(result)
        self.assertIsNone(state["trace"])
        self.assertIn("compressed_trace_exceeds_budget", state["error"])
        self.assertFalse((self.output / "trace.pftrace.gz").exists())

    def test_failed_start_with_live_recorder_is_cleaned_up_without_becoming_success(self):
        self.state.update(status="failed", ready=False, error="perfetto_first_stream_timeout")
        capture.save(self.output, self.state)
        result, state = self.run_stop()
        self.assertFalse(result)
        self.assertTrue(state["stopped"])
        self.assertEqual(state["error"], "perfetto_first_stream_timeout")
        self.assertTrue(state["compressed"])

    def test_malformed_pid_does_not_reach_signal_command(self):
        self.state["pid"] = "123;touch unexpected"
        capture.save(self.output, self.state)
        result, state = self.run_stop()
        self.assertFalse(result)
        self.assertEqual(state["error"], "invalid_perfetto_pid")
        self.assertEqual(self.calls, [])

    def test_api30_uses_supported_background_option_and_requires_first_stream(self):
        def fake(*args, **kwargs):
            self.calls.append(args)
            value = ""
            if args == ("shell", "perfetto", "--help"):
                value = "--background -d"
            elif args[:2] == ("shell", "perfetto"):
                self.assertEqual(args[args.index("-c") + 1], "-")
                self.assertIn("write_into_file: true", kwargs["input_text"])
                value = "123\n"
            elif args[:2] == ("shell", "cat"):
                value = "perfetto\n"
            elif args[:2] == ("shell", "stat"):
                value = "42\n"
            return subprocess.CompletedProcess(args, 0, value, "")
        with mock.patch.object(capture, "adb", side_effect=fake):
            self.assertTrue(capture.start(self.output))
        state = json.loads((self.output / capture.STATE_NAME).read_text())
        self.assertEqual(state["startMode"], "--background")
        self.assertEqual(state["schemaVersion"], 4)
        self.assertEqual(state["configVersion"], 3)
        self.assertIn("max_file_size_bytes: 268435456", state["config"])
        self.assertEqual(state["configDelivery"], "stdin")
        self.assertEqual(state["startExitCode"], 0)
        self.assertTrue(state["ready"])
        self.assertFalse(any("--background-wait" in call for call in self.calls))
        self.assertFalse(any(call[0] == "push" for call in self.calls))

    def test_start_failure_keeps_bounded_real_output_without_claiming_recording(self):
        def fake(*args, **kwargs):
            if args == ("shell", "perfetto", "--help"):
                return subprocess.CompletedProcess(args, 0, "--background-wait", "")
            if args[:2] == ("shell", "perfetto"):
                return subprocess.CompletedProcess(args, 1, "synthetic stdout", "Could not open config\n" + "x" * 3000)
            return subprocess.CompletedProcess(args, 0, "", "")
        with mock.patch.object(capture, "adb", side_effect=fake):
            self.assertFalse(capture.start(self.output))
        state = json.loads((self.output / capture.STATE_NAME).read_text())
        self.assertEqual(state["startExitCode"], 1)
        self.assertEqual(state["startStdout"], "synthetic stdout")
        self.assertTrue(state["startStderr"].startswith("Could not open config\n"))
        self.assertEqual(len(state["startStderr"]), 2048)
        self.assertEqual(state["error"], "perfetto_start_exit_1")
        self.assertFalse(state["ready"])
        self.assertIsNone(state["pid"])


if __name__ == "__main__":
    unittest.main()
