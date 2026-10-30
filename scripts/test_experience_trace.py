"""Fail-closed startup attribution tests; all builders are synthetic data."""

import copy
import csv
import gzip
import io
import json
import os
from pathlib import Path
import tempfile
import unittest

import experience_trace as trace


def manifest(kind="cold"):
    return dict(targetPackage=trace.PACKAGE, launches=[dict(
        index=0, marker="aos.experience.launch.0", expectedPid=200,
        expectedStartupType=kind, driverPid=300)])


def extraction(kind="cold"):
    return dict(markers=[dict(name="aos.experience.launch.0", ts=1000, dur=1000, pid=300)],
                startups=[dict(startupId=1, ts=1100, tsEnd=1700, startupType=kind,
                               processCount=1, startupPid=200, startupUpid=2,
                               ttid=500, ttidFrameId=1, ttidUpid=2, ttidPid=200,
                               drawFrameId=6, drawTs=1400, drawDur=200, uiTs=1350,
                               uiDur=150, uiPid=200, uiTid=200, renderPid=200,
                               renderThreadName="RenderThread")],
                stats=[], traceStartNanos=1000, traceEndNanos=2000)


def encoded_csv(raw):
    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow(["result"])
    writer.writerow([json.dumps(raw).encode().hex().upper()])
    return output.getvalue().encode()


def synthetic_chrome_trace():
    events = []
    for pid, name in [(100, "system_server"), (200, trace.PACKAGE), (300, trace.DRIVER_PACKAGE)]:
        events += [dict(ph="M", pid=pid, tid=pid, name="process_name", args=dict(name=name)),
                   dict(ph="M", pid=pid, tid=pid, name="thread_name", args=dict(name=name))]
    events.append(dict(ph="M", pid=200, tid=201, name="thread_name", args=dict(name="RenderThread")))
    def span(pid, tid, name, ts, dur):
        events.append(dict(ph="X", cat="test", pid=pid, tid=tid, name=name, ts=ts, dur=dur))
    for index, kind in enumerate(("cold", "hot")):
        offset = index * 2000
        span(300, 300, f"aos.experience.launch.{index}", offset + 1000, 1000)
        events.append(dict(ph="b", cat="am", pid=100, tid=100, id=index + 1,
                           name=f"launchingActivity#{index + 1}", ts=offset + 1100))
        if kind == "cold":
            span(200, 200, "bindApplication", offset + 1200, 10)
            span(200, 200, "activityStart", offset + 1250, 10)
        span(200, 200, "activityResume", offset + 1300, 10)
        span(200, 200, "Choreographer#doFrame", offset + 1350, 150)
        span(200, 201, "DrawFrame", offset + 1400, 200)
        events.append(dict(ph="e", cat="am", pid=100, tid=100, id=index + 1,
                           name=f"launchingActivity#{index + 1}", ts=offset + 1700))
        events.append(dict(ph="i", s="t", cat="am", pid=100, tid=100,
                           name=f"launchingActivity#{index + 1}:completed-{kind}:{trace.PACKAGE}",
                           ts=offset + 1701))
    return json.dumps(dict(traceEvents=events)).encode()


