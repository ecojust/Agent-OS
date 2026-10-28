#!/usr/bin/env python3
"""Record one fixed synthetic API35 walkthrough; never remove secure flags or invent frames."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import subprocess
import time
import threading

import third_party_smoke as bounded
import third_party_smoke_archive as trusted

REPO = "ecojust/Agent-OS"
WORKFLOW = ".github/workflows/demo-video.yml"
TEST = "dev.agentos.app.DemoWalkthroughInstrumentedTest"
METHOD = "observedActionsRequireConfirmationAndRespectUserControls"
JOB = "record-demo (API 35)"
REMOTE = "/sdcard/agent-os-demo.mp4"
MAX_VIDEO = 100_000_000  # Strictly less; decimal bytes, not MiB.
MAX_TOTAL = 120_000_000
MAX_FILES = 128
MAX_JSON = 512 * 1024
STAGES = ("ready", "text_entered", "text_saved", "counter_verified", "scrolled_down",
          "scrolled_up", "second_page", "returned_to_fixture", "controls_waiting",
          "paused", "resumed", "stopped", "stale_approval_rejected")
require = bounded.require
MAX_RECORDER_STDERR = 8192
DIAGNOSTIC_STAGES = ("device_preflight", "recording_start", "recording_stop", "recording_stderr",
                     "instrumentation", "instrumentation_cleanup", "collect_flow", "collect_screenshots", "raw_stat", "raw_pull",
                     "raw_probe", "video_encode", "encoded_probe", "encoded_policy", "video_decode", "flow_coverage")


def failure_family(stage):
    if stage in ("raw_stat", "raw_pull", "raw_probe", "video_encode", "encoded_probe", "encoded_policy", "video_decode", "flow_coverage"):
        return "video_validation"
    if stage == "instrumentation_cleanup": return "instrumentation"
    return "recording_stop" if stage == "recording_stderr" else stage


class RecorderStderr:
    """Drain only this recorder's pipe; retain at most 8 KiB, never block its writer."""
    def __init__(self, stream):
        self.stream = stream; self.output = bytearray(); self.truncated = False; self.complete = False
        self.stop = threading.Event()
        os.set_blocking(stream.fileno(), False)
        self.thread = threading.Thread(target=self._drain, name="demo-recorder-stderr", daemon=True)
        self.thread.start()

    def _drain(self):
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(self.stream, selectors.EVENT_READ)
                while True:
                    events = selector.select(0.05)
                    if not events:
                        if self.stop.is_set(): return
                        continue
                    chunk = os.read(self.stream.fileno(), 4096)
                    if not chunk:
                        self.complete = True; return
                    available = MAX_RECORDER_STDERR - len(self.output)
                    self.output.extend(chunk[:available])
                    self.truncated |= len(chunk) > available
                    if self.stop.is_set(): return
        except (OSError, ValueError):
            self.complete = False
        finally:
            self.stream.close()

    def finish(self):
        self.thread.join(0.2)
        self.stop.set(); self.thread.join(0.2)
        require(not self.thread.is_alive(), "Recorder stderr did not stop within its bound")
        return {"text": bytes(self.output).decode("utf-8", "replace"),
                "truncated": self.truncated, "complete": self.complete}


def validate_diagnostics(value, source):
    version = value.get("schemaVersion")
    require(type(version) is int and version in (1, 2), "Unknown recording diagnostic version")
    exact(value, ("schemaVersion", "kind", "source", "failures", "recorder", "probes") if version == 2
          else ("schemaVersion", "kind", "source", "failures", "recorder"))
    require(value["kind"] == "agent-os-demo-recording-diagnostics" and matches_source(value["source"], source),
            "Foreign recording diagnostics")
    require(isinstance(value["failures"], list) and len(value["failures"]) <= 8, "Too many recording failures")
    for item in value["failures"]:
        exact(item, ("stage", "errorClass"))
        require(item["stage"] in DIAGNOSTIC_STAGES and (item["errorClass"] is None
                or isinstance(item["errorClass"], str) and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_.$]{0,159}", item["errorClass"])),
                "Unsafe recording failure diagnostic")
    recorder = value["recorder"]
    exact(recorder, ("started", "exitCode", "stderr"))
    require(type(recorder["started"]) is bool and (recorder["exitCode"] is None
            or type(recorder["exitCode"]) is int and -128 <= recorder["exitCode"] <= 255), "Invalid recorder result")
    if recorder["stderr"] is not None:
        stderr = recorder["stderr"]; exact(stderr, ("text", "truncated", "complete"))
        require(recorder["started"] and isinstance(stderr["text"], str)
                and len(stderr["text"].encode("utf-8")) <= MAX_RECORDER_STDERR * 3
                and type(stderr["truncated"]) is bool and type(stderr["complete"]) is bool, "Invalid recorder stderr")
    if version == 2:
        require(isinstance(value["probes"], list) and len(value["probes"]) <= 2, "Too many video probes")
        stages = []
        for probe in value["probes"]:
            exact(probe, ("stage", "status", "exitCode", "stdout"))
            require(probe["stage"] in ("raw_probe", "encoded_probe")
                    and probe["status"] in ("completed", "timed_out", "output_limit")
                    and (probe["exitCode"] is None or type(probe["exitCode"]) is int and -128 <= probe["exitCode"] <= 255)
                    and (probe["status"] != "completed" or probe["exitCode"] is not None)
                    and isinstance(probe["stdout"], str) and len(probe["stdout"].encode()) <= 196608, "Invalid bounded video probe")
            stages.append(probe["stage"])
        require(stages in ([], ["raw_probe"], ["raw_probe", "encoded_probe"]), "Unexpected video probe order")
    return value


def review_opt_in(env):
    value = env.get("DEMO_REVIEW_BRANCH", "false")
    require(value in ("true", "false"), "Unknown review-branch opt-in")
    return value == "true"


