#!/usr/bin/env python3
"""Pinned Perfetto startup extraction; missing frames never become zero TTID.

Uses Perfetto v59.1 android.startup.time_to_display, then independently bounds
its selected frame to the driver's launch marker and the next startup. The
stdlib's frame search alone has no such upper bound. Durations are nanoseconds,
not presentation timestamps or physical-device budget acceptance.
"""

import argparse
import csv
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import resource
import subprocess
import tempfile
import urllib.request

VERSION = "v59.1"
PROCESSOR_SHA256 = "2434129a3af4dc9e2034a476f7b76447b490975213e301769310d3bd0d5d6458"
PROCESSOR_BYTES = 15885448
PROCESSOR_URL = ("https://commondatastorage.googleapis.com/perfetto-luci-artifacts/"
                 "v59.1/linux-amd64/trace_processor_shell")
KIND = "agent-os-startup-trace-v1"
RAW_LIMIT = 64 * 1024 * 1024
GZIP_LIMIT = 12 * 1024 * 1024
QUERY_LIMIT = 4 * 1024 * 1024
PACKAGE = "dev.agentos.fixture"
DRIVER_PACKAGE = "dev.agentos.app"
NULL_KEYS = ("startupId", "startupType", "pid", "upid", "markerStartNanos",
             "markerEndNanos", "startupStartNanos", "startupEndNanos", "ttidNanos",
             "ttidFrameId", "drawFrameId", "drawFrameStartNanos", "drawFrameEndNanos")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def same_typed(left, right):
    if type(left) is not type(right):
        return False
    if type(left) is dict:
        return left.keys() == right.keys() and all(same_typed(left[k], right[k]) for k in left)
    if type(left) is list:
        return len(left) == len(right) and all(same_typed(a, b) for a, b in zip(left, right))
    return left == right


def strict_json(data):
    def pairs(values):
        result = {}
        for key, value in values:
            if key in result:
                raise ValueError("duplicate_json_key")
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError("nonfinite_json")))


def trace_config(packages=None):
    """Streaming capture: 8 MiB bounded buffers, 64 MiB file; no timed exit."""
    packages = [PACKAGE, DRIVER_PACKAGE] if packages is None else list(packages)
    if not packages or any(not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.]*", p) for p in packages):
        raise ValueError("invalid_atrace_package")
    apps = "\n".join(f'      atrace_apps: "{p}"' for p in dict.fromkeys(packages))
    return f'''buffers {{ size_kb: 7168 fill_policy: DISCARD }}
buffers {{ size_kb: 1024 fill_policy: DISCARD }}
data_sources {{ config {{ name: "linux.ftrace" target_buffer: 0
  ftrace_config {{
    atrace_categories: "am"
    atrace_categories: "wm"
    atrace_categories: "view"
    atrace_categories: "gfx"
{apps}
    ftrace_events: "task/task_newtask"
    ftrace_events: "task/task_rename"
    ftrace_events: "sched/sched_process_exit"
    ftrace_events: "sched/sched_process_free"
  }}
}} }}
data_sources {{ config {{ name: "linux.process_stats" target_buffer: 1
  process_stats_config {{ scan_all_processes_on_start: true proc_stats_poll_ms: 1000 }}
}} }}
data_sources {{ config {{ name: "android.packages_list" target_buffer: 1 }} }}
data_sources {{ config {{ name: "android.surfaceflinger.frametimeline" target_buffer: 0 }} }}
write_into_file: true
file_write_period_ms: 2500
max_file_size_bytes: {RAW_LIMIT}
flush_period_ms: 5000
incremental_state_config {{ clear_period_ms: 5000 }}
'''


def verify_processor(path):
    path = Path(path)
    if (path.is_symlink() or not path.is_file() or path.stat().st_size != PROCESSOR_BYTES
            or sha256(path.read_bytes()) != PROCESSOR_SHA256):
        raise ValueError("trace_processor_checksum_mismatch")
    return path


