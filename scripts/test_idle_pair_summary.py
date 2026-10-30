"""Check per-pair arithmetic, failed denominators and archive provenance binding."""

import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

import collect_idle_pair_evidence as evidence
import summarize_idle_pairs as summary

SHA = "a" * 40


def report(api):
    windows = []
    for index in range(12):
        pair, position = divmod(index, 2)
        b = (position ^ (pair % 2)) == 1
        start = (index * 25 + 3) * 1_000_000_000
        end = start + 20_000_000_000
        mean = 100 + pair * 10 + (50 * (pair + 1) if b else 0)
        cpu = (pair + 2 if b else 1) * 190
        state = dict(servicePresent=b, serviceConnected=b, controllerRunning=False,
                     controlAvailable=False, fixtureFocused=True)
        windows.append(dict(pairIndex=pair, position=position, mode=summary.MODES[int(b)], status="passed",
                            startedNanos=start, endedNanos=end, samples=[
                                dict(elapsedRealtimeNanos=start + 100_000_000 + offset * 19_000_000_000,
                                     processCpuMillis=(index + 1) * 5000 + offset * cpu,
                                     totalPssKiB=mean - 10 + offset * 20, dalvikPssKiB=10,
                                     nativePssKiB=20 + offset * 10, otherPssKiB=mean - 40 + offset * 10,
                                     collectionNanos=1_000_000) for offset in range(2)],
                            sampleLimitReached=False, trace=[], traceOverwrittenEvents=0, traceCapacity=4096,
                            stateBefore=dict(state, checkedNanos=start - 1), stateAfter=dict(state, checkedNanos=end + 1)))
    return dict(schemaVersion=1, kind="agent-idle-paired-diagnostics", diagnosticOnly=True, budgetAcceptance=False,
                status="passed", appProcessPid=1234,
                device=dict(sdkInt=api, manufacturer="test", model="sdk", hardware="ranchu",
                            fingerprint="synthetic-test-fingerprint", emulatorHeuristic=True),
                conditions=dict(buildType="debug", processScope="instrumented_app_with_sampler", fixtureProcessIncluded=False,
                                screenshotSamplingIncluded=False, forcedGc=False, systemAnimationOverrides=False,
                                windowMillis=20000, settleMillis=2000, sampleIntervalMillis=1000, pairCount=6,
                                order="AB_BA_AB_BA_AB_BA", fixtureReset="once_before_all_windows", modelUsed=False,
                                stateChecks="before_and_after_window"), windows=windows)