def workflow_branch(env, review_branch=False):
    require(type(review_branch) is bool, "Review-branch opt-in must be boolean")
    ref = env.get("GITHUB_REF", "")
    branch = ref.removeprefix("refs/heads/")
    agent_branch = re.fullmatch(r"agent/[A-Za-z0-9][A-Za-z0-9._/-]{0,179}", branch)
    require(ref == "refs/heads/master" or (review_branch and agent_branch
            and ref == "refs/heads/" + branch and ".." not in branch
            and all(part and not part.startswith(".") and not part.endswith((".", ".lock")) for part in branch.split("/"))),
            "Only master or an explicitly opted-in agent branch may record or archive")
    require(env.get("GITHUB_ACTIONS") == "true" and env.get("RUNNER_OS") == "Linux"
            and env.get("GITHUB_REPOSITORY") == REPO
            and env.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
            and env.get("GITHUB_WORKFLOW_REF") == REPO + "/" + WORKFLOW + "@" + ref,
            "Only the dedicated trusted workflow may record or archive a demo")
    return branch


def archive_options(env=None):
    env = os.environ if env is None else env
    return {"review_branch": review_opt_in(env), "expected_source_sha": env.get("DEMO_EXPECTED_SOURCE_SHA"), "env": env}


def identity(env=None):
    env = os.environ if env is None else env
    workflow_branch(env, review_opt_in(env))
    sha = env.get("GITHUB_SHA", "")
    require(re.fullmatch(r"[0-9a-f]{40}", sha), "Invalid source SHA")
    values = [env.get(key, "") for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT")]
    require(all(re.fullmatch(r"[1-9][0-9]{0,18}", value) for value in values), "Invalid run identity")
    return {"repository": REPO, "commit": sha, "runId": int(values[0]), "attempt": int(values[1])}


def exact(value, keys):
    require(isinstance(value, dict) and set(value) == set(keys), "Unexpected JSON fields")


def nonnegative(value):
    return type(value) is int and 0 <= value <= 2**63 - 1


def matches_source(value, source):
    return isinstance(value, dict) and value == source and type(value.get("runId")) is int and type(value.get("attempt")) is int


def validate_flow(value, source):
    exact(value, ("schemaVersion", "kind", "source", "device", "model", "startedAt", "finishedAt",
                  "startedElapsedMs", "finishedElapsedMs", "status", "stages", "failure"))
    require(type(value["schemaVersion"]) is int and value["schemaVersion"] == 1
            and value["kind"] == "agent-os-synthetic-controller-demo" and matches_source(value["source"], source),
            "Foreign flow identity")
    exact(value["device"], ("api", "emulator"))
    require(type(value["device"]["api"]) is int and value["device"]["api"] == 35
            and type(value["device"]["emulator"]) is bool
            and (value["status"] == "failed" or value["device"]["emulator"] is True),
            "Not the fixed emulator")
    require(value["model"] == {"kind": "local_synthetic_http", "externalModelUsed": False}
            and value["model"]["externalModelUsed"] is False, "Not the synthetic model profile")
    require(all(nonnegative(value[key]) for key in ("startedAt", "finishedAt", "startedElapsedMs", "finishedElapsedMs")), "Invalid flow time")
    elapsed = value["finishedElapsedMs"] - value["startedElapsedMs"]
    require(0 <= elapsed <= 120_000 and value["finishedAt"] >= value["startedAt"], "Flow exceeds bounded diagnostic interval")
    require(value["status"] in ("passed", "failed") and isinstance(value["stages"], list)
            and len(value["stages"]) <= 40, "Invalid flow status or stage count")
    completed = []; previous = -1; ordinal = 0
    for stage in value["stages"]:
        exact(stage, ("name", "status", "atElapsedMs", "wallTimeMs", "controllerRunning", "controllerPaused",
                      "pendingApproval", "requestCount", "dispatchAttempts", "approvalOrdinal"))
        require(stage["name"] in (*STAGES, "confirmation_waiting") and stage["status"] in ("passed", "failed"), "Unknown stage")
        require(all(type(stage[key]) is bool for key in ("controllerRunning", "controllerPaused", "pendingApproval")), "State is not boolean")
        require(all(nonnegative(stage[key]) for key in ("atElapsedMs", "wallTimeMs", "requestCount", "dispatchAttempts", "approvalOrdinal")), "Invalid stage counter")
        require(previous <= stage["atElapsedMs"] <= elapsed, "Stage time out of order")
        require(value["startedAt"] <= stage["wallTimeMs"] <= value["finishedAt"], "Stage wall time outside flow")
        previous = stage["atElapsedMs"]
        if stage["name"] == "confirmation_waiting":
            require((ordinal < stage["approvalOrdinal"] if stage["status"] == "passed" else ordinal <= stage["approvalOrdinal"])
                    and stage["approvalOrdinal"] <= 24, "Repeated or excessive approval ordinal")
            ordinal = stage["approvalOrdinal"]
        else:
            require(stage["approvalOrdinal"] == 0, "Non-approval stage has an ordinal")
            if stage["status"] == "passed": completed.append(stage["name"])
    if value["status"] == "passed":
        require(elapsed <= 90_000 and completed == list(STAGES) and value["failure"] is None
                and all(stage["status"] == "passed" for stage in value["stages"]), "Incomplete successful flow")
    else:
        exact(value["failure"], ("stage", "errorClass"))
        require(value["failure"]["stage"] in (*STAGES, "confirmation_waiting", "setup", "cleanup")
                and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_.$]{0,159}", str(value["failure"]["errorClass"])), "Unsafe failure detail")
    return value


def file_record(path):
    require(path.is_file() and not path.is_symlink(), "Missing or linked evidence")
    return {"bytes": path.stat().st_size, "sha256": bounded.sha256_file(path)}


def capture_video_probe(command, callback):
    """Bound both pipes of this fixed ffprobe command; retain only selected metadata stdout."""
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
    output = bytearray(); status = "completed"; used = 0; deadline = time.monotonic() + 15
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ, True)
            selector.register(process.stderr, selectors.EVENT_READ, False)
            while selector.get_map():
                if time.monotonic() >= deadline:
                    status = "timed_out"; break
                for key, _ in selector.select(min(0.1, max(0, deadline - time.monotonic()))):
                    chunk = os.read(key.fd, min(4096, 65536 - used + 1))
                    if not chunk:
                        selector.unregister(key.fileobj); continue
                    available = 65536 - used
                    if key.data: output.extend(chunk[:available])
                    used += len(chunk)
                    if used > 65536:
                        status = "output_limit"; break
                if status != "completed": break
        if status == "completed":
            try: process.wait(timeout=max(0.001, deadline - time.monotonic()))
            except subprocess.TimeoutExpired: status = "timed_out"
    finally:
        if process.poll() is None:
            process.kill()
            try: process.wait(timeout=1)
            except subprocess.TimeoutExpired: pass
        process.stdout.close(); process.stderr.close()
    evidence = {"status": status, "exitCode": process.poll(), "stdout": bytes(output).decode("utf-8", "replace")}
    if callback is not None: callback(evidence)
    require(status == "completed" and evidence["exitCode"] == 0, "Video probe did not complete successfully")
    return bytes(output)