class ExperienceTraceTests(unittest.TestCase):
    def test_cold_and_hot_use_render_completion(self):
        for kind in ("cold", "hot"):
            value = trace.derive_measurements(extraction(kind), manifest(kind))[0]
            self.assertEqual("passed", value["status"])
            self.assertEqual(500, value["ttidNanos"])
            self.assertEqual(1600, value["drawFrameEndNanos"])

    def test_missing_hot_draw_never_becomes_zero(self):
        value = extraction("hot")
        value["startups"][0].update(ttid=None, ttidFrameId=None, drawFrameId=None)
        row = trace.derive_measurements(value, manifest("hot"))[0]
        self.assertEqual("failed", row["status"])
        self.assertIsNone(row["ttidNanos"])
        self.assertEqual("missing_or_incomplete_frame", row["reason"])

    def test_am_total_time_is_not_an_input(self):
        value = manifest("hot")
        value["launches"][0]["totalTime"] = 0
        raw = extraction("hot")
        raw["startups"] = []
        self.assertIsNone(trace.derive_measurements(raw, value)[0]["ttidNanos"])

    def test_missing_duplicate_unclosed_or_wrong_driver_markers_fail(self):
        variants = [[], extraction()["markers"] * 2,
                    [dict(extraction()["markers"][0], dur=-1)],
                    [dict(extraction()["markers"][0], pid=400)]]
        for markers in variants:
            with self.subTest(markers=markers):
                raw = extraction(); raw["markers"] = markers
                self.assertEqual("failed", trace.derive_measurements(raw, manifest())[0]["status"])

    def test_missing_ambiguous_and_wrong_startup_types_fail(self):
        for startups in ([], extraction()["startups"] * 2,
                         [dict(extraction()["startups"][0], startupType="warm")]):
            raw = extraction(); raw["startups"] = startups
            self.assertEqual("failed", trace.derive_measurements(raw, manifest())[0]["status"])

    def test_pid_upid_and_multiprocess_reuse_are_rejected(self):
        for key, value in (("startupPid", 201), ("ttidPid", 201), ("startupUpid", 3), ("processCount", 2)):
            raw = extraction(); raw["startups"][0][key] = value
            self.assertEqual("startup_process_mismatch", trace.derive_measurements(raw, manifest())[0]["reason"])

    def test_wrong_main_or_render_thread_is_rejected(self):
        for key, value in (("uiPid", 201), ("uiTid", 201), ("renderPid", 201), ("renderThreadName", "worker")):
            raw = extraction(); raw["startups"][0][key] = value
            self.assertEqual("frame_thread_mismatch", trace.derive_measurements(raw, manifest())[0]["reason"])

    def test_late_frame_cannot_be_taken_from_next_launch(self):
        raw = extraction()
        raw["startups"][0].update(drawTs=2000, drawDur=200, ttid=1100)
        row = trace.derive_measurements(raw, manifest())[0]
        self.assertEqual("frame_outside_launch_or_invalid_ttid", row["reason"])
        self.assertIsNone(row["ttidNanos"])

    def test_zero_negative_and_inconsistent_ttid_fail(self):
        for value in (0, -1, 499, 501):
            raw = extraction(); raw["startups"][0]["ttid"] = value
            self.assertEqual("failed", trace.derive_measurements(raw, manifest())[0]["status"])

    def test_frame_started_before_startup_is_rejected(self):
        raw = extraction(); raw["startups"][0]["uiTs"] = 1000
        self.assertEqual("failed", trace.derive_measurements(raw, manifest())[0]["status"])

    def test_error_or_data_loss_disqualifies_every_launch(self):
        raw = extraction()
        raw["stats"] = [dict(name="ftrace_cpu_overrun_end", idx=0, value=1,
                             severity="data_loss", source="trace")]
        self.assertEqual("trace_error_or_data_loss", trace.derive_measurements(raw, manifest())[0]["reason"])

    def test_manifest_identity_and_bool_pid_rejected(self):
        for key, value in (("index", True), ("driverPid", True), ("expectedPid", 0),
                           ("marker", "other"), ("expectedStartupType", "warm")):
            report = manifest(); report["launches"][0][key] = value
            with self.assertRaises(ValueError): trace.validate_manifest(report)

    def test_failed_prefix_unknown_pid_and_unavailable_launches_are_preserved(self):
        report = manifest()
        report["launches"][0].update(status="failed", expectedPid=-1)
        value = trace.derive_measurements(extraction(), report)[0]
        self.assertEqual("driver_launch_failed", value["reason"])
        self.assertIsNone(value["ttidNanos"])
        report["launches"][0].update(status="unavailable", expectedPid=200)
        self.assertEqual("driver_launch_unavailable", trace.derive_measurements(extraction(), report)[0]["reason"])
        report["launches"] = []
        self.assertEqual([], trace.derive_measurements(extraction(), report))

    def test_float_or_bool_trace_timestamps_are_not_accepted(self):
        for value in (True, 1400.0):
            raw = extraction(); raw["startups"][0]["drawTs"] = value
            with self.assertRaises(ValueError): trace.derive_measurements(raw, manifest())

    def test_csv_hex_roundtrip_preserves_integer_nanoseconds(self):
        raw = extraction(); raw["traceEndNanos"] = 9_000_000_000_000_001
        self.assertEqual(raw, trace.parse_extraction_csv(encoded_csv(raw)))
        for data in (b'result\n"{}"\n', b'wrong\n00\n', b'result\n00\n00\n'):
            with self.assertRaises(ValueError): trace.parse_extraction_csv(data)

    def test_strict_json_rejects_duplicate_keys_and_nan(self):
        for data in (b'{"x":1,"x":2}', b'{"x":NaN}'):
            with self.assertRaises(ValueError): trace.strict_json(data)

    def test_streaming_config_is_bounded_and_not_a_timed_exit(self):
        config = trace.trace_config()
        self.assertIn("max_file_size_bytes: 67108864", config)
        self.assertIn("write_into_file: true", config)
        self.assertIn("android.surfaceflinger.frametimeline", config)
        self.assertEqual(2, config.count("fill_policy: RING_BUFFER"))
        self.assertNotIn("DISCARD", config)
        self.assertNotIn("sched/sched_switch", config)
        self.assertNotIn("duration_ms", config)
        self.assertIn('atrace_categories: "am"', config)
        self.assertIn('atrace_categories: "view"', config)
        self.assertNotIn('atrace_categories: "gfx"', config)
        self.assertNotIn('atrace_categories: "wm"', config)

    def test_legacy_config_remains_byte_exact_for_archived_failures(self):
        legacy = trace.trace_config(version=1)
        self.assertEqual("6646bb1c53eeafbcf95efba46636928a4e388d51b02360dd50577674512386ed",
                         trace.sha256(legacy.encode()))
        expected = legacy.replace("fill_policy: DISCARD", "fill_policy: RING_BUFFER")
        expected = expected.replace('    atrace_categories: "gfx"\n', '')
        expected = expected.replace('    atrace_categories: "wm"\n', '')
        self.assertEqual(expected, trace.trace_config(version=2))
        for version in (True, 0, 3, "2"):
            with self.assertRaises(ValueError): trace.trace_config(version=version)

    def test_parser_arguments_are_versioned_without_relabeling_legacy(self):
        legacy = trace.processor_identity(1)
        current = trace.processor_identity(2)
        self.assertNotIn("arguments", legacy)
        self.assertEqual(dict(legacy, arguments=["--full-sort"]), current)
        for version in (True, 0, 3, "2"):
            with self.assertRaises(ValueError): trace.processor_identity(version)

    def test_trace_bounds_gzip_and_checksum_are_verified(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "trace.pftrace.gz"
            path.write_bytes(gzip.compress(b"synthetic-test", mtime=0))
            value = trace.trace_identity(path)
            self.assertEqual(14, value["uncompressedBytes"])
            path.write_bytes(path.read_bytes()[:-4])
            with self.assertRaises((OSError, EOFError)): trace.trace_identity(path)

    def test_unverified_or_symlink_binary_is_never_executed(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "fake"; path.write_bytes(b"not official")
            with self.assertRaises(ValueError): trace.verify_processor(path)
            link = Path(temp) / "link"; link.symlink_to(path)
            with self.assertRaises(ValueError): trace.verify_processor(link)

    @unittest.skipUnless(os.environ.get("TEST_TRACE_PROCESSOR"), "pinned processor integration opt-in")
    def test_official_stdlib_extracts_synthetic_cold_hot_and_offline_rederives(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "trace.pftrace.gz"
            path.write_bytes(gzip.compress(synthetic_chrome_trace(), mtime=0))
            report = manifest()
            report["launches"].append(dict(index=1, marker="aos.experience.launch.1", expectedPid=200,
                                            expectedStartupType="hot", driverPid=300))
            query = Path(temp) / "startup-ttid.csv"
            value = trace.derive_trace(path, report, os.environ["TEST_TRACE_PROCESSOR"], query)
            self.assertEqual(2, value["schemaVersion"])
            self.assertEqual(["--full-sort"], value["processor"]["arguments"])
            self.assertEqual("passed", value["status"])
            self.assertEqual([500000, 500000], [r["ttidNanos"] for r in value["launches"]])
            trace.validate_derivation(value, report, path, query)
            modified = copy.deepcopy(value); modified["launches"][0]["ttidNanos"] = 0
            with self.assertRaisesRegex(ValueError, "recompute"):
                trace.validate_derivation(modified, report, path, query)
            for key, replacement in (("index", False), ("ttidNanos", 500000.0), ("startupId", True)):
                modified = copy.deepcopy(value); modified["launches"][0][key] = replacement
                with self.assertRaisesRegex(ValueError, "recompute"):
                    trace.validate_derivation(modified, report, path, query)
            modified = copy.deepcopy(value); modified["processor"]["arguments"] = []
            with self.assertRaisesRegex(ValueError, "processor"):
                trace.validate_derivation(modified, report, path, query)
            legacy = trace.derive_trace(path, report, os.environ["TEST_TRACE_PROCESSOR"], query,
                                        schema_version=1)
            self.assertEqual(1, legacy["schemaVersion"])
            self.assertNotIn("arguments", legacy["processor"])
            trace.validate_derivation(legacy, report, path, query)


if __name__ == "__main__":
    unittest.main()
