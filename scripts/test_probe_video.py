"""Synthetic boundary tests; these bytes are never recorded UI evidence."""
import copy
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import probe_video as video

SOURCE = {"repository": "ecojust/Agent-OS", "commit": "a" * 40, "runId": 17, "attempt": 1}


class ProbeVideo(unittest.TestCase):
    def record(self, directory, callback=lambda: "original", *, elapsed=2000, expired=False, compression_error=None):
        recorder = mock.Mock(stdout=io.BytesIO(), stderr=io.BytesIO())
        recorder.poll.return_value = None
        stderr = {"text": "", "truncated": False, "complete": True}
        commands = []
        def command(args, **kwargs):
            commands.append(args)
            if "getprop" in args:
                return {"ro.kernel.qemu": "1", "ro.boot.qemu": "1", "ro.build.version.sdk": "35",
                        "ro.product.cpu.abi": "x86_64", "sys.boot_completed": "1"}[args[-1]]
            if "stat" in args: return "4"
            if "pull" in args: Path(args[-1]).write_bytes(b"raw!")
            return ""
        def stop(process, pid, adb):
            self.assertIs(process, recorder); self.assertEqual("4321", pid)
            recorder.poll.return_value = 0
            return not expired
        def compress(raw, output, **kwargs):
            if compression_error:
                kwargs["stage_callback"]("video_decode")
                raise compression_error
            output.write_bytes(b"mp4!")
            meta = {"durationMs": 3000 if elapsed < 100000 else 120000, "width": 320, "height": 640,
                    "codec": "h264", "pixelFormat": "yuv420p"}
            for stage in ("raw_probe", "encoded_probe"):
                kwargs["stage_callback"](stage)
                kwargs["probe_callback"]({"status": "completed", "exitCode": 0, "stdout": "{}"})
            return ({**video.media.file_record(raw), **meta}, {**video.media.file_record(output), **meta})
        def call():
            try: return callback()
            finally:
                if expired: recorder.poll.return_value = 0
        with mock.patch.object(video.bounded, "run_command", side_effect=command), \
                mock.patch.object(video.subprocess, "Popen", return_value=recorder) as launch, \
                mock.patch.object(video.media, "RecorderStderr") as drain, \
                mock.patch.object(video, "_read_pid", return_value="4321"), \
                mock.patch.object(video.media, "stop_recorder", side_effect=stop), \
                mock.patch.object(video.media, "compress", side_effect=compress), \
                mock.patch.object(video.time, "sleep"), \
                mock.patch.object(video.time, "monotonic_ns", side_effect=[0, 400_000_000, (400 + elapsed) * 1_000_000]):
            drain.return_value.finish.return_value = stderr
            result = video.record_call(directory, SOURCE, "third-party-app-set", 35, "/sdk/adb", "emulator-5554", call)
        return result, commands, launch

    def read(self, directory):
        report = json.loads((Path(directory) / "recording.json").read_text())
        video.validate_recording_report(report, SOURCE, "third-party-app-set")
        return report

    def test_callback_value_and_whole_interval_are_preserved(self):
        sentinel = object()
        with tempfile.TemporaryDirectory() as directory:
            result, commands, launch = self.record(directory, lambda: sentinel)
            self.assertIs(sentinel, result)
            report = self.read(directory)
            self.assertEqual("passed", report["status"])
            self.assertEqual(2000, report["callbackElapsedMs"])
            self.assertEqual((400, 2400), (report["callbackStartedAfterRecorderMs"], report["callbackFinishedAfterRecorderMs"]))
            self.assertFalse((Path(directory) / "raw.mp4").exists())
            self.assertEqual({"recording.json", "demo.mp4"}, {p.name for p in Path(directory).iterdir()})
            self.assertIn("--time-limit 120", launch.call_args.args[0][-1])
            self.assertFalse(any(token in ("am", "input", "settings", "force-stop", "pkill", "killall") for c in commands for token in c))

    def test_callback_exception_object_is_preserved_and_video_is_independent(self):
        original = RuntimeError("private callback text")
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(RuntimeError) as caught:
                self.record(directory, lambda: (_ for _ in ()).throw(original))
            self.assertIs(original, caught.exception)
            report = self.read(directory)
            self.assertEqual(("passed", "raised", "RuntimeError"),
                             (report["status"], report["callbackOutcome"], report["callbackErrorClass"]))
            self.assertNotIn("private callback text", json.dumps(report))

    def test_long_callback_is_not_cancelled_and_partial_video_is_retained(self):
        with tempfile.TemporaryDirectory() as directory:
            result, _, _ = self.record(directory, elapsed=600_000, expired=True)
            report = self.read(directory)
            self.assertEqual("original", result)
            self.assertEqual(600_000, report["callbackElapsedMs"])
            self.assertEqual(("failed", "recorded", "incomplete"),
                             (report["status"], report["recordingStatus"], report["coverage"]["status"]))
            self.assertTrue((Path(directory) / "demo.mp4").exists())

    def test_short_media_cannot_claim_full_callback_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            self.record(directory, elapsed=10_000)
            report = self.read(directory)
            self.assertEqual("incomplete", report["coverage"]["status"])
            self.assertEqual("failed", report["status"])

    def test_encoding_failure_preserves_bounded_raw_and_original_value(self):
        with tempfile.TemporaryDirectory() as directory:
            result, _, _ = self.record(directory, compression_error=OSError("private media text"))
            report = self.read(directory)
            self.assertEqual("original", result)
            self.assertTrue(report["rawRetained"])
            self.assertEqual(video.media.file_record(Path(directory) / "raw.mp4"), report["rawVideo"])
            self.assertEqual("failed", report["status"])
            self.assertNotIn("private media text", json.dumps(report))

    def test_start_failure_does_not_skip_callback_or_mask_original_exception(self):
        original = ValueError("callback")
        callback = mock.Mock(side_effect=original)
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(video.bounded, "run_command", side_effect=OSError("private setup")):
            with self.assertRaises(ValueError) as caught:
                video.record_call(directory, SOURCE, "third-party-app-set", 35, "adb", "emulator-5554", callback)
            self.assertIs(original, caught.exception); callback.assert_called_once_with()
            report = self.read(directory)
            self.assertEqual("failed", report["status"])
            self.assertFalse(report["recorder"]["started"])
            self.assertNotIn("private setup", json.dumps(report))

    def test_existing_recording_and_linked_directory_are_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / "evidence"; directory.mkdir()
            old = directory / "recording.json"; old.write_bytes(b"original")
            linked = Path(temp) / "linked"; linked.symlink_to(directory, target_is_directory=True)
            for path in (directory, linked):
                callback = mock.Mock(return_value=7)
                with mock.patch.object(video.subprocess, "Popen") as launch:
                    self.assertEqual(7, video.record_call(path, SOURCE, "third-party-app-set", 35, "adb", "emulator-5554", callback))
                    callback.assert_called_once_with(); launch.assert_not_called()
                self.assertEqual(b"original", old.read_bytes())

    def test_raw_cleanup_failure_retains_both_files_as_failed_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            unlink = Path.unlink
            def cannot_remove_raw(path, *args, **kwargs):
                if path.name == "raw.mp4": raise PermissionError("synthetic cleanup failure")
                return unlink(path, *args, **kwargs)
            with mock.patch.object(Path, "unlink", cannot_remove_raw):
                result, _, _ = self.record(directory)
            report = self.read(directory)
            self.assertEqual("original", result)
            self.assertEqual("failed", report["status"])
            self.assertTrue(report["rawRetained"])
            self.assertEqual({"recording.json", "raw.mp4", "demo.mp4"}, {p.name for p in Path(directory).iterdir()})

    def test_unwritable_report_and_stderr_cannot_replace_callback_exception(self):
        original = RuntimeError("original callback")
        output = mock.Mock()
        output.write.side_effect = OSError("full filesystem")
        output.close.side_effect = OSError("close failed")
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(Path, "open", return_value=output), \
                mock.patch.object(video.sys, "stderr") as stderr:
            stderr.write.side_effect = OSError("closed stderr")
            with self.assertRaises(RuntimeError) as caught:
                video.record_call(directory, SOURCE, "third-party-app-set", 35, "adb", "emulator-5554",
                                  lambda: (_ for _ in ()).throw(original))
            self.assertIs(original, caught.exception)

    def test_other_api_or_profile_does_not_start_recording_or_skip_callback(self):
        for profile, api in (("third-party-app-set", 34), ("other", 35), ("pinned-calculator-page", True)):
            with self.subTest(profile=profile, api=api), tempfile.TemporaryDirectory() as directory:
                callback = mock.Mock(return_value=7)
                with mock.patch.object(video.subprocess, "Popen") as launch:
                    self.assertEqual(7, video.record_call(directory, SOURCE, profile, api, "adb", "emulator-5554", callback))
                    callback.assert_called_once_with(); launch.assert_not_called()

    def test_validator_rejects_source_scope_coverage_and_success_tampering(self):
        with tempfile.TemporaryDirectory() as directory:
            self.record(directory)
            report = self.read(directory)
        for mutate in (lambda v: v["source"].update(commit="b" * 40),
                       lambda v: v.update(apiLevel=34), lambda v: v.update(profile="unknown"),
                       lambda v: v.update(callbackElapsedMs=600000),
                       lambda v: v["coverage"].update(recorderAliveAfterCallback=False),
                       lambda v: v["video"].update(durationMs=500),
                       lambda v: v.update(secureFlagsPreserved=False),
                       lambda v: v.update(probes=[]),
                       lambda v: v["probes"][0].update(status="timed_out", exitCode=None),
                       lambda v: v["probes"][1].update(exitCode=1),
                       lambda v: v["failures"].append({"stage": "coverage", "errorClass": None}),
                       lambda v: v.update(rawRetained=True)):
            changed = copy.deepcopy(report); mutate(changed)
            with self.assertRaises(ValueError): video.validate_recording_report(changed, SOURCE, "third-party-app-set")

    def test_pid_reader_rejects_invalid_ownership_without_signalling_device(self):
        for data in (b"4321\n", b"4321\r\n", b"0\n", b"4321;killall screenrecord\n", b"x" * 32):
            with self.subTest(data=data):
                process = subprocess.Popen(["python3", "-c", "import sys;sys.stdout.buffer.write(" + repr(data) + ")"], stdout=subprocess.PIPE)
                try:
                    if data in (b"4321\n", b"4321\r\n"): self.assertEqual("4321", video._read_pid(process.stdout))
                    else:
                        with self.assertRaises(ValueError): video._read_pid(process.stdout)
                finally: process.wait(timeout=2); process.stdout.close()


if __name__ == "__main__":
    unittest.main()