def video_metadata(path, encoded=False, command=bounded.run_command, probe_callback=None):
    size = file_record(path)["bytes"]
    require(0 < size < (MAX_VIDEO if encoded else 70_000_000), "Video exceeds size budget")
    args = ["ffprobe", "-v", "error", "-show_entries",
            "stream=codec_type,codec_name,codec_tag_string,pix_fmt,width,height:format=duration", "-of", "json", str(path)]
    raw = capture_video_probe(args, probe_callback) if command is bounded.run_command else command(
        args, timeout=15, binary=True, limit=64 * 1024)
    return {**file_record(path), **parse_video_probe(raw, encoded)}


def parse_video_probe(raw, encoded):
    probe = bounded.parse_json(raw); streams = probe.get("streams", [])
    require(isinstance(streams, list) and all(isinstance(item, dict) for item in streams), "Invalid video streams")
    videos = [item for item in streams if item.get("codec_type") == "video"]
    require(len(videos) == 1, "Expected exactly one video stream")
    if encoded:
        require(len(streams) == 1, "Output must contain only one silent video stream")
    else:
        # API35 screenrecord writes two optional Winscope metadata tracks (MP4 mett).
        # They carry timing, not sound/picture. The encoder explicitly maps only 0:v:0.
        metadata = [item for item in streams if item.get("codec_type") != "video"]
        require(len(metadata) <= 2 and all(item.get("codec_type") == "data"
                and item.get("codec_tag_string") == "mett" for item in metadata), "Unexpected raw recording track")
    stream = videos[0]
    require(all(type(stream.get(key)) is int and 64 <= stream[key] <= 4096 for key in ("width", "height")), "Invalid video dimensions")
    duration = str(probe.get("format", {}).get("duration", ""))
    require(re.fullmatch(r"[0-9]{1,3}(\.[0-9]{1,6})?", duration), "Invalid duration")
    duration_ms = round(float(duration) * 1000)
    require(500 <= duration_ms <= 125_000, "Recording duration outside budget")
    require(stream.get("codec_name") == "h264", "Recording is not H.264")
    if encoded:
        require(stream.get("codec_name") == "h264" and stream.get("pix_fmt") == "yuv420p"
                and stream["width"] % 2 == 0 and stream["height"] % 2 == 0, "Output is not portable H.264/yuv420p")
    return {"durationMs": duration_ms, "width": stream["width"], "height": stream["height"],
            "codec": stream.get("codec_name"), "pixelFormat": stream.get("pix_fmt")}


def compress(raw, output, command=bounded.run_command, stage_callback=lambda stage: None, probe_callback=None):
    require(not output.exists() and not output.is_symlink(), "Do not overwrite evidence")
    stage_callback("raw_probe")
    original = video_metadata(raw, command=command, probe_callback=probe_callback)
    try:
        stage_callback("video_encode")
        command(["ffmpeg", "-nostdin", "-v", "error", "-i", str(raw), "-map", "0:v:0", "-an",
                 "-vf", "scale=720:1280:force_original_aspect_ratio=decrease:force_divisible_by=2",
                 "-c:v", "libx264", "-preset", "medium", "-crf", "24", "-maxrate", "2500k", "-bufsize", "5000k",
                 "-pix_fmt", "yuv420p", "-map_metadata", "-1", "-movflags", "+faststart", str(output)], timeout=180, limit=64 * 1024)
        stage_callback("encoded_probe")
        encoded = video_metadata(output, True, command, probe_callback=probe_callback)
        stage_callback("encoded_policy")
        require(encoded["bytes"] <= 95_000_000 and abs(encoded["durationMs"] - original["durationMs"]) <= 250,
                "Encoding exceeded target or changed duration")
        stage_callback("video_decode")
        command(["ffmpeg", "-nostdin", "-v", "error", "-xerror", "-i", str(output), "-map", "0:v:0", "-f", "null", "-"], timeout=90, limit=64 * 1024)
        return original, encoded
    except Exception:
        if output.exists() and not output.is_symlink(): output.unlink()
        raise


