"""Independent structural fixtures for the bounded ART capability decoder.

These are synthetic binary fixtures derived from ART android-17.0.0_r1,
commit b753cf97923c3695338d21466fa14c57b480a59a. They are not device evidence.
trace.cc writes the 32-byte SLOW header and 10/14-byte V1 records;
trace_common.h:44-50 supplies four method fields, with an ID prepended by
trace.cc:1347-1349. No source-line-number field exists in that fixed format.
"""

import copy
import json
import struct
import unittest

import ime_method_trace as subject


START_MONOTONIC_US = 23_000_000
THREAD_LINES = (b"1\tmain", b"2\tworker")
METHOD_LINES = (
    b"0x4\tandroid.os.Looper\tloop\t()V\tLooper.java",
    b"0x8\tandroid.inputmethodservice.InputMethodService$SettingsObserver"
    b"\tonChange\t(Z)V\tInputMethodService.java",
)
# encoded thread ID, aligned method ID, action, per-thread CPU us, wall delta us
RECORDS = ((1, 4, 0, 0, 0), (1, 8, 0, 3, 10),
           (1, 8, 1, 7, 20), (1, 4, 2, 8, 30))


def fixture(version=3, clock="dual", records=RECORDS, overrides=None,
            threads=THREAD_LINES, methods=METHOD_LINES, extra=(),
            header_version=None, raw_records=None):
    """Build from the published byte layout, not production parser helpers."""
    summary = {
        "data-file-overflow": "false",
        "clock": clock,
        "elapsed-time-usec": "1000",
        "num-method-calls": str(len(records)),
        "clock-call-overhead-nsec": "100",
        "vm": "art",
        "pid": "1661",
        "is_streaming": "false",
        "is_precise_trace": "true",
        "end_time": str(START_MONOTONIC_US * 1000 + 1_000_000),
        "end_tsc": "123456789",
        "compilation-filter": "speed",
    }
    summary.update(overrides or {})
    text = [b"*version", str(version).encode()]
    text.extend((k + "=" + v).encode() for k, v in summary.items() if v is not None)
    text.extend(extra)
    text.extend((b"*threads", *threads, b"*methods", *methods, b"*end"))
    prefix = b"\n".join(text) + b"\n"
    header = bytearray(32)
    header[:4] = b"SLOW"
    struct.pack_into("<H", header, 4, version if header_version is None else header_version)
    struct.pack_into("<H", header, 6, 32)
    struct.pack_into("<Q", header, 8, START_MONOTONIC_US)
    if version >= 3:
        struct.pack_into("<H", header, 16, 14)
    if raw_records is None:
        raw_records = bytearray()
        for tid, method, action, cpu, wall in records:
            raw_records.extend(struct.pack("<HI", tid, method | action))
            if clock in ("dual", "thread-cpu"):
                raw_records.extend(struct.pack("<I", cpu))
            if clock in ("dual", "wall"):
                raw_records.extend(struct.pack("<I", wall))
    return prefix + bytes(header) + bytes(raw_records)


def replace_header(data, offset, value):
    start = data.index(b"*end\n") + len(b"*end\n")
    result = bytearray(data)
    result[start + offset:start + offset + len(value)] = value
    return bytes(result)