def save_json(path, value):
    data = (json.dumps(value) + "\n").encode()
    path.write_bytes(data)
    return {"path": path.name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def write_archive(parent, run_id=101, mutate_report=None, conclusion="success", gradle_exit=0):
    root = parent / f"{run_id}-1"
    root.mkdir()
    identity = dict(repository=summary.REPOSITORY, commit=SHA, runId=run_id, attempt=1)
    records = []
    for label in evidence.LABELS:
        api = int(label.split(".")[0])
        directory = root / "idle-paired-performance" / ("api-" + label)
        directory.mkdir(parents=True)
        raw = report(api)
        if mutate_report:
            mutate_report(raw)
        ref = save_json(directory / evidence.REPORT_NAME, raw)
        capture = evidence.empty_capture(label, identity)
        capture.update(actualSdkInt=api, captureStatus="collected", captureError=None, gradleExitCode=gradle_exit,
                       report=dict(ref, status=raw["status"]), emulatorStepOutcome=conclusion)
        capture_ref = save_json(directory / "capture.json", capture)
        records.append(dict(apiLevel=api, matrixLabel=label, jobConclusion=conclusion, captureStatus="collected",
                            captureError=None, actualSdkInt=api, diagnosticStatus=raw["status"], gradleExitCode=gradle_exit,
                            declaredCiProfile=capture["declaredCiProfile"], artifactId=run_id * 100 + api,
                            files=[capture_ref, ref]))
    manifest = dict(schemaVersion=1, kind="android-emulator-test-evidence", executionScope="idle-pair-diagnostic",
                    sourceRun=dict(identity, branch="master", conclusion=conclusion,
                                   url=f"https://github.com/{summary.REPOSITORY}/actions/runs/{run_id}"),
                    expectedMatrixLabels=list(evidence.LABELS),
                    idlePairedPerformance=dict(kind="idle-paired-performance-attachments", diagnosticOnly=True,
                                               budgetAcceptance=False, scope="fixed idle pair diagnostics", apis=records))
    save_json(root / "manifest.json", manifest)
    return root


def edit_manifest(root, edit):
    path = root / "manifest.json"
    value = json.loads(path.read_text())
    edit(value)
    save_json(path, value)


class IdlePairSummaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive = write_archive(self.root)

    def run_summary(self, archive=None):
        return summary.summarize(archive or self.archive, SHA)

    def test_per_pair_arithmetic_precedes_distributions_and_order_is_separate(self):
        result = self.run_summary()
        self.assertEqual(("complete", 48, 48, 0, 96, 96),
                         (result["status"], result["expectedPairCount"], result["eligiblePairCount"],
                          result["excludedPairCount"], result["expectedWindowCount"], result["passedWindowCount"]))
        lane = result["lanes"][0]
        self.assertEqual(["AB", "BA"] * 3, [pair["order"] for pair in lane["pairs"]])
        self.assertEqual([1, 2, 3, 4, 5, 6], [pair["differences"]["cpuPercentagePoints"] for pair in lane["pairs"]])
        self.assertEqual([50, 100, 150, 200, 250, 300], [pair["differences"]["sampleMeanTotalPssKiB"] for pair in lane["pairs"]])
        first = lane["windows"][0]
        self.assertEqual((20000, 19000, 1, 100, 110),
                         (first["wallMillis"], first["resourceSampleSpanMillis"], first["cpuPercentOneCore"],
                          first["sampleMeanTotalPssKiB"], first["sampleMaxTotalPssKiB"]))
        self.assertEqual(report(30)["windows"][0]["samples"], first["resourceSamples"])
        group = result["groups"][0]
        self.assertEqual(dict(count=6, missing=0, min=1, median=3.5, max=6), group["allPairs"]["differences"]["cpuPercentagePoints"])
        self.assertEqual(3, group["byOrder"]["AB"]["differences"]["cpuPercentagePoints"]["median"])
        self.assertEqual(4, group["byOrder"]["BA"]["differences"]["cpuPercentagePoints"]["median"])
        self.assertTrue(result["diagnosticOnly"])
        self.assertFalse(result["budgetAcceptance"])
        self.assertEqual(64, len(result["tool"]["commonValidatorSha256"]))

    def test_negative_differences_and_absolute_sampled_maxima_are_retained(self):
        def change(raw):
            raw["windows"][1]["samples"][1]["processCpuMillis"] = raw["windows"][1]["samples"][0]["processCpuMillis"]
            for sample in raw["windows"][1]["samples"]:
                sample["totalPssKiB"] = 40
        alternate = write_archive(self.root, 102, mutate_report=change)
        lane = self.run_summary(alternate)["lanes"][0]
        self.assertEqual(dict(cpuPercentagePoints=-1, sampleMeanTotalPssKiB=-60), lane["pairs"][0]["differences"])
        self.assertEqual(40, lane["windows"][1]["sampleMaxTotalPssKiB"])

    def test_failed_prefix_retains_completed_pairs_failed_window_and_unexecuted_denominator(self):
        def change(raw):
            if raw["device"]["sdkInt"] != 30:
                return
            raw["status"] = "failed"
            raw["windows"] = raw["windows"][:4]
            raw["windows"][-1].update(status="failed", stateAfter=None)
            raw["windows"][-1]["samples"] = raw["windows"][-1]["samples"][:1]
        alternate = write_archive(self.root, 102, mutate_report=change, conclusion="failure", gradle_exit=1)
        result = self.run_summary(alternate)
        self.assertEqual(("incomplete", 48, 43, 5, 88), (result["status"], result["expectedPairCount"], result["eligiblePairCount"], result["excludedPairCount"], result["recordedWindowCount"]))
        lane = result["lanes"][0]
        self.assertEqual(("failure", "failure", 1, "failed"),
                         (lane["sourceRunConclusion"], lane["jobConclusion"], lane["gradleExitCode"], lane["diagnosticStatus"]))
        self.assertEqual(["passed", "failed"] + ["incomplete"] * 4, [pair["status"] for pair in lane["pairs"]])
        self.assertIsNone(lane["windows"][3]["cpuPercentOneCore"])
        self.assertEqual("insufficient_sample_span", lane["windows"][3]["cpuUnavailableReason"])
        self.assertEqual(1, len(lane["windows"][3]["resourceSamples"]))
        self.assertEqual(dict(count=1, missing=5, min=1, median=1, max=1), result["groups"][0]["allPairs"]["differences"]["cpuPercentagePoints"])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, summary.main([str(alternate), "--source", SHA]))

    def test_report_cleanup_failure_never_becomes_complete_even_with_all_valid_pairs(self):
        alternate = write_archive(self.root, 102, mutate_report=lambda raw: raw.update(status="failed"), conclusion="failure", gradle_exit=1)
        result = self.run_summary(alternate)
        self.assertEqual(("incomplete", 48, 0), (result["status"], result["eligiblePairCount"], result["passedDiagnosticLaneCount"]))
        self.assertEqual("failure", result["archive"]["sourceRun"]["conclusion"])

    def test_original_run_failure_is_retained_even_when_all_diagnostics_pass(self):
        alternate = write_archive(self.root, 102, conclusion="failure", gradle_exit=1)
        result = self.run_summary(alternate)
        self.assertEqual(("complete", 48, 8), (result["status"], result["eligiblePairCount"], result["passedDiagnosticLaneCount"]))
        self.assertEqual("failure", result["archive"]["sourceRun"]["conclusion"])
        self.assertTrue(all(lane["jobConclusion"] == "failure" and lane["gradleExitCode"] == 1 for lane in result["lanes"]))

    def test_failure_before_first_window_preserves_all_planned_denominators(self):
        alternate = write_archive(self.root, 102, mutate_report=lambda raw: raw.update(status="failed", windows=[]), conclusion="failure", gradle_exit=1)
        result = self.run_summary(alternate)
        self.assertEqual(("incomplete", 48, 0, 96, 0, 0),
                         (result["status"], result["excludedPairCount"], result["recordedWindowCount"],
                          result["notExecutedWindowCount"], result["unavailableWindowCount"], result["failedWindowCount"]))
        self.assertIsNone(result["groups"][0]["allPairs"]["differences"]["cpuPercentagePoints"]["median"])

    def test_missing_artifact_remains_six_pairs_and_twelve_unknown_windows(self):
        directory = self.archive / "idle-paired-performance/api-30"
        for path in directory.iterdir():
            path.unlink()
        edit_manifest(self.archive, lambda value: value["idlePairedPerformance"]["apis"][0].update(files=[], captureStatus="artifact_unavailable", diagnosticStatus=None))
        result = self.run_summary()
        self.assertEqual((42, 6, 84), (result["eligiblePairCount"], result["excludedPairCount"], result["recordedWindowCount"]))
        lane = result["lanes"][0]
        self.assertEqual("artifact_unavailable", lane["reason"])
        self.assertTrue(all(window["status"] == "unavailable" for window in lane["windows"]))
        self.assertEqual((12, 0), (result["unavailableWindowCount"], result["notExecutedWindowCount"]))
        self.assertEqual(dict(count=0, missing=6, min=None, median=None, max=None), result["groups"][0]["allPairs"]["differences"]["cpuPercentagePoints"])

    def test_unavailable_capture_is_preserved_without_zero_measurements(self):
        directory = self.archive / "idle-paired-performance/api-30"
        (directory / evidence.REPORT_NAME).unlink()
        capture_path = directory / "capture.json"
        capture = json.loads(capture_path.read_text())
        capture.update(captureStatus="unavailable", captureError="device_or_report_unavailable", report=None)
        ref = save_json(capture_path, capture)
        edit_manifest(self.archive, lambda value: value["idlePairedPerformance"]["apis"][0].update(files=[ref], captureStatus="unavailable", captureError=capture["captureError"], diagnosticStatus=None))
        result = self.run_summary()
        self.assertEqual(42, result["eligiblePairCount"])
        self.assertEqual("capture_unavailable", result["lanes"][0]["reason"])

    def test_source_scope_api_and_capture_bindings_are_strict(self):
        original = (self.archive / "manifest.json").read_bytes()
        changes = [lambda m: m.update(executionScope="full"),
                   lambda m: m["sourceRun"].update(commit="b" * 40),
                   lambda m: m["sourceRun"].update(runId=True),
                   lambda m: m["sourceRun"].update(repository="other/repo"),
                   lambda m: m["sourceRun"].update(branch="other"),
                   lambda m: m["sourceRun"].update(url="https://example.test/101"),
                   lambda m: m["idlePairedPerformance"]["apis"].pop(),
                   lambda m: m["idlePairedPerformance"]["apis"].reverse(),
                   lambda m: m["idlePairedPerformance"]["apis"][0].update(actualSdkInt=31),
                   lambda m: m["idlePairedPerformance"]["apis"][0].update(gradleExitCode=1),
                   lambda m: m["idlePairedPerformance"].update(budgetAcceptance=True)]
        for change in changes:
            with self.subTest(change=change):
                (self.archive / "manifest.json").write_bytes(original)
                edit_manifest(self.archive, change)
                with self.assertRaises(ValueError):
                    self.run_summary()

    def test_outer_hashes_cannot_hide_wrong_report_or_capture_identity(self):
        directory = self.archive / "idle-paired-performance/api-30"
        capture_path = directory / "capture.json"
        original_capture = json.loads(capture_path.read_text())
        original_manifest = (self.archive / "manifest.json").read_bytes()
        changes = [lambda c: c["sourceRun"].update(runId=999),
                   lambda c: c["report"].update(sha256="0" * 64),
                   lambda c: c["report"].update(status="failed"),
                   lambda c: c.update(declaredCiProfile="unknown")]
        for change in changes:
            capture = copy.deepcopy(original_capture)
            change(capture)
            ref = save_json(capture_path, capture)
            (self.archive / "manifest.json").write_bytes(original_manifest)
            edit_manifest(self.archive, lambda m: m["idlePairedPerformance"]["apis"][0]["files"].__setitem__(0, ref))
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.run_summary()

    def test_hash_extra_file_and_symlink_tampering_are_rejected(self):
        directory = self.archive / "idle-paired-performance/api-30"
        path = directory / evidence.REPORT_NAME
        original = path.read_bytes()
        path.write_bytes(original.replace(b'"passed"', b'"failed"', 1))
        with self.assertRaisesRegex(ValueError, "hash_mismatch"):
            self.run_summary()
        path.write_bytes(original)
        extra = directory / "extra.json"
        extra.write_text("{}")
        with self.assertRaisesRegex(ValueError, "file_set_mismatch"):
            self.run_summary()
        extra.unlink()
        copy_path = self.root / "copy.json"
        copy_path.write_bytes(original)
        path.unlink()
        path.symlink_to(copy_path)
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.run_summary()

    def test_invalid_window_order_and_conditions_are_rejected_despite_valid_hashes(self):
        changes = [lambda raw: raw["windows"][1].update(pairIndex=1),
                   lambda raw: raw["windows"][0].update(mode=summary.MODES[1]),
                   lambda raw: raw["conditions"].update(windowMillis=6000),
                   lambda raw: raw["windows"][0]["stateAfter"].update(fixtureFocused=False)]
        for index, change in enumerate(changes):
            alternate = write_archive(self.root, 200 + index, mutate_report=change)
            with self.subTest(index=index), self.assertRaises(ValueError):
                self.run_summary(alternate)

    def test_cli_refuses_input_overwrite_existing_output_and_path_aliases(self):
        original = (self.archive / "manifest.json").read_bytes()
        for output in (self.archive / "manifest.json", self.archive / "new.json"):
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, summary.main([str(self.archive), "--source", SHA, "--output", str(output)]))
        self.assertEqual(original, (self.archive / "manifest.json").read_bytes())
        output = self.root / "summary.json"
        self.assertEqual(0, summary.main([str(self.archive), "--source", SHA, "--output", str(output)]))
        generated = output.read_bytes()
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, summary.main([str(self.archive), "--source", SHA, "--output", str(output)]))
        self.assertEqual(generated, output.read_bytes())
        alias = self.root / "alias"
        alias.symlink_to(self.archive, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.run_summary(alias)
        with self.assertRaisesRegex(ValueError, "symlink"):
            summary.write_output(alias / "new.json", self.archive, "{}")
        with self.assertRaisesRegex(ValueError, "path_traversal"):
            summary.checked_path(self.root / ".." / "escape")

    def test_malformed_json_and_structures_reject_cleanly(self):
        original = json.loads((self.archive / "manifest.json").read_text())
        variants = [None, [], dict(original, sourceRun=[]), dict(original, idlePairedPerformance=[]),
                    dict(original, idlePairedPerformance=dict(original["idlePairedPerformance"], apis=[None] * 8))]
        for value in variants:
            save_json(self.archive / "manifest.json", value)
            error = io.StringIO()
            with self.subTest(value=type(value)), contextlib.redirect_stderr(error):
                self.assertEqual(2, summary.main([str(self.archive), "--source", SHA]))
            self.assertIn("Idle pair summary rejected:", error.getvalue())
            self.assertNotIn("Traceback", error.getvalue())
        (self.archive / "manifest.json").write_text('{"schemaVersion":1,"schemaVersion":1}')
        with self.assertRaisesRegex(ValueError, "duplicate_json_key"):
            self.run_summary()


if __name__ == "__main__":
    unittest.main()