def prepare(directory, source):
    directory = bounded.ensure_directory(directory)
    require(not (directory / "report.json").exists(), "Run evidence already exists")
    report = {"schemaVersion": 2, "kind": "agent-os-demo-video", "source": source, "apiLevel": 35,
              "status": "unverified", "recordingStatus": "not_recorded", "flowStatus": "unavailable",
              "instrumentationExitCode": None, "instrumentationPassed": False, "failureStage": "not_started",
              "secureFlagsPreserved": True, "externalModelUsed": False, "rawVideo": None, "video": None,
              "flow": None, "screenshots": None, "screenshotsStatus": "not_collected", "instrumentation": None, "diagnostics": None}
    write_report(directory, report); return report


def write_report(directory, report):
    path = directory / "report.json"
    require(not path.is_symlink(), "Linked report")
    path.write_text(json.dumps(report, indent=2) + "\n")


def instrument_passed(text, code):
    return code == 0 and re.search(r"\bOK \(1 tests?\)", text) is not None and not any(
        token in text for token in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "shortMsg=", "Process crashed"))


def instrument_case_passed(value):
    exact(value, ("schemaVersion", "runner", "selectedClass", "selectedMethod", "expectedTests", "status", "exitCode", "stdout"))
    require(value["schemaVersion"] == 1 and type(value["schemaVersion"]) is int and value["runner"] == "direct_adb_instrumentation"
            and value["selectedClass"] == TEST and value["selectedMethod"] == METHOD
            and type(value["expectedTests"]) is int and value["expectedTests"] == 1
            and value["status"] in ("completed", "timed_out", "output_limit", "cancelled")
            and (value["exitCode"] is None or type(value["exitCode"]) is int and -128 <= value["exitCode"] <= 255)
            and isinstance(value["stdout"], str) and len(value["stdout"].encode()) <= 196608, "Invalid instrumentation evidence")
    text = value["stdout"]
    return (value["status"] == "completed" and instrument_passed(text, value["exitCode"])
            and set(re.findall(r"^INSTRUMENTATION_STATUS: class=(.+)$", text, re.M)) == {TEST}
            and set(re.findall(r"^INSTRUMENTATION_STATUS: test=(.+)$", text, re.M)) == {METHOD}
            and set(re.findall(r"^INSTRUMENTATION_STATUS: numtests=(.+)$", text, re.M)) == {"1"})


def stop_recorder(recorder, pid, adb):
    """Stop only this launch's child; never killall/pkill other device processes."""
    # The 120s recorder must still be alive after the <=105s instrumentation.
    # A decodable short file alone does not prove coverage of the final actions.
    stopped = recorder.poll() is None
    try:
        if stopped:
            if pid is None: stopped = False
            else:
                try: adb("shell", "kill", "-2", pid, timeout=5)
                except Exception: stopped = False
        try: recorder.wait(timeout=12)
        except subprocess.TimeoutExpired:
            stopped = False; recorder.kill()
            try: recorder.wait(timeout=2)
            except subprocess.TimeoutExpired: pass
    finally:
        if recorder.stdout: recorder.stdout.close()
    return stopped


