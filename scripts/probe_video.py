#!/usr/bin/env python3
"""Optional recording around an unchanged probe; never drive UI or decide its outcome."""
import copy
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import sys
import time
import uuid

import demo_video as media
import third_party_smoke as bounded

PROFILES = ("third-party-app-set", "pinned-calculator-page", "busy-stop-platform")
TIME_LIMIT_SECONDS = 120
MAX_RAW = 70_000_000
COVERAGE_TOLERANCE_MS = 1500
STAGES = ("output_prepare", "device_preflight", "recording_start", "recording_stop",
          "recording_stderr", "raw_stat", "raw_pull", "raw_probe", "video_encode",
          "encoded_probe", "encoded_policy", "video_decode", "coverage", "raw_cleanup",
          "remote_cleanup", "report_write")
require = bounded.require


def _source(value):
    media.exact(value, ("repository", "commit", "runId", "attempt"))
    require(value["repository"] == media.REPO and isinstance(value["commit"], str)
            and re.fullmatch(r"[0-9a-f]{40}", value["commit"])
            and all(type(value[key]) is int and 0 < value[key] < 10**19 for key in ("runId", "attempt")),
            "Invalid video source")


def _file_record(value, full=False):
    basic = {"bytes", "sha256"}
    complete = basic | {"durationMs", "width", "height", "codec", "pixelFormat"}
    require(isinstance(value, dict) and set(value) in ((complete,) if full else (basic, complete)),
            "Invalid video file record")
    require(type(value["bytes"]) is int and 0 < value["bytes"] < media.MAX_VIDEO
            and isinstance(value["sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", value["sha256"]),
            "Invalid video size or digest")
    if set(value) == complete:
        require(type(value["durationMs"]) is int and 500 <= value["durationMs"] <= 125_000
                and all(type(value[k]) is int and 64 <= value[k] <= 4096 for k in ("width", "height"))
                and value["codec"] == "h264" and isinstance(value["pixelFormat"], str)
                and len(value["pixelFormat"]) <= 40, "Invalid video metadata")
    if full:
        require(value["bytes"] <= 95_000_000 and value["pixelFormat"] == "yuv420p"
                and value["width"] % 2 == value["height"] % 2 == 0, "Video is not portable")


def validate_recording_report(value, source, profile):
    """Validate metadata only; archives must independently hash and decode present videos."""
    _source(source)
    media.exact(value, ("schemaVersion", "kind", "source", "profile", "apiLevel", "status",
        "recordingStatus", "callbackOutcome", "callbackErrorClass", "callbackElapsedMs",
        "callbackStartedAfterRecorderMs", "callbackFinishedAfterRecorderMs", "timeLimitSeconds",
        "secureFlagsPreserved", "testOutcomeIndependent", "coverage", "rawVideo", "rawRetained",
        "video", "recorder", "failures", "probes"))
    require(type(value["schemaVersion"]) is int and value["schemaVersion"] == 1
            and value["kind"] == "agent-os-probe-video" and value["source"] == source
            and profile in PROFILES and value["profile"] == profile
            and type(value["apiLevel"]) is int and value["apiLevel"] == 35,
            "Foreign probe recording")
    _source(value["source"])
    require(value["status"] in ("passed", "failed") and value["recordingStatus"] in ("recorded", "unavailable")
            and value["callbackOutcome"] in ("returned", "raised")
            and media.nonnegative(value["callbackElapsedMs"])
            and type(value["timeLimitSeconds"]) is int and value["timeLimitSeconds"] == TIME_LIMIT_SECONDS
            and value["secureFlagsPreserved"] is True and value["testOutcomeIndependent"] is True,
            "Invalid recording state")
    error = value["callbackErrorClass"]
    require(error is None if value["callbackOutcome"] == "returned" else isinstance(error, str)
            and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_.$]{0,159}", error), "Invalid callback classification")
    recorder = value["recorder"]
    media.exact(recorder, ("started", "exitCode", "stderr"))
    require(type(recorder["started"]) is bool and (recorder["exitCode"] is None
            or type(recorder["exitCode"]) is int and -128 <= recorder["exitCode"] <= 255), "Invalid recorder")
    if recorder["stderr"] is not None:
        stderr = recorder["stderr"]; media.exact(stderr, ("text", "truncated", "complete"))
        require(recorder["started"] and isinstance(stderr["text"], str)
                and len(stderr["text"].encode()) <= media.MAX_RECORDER_STDERR * 3
                and type(stderr["truncated"]) is bool and type(stderr["complete"]) is bool,
                "Invalid bounded recorder diagnostic")
    started, finished = (value[k] for k in ("callbackStartedAfterRecorderMs", "callbackFinishedAfterRecorderMs"))
    require((started is None and finished is None and not recorder["started"])
            or (recorder["started"] and media.nonnegative(started) and media.nonnegative(finished)
                and finished >= started and abs(finished - started - value["callbackElapsedMs"]) <= 1),
            "Invalid callback interval")
    coverage = value["coverage"]
    media.exact(coverage, ("status", "recorderAliveBeforeCallback", "recorderAliveAfterCallback", "durationToleranceMs"))
    require(coverage["status"] in ("covered", "incomplete", "unavailable")
            and all(type(coverage[key]) is bool for key in ("recorderAliveBeforeCallback", "recorderAliveAfterCallback"))
            and type(coverage["durationToleranceMs"]) is int
            and coverage["durationToleranceMs"] == COVERAGE_TOLERANCE_MS, "Invalid coverage")
    require(type(value["rawRetained"]) is bool, "Invalid raw retention")
    if value["rawVideo"] is not None:
        _file_record(value["rawVideo"])
        require(value["rawVideo"]["bytes"] < MAX_RAW, "Raw video exceeds bound")
    require(not value["rawRetained"] or value["rawVideo"] is not None, "Missing retained raw identity")
    if value["video"] is not None:
        _file_record(value["video"], True)
        require(value["rawVideo"] is not None
                and "durationMs" in value["rawVideo"]
                and abs(value["video"]["durationMs"] - value["rawVideo"]["durationMs"]) <= 250,
                "Missing original encoded-video identity")
    require((value["recordingStatus"] == "recorded") == (value["video"] is not None), "Recording status mismatch")
    covered = (value["video"] is not None and recorder["started"]
               and coverage["recorderAliveBeforeCallback"] and coverage["recorderAliveAfterCallback"]
               and value["callbackElapsedMs"] <= TIME_LIMIT_SECONDS * 1000
               and value["video"]["durationMs"] + COVERAGE_TOLERANCE_MS >= finished)
    require(coverage["status"] == ("covered" if covered else "incomplete" if value["video"] else "unavailable"),
            "Recording coverage does not match evidence")
    require(isinstance(value["failures"], list) and len(value["failures"]) <= 16, "Too many recording failures")
    for failure in value["failures"]:
        media.exact(failure, ("stage", "errorClass"))
        require(failure["stage"] in STAGES and (failure["errorClass"] is None
                or isinstance(failure["errorClass"], str)
                and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_.$]{0,159}", failure["errorClass"])), "Unsafe recording error")
    if value["video"] is not None and value["rawRetained"]:
        require(value["status"] == "failed" and any(f["stage"] == "raw_cleanup" for f in value["failures"]),
                "Unexpected retained encoded source")
    require(isinstance(value["probes"], list) and len(value["probes"]) <= 2, "Too many probes")
    stages = []
    for probe in value["probes"]:
        media.exact(probe, ("stage", "status", "exitCode", "stdout"))
        stages.append(probe["stage"])
        require(probe["status"] in ("completed", "timed_out", "output_limit")
                and (probe["exitCode"] is None or type(probe["exitCode"]) is int and -128 <= probe["exitCode"] <= 255)
                and (probe["status"] != "completed" or probe["exitCode"] is not None)
                and isinstance(probe["stdout"], str) and len(probe["stdout"].encode()) <= 196608,
                "Invalid bounded media probe")
    require(stages in ([], ["raw_probe"], ["raw_probe", "encoded_probe"]), "Invalid probe sequence")
    require((value["status"] == "passed") == (covered and not value["failures"]), "Recording result mismatch")
    if value["status"] == "passed":
        require(recorder["exitCode"] == 0 and recorder["stderr"] is not None
                and recorder["stderr"]["complete"], "Incomplete successful recorder")
        require(stages == ["raw_probe", "encoded_probe"]
                and all(probe["status"] == "completed" and probe["exitCode"] == 0 for probe in value["probes"]),
                "Missing successful raw and encoded probes")
    return value


def _read_pid(stream):
    """Read one bounded ownership line without a blocking readline after select."""
    deadline = time.monotonic() + 8
    value = bytearray()
    with selectors.DefaultSelector() as selector:
        selector.register(stream, selectors.EVENT_READ)
        while time.monotonic() < deadline and len(value) < 32:
            if not selector.select(max(0, deadline - time.monotonic())):
                break
            chunk = os.read(stream.fileno(), 32 - len(value))
            if not chunk:
                break
            value.extend(chunk)
            if b"\n" in value:
                pid, rest = bytes(value).split(b"\n", 1)
                require(not rest.strip() and re.fullmatch(rb"[1-9][0-9]{0,7}\r?", pid), "Invalid recorder ownership")
                return pid.decode().strip()
    raise ValueError("Recorder did not report ownership within its bound")


def record_call(directory, source, profile, api, adb_path, serial, callback):
    """Call callback exactly once and preserve its return/exception, regardless of recording.

    The callback retains its own instrumentation deadline. A recording that expires
    at 120 seconds is incomplete; it never cancels, repeats or shortens that call.
    Only recording.json, demo.mp4 and an optional failed raw.mp4 belong here.
    """
    report = {"schemaVersion": 1, "kind": "agent-os-probe-video", "source": copy.deepcopy(source),
              "profile": profile, "apiLevel": api, "status": "failed", "recordingStatus": "unavailable",
              "callbackOutcome": "not_run", "callbackErrorClass": None, "callbackElapsedMs": 0,
              "callbackStartedAfterRecorderMs": None, "callbackFinishedAfterRecorderMs": None,
              "timeLimitSeconds": TIME_LIMIT_SECONDS, "secureFlagsPreserved": True, "testOutcomeIndependent": True,
              "coverage": {"status": "unavailable", "recorderAliveBeforeCallback": False,
                           "recorderAliveAfterCallback": False, "durationToleranceMs": COVERAGE_TOLERANCE_MS},
              "rawVideo": None, "rawRetained": False, "video": None,
              "recorder": {"started": False, "exitCode": None, "stderr": None}, "failures": [], "probes": []}
    recorder = pid = reader = output = raw = remote = None
    launched = None
    stage = "output_prepare"

    def failed(where, error=None):
        if len(report["failures"]) < 16:
            report["failures"].append({"stage": where, "errorClass": None if error is None else type(error).__name__[:160]})

    def set_stage(value):
        nonlocal stage
        stage = value

    def adb(*args, **kwargs):
        return bounded.run_command([str(adb_path), "-s", serial, *args], **kwargs)

    def write_report():
        if output is not None:
            output.seek(0); output.write(json.dumps(report, indent=2) + "\n"); output.truncate(); output.flush()

    try:
        directory = bounded.ensure_directory(directory)
        require(not any(directory.iterdir()), "Do not overwrite recording evidence")
        output = (directory / "recording.json").open("x", encoding="utf-8")
        write_report()
        stage = "device_preflight"
        _source(source)
        require(profile in PROFILES and type(api) is int and api == 35, "Recording is restricted to the API35 profiles")
        properties = {key: adb("shell", "getprop", key, timeout=5).strip() for key in
                      ("ro.kernel.qemu", "ro.boot.qemu", "ro.build.version.sdk", "ro.product.cpu.abi", "sys.boot_completed")}
        bounded.validate_device(serial, properties, 35)
        require(adb("shell", "sh", "-c", "'pidof screenrecord || true'", timeout=5).strip() == "", "Another recorder is active")
        remote = "/sdcard/agent-os-probe-" + uuid.uuid4().hex + ".mp4"
        adb("shell", "test", "!", "-e", remote, timeout=5)
        raw = directory / "raw.mp4"
        stage = "recording_start"
        launched = time.monotonic_ns()
        recorder = subprocess.Popen([str(adb_path), "-s", serial, "shell",
            "screenrecord --time-limit 120 --bit-rate 4000000 " + remote + " & p=$!; echo $p; wait $p"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        report["recorder"]["started"] = True
        reader = media.RecorderStderr(recorder.stderr)
        pid = _read_pid(recorder.stdout)
        time.sleep(0.4)
        require(recorder.poll() is None, "Recorder exited before the callback")
        report["coverage"]["recorderAliveBeforeCallback"] = True
    except Exception as error:
        failed(stage, error)

    callback_start = time.monotonic_ns()
    if recorder is not None:
        report["callbackStartedAfterRecorderMs"] = (callback_start - launched) // 1_000_000
    try:
        result = callback()
        report["callbackOutcome"] = "returned"
        return result
    except BaseException as error:
        report["callbackOutcome"] = "raised"
        report["callbackErrorClass"] = type(error).__name__[:160]
        raise
    finally:
        callback_end = time.monotonic_ns()
        report["callbackElapsedMs"] = (callback_end - callback_start) // 1_000_000
        if recorder is not None:
            report["callbackFinishedAfterRecorderMs"] = (callback_end - launched) // 1_000_000
        # All recorder work is optional. In particular no cleanup may replace the
        # callback's original exception or convert a failed probe into success.
        try:
            if recorder is not None:
                try:
                    report["coverage"]["recorderAliveAfterCallback"] = recorder.poll() is None
                    if report["coverage"]["recorderAliveAfterCallback"]:
                        time.sleep(0.4)
                    if not media.stop_recorder(recorder, pid, adb): failed("recording_stop")
                except Exception as error:
                    failed("recording_stop", error)
                report["recorder"]["exitCode"] = recorder.poll()
                if report["recorder"]["exitCode"] != 0: failed("recording_stop")
                if reader is None and recorder.stderr is not None: recorder.stderr.close()
            if reader is not None:
                try:
                    report["recorder"]["stderr"] = reader.finish()
                    if not report["recorder"]["stderr"]["complete"]: failed("recording_stderr")
                except Exception as error:
                    failed("recording_stderr", error)
            if recorder is not None and remote is not None:
                try:
                    stage = "raw_stat"
                    count = adb("shell", "stat", "-c", "%s", remote, timeout=5).strip()
                    require(count.isdigit() and 0 < int(count) < MAX_RAW, "Raw recording exceeds bound")
                    stage = "raw_pull"
                    adb("pull", remote, str(raw), timeout=20)
                    require(media.file_record(raw)["bytes"] == int(count), "Raw recording changed while collecting")
                    report["rawVideo"], report["video"] = media.compress(raw, directory / "demo.mp4",
                        stage_callback=set_stage, probe_callback=lambda probe: report["probes"].append({"stage": stage, **probe}))
                    report["recordingStatus"] = "recorded"
                except Exception as error:
                    failed(stage, error)
                finally:
                    if raw.exists() and not raw.is_symlink():
                        if report["video"] is None and 0 < raw.stat().st_size < MAX_RAW:
                            report["rawVideo"] = media.file_record(raw); report["rawRetained"] = True
                        else:
                            try: raw.unlink()
                            except OSError as error:
                                if 0 < raw.stat().st_size < MAX_RAW:
                                    report["rawRetained"] = True
                                failed("raw_cleanup", error)
                try: adb("shell", "rm", "-f", remote, timeout=5)
                except Exception as error: failed("remote_cleanup", error)
            video = report["video"]
            covered = (video is not None and report["coverage"]["recorderAliveBeforeCallback"]
                       and report["coverage"]["recorderAliveAfterCallback"]
                       and report["callbackElapsedMs"] <= TIME_LIMIT_SECONDS * 1000
                       and video["durationMs"] + COVERAGE_TOLERANCE_MS >= report["callbackFinishedAfterRecorderMs"])
            report["coverage"]["status"] = "covered" if covered else "incomplete" if video else "unavailable"
            if not covered: failed("coverage")
            report["status"] = "passed" if covered and not report["failures"] else "failed"
        except BaseException as error:
            failed(stage if stage in STAGES else "recording_stop", error)
            report["status"] = "failed"
        finally:
            try:
                write_report()
            except BaseException:
                # A full/unwritable filesystem cannot supply a report. Never
                # damage existing evidence or hide the probe's original outcome.
                try: print("Optional probe video report could not be written", file=sys.stderr)
                except BaseException: pass
            finally:
                if output is not None:
                    try: output.close()
                    except BaseException: pass
