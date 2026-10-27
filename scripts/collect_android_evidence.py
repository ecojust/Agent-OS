#!/usr/bin/env python3
"""Collect synthetic-device screenshots without changing instrumentation results."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import tempfile
import xml.etree.ElementTree as ET

MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 128 * 1024 * 1024
MAX_FILES = 2000
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
DIAGNOSTIC_SCOPE = "owned-control-diagnostic"
DIAGNOSTIC_TEST = "dev.agentos.app.OwnedControlWindowInstrumentedTest#changedPresentationAndCallbacksStillRevokeTheActualControlOwner"


def test_selection(scope: str) -> str:
    if scope not in ("full", DIAGNOSTIC_SCOPE):
        raise ValueError("unsupported execution scope")
    return DIAGNOSTIC_TEST if scope == DIAGNOSTIC_SCOPE else "full-suite"


def verify_capture_scope(record: dict, scope: str) -> None:
    # Old full-run captures remain readable. Missing metadata cannot identify a diagnostic.
    if (record.get("executionScope", "full") != scope
            or record.get("testSelection", "full-suite") != test_selection(scope)):
        raise ValueError("capture scope differs from the requested execution")


def limit_archive_output() -> None:
    # This collector runs on Linux CI. Bound adb's file output before extraction.
    import resource
    maximum = MAX_TOTAL_BYTES + 2 * 1024 * 1024
    resource.setrlimit(resource.RLIMIT_FSIZE, (maximum, maximum))


def junit_summary(directory: Path, selection: str | None = None) -> dict:
    result = {"reports": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0,
              "unreadableReports": 0}
    if selection is not None:
        result.update({"selection": selection, "selectedTests": 0, "unexpectedTests": 0})
    reports = sorted(directory.rglob("*.xml"))
    if selection is not None and len(reports) > 24:
        result["unreadableReports"] += 1  # Unread reports cannot silently certify the fixed selection.
    for path in reports[:24]:
        try:
            if path.stat().st_size > 2 * 1024 * 1024:
                raise ValueError("oversized XML")
            root = ET.parse(path).getroot()
            cases = list(root.iter("testcase"))
            result["reports"] += 1
            result["tests"] += len(cases)
            for case in cases:
                if selection is not None:
                    identity = str(case.get("classname", "")) + "#" + str(case.get("name", ""))
                    result["selectedTests" if identity == selection else "unexpectedTests"] += 1
                for tag, field in (("failure", "failures"), ("error", "errors"),
                                   ("skipped", "skipped")):
                    result[field] += int(case.find(tag) is not None)
        except (OSError, ValueError, ET.ParseError):
            result["unreadableReports"] += 1
    return result


def extract_evidence(archive: Path, output: Path) -> list[str]:
    copied = []
    size = 0
    with tarfile.open(archive) as tar:
        for member in tar:
            path = PurePosixPath(member.name)
            if member.isdir():
                continue
            if (not member.isfile() or path.is_absolute() or len(path.parts) < 2
                    or path.parts[0] != "screenshots"
                    or any(not re.fullmatch(r"[A-Za-z0-9_.-]+", part)
                           or part in (".", "..") for part in path.parts)
                    or path.suffix.lower() not in (".png", ".json")):
                raise ValueError("invalid screenshot archive entry")
            if member.size < 0 or member.size > MAX_FILE_BYTES:
                raise ValueError("oversized screenshot archive entry")
            size += member.size
            if size > MAX_TOTAL_BYTES or len(copied) >= MAX_FILES:
                raise ValueError("screenshot archive exceeds collection budget")
            stream = tar.extractfile(member)
            if stream is None:
                raise ValueError("unreadable screenshot archive entry")
            data = stream.read(MAX_FILE_BYTES + 1)
            if len(data) != member.size:
                raise ValueError("incomplete screenshot archive entry")
            if path.suffix.lower() == ".png" and not data.startswith(PNG_SIGNATURE):
                raise ValueError("invalid PNG signature")
            if path.suffix.lower() == ".json":
                json.loads(data)
            destination = output.joinpath(*path.parts)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
            copied.append(path.as_posix())
    return copied


def collect(api: int, matrix_label: str, exit_code: int, output: Path) -> dict:
    record = {"schemaVersion": 1, "apiLevel": api, "gradleExitCode": exit_code,
              "capturedAt": datetime.now(timezone.utc).isoformat(), "files": [],
              "captureStatus": "unavailable", "captureError": None,
              "matrixLabel": matrix_label, "actualSdkInt": None}
    try:
        device_api = subprocess.run(["adb", "shell", "getprop", "ro.build.version.sdk"],
                                    capture_output=True, text=True, timeout=5, check=True).stdout.strip()
        record["actualSdkInt"] = int(device_api)
        if record["actualSdkInt"] != api:
            raise ValueError("device SDK does not match requested matrix")
        with tempfile.TemporaryDirectory(prefix="android-evidence-") as temp:
            archive = Path(temp) / "screenshots.tar"
            with archive.open("wb") as destination:
                result = subprocess.run(
                    ["adb", "exec-out", "run-as", "dev.agentos.app", "tar", "-C", "files",
                     "-cf", "-", "screenshots"], stdout=destination, stderr=subprocess.DEVNULL,
                    timeout=30, check=False, preexec_fn=limit_archive_output)
            if result.returncode != 0:
                raise RuntimeError("device screenshot directory unavailable")
            if archive.stat().st_size > MAX_TOTAL_BYTES + 2 * 1024 * 1024:
                raise ValueError("screenshot archive exceeds collection budget")
            record["files"] = extract_evidence(archive, output)
            record["captureStatus"] = "collected" if record["files"] else "empty"
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, tarfile.TarError) as error:
        record["captureError"] = str(error)[:300]
    return record


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("collect", "finalize"))
    parser.add_argument("--api", type=int)
    parser.add_argument("--matrix-label")
    parser.add_argument("--exit-code", type=int, default=1)
    parser.add_argument("--step-outcome", default="unknown")
    parser.add_argument("--scope", choices=("full", DIAGNOSTIC_SCOPE), default="full")
    args = parser.parse_args()
    matrix_label = args.matrix_label or str(args.api)
    if not re.fullmatch(r"3[0-6]|37\.0", matrix_label):
        parser.error("unsupported matrix label")
    api = int(matrix_label.split(".")[0])
    if args.api is not None and args.api != api:
        parser.error("API value and matrix label differ")
    output = Path("test-evidence") / f"api-{matrix_label}"
    output.mkdir(parents=True, exist_ok=True)
    path = output / "capture.json"
    if args.mode == "collect":
        record = collect(api, matrix_label, args.exit_code, output)
    elif path.exists():
        record = json.loads(path.read_text())
        verify_capture_scope(record, args.scope)
    else:
        record = {"schemaVersion": 1, "apiLevel": api, "matrixLabel": matrix_label,
                  "actualSdkInt": None, "gradleExitCode": None,
                  "captureStatus": "not_collected", "captureError": "emulator script did not finish",
                  "capturedAt": datetime.now(timezone.utc).isoformat(), "files": []}
    if args.mode == "finalize":
        record["emulatorStepOutcome"] = args.step_outcome
    record.update({"executionScope": args.scope, "testSelection": test_selection(args.scope)})
    record["junit"] = junit_summary(Path("app/build/outputs/androidTest-results"),
                                    DIAGNOSTIC_TEST if args.scope == DIAGNOSTIC_SCOPE else None)
    record["screenshotCount"] = sum(name.endswith(".png") for name in record["files"])
    path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
    print(f"API {matrix_label}: {record['captureStatus']}, {record['screenshotCount']} screenshots; "
          f"Gradle exit={record['gradleExitCode']}")


if __name__ == "__main__":
    main()