def record(directory, source):
    report = bounded.read_json(directory / "report.json")
    require(report["source"] == source and report["status"] == "unverified", "Foreign or reused report")
    serial = None; recorder = None; recorder_pid = None; stderr_reader = None
    raw = directory / "raw.mp4"; stage = "device_preflight"
    diagnostics = {"schemaVersion": 2, "kind": "agent-os-demo-recording-diagnostics", "source": source,
                   "failures": [], "probes": [], "recorder": {"started": False, "exitCode": None, "stderr": None}}
    def set_stage(value):
        nonlocal stage
        stage = value
    def probed(value):
        diagnostics["probes"].append({"stage": stage, **value})
    def failed(value, error=None, error_class=None):
        if len(diagnostics["failures"]) < 8:
            diagnostics["failures"].append({"stage": value, "errorClass": error_class if error is None else type(error).__name__})
        if report["failureStage"] in (None, "not_started"):
            report["failureStage"] = failure_family(value)
    def adb(*args, **kwargs):
        return bounded.run_command(["adb", "-s", serial, *args], **kwargs)
    try:
        serial = bounded.select_emulator(bounded.run_command(["adb", "devices"], timeout=5))
        properties = {key: adb("shell", "getprop", key, timeout=5).strip() for key in
                      ("ro.kernel.qemu", "ro.boot.qemu", "ro.build.version.sdk", "ro.product.cpu.abi", "sys.boot_completed")}
        bounded.validate_device(serial, properties, 35)
        # Fresh dedicated install only; do not clear an existing user's application.
        adb("shell", "run-as", "dev.agentos.app", "sh", "-c",
            "'test ! -d files || test -z \"$(ls -A files)\"'", timeout=5)
        require(adb("shell", "sh", "-c", "'pidof screenrecord || true'", timeout=5).strip() == "", "Another recorder is active")
        adb("shell", "rm", "-f", REMOTE, timeout=5)
        stage = "recording_start"
        recorder = subprocess.Popen(["adb", "-s", serial, "shell",
            "screenrecord --time-limit 120 --bit-rate 4000000 " + REMOTE + " & p=$!; echo $p; wait $p"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        diagnostics["recorder"]["started"] = True
        stderr_reader = RecorderStderr(recorder.stderr)
        with selectors.DefaultSelector() as wait:
            wait.register(recorder.stdout, selectors.EVENT_READ)
            require(wait.select(8), "Recorder did not report ownership")
            pid = recorder.stdout.readline(32).decode().strip()
        require(re.fullmatch(r"[1-9][0-9]{0,7}", pid), "Invalid recorder PID")
        recorder_pid = pid
        time.sleep(0.4)
        require(recorder.poll() is None, "Recorder exited before the walkthrough")
        stage = "instrumentation"
        command = ["adb", "-s", serial, "shell", "am", "instrument", "-w", "-r"]
        args = {"class": TEST + "#" + METHOD, "demoMarker": "agent-os-real-demo-v1", "expectedApi": "35",
                "sourceSha": source["commit"], "runId": str(source["runId"]), "runAttempt": str(source["attempt"])}
        for key, value in args.items(): command.extend(["-e", key, value])
        command.append("dev.agentos.app.test/androidx.test.runner.AndroidJUnitRunner")
        from run_bounded_instrumentation import bounded_capture
        output, outcome = bounded_capture(command, 105, 64 * 1024)
        evidence = {"schemaVersion": 1, "runner": "direct_adb_instrumentation", "selectedClass": TEST,
                    "selectedMethod": METHOD, "expectedTests": 1, "status": outcome["status"],
                    "exitCode": outcome["exitCode"], "stdout": output.decode("utf-8", "replace").replace("\r\n", "\n")}
        report["instrumentationExitCode"] = evidence["exitCode"]
        report["instrumentationPassed"] = instrument_case_passed(evidence)
        log = directory / "instrumentation.json"; log.write_text(json.dumps(evidence, indent=2) + "\n")
        report["instrumentation"] = file_record(log)
        if report["instrumentationPassed"]: report["failureStage"] = None
        else:
            classes = re.findall(r"^INSTRUMENTATION_STATUS: stack=([A-Za-z_$][A-Za-z0-9_.$]{0,159})(?=:|$)", evidence["stdout"], re.M)
            failed("instrumentation", error_class=classes[0] if classes else None)
            # No inferred exception class when the original runner supplied none.
        if outcome["status"] != "completed":
            stage = "instrumentation_cleanup"
            adb("shell", "am", "force-stop", "dev.agentos.app", timeout=5)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        failed(stage, error)
    finally:
        if recorder is not None:
            try:
                if not stop_recorder(recorder, recorder_pid, adb): failed("recording_stop")
            except (ValueError, OSError, subprocess.SubprocessError) as error:
                failed("recording_stop", error)
            diagnostics["recorder"]["exitCode"] = recorder.poll()
            if stderr_reader is None and recorder.stderr is not None:
                recorder.stderr.close()
        if stderr_reader is not None:
            try:
                diagnostics["recorder"]["stderr"] = stderr_reader.finish()
                if not diagnostics["recorder"]["stderr"]["complete"]: failed("recording_stderr")
            except (ValueError, OSError) as error: failed("recording_stderr", error)
        if serial:
            try:
                content = adb("exec-out", "run-as", "dev.agentos.app", "cat", "files/demo-video/flow-result.json",
                              timeout=5, binary=True, limit=MAX_JSON)
                flow = validate_flow(bounded.parse_json(content), source)
                (directory / "flow-result.json").write_bytes(content)
                report["flow"] = file_record(directory / "flow-result.json"); report["flowStatus"] = flow["status"]
            except Exception as error:
                failed("collect_flow", error)
            try:
                from collect_android_evidence import collect
                captures = collect(35, "35", 0 if report["instrumentationPassed"] else 1, directory)
                captures["gradleExitCode"] = None; captures["runnerKind"] = "direct_adb_instrumentation"
                captures["junitStatus"] = "not_produced_direct_adb"
                (directory / "capture.json").write_text(json.dumps(captures, indent=2) + "\n")
                report["screenshots"] = file_record(directory / "capture.json")
                report["screenshotsStatus"] = captures["captureStatus"]
            except Exception as error:
                failed("collect_screenshots", error)
            try:
                stage = "raw_stat"
                remote_bytes = adb("shell", "stat", "-c", "%s", REMOTE, timeout=5).strip()
                require(remote_bytes.isdigit() and 0 < int(remote_bytes) < 70_000_000, "Raw recording out of bounds")
                stage = "raw_pull"
                adb("pull", REMOTE, str(raw), timeout=20)
                report["rawVideo"], report["video"] = compress(raw, directory / "demo.mp4", stage_callback=set_stage, probe_callback=probed)
                if report["flow"]:
                    stage = "flow_coverage"
                    require(report["video"]["durationMs"] + 1500 >= flow["finishedElapsedMs"] - flow["startedElapsedMs"],
                            "Recording ended before the flow")
                report["recordingStatus"] = "recorded"
            except Exception as error:
                report["recordingStatus"] = "unavailable"; failed(stage, error)
                report["video"] = None
                failed_video = directory / "demo.mp4"
                if failed_video.exists() and not failed_video.is_symlink(): failed_video.unlink()
            finally:
                if raw.exists() and not raw.is_symlink(): raw.unlink()
                try: adb("shell", "rm", "-f", REMOTE, timeout=5)
                except Exception: pass
                try: adb("shell", "am", "force-stop", "dev.agentos.app", timeout=5)
                except Exception: pass
        report["status"] = "passed" if report["failureStage"] is None and report["instrumentationPassed"] and report["flowStatus"] == "passed" and report["recordingStatus"] == "recorded" and report["screenshotsStatus"] == "collected" else "failed"
        if report["status"] == "passed": report["failureStage"] = None
        validate_diagnostics(diagnostics, source)
        diagnostic_file = directory / "recording-diagnostics.json"
        diagnostic_file.write_text(json.dumps(diagnostics, indent=2) + "\n")
        report["diagnostics"] = file_record(diagnostic_file)
        write_report(directory, report)
    return report["status"] == "passed"


def source_run(run, jobs, *, review_branch=False, expected_source_sha=None, env=None):
    require(type(review_branch) is bool, "Review-branch opt-in must be boolean")
    branch = "master"
    if review_branch:
        branch = workflow_branch(os.environ if env is None else env, True)
        require(branch.startswith("agent/"), "Review archive requires the current agent branch")
        require(isinstance(expected_source_sha, str) and re.fullmatch(r"[0-9a-f]{40}", expected_source_sha),
                "Review archive requires an exact expected source SHA")
    if expected_source_sha not in (None, ""):
        require(isinstance(expected_source_sha, str) and re.fullmatch(r"[0-9a-f]{40}", expected_source_sha)
                and run.get("head_sha") == expected_source_sha, "Unexpected recording source SHA")
    require(run.get("repository", {}).get("full_name") == run.get("head_repository", {}).get("full_name") == REPO
            and trusted.positive_int(run.get("repository", {}).get("id"))
            and trusted.positive_int(run.get("head_repository", {}).get("id"))
            and run["repository"]["id"] == run["head_repository"].get("id"), "Foreign repository")
    require(run.get("event") == "workflow_dispatch" and run.get("head_branch") == branch
            and run.get("path") == WORKFLOW and run.get("status") == "completed"
            and re.fullmatch(r"[0-9a-f]{40}", str(run.get("head_sha", "")))
            and trusted.positive_int(run.get("id")) and trusted.positive_int(run.get("run_attempt")), "Untrusted source run")
    pages = jobs if isinstance(jobs, list) else [jobs]
    require(len(pages) <= 4, "Too many job pages")
    entries = [job for page in pages for job in page.get("jobs", [])]
    require(len(entries) <= 16, "Too many jobs")
    matches = [job for job in entries if job.get("name") == JOB]
    require(len(matches) == 1, "Missing or duplicate recording job")
    job = matches[0]
    require(job.get("status") == "completed" and job.get("conclusion") in (
                "success", "failure", "cancelled", "timed_out", "neutral", "action_required", "startup_failure", "stale")
            and trusted.positive_int(job.get("run_id")) and trusted.positive_int(job.get("run_attempt"))
            and job.get("run_id") == run["id"] and job.get("run_attempt") == run["run_attempt"]
            and job.get("head_sha") == run["head_sha"], "Not a completed test-mode source job")
    require(trusted.timestamp(job["started_at"]) <= trusted.timestamp(job["completed_at"]), "Invalid job times")
    return {"repository": REPO, "commit": run["head_sha"], "runId": run["id"], "attempt": run["run_attempt"]}, job


def select_artifact(run, jobs, index, **scope):
    source, job = source_run(run, jobs, **scope)
    name = "demo-video-api-35-attempt-" + str(source["attempt"])
    matches = [a for a in trusted.artifact_entries(index) if a.get("name") == name]
    require(len(matches) <= 1, "Duplicate demo artifact")
    if not matches: return source, job, None
    artifact = matches[0]; origin = artifact.get("workflow_run", {})
    require(artifact.get("expired") is False and trusted.positive_int(artifact.get("id"))
            and trusted.positive_int(artifact.get("size_in_bytes")) and artifact["size_in_bytes"] <= 130_000_000
            and all(trusted.positive_int(origin.get(key)) for key in ("id", "repository_id", "head_repository_id"))
            and origin.get("id") == source["runId"] and origin.get("head_sha") == source["commit"]
            and origin.get("head_branch") == run["head_branch"]
            and origin.get("repository_id") == origin.get("head_repository_id") == run["repository"]["id"], "Artifact provenance mismatch")
    require(trusted.timestamp(job["started_at"]) <= trusted.timestamp(artifact["created_at"]) <= trusted.timestamp(job["completed_at"]),
            "Artifact is outside its job attempt")
    return source, job, artifact


def declared_video_metadata(value, limit):
    """Validate a source declaration without claiming these pixels were decoded here."""
    exact(value, ("bytes", "sha256", "durationMs", "width", "height", "codec", "pixelFormat"))
    require(nonnegative(value["bytes"]) and 0 < value["bytes"] < limit
            and re.fullmatch(r"[0-9a-f]{64}", str(value["sha256"]))
            and all(nonnegative(value[k]) for k in ("durationMs", "width", "height"))
            and 500 <= value["durationMs"] <= 125_000
            and all(64 <= value[k] <= 4096 and value[k] % 2 == 0 for k in ("width", "height"))
            and value["codec"] == "h264" and value["pixelFormat"] == "yuv420p", "Invalid declared video metadata")


def inspect_bundle(directory, source, failed_source=False):
    require(directory.is_dir() and not directory.is_symlink(), "Missing bundle")
    files = []; total = 0
    for path in sorted(directory.rglob("*")):
        require(not path.is_symlink(), "Linked evidence")
        relative = path.relative_to(directory)
        if path.is_dir():
            require(relative.parts == ("screenshots",), "Unexpected evidence directory")
            continue
        require(path.is_file() and (relative.as_posix() in ("report.json", "capture.json", "flow-result.json", "demo.mp4", "instrumentation.json", "recording-diagnostics.json")
                or len(relative.parts) == 2 and relative.parts[0] == "screenshots"
                and re.fullmatch(r"[A-Za-z0-9_.-]+\.(png|json)", path.name)), "Unexpected evidence file")
        entry = {"path": relative.as_posix(), **file_record(path)}
        total += entry["bytes"]; files.append(entry)
        require(len(files) <= MAX_FILES and total <= MAX_TOTAL, "Bundle exceeds budget")
        if path.suffix == ".json": bounded.read_json(path)
        elif path.suffix == ".png":
            require(entry["bytes"] <= 16 * 1024 * 1024, "PNG too large")
            with path.open("rb") as stream: header = stream.read(24)
            require(len(header) == 24 and header[:8] == b"\x89PNG\r\n\x1a\n" and header[12:16] == b"IHDR"
                    and all(0 < int.from_bytes(header[start:start + 4], "big") <= 4096 for start in (16, 20)), "Invalid PNG")
        else: require(entry["bytes"] < MAX_VIDEO, "Video must be strictly under 100 MB")
    report = bounded.read_json(directory / "report.json")
    require(type(report.get("schemaVersion")) is int and report["schemaVersion"] in (1, 2), "Unknown report version")
    expected = set(prepare_schema_keys(report["schemaVersion"]))
    exact(report, expected)
    require(report["kind"] == "agent-os-demo-video" and matches_source(report["source"], source)
            and type(report["apiLevel"]) is int and report["apiLevel"] == 35
            and report["secureFlagsPreserved"] is True and report["externalModelUsed"] is False,
            "Foreign or unsafe recording report")
    require(report["status"] in ("passed", "failed", "unverified")
            and report["recordingStatus"] in ("not_recorded", "recorded", "unavailable")
            and report["flowStatus"] in ("unavailable", "passed", "failed")
            and report["screenshotsStatus"] in ("not_collected", "collected", "empty", "unavailable"), "Unknown report status")
    require(type(report["instrumentationPassed"]) is bool and (report["instrumentationExitCode"] is None
            or type(report["instrumentationExitCode"]) is int and -128 <= report["instrumentationExitCode"] <= 255), "Invalid instrumentation result")
    require(report["failureStage"] in (None, "not_started", "device_preflight", "recording_start", "instrumentation",
            "recording_stop", "collect_flow", "collect_screenshots", "video_validation", "environment"), "Unsafe failure field")
    require(not failed_source or report["status"] == "failed", "Raw preservation requires an explicitly failed report")
    flow = None
    for key, name in (("flow", "flow-result.json"), ("screenshots", "capture.json"), ("instrumentation", "instrumentation.json"),
                      ("diagnostics", "recording-diagnostics.json")):
        path = directory / name
        require((report.get(key) is not None) == path.exists(), "Report/file presence mismatch")
        if report.get(key) is not None: require(report[key] == file_record(path), "Report/file hash mismatch")
    if report.get("diagnostics"):
        diagnostics = validate_diagnostics(bounded.read_json(directory / "recording-diagnostics.json"), source)
        if diagnostics["failures"]:
            require(report["status"] == "failed" and report["failureStage"] == failure_family(diagnostics["failures"][0]["stage"]),
                    "First recording failure was replaced")
        if report["status"] == "passed":
            require(not diagnostics["failures"] and diagnostics["recorder"]["started"]
                    and diagnostics["recorder"]["stderr"] is not None
                    and diagnostics["recorder"]["stderr"]["complete"] is True
                    and diagnostics["recorder"]["exitCode"] is not None, "Missing successful recorder diagnostics")
            if diagnostics["schemaVersion"] == 2:
                probes = diagnostics["probes"]
                require(len(probes) == 2 and all(item["status"] == "completed" and item["exitCode"] == 0 for item in probes),
                        "Missing successful video probe evidence")
                for probe, key, encoded in zip(probes, ("rawVideo", "video"), (False, True)):
                    fields = parse_video_probe(probe["stdout"].encode(), encoded)
                    require(isinstance(report[key], dict) and fields == {name: report[key].get(name) for name in fields},
                            "Video probe metadata disagrees with the report")
    if report["instrumentation"]:
        instrument = bounded.read_json(directory / "instrumentation.json")
        require(instrument_case_passed(instrument) == report["instrumentationPassed"]
                and instrument["exitCode"] == report["instrumentationExitCode"], "Instrumentation result mismatch")
    else: require(not report["instrumentationPassed"] and report["instrumentationExitCode"] is None, "Missing original instrumentation result")
    if report["flow"]:
        flow = validate_flow(bounded.read_json(directory / "flow-result.json"), source)
        require(flow["status"] == report["flowStatus"], "Flow result mismatch")
    if report["rawVideo"] is not None:
        raw = report["rawVideo"]
        exact(raw, ("bytes", "sha256", "durationMs", "width", "height", "codec", "pixelFormat"))
        require(nonnegative(raw["bytes"]) and 0 < raw["bytes"] < 70_000_000
                and re.fullmatch(r"[0-9a-f]{64}", str(raw["sha256"]))
                and all(nonnegative(raw[k]) for k in ("durationMs", "width", "height"))
                and 500 <= raw["durationMs"] <= 125_000 and all(64 <= raw[k] <= 4096 for k in ("width", "height"))
                and raw["codec"] == "h264" and raw["pixelFormat"] == "yuv420p", "Invalid raw recording metadata")
    video = directory / "demo.mp4"
    require((report["video"] is not None) == video.exists(), "Video presence mismatch")
    if report["video"]:
        declared_video_metadata(report["video"], MAX_VIDEO)
        require(file_record(video) == {key: report["video"][key] for key in ("bytes", "sha256")}
                and report["recordingStatus"] == "recorded" and report["rawVideo"] is not None
                and abs(report["rawVideo"]["durationMs"] - report["video"]["durationMs"]) <= 250, "Video declaration/hash mismatch")
        if not failed_source:
            require(video_metadata(video, True) == report["video"], "Video metadata mismatch")
    test = None
    if report["screenshots"]:
        capture = bounded.read_json(directory / "capture.json")
        require(capture.get("apiLevel") == capture.get("actualSdkInt") == 35
                and capture.get("runnerKind") == "direct_adb_instrumentation" and capture.get("gradleExitCode") is None
                and capture.get("junitStatus") == "not_produced_direct_adb"
                and capture.get("captureStatus") == report["screenshotsStatus"], "Screenshot collection mismatch")
        if report["screenshotsStatus"] == "collected":
            shots = bounded.read_json(directory / "screenshots" / "manifest.json")
            require(len(shots.get("tests", [])) == 1, "Wrong demo test count")
            test = shots["tests"][0]
            require(test.get("testClass") == TEST and test.get("testName") == METHOD
                    and test.get("testResult") in ("pass", "fail"), "Unexpected screenshot case")
            captures = test.get("captures", [])
            require(isinstance(captures, list) and len(captures) <= 50
                    and all(c.get("secureWindowTemporarilyCleared") is not True for c in captures), "Invalid or unsafe stage captures")
            pngs = {"screenshots/" + c["png"] for c in captures if c.get("status") == "pass"}
            require(pngs == {f["path"] for f in files if f["path"].endswith(".png")}, "Unreferenced or missing screenshot PNG")
            case_files = [f for f in files if f["path"].startswith("screenshots/") and f["path"].endswith(".json")
                          and f["path"] != "screenshots/manifest.json"]
            require(len(case_files) == 1 and bounded.read_json(directory / case_files[0]["path"]) == test, "Individual screenshot record mismatch")
    if report["status"] == "passed":
        require(report["schemaVersion"] == 1 or report["diagnostics"] is not None, "Missing recording diagnostics")
        require(report["instrumentationPassed"] and report["instrumentationExitCode"] == 0
                and report["flowStatus"] == "passed" and report["video"] and report["screenshotsStatus"] == "collected"
                and report["failureStage"] is None, "Cannot promote incomplete evidence")
        require(test is not None and test.get("testResult") == "pass", "Demo case did not pass")
        require(set(STAGES) <= {c.get("stage") for c in test.get("captures", [])}, "Missing actual stage capture records")
        require(all(c.get("secureWindowTemporarilyCleared") is not True for c in test.get("captures", [])), "Secure flags were changed")
        require(report["video"]["durationMs"] + 1500 >= flow["finishedElapsedMs"] - flow["startedElapsedMs"], "Video did not cover the flow")
        bounded.run_command(["ffmpeg", "-nostdin", "-v", "error", "-xerror", "-i", str(video), "-map", "0:v:0", "-f", "null", "-"], timeout=90, limit=64 * 1024)
    return report, files


def prepare_schema_keys(version=2):
    base = ("schemaVersion", "kind", "source", "apiLevel", "status", "recordingStatus", "flowStatus",
            "instrumentationExitCode", "instrumentationPassed", "failureStage", "secureFlagsPreserved",
            "externalModelUsed", "rawVideo", "video", "flow", "screenshots", "screenshotsStatus", "instrumentation")
    return base if version == 1 else (*base, "diagnostics")


def archive(args):
    run = trusted.read_json(args.run); jobs = trusted.read_json(args.jobs)
    source, job, artifact = select_artifact(run, jobs, trusted.read_json(args.artifacts), **archive_options())
    relative = Path("screenshots/demos") / f"{source['runId']}-{source['attempt']}"
    require(not relative.exists(), "Never overwrite an archived attempt")
    manifest = {"schemaVersion": 1, "kind": "agent-os-demo-video-archive", "source": source, "sourceBranch": run["head_branch"],
                "sourceRunUrl": f"https://github.com/{REPO}/actions/runs/{source['runId']}",
                "jobConclusion": job["conclusion"], "apiLevel": 35, "status": "unavailable", "files": [],
                "scope": "Dedicated emulator; preconfigured app; local synthetic HTTP model; secure flags preserved; no real-model or phone acceptance."}
    if artifact:
        failed_source = job["conclusion"] == "failure"
        report, files = inspect_bundle(args.incoming, source, failed_source=failed_source)
        manifest.update(status="passed" if report["status"] == "passed" and job["conclusion"] == "success" else "failed",
                        artifactId=artifact["id"], files=files, video=report["video"], flowStatus=report["flowStatus"],
                        videoValidation="not_revalidated" if failed_source else "full_decode" if report["status"] == "passed" else "metadata_only")
        shutil.copytree(args.incoming, relative)
    else: relative.mkdir(parents=True)
    (relative / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    if manifest["status"] == "passed":
        progress = Path("docs/progress.json"); value = trusted.read_json(progress)
        value["latest_demo"] = {"path": relative.as_posix(), "run_url": manifest["sourceRunUrl"],
                                "source_sha": source["commit"], "status": "passed"}
        progress.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"path": relative.as_posix(), "status": manifest["status"]}))