class MethodTraceCapabilityTests(unittest.TestCase):
    def rejected(self, data):
        result = subject.parse_trace(data)
        self.assertIsInstance(result, dict)
        self.assertEqual("unverified", result["status"])
        self.assertIsInstance(result["reasons"], list)
        self.assertTrue(result["reasons"])
        self.assertTrue(all(isinstance(item, str) and item for item in result["reasons"]))
        return result

    def test_v3_dual_clock_source_layout(self):
        result = subject.parse_trace(fixture())
        self.assertEqual("recorded", result["status"])
        self.assertEqual([], result["reasons"])
        self.assertEqual(1661, result["summary"]["pid"])
        self.assertEqual(3, result["format"]["version"])
        self.assertEqual(14, result["format"]["recordBytes"])
        self.assertEqual(4, result["records"]["count"])
        self.assertEqual({"enter": 2, "exit": 1, "unwind": 1}, result["records"]["actions"])
        self.assertEqual(2, result["dictionaries"]["threadCount"])
        self.assertEqual(2, result["dictionaries"]["methodCount"])

    def test_v2_accepts_both_supported_single_clock_sources(self):
        for clock in ("wall", "thread-cpu"):
            with self.subTest(clock=clock):
                result = subject.parse_trace(fixture(version=2, clock=clock))
                self.assertEqual("recorded", result["status"])
                self.assertEqual(2, result["format"]["version"])
                self.assertEqual(10, result["format"]["recordBytes"])
                self.assertEqual(4, result["records"]["count"])

    def test_trace_boundaries_may_have_open_frames(self):
        # Capability recognition is not complete-stack reconstruction.
        for records in (((1, 4, 0, 0, 0),), ((1, 4, 1, 0, 0),),
                        ((1, 4, 2, 0, 0),)):
            with self.subTest(records=records):
                self.assertEqual("recorded", subject.parse_trace(fixture(records=records))["status"])

    def test_encoded_thread_index_is_not_required_to_equal_process_pid(self):
        records = tuple((4095, *row[1:]) for row in RECORDS)
        result = subject.parse_trace(fixture(records=records, threads=(b"4095\tmain",)))
        self.assertEqual("recorded", result["status"])
        self.assertEqual(1661, result["summary"]["pid"])

    def test_first_method_id_zero_uses_the_native_printf_spelling(self):
        # current_method_index_ starts at zero; C printf("%#x", 0) emits "0".
        methods = (METHOD_LINES[0].replace(b"0x4", b"0", 1),
                   METHOD_LINES[1].replace(b"0x8", b"0x4", 1))
        records = tuple((tid, method - 4, action, cpu, wall)
                        for tid, method, action, cpu, wall in RECORDS)
        result = subject.parse_trace(fixture(records=records, methods=methods))
        self.assertEqual("recorded", result["status"])
        self.assertEqual(4, result["records"]["count"])

    def test_parse_is_deterministic_and_result_does_not_share_mutable_state(self):
        original = fixture()
        first = subject.parse_trace(original)
        snapshot = copy.deepcopy(first)
        self.assertEqual(snapshot, subject.parse_trace(original))
        first["records"]["actions"]["enter"] = 999
        self.assertEqual(snapshot, subject.parse_trace(original))
        json.dumps(snapshot, allow_nan=False)

    def test_wrong_input_type_and_empty_bytes_are_unverified(self):
        for data in (None, "not bytes", bytearray(fixture()), memoryview(fixture()), 1, {}, b""):
            with self.subTest(type=type(data).__name__):
                self.rejected(data)

    def test_invalid_text_encoding_and_control_characters(self):
        for data in (
            fixture().replace(b"Looper.java", b"Loop\x00er.java"),
            fixture().replace(b"Looper.java", b"Loop\xffer.java"),
            fixture(threads=(b"1\tma\rin",)),
        ):
            self.rejected(data)

    def test_overflow_and_other_runtime_modes_are_not_success(self):
        for overrides in (
            {"data-file-overflow": "true"}, {"is_streaming": "true"},
            {"is_precise_trace": "false"}, {"vm": "dalvik"},
            {"data-file-overflow": "False"}, {"is_streaming": "0"},
        ):
            with self.subTest(overrides=overrides):
                self.rejected(fixture(overrides=overrides))

    def test_unknown_missing_and_duplicate_summary_keys(self):
        for data in (
            fixture(extra=(b"unknown-contract=1",)),
            fixture(extra=(b"pid=1661",)),
            fixture(overrides={"clock": None}),
            fixture(overrides={"num-method-calls": None}),
            fixture(overrides={"is_precise_trace": None}),
        ):
            self.rejected(data)

    def test_numeric_summary_fields_reject_invalid_values(self):
        for key, values in (
            ("pid", ("0", "-1", "nan", "1.5", "999999999999999999999999999")),
            ("num-method-calls", ("-1", "1.5", "nan")),
            ("elapsed-time-usec", ("-1", "inf", "1.5")),
            ("clock-call-overhead-nsec", ("-1", "NaN")),
        ):
            for value in values:
                with self.subTest(key=key, value=value):
                    self.rejected(fixture(overrides={key: value}))

    def test_version_clock_must_match_supported_layout(self):
        for version, clock in ((2, "dual"), (3, "wall"), (3, "thread-cpu"), (3, "boot")):
            with self.subTest(version=version, clock=clock):
                self.rejected(fixture(version=version, clock=clock))

    def test_header_and_summary_version_must_match(self):
        self.rejected(fixture(header_version=2))

    def test_streaming_and_new_formats_are_not_reinterpreted(self):
        for version in (0, 1, 4, 5, 0xF2, 0xF3, 65535):
            with self.subTest(version=version):
                self.rejected(fixture(version=version))
        self.rejected(fixture(header_version=0xF3))

    def test_binary_magic_header_size_record_size_and_reserved_bytes(self):
        for offset, value in ((0, b"FAST"), (6, struct.pack("<H", 18)),
                              (16, struct.pack("<H", 10)), (18, b"\x01"), (31, b"\x01")):
            with self.subTest(offset=offset):
                self.rejected(replace_header(fixture(), offset, value))
        self.rejected(replace_header(fixture(version=2, clock="wall"), 16, b"\x01"))

    def test_truncation_at_text_header_and_record_boundaries(self):
        data = fixture()
        binary = data.index(b"SLOW")
        positions = (0, 1, 9, binary - 1, binary, binary + 1, binary + 31,
                     binary + 32, len(data) - 14, len(data) - 1)
        for end in positions:
            with self.subTest(end=end):
                self.rejected(data[:end])

    def test_record_count_mismatch_and_trailing_data(self):
        for data in (
            fixture(overrides={"num-method-calls": "3"}),
            fixture(overrides={"num-method-calls": "5"}),
            fixture() + b"\x00", fixture() + b"\x00" * 14,
        ):
            self.rejected(data)

    def test_empty_record_or_dictionary_sets_are_not_evidence(self):
        for data in (fixture(records=()), fixture(threads=()), fixture(methods=())):
            self.rejected(data)

    def test_thread_id_uniqueness_range_and_missing_references(self):
        for threads in (
            (b"1\tmain", b"1\tother"), (b"0\tmain",),
            (b"65536\tmain",), (b"2\tworker",), (b"-1\tmain",),
        ):
            with self.subTest(threads=threads):
                self.rejected(fixture(threads=threads))
        self.rejected(fixture(records=((0, 4, 0, 0, 0),)))

    def test_method_id_uniqueness_alignment_and_missing_references(self):
        for methods in (
            (METHOD_LINES[0], METHOD_LINES[0]),
            (METHOD_LINES[0].replace(b"0x4", b"0x5", 1), METHOD_LINES[1]),
            (METHOD_LINES[0],),
            (METHOD_LINES[0].replace(b"0x4", b"0x100000000", 1), METHOD_LINES[1]),
        ):
            with self.subTest(methods=methods):
                self.rejected(fixture(methods=methods))

    def test_method_line_is_exact_fixed_source_five_field_layout(self):
        # Fixed trace_common.h does not write source line numbers.
        for method in (METHOD_LINES[0] + b"\t42", METHOD_LINES[0].rsplit(b"\t", 1)[0],
                       METHOD_LINES[0].replace(b"\t()V\t", b"\t\t")):
            self.rejected(fixture(methods=(method, METHOD_LINES[1])))

    def test_action_three_is_invalid_even_with_a_known_method(self):
        self.rejected(fixture(records=((1, 4, 3, 0, 0),)))

    def test_same_thread_clock_regression_and_wall_outside_elapsed(self):
        for records in (
            ((1, 4, 0, 10, 10), (1, 4, 1, 9, 20)),
            ((1, 4, 0, 10, 20), (1, 4, 1, 11, 19)),
            ((1, 4, 0, 0, 1001),),
        ):
            with self.subTest(records=records):
                self.rejected(fixture(records=records))

    def test_clock_order_is_per_encoded_thread_not_file_order(self):
        records = ((1, 4, 0, 2, 30), (2, 8, 0, 1, 10),
                   (1, 4, 1, 4, 40), (2, 8, 2, 2, 20))
        result = subject.parse_trace(fixture(records=records))
        self.assertEqual("recorded", result["status"])
        self.assertEqual(4, result["records"]["count"])

    def test_native_null_or_empty_source_file_does_not_invent_line_metadata(self):
        for source in (b"", b"(null)"):
            with self.subTest(source=source):
                method = METHOD_LINES[0].rsplit(b"\t", 1)[0] + b"\t" + source
                result = subject.parse_trace(fixture(methods=(method, METHOD_LINES[1])))
                self.assertEqual("recorded", result["status"])

    def test_malformed_jvm_signatures_are_rejected(self):
        for signature in (b"(V)V", b"([V)V", b"(I", b"()", b"(I)VV", b"(L;)V",
                          b"(Ljava/lang/String)V", b"(Ljava.lang.String;)V",
                          b"(" + b"[" * 256 + b"I)V"):
            with self.subTest(signature=signature):
                method = METHOD_LINES[0].replace(b"\t()V\t", b"\t" + signature + b"\t")
                self.rejected(fixture(methods=(method, METHOD_LINES[1])))

    def test_array_and_reference_jvm_signatures_are_supported(self):
        signature = b"([[I[Ljava/lang/String;)Ljava/util/List;"
        method = METHOD_LINES[0].replace(b"\t()V\t", b"\t" + signature + b"\t")
        self.assertEqual("recorded", subject.parse_trace(
            fixture(methods=(method, METHOD_LINES[1])))["status"])

    def test_monotonic_header_end_before_start_is_invalid(self):
        self.rejected(fixture(overrides={"end_time": str(START_MONOTONIC_US * 1000 - 1)}))

    def test_known_allocation_counters_are_all_or_none_and_unsigned(self):
        counters = {"alloc-count": "0", "alloc-size": "123", "gc-count": "1"}
        self.assertEqual("recorded", subject.parse_trace(fixture(overrides=counters))["status"])
        self.rejected(fixture(overrides={"alloc-count": "0"}))
        self.rejected(fixture(overrides=dict(counters, **{"gc-count": "-1"})))

    def test_duplicate_or_reordered_sections(self):
        for data in (
            fixture().replace(b"*threads\n", b"*threads\n*threads\n", 1),
            fixture().replace(b"*methods\n", b"*methods\n*methods\n", 1),
            fixture().replace(b"*threads\n", b"*methods\n", 1),
            fixture().replace(b"*end\n", b"*end\n*end\n", 1),
        ):
            self.rejected(data)

    def test_whole_input_limit_is_independent_of_method_buffer_size(self):
        self.rejected(b"x" * (16 * 1024 * 1024 + 1))

    def test_summary_and_line_byte_limits(self):
        self.rejected(fixture(threads=(b"1\t" + b"x" * 4097,)))
        self.rejected(fixture(extra=(b"x=1\n" * (4 * 1024 * 1024 // 4 + 1),)))

    def test_thread_dictionary_limit(self):
        self.rejected(fixture(threads=tuple(f"{n}\tt{n}".encode() for n in range(1, 4098))))

    def test_method_dictionary_limit(self):
        methods = tuple(f"0x{4*n:x}\tA\tm\t()V\ts".encode() for n in range(1, 100002))
        self.rejected(fixture(methods=methods))

    def test_record_count_limit_with_an_aligned_bounded_file(self):
        # 15,000,010 bytes of records fit the independent 16MiB input cap.
        count = 1_500_001
        body = struct.pack("<HII", 1, 4, 0) * count
        self.rejected(fixture(version=2, clock="wall", records=(),
                              overrides={"num-method-calls": str(count)}, raw_records=body))


if __name__ == "__main__":
    unittest.main()