def download_processor(cache_dir):
    """Download only the pinned Linux x86_64 official binary and verify bytes."""
    cache = Path(cache_dir)
    cache.mkdir(parents=True, exist_ok=True)
    path = cache / "trace_processor_shell-v59.1"
    if path.exists() or path.is_symlink():
        return verify_processor(path)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as stream:
            temporary = Path(stream.name)
            with urllib.request.urlopen(PROCESSOR_URL, timeout=60) as response:
                total = 0
                while block := response.read(65536):
                    total += len(block)
                    if total > PROCESSOR_BYTES:
                        raise ValueError("oversized_trace_processor_download")
                    stream.write(block)
        verify_processor(temporary)
        temporary.chmod(0o755)
        os.replace(temporary, path)
        return path
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def trace_identity(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError("trace_missing_or_symlink")
    size = path.stat().st_size
    compressed = path.suffix == ".gz"
    if not 0 < size <= (GZIP_LIMIT if compressed else RAW_LIMIT):
        raise ValueError("trace_size_limit")
    length = 0
    opener = gzip.open if compressed else open
    with opener(path, "rb") as stream:
        while block := stream.read(65536):
            length += len(block)
            if length >= RAW_LIMIT:
                raise ValueError("trace_reached_file_limit")
    if not length:
        raise ValueError("empty_trace")
    return dict(path=path.name, bytes=size, sha256=sha256(path.read_bytes()), uncompressedBytes=length)


def validate_manifest(report):
    if type(report) is not dict or report.get("targetPackage") != PACKAGE:
        raise ValueError("unexpected_trace_target")
    launches = report.get("launches")
    if type(launches) is not list or len(launches) > 400:
        raise ValueError("invalid_launch_manifest")
    for index, launch in enumerate(launches):
        if (type(launch) is not dict or type(launch.get("index")) is not int
                or launch["index"] != index
                or launch.get("marker") != f"aos.experience.launch.{index}"
                or launch.get("expectedStartupType") not in ("cold", "hot")):
            raise ValueError("invalid_launch_marker")
        for key in ("expectedPid", "driverPid"):
            unavailable_pid = key == "expectedPid" and launch.get("status") == "failed" and launch.get(key) == -1
            if type(launch.get(key)) is not int or not (unavailable_pid or 1 <= launch[key] < 2 ** 31):
                raise ValueError("invalid_launch_pid")
    return launches


def extraction_sql():
    # The package and marker namespace are fixed, never SQL-interpolated input.
    return '''INCLUDE PERFETTO MODULE android.startup.time_to_display;
SELECT hex(json_object(
 'markers', json((SELECT json_group_array(json_object(
   'name', s.name, 'ts', s.ts, 'dur', s.dur, 'pid', p.pid))
   FROM slice s JOIN thread_track tr ON s.track_id=tr.id
   JOIN thread th ON tr.utid=th.utid JOIN process p ON th.upid=p.upid
   WHERE s.name GLOB 'aos.experience.launch.*')),
 'startups', json((SELECT json_group_array(json_object(
   'startupId', s.startup_id, 'ts', s.ts, 'tsEnd', s.ts_end,
   'startupType', s.startup_type, 'processCount',
     (SELECT count(*) FROM android_startup_processes sp WHERE sp.startup_id=s.startup_id),
   'startupPid', sp.pid, 'startupUpid', sp.upid,
   'ttid', d.time_to_initial_display, 'ttidFrameId', d.ttid_frame_id,
   'ttidUpid', d.upid, 'ttidPid', p.pid, 'drawFrameId', f.draw_frame_id,
   'drawTs', draw.ts, 'drawDur', draw.dur, 'uiTs', ui.ts, 'uiDur', ui.dur,
   'uiPid', ui_p.pid, 'uiTid', ui_th.tid,
   'renderPid', rt_p.pid, 'renderThreadName', rt_th.name))
   FROM android_startups s
   LEFT JOIN android_startup_processes sp USING (startup_id)
   LEFT JOIN android_startup_time_to_display d USING (startup_id)
   LEFT JOIN process p ON d.upid=p.upid
   LEFT JOIN android_frames f ON f.frame_id=d.ttid_frame_id AND f.upid=d.upid
   LEFT JOIN slice draw ON draw.id=f.draw_frame_id
   LEFT JOIN slice ui ON ui.id=f.do_frame_id
   LEFT JOIN thread ui_th ON f.ui_thread_utid=ui_th.utid
   LEFT JOIN process ui_p ON ui_th.upid=ui_p.upid
   LEFT JOIN thread rt_th ON f.render_thread_utid=rt_th.utid
   LEFT JOIN process rt_p ON rt_th.upid=rt_p.upid
   WHERE s.package='dev.agentos.fixture')),
 'stats', json((SELECT json_group_array(json_object(
   'name', name, 'idx', idx, 'severity', severity, 'source', source, 'value', value))
   FROM stats WHERE value > 0 AND severity IN ('error', 'data_loss'))),
 'traceStartNanos', (SELECT start_ts FROM trace_bounds),
 'traceEndNanos', (SELECT end_ts FROM trace_bounds)
)) AS result;
'''


def parse_extraction_csv(data):
    if len(data) > QUERY_LIMIT:
        raise ValueError("trace_extraction_size_limit")
    csv.field_size_limit(QUERY_LIMIT)
    reader = csv.DictReader(io.StringIO(data.decode("utf-8")))
    rows = list(reader)
    if reader.fieldnames != ["result"] or len(rows) != 1:
        raise ValueError("unexpected_trace_query_output")
    # trace_processor's CSV string printer does not escape embedded JSON
    # quotes. HEX transports the exact JSON bytes through its CSV interface.
    encoded = rows[0]["result"]
    if not re.fullmatch(r"(?:[0-9A-F]{2})+", encoded):
        raise ValueError("invalid_trace_query_hex")
    value = strict_json(bytes.fromhex(encoded))
    if (type(value) is not dict or set(value) != {
            "markers", "startups", "stats", "traceStartNanos", "traceEndNanos"}
            or any(type(value[k]) is not list for k in ("markers", "startups", "stats"))):
        raise ValueError("invalid_trace_extraction")
    validate_extraction(value)
    return value


def validate_extraction(value):
    for key in ("traceStartNanos", "traceEndNanos"):
        if type(value[key]) is not int or value[key] < 0:
            raise ValueError("invalid_trace_clock")
    if value["traceEndNanos"] < value["traceStartNanos"]:
        raise ValueError("invalid_trace_bounds")
    for marker in value["markers"]:
        if (type(marker) is not dict or set(marker) != {"name", "ts", "dur", "pid"}
                or type(marker["name"]) is not str
                or any(type(marker[k]) is not int for k in ("ts", "dur", "pid"))):
            raise ValueError("invalid_marker_extraction")
    numeric = {"startupId", "ts", "tsEnd", "processCount", "startupPid", "startupUpid",
               "ttid", "ttidFrameId", "ttidUpid", "ttidPid", "drawFrameId", "drawTs",
               "drawDur", "uiTs", "uiDur", "uiPid", "uiTid", "renderPid"}
    for startup in value["startups"]:
        if (type(startup) is not dict or set(startup) != numeric | {"startupType", "renderThreadName"}
                or any(v is not None and type(v) is not int for k, v in startup.items() if k in numeric)
                or any(type(startup[k]) is not int for k in ("startupId", "ts", "tsEnd", "processCount"))
                or any(startup[k] is not None and type(startup[k]) is not str
                       for k in ("startupType", "renderThreadName"))):
            raise ValueError("invalid_startup_extraction")
    for stat in value["stats"]:
        if (type(stat) is not dict or set(stat) != {"name", "idx", "severity", "source", "value"}
                or any(type(stat[k]) is not str for k in ("name", "severity", "source"))
                or stat["severity"] not in ("error", "data_loss")
                or type(stat["value"]) is not int or stat["value"] <= 0
                or stat["idx"] is not None and type(stat["idx"]) is not int):
            raise ValueError("invalid_trace_stat_extraction")


def derive_measurements(raw, manifest):
    validate_extraction(raw)
    launches = validate_manifest(manifest)
    result = []
    used_startups = set()
    for launch in launches:
        row = dict(index=launch["index"], marker=launch["marker"], status="failed", reason=None,
                   **dict.fromkeys(NULL_KEYS))
        result.append(row)
        if launch.get("status") in ("failed", "unavailable"):
            row["reason"] = "driver_launch_" + launch["status"]
            continue
        markers = [m for m in raw["markers"] if m["name"] == launch["marker"]]
        if len(markers) != 1:
            row["reason"] = "missing_or_duplicate_marker"
            continue
        marker = markers[0]
        if (marker["pid"] != launch["driverPid"] or marker["dur"] <= 0
                or marker["ts"] < raw["traceStartNanos"]
                or marker["ts"] + marker["dur"] > raw["traceEndNanos"]):
            row["reason"] = "wrong_driver_or_unclosed_marker"
            continue
        start, end = marker["ts"], marker["ts"] + marker["dur"]
        row.update(markerStartNanos=start, markerEndNanos=end)
        candidates = [s for s in raw["startups"] if start <= s["ts"] < end]
        if len(candidates) != 1:
            row["reason"] = "missing_or_ambiguous_startup"
            continue
        sample = candidates[0]
        if sample["startupId"] in used_startups:
            row["reason"] = "startup_reused_across_markers"
            continue
        used_startups.add(sample["startupId"])
        row.update(startupId=sample["startupId"], startupType=sample["startupType"],
                   startupStartNanos=sample["ts"], startupEndNanos=sample["tsEnd"])
        if sample["startupType"] != launch["expectedStartupType"]:
            row["reason"] = "startup_type_mismatch"
            continue
        if (sample["processCount"] != 1 or sample["startupPid"] != launch["expectedPid"]
                or sample["ttidPid"] != launch["expectedPid"]
                or sample["startupUpid"] != sample["ttidUpid"]):
            row["reason"] = "startup_process_mismatch"
            continue
        keys = ("ttid", "ttidFrameId", "drawFrameId", "drawTs", "drawDur", "uiTs", "uiDur")
        if any(type(sample[k]) is not int or sample[k] < 0 for k in keys):
            row["reason"] = "missing_or_incomplete_frame"
            continue
        draw_end = sample["drawTs"] + sample["drawDur"]
        next_start = min((s["ts"] for s in raw["startups"] if s["ts"] > sample["ts"]), default=end)
        if (sample["ttid"] <= 0 or sample["drawDur"] <= 0 or sample["uiDur"] <= 0
                or not sample["ts"] <= sample["uiTs"] <= sample["drawTs"] < draw_end <= min(end, next_start)
                or sample["ttid"] != draw_end - sample["ts"]):
            row["reason"] = "frame_outside_launch_or_invalid_ttid"
            continue
        if (sample["uiPid"] != launch["expectedPid"] or sample["uiTid"] != launch["expectedPid"]
                or sample["renderPid"] != launch["expectedPid"] or sample["renderThreadName"] != "RenderThread"):
            row["reason"] = "frame_thread_mismatch"
            continue
        row.update(pid=sample["ttidPid"], upid=sample["ttidUpid"], ttidNanos=sample["ttid"],
                   ttidFrameId=sample["ttidFrameId"], drawFrameId=sample["drawFrameId"],
                   drawFrameStartNanos=sample["drawTs"], drawFrameEndNanos=draw_end)
        if raw["stats"]:
            row["reason"] = "trace_error_or_data_loss"
        else:
            row.update(status="passed", reason=None)
    return result


def derive_trace(trace_path, report, processor_path, query_output=None):
    """Extract actual trace data; preserve failed rows and the original CSV."""
    validate_manifest(report)
    trace = trace_identity(trace_path)
    processor = verify_processor(processor_path)
    sql = extraction_sql()
    query_output = Path(query_output) if query_output else Path(trace_path).parent / "startup-ttid.csv"
    def output_limit():
        resource.setrlimit(resource.RLIMIT_FSIZE, (QUERY_LIMIT + 1, QUERY_LIMIT + 1))
    with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
        completed = subprocess.run([str(processor), "query", "--format", "csv", str(trace_path)],
                                   input=sql.encode(), stdout=stdout, stderr=stderr,
                                   timeout=180, preexec_fn=output_limit)
        if completed.returncode:
            stderr.seek(max(0, stderr.tell() - 1000))
            raise ValueError("trace_processor_query_failed: " + stderr.read(1000).decode(errors="replace"))
        if stdout.tell() > QUERY_LIMIT:
            raise ValueError("trace_extraction_size_limit")
        stdout.seek(0)
        output = stdout.read(QUERY_LIMIT + 1)
    raw = parse_extraction_csv(output)
    query_output.write_bytes(output)
    launches = derive_measurements(raw, report)
    return dict(schemaVersion=1, kind=KIND, diagnosticOnly=True, budgetAcceptance=False,
                status="passed" if launches and all(r["status"] == "passed" for r in launches) else "failed",
                trace=trace, processor=dict(version=VERSION, sha256=PROCESSOR_SHA256, url=PROCESSOR_URL),
                package=PACKAGE, launches=launches, traceErrors=raw["stats"],
                extraction=dict(path=query_output.name, bytes=len(output),
                                sha256=sha256(output), sqlSha256=sha256(sql.encode())))


def validate_derivation(value, manifest, trace_path, query_path):
    """Offline archive validation: rederive every row from archived query CSV."""
    expected_keys = {"schemaVersion", "kind", "diagnosticOnly", "budgetAcceptance", "status", "trace",
                     "processor", "package", "launches", "traceErrors", "extraction"}
    if (type(value) is not dict or set(value) != expected_keys or type(value["schemaVersion"]) is not int
            or value["schemaVersion"] != 1 or value["kind"] != KIND or value["diagnosticOnly"] is not True
            or value["budgetAcceptance"] is not False or value["package"] != PACKAGE):
        raise ValueError("invalid_startup_derivation_identity")
    if not same_typed(value["trace"], trace_identity(trace_path)):
        raise ValueError("startup_trace_identity_mismatch")
    if not same_typed(value["processor"], dict(version=VERSION, sha256=PROCESSOR_SHA256, url=PROCESSOR_URL)):
        raise ValueError("startup_processor_identity_mismatch")
    query_path = Path(query_path)
    if query_path.is_symlink() or not query_path.is_file() or query_path.stat().st_size > QUERY_LIMIT:
        raise ValueError("invalid_startup_extraction_file")
    data = query_path.read_bytes()
    expected = dict(path=query_path.name, bytes=len(data), sha256=sha256(data),
                    sqlSha256=sha256(extraction_sql().encode()))
    if not same_typed(value["extraction"], expected):
        raise ValueError("startup_extraction_identity_mismatch")
    raw = parse_extraction_csv(data)
    rows = derive_measurements(raw, manifest)
    if not same_typed(value["launches"], rows) or not same_typed(value["traceErrors"], raw["stats"]):
        raise ValueError("startup_derivation_recompute_mismatch")
    expected_status = "passed" if rows and all(r["status"] == "passed" for r in rows) else "failed"
    if value["status"] != expected_status:
        raise ValueError("startup_derivation_status_mismatch")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    config = commands.add_parser("config")
    config.add_argument("--package", action="append")
    download = commands.add_parser("download")
    download.add_argument("--cache", type=Path, default=Path("/tmp/agent-os-perfetto-v59.1"))
    parse = commands.add_parser("parse")
    parse.add_argument("--trace", type=Path, required=True)
    parse.add_argument("--manifest", type=Path, required=True)
    parse.add_argument("--processor", type=Path)
    parse.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "config":
        print(trace_config(args.package), end="")
    elif args.command == "download":
        print(download_processor(args.cache))
    else:
        if args.manifest.stat().st_size > 2 * 1024 * 1024:
            raise ValueError("launch_manifest_size_limit")
        processor = args.processor or download_processor("/tmp/agent-os-perfetto-v59.1")
        value = derive_trace(args.trace, strict_json(args.manifest.read_bytes()), processor,
                             args.output.with_suffix(".csv"))
        args.output.write_text(json.dumps(value, sort_keys=True, indent=2) + "\n")
        raise SystemExit(0 if value["status"] == "passed" else 1)


if __name__ == "__main__":
    main()