def main():
    parser = argparse.ArgumentParser(description=__doc__); commands = parser.add_subparsers(dest="mode", required=True)
    for mode in ("prepare", "record", "finalize"):
        child = commands.add_parser(mode); child.add_argument("--output", type=Path, required=True)
        if mode == "finalize": child.add_argument("--outcome", choices=("success", "failure", "cancelled", "skipped", "unknown"), required=True)
    for mode in ("select", "archive"):
        child = commands.add_parser(mode)
        for name in ("run", "jobs", "artifacts"): child.add_argument("--" + name, type=Path, required=True)
        if mode == "select": child.add_argument("--github-output", type=Path, required=True)
        else: child.add_argument("--incoming", type=Path, required=True)
    args = parser.parse_args()
    if args.mode in ("prepare", "record", "finalize"):
        source = identity(); directory = bounded.ensure_directory(args.output)
        if args.mode == "prepare": prepare(directory, source)
        elif args.mode == "record": return 0 if record(directory, source) else 1
        else:
            report = bounded.read_json(directory / "report.json"); require(report["source"] == source, "Foreign finalization")
            if args.outcome != "success":
                report["status"] = "failed"
                if report["failureStage"] in (None, "not_started"): report["failureStage"] = "environment"
            write_report(directory, report)
    elif args.mode == "select":
        source, job, artifact = select_artifact(trusted.read_json(args.run), trusted.read_json(args.jobs),
                trusted.read_json(args.artifacts), **archive_options())
        with args.github_output.open("a") as stream:
            stream.write(f"artifact_id={artifact['id'] if artifact else ''}\nsource_run={source['runId']}\nattempt={source['attempt']}\nfailed_source={'true' if job['conclusion'] == 'failure' else 'false'}\n")
    else: archive(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
