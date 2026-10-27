#!/usr/bin/env python3
"""Inspect a completed trusted Android job without executing downloaded content."""

import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import sys
import tempfile
import time
from collections import deque
import xml.etree.ElementTree as ET


LABELS = tuple(str(api) for api in range(30, 37)) + ("37.0",)
MAX_ARTIFACT_BYTES = 64 * 1024 * 1024
MAX_FILES = 2000
MAX_XML_FILES = 24
MAX_XML_BYTES = 2 * 1024 * 1024
MAX_PRINT_CHARS = 64000
MAX_IME_CHARS = 20000
MAX_IME_FAILURE_CHARS = 6000
MAX_LOG_BYTES = 32 * 1024 * 1024
MAX_LOG_SECONDS = 60
MAX_LOG_LINE_BYTES = 4096
LOG_PROGRESS_CHARS = MAX_PRINT_CHARS - 1024
LOG_TASKS = frozenset((":fixture:installDebug", ":app:connectedDebugAndroidTest",
                       ":app:connectedAndroidTest", ":app:assembleDebug",
                       ":app:assembleDebugAndroidTest", ":app:installDebug"))
IME_SCALARS = re.compile(
    r"\b(mShowRequested|mShowExplicitlyRequested|mShowForced|mInputShown|"
    r"mWindowVisible|mDecorViewVisible|mShowInputRequested|mInputViewStarted|"
    r"mInputViewShown|mIsInputViewShown|mImeWindowVis|mInputStarted|"
    r"mIsHardwareKeyboardAvailable|mImeHiddenByDisplayPolicy|mVisibility|"
    r"mReportedVisibility|mRequestedVisibleTypes|mShowInputFlags|mInShowWindow|"
    r"mViewsCreated|show_ime_with_hard_keyboard|hardKeyboardHidden|keyboardHidden|"
    r"onEvaluateFullscreenMode|onEvaluateInputViewShown)(?:\(\))?\s*[:=]\s*"
    r"(true|false|null|0x[0-9a-fA-F]+|-?[0-9]+|[A-Z][A-Z0-9_|]*)(?=$|[\s,;}])")
IME_REASONS = re.compile(r"\breason\s*[:=]\s*((?:SHOW|HIDE)_[A-Z0-9_]+)\b")


def read_json(path):
    if path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError("Metadata exceeds the inspection limit")
    return json.loads(path.read_text())


def positive_int(value):
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def verify_run(run, repository):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid repository")
    repo, head_repo = run.get("repository", {}), run.get("head_repository", {})
    if (repo.get("full_name") != repository or head_repo.get("full_name") != repository
            or not positive_int(repo.get("id")) or repo.get("id") != head_repo.get("id")
            or run.get("event") not in ("push", "workflow_dispatch")
            or run.get("head_branch") != "master"
            or run.get("path") != ".github/workflows/android.yml"
            or not re.fullmatch(r"[0-9a-f]{40}", run.get("head_sha", ""))
            or not positive_int(run.get("id")) or not positive_int(run.get("run_attempt"))):
        raise ValueError("Source must be this repository's trusted master Android checks run")
    # The matrix may still be running. The selected individual job must be complete.
    return run["id"], run["run_attempt"]


def entries(pages, name):
    if isinstance(pages, dict):
        pages = [pages]
    if not isinstance(pages, list) or not pages:
        raise ValueError("Missing API metadata pages")
    result = []
    for page in pages:
        if not isinstance(page, dict) or not isinstance(page.get(name), list):
            raise ValueError("Invalid API metadata page")
        if any(not isinstance(item, dict) for item in page[name]):
            raise ValueError("Invalid API metadata entry")
        result.extend(page[name])
    return result


def timestamp(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def select_completed_job(run, jobs, repository, label):
    run_id, attempt = verify_run(run, repository)
    if label not in LABELS:
        raise ValueError("Unsupported API label")
    matches = [job for job in entries(jobs, "jobs")
               if job.get("name") == f"emulator-tests ({label})"]
    if len(matches) != 1:
        raise ValueError("Expected exactly one matching API job in this attempt")
    job = matches[0]
    if (not positive_int(job.get("id")) or job.get("status") != "completed"
            or job.get("conclusion") not in ("success", "failure", "cancelled", "timed_out",
                                             "neutral", "skipped", "action_required", "stale")
            or job.get("run_id") != run_id or job.get("run_attempt") != attempt
            or job.get("head_sha") != run["head_sha"]):
        raise ValueError("Selected API job must be completed in the matching run attempt")
    if timestamp(job["completed_at"]) < timestamp(job["started_at"]):
        raise ValueError("Invalid completed job timestamps")
    return job


def select_report(run, jobs, artifacts, repository, label):
    job = select_completed_job(run, jobs, repository, label)
    run_id = run["id"]
    start, finish = timestamp(job["started_at"]), timestamp(job["completed_at"])
    matches = [artifact for artifact in entries(artifacts, "artifacts")
               if artifact.get("name") == f"emulator-test-reports-api-{label}"]
    if not matches:
        return None, job["conclusion"]
    if len(matches) != 1:
        raise ValueError("Report artifacts are ambiguous")
    for artifact in matches:
        origin = artifact.get("workflow_run", {})
        if (artifact.get("expired") is not False or origin.get("id") != run_id
                or origin.get("head_sha") != run["head_sha"]
                or origin.get("head_branch") != "master"
                or origin.get("repository_id") != run["repository"]["id"]
                or origin.get("head_repository_id") != run["repository"]["id"]
                or not positive_int(artifact.get("id"))
                or not positive_int(artifact.get("size_in_bytes"))
                or artifact["size_in_bytes"] > MAX_ARTIFACT_BYTES):
            raise ValueError("Report artifact metadata failed verification")
        # Existing report names lack an attempt suffix. Reject old attempts by upload time.
        if not start <= timestamp(artifact["created_at"]) <= finish:
            raise ValueError("Report artifact is outside the selected job attempt")
    return matches[0]["id"], job["conclusion"]


class NoJUnitReports(ValueError):
    """Only a safely inspected directory with no XML may use the log fallback."""


def known_instrumentation_tests():
    """Names come from reviewed source, never arbitrary job-log values."""
    directory = Path(__file__).resolve().parents[1] / "app/src/androidTest/java/dev/agentos/app"
    tests = set()
    for source in directory.glob("*Test.java"):
        text = source.read_text()
        for method in re.findall(r"@Test\s+public\s+void\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", text):
            tests.add((f"dev.agentos.app.{source.stem}", method))
    return tests


class StructuredLogTail:
    """Retain only bounded reconstructed progress, never raw job-log lines."""

    def __init__(self, tests):
        self.tests = tests
        self.lines = deque()
        self.characters = 0
        self.pending = b""
        self.discard_line = False
        self.trimmed = False

    def feed(self, data):
        parts = data.split(b"\n")
        for index, part in enumerate(parts):
            if not self.discard_line:
                if len(self.pending) + len(part) > MAX_LOG_LINE_BYTES:
                    self.pending = b""
                    self.discard_line = True
                else:
                    self.pending += part
            if index < len(parts) - 1:
                if not self.discard_line:
                    self.consume(self.pending)
                self.pending = b""
                self.discard_line = False

    def finish(self):
        if self.pending and not self.discard_line:
            self.consume(self.pending)
        self.pending = b""

    def consume(self, data):
        try:
            line = data.decode("ascii").rstrip("\r")
        except UnicodeDecodeError:
            return
        if any(ord(char) < 32 or ord(char) == 127 for char in line):
            return
        stamp = ""
        match = re.match(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z) +", line)
        if match:
            stamp = match[1] + " "
            line = line[match.end():]
        line = line.strip()
        value = None
        case = re.fullmatch(
            r"(dev\.agentos\.app\.[A-Za-z_][A-Za-z0-9_]*) > ([A-Za-z_][A-Za-z0-9_]*)"
            r"(?:\[agent-os-phone(?:\(AVD\))? - \d{1,2}(?:\.\d)?\])? "
            r"(STARTED|PASSED|SUCCESS|FAILED|SKIPPED)", line)
        started = re.fullmatch(
            r"(?:TestRunner: )?(started|finished|failed): ([A-Za-z_][A-Za-z0-9_]*)"
            r"\((dev\.agentos\.app\.[A-Za-z_][A-Za-z0-9_]*)\)", line)
        task = re.fullmatch(r"> Task (:[A-Za-z:]+)(?: (UP-TO-DATE|FROM-CACHE|NO-SOURCE|FAILED|SKIPPED))?", line)
        progress = re.fullmatch(
            r"agent-os-phone(?:\(AVD\))? - \d{1,2}(?:\.\d)? Tests (\d{1,6})/(\d{1,6}) completed\."
            r" \((\d{1,6}) skipped\) \((\d{1,6}) failed\)", line)
        count = re.fullmatch(r"(\d{1,6}) tests completed(?:, (\d{1,6}) failed)?(?:, (\d{1,6}) skipped)?", line)
        beginning = re.fullmatch(r"Starting (\d{1,6}) tests on agent-os-phone(?:\(AVD\))? - \d{1,2}(?:\.\d)?", line)
        build = re.fullmatch(r"BUILD (SUCCESSFUL|FAILED) in (?:\d{1,4}[hms] ?){1,3}", line)
        if case and (case[1], case[2]) in self.tests:
            value = f"Test {case[1]}.{case[2]}: {case[3]}"
        elif started and (started[3], started[2]) in self.tests:
            value = f"Test {started[3]}.{started[2]}: {started[1].upper()}"
        elif task and task[1] in LOG_TASKS:
            value = f"Gradle task {task[1]}" + (f": {task[2]}" if task[2] else "")
        elif progress:
            value = f"Test progress {progress[1]}/{progress[2]}; skipped={progress[3]}; failed={progress[4]}"
        elif count:
            value = f"Test count completed={count[1]}; failed={count[2] or '0'}; skipped={count[3] or '0'}"
        elif beginning:
            value = f"Starting {beginning[1]} tests"
        elif build:
            value = f"Gradle reported BUILD {build[1]}"
        elif line in ("The operation was canceled.", "##[error]The operation was canceled.",
                      "Error: The operation was canceled."):
            value = "Job operation was canceled"
        elif re.fullmatch(r"(?:##\[error\])?The job running on runner '[A-Za-z0-9_-]{1,128}' "
                          r"has exceeded the maximum execution time of \d{1,4} minutes\.", line):
            value = "Job execution time limit reached"
        if value is None:
            return
        value = "  | " + stamp + value + "\n"
        self.lines.append(value)
        self.characters += len(value)
        while self.characters > LOG_PROGRESS_CHARS:
            self.characters -= len(self.lines.popleft())
            self.trimmed = True


def fetch_structured_log(repository, job_id, tests):
    """GitHub-hosted inspection only; bounded fixed-endpoint read with no raw log file."""
    if (os.environ.get("GITHUB_ACTIONS") != "true"
            or os.environ.get("GITHUB_REPOSITORY") != repository
            or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository)
            or not positive_int(job_id)):
        raise ValueError("Job logs may only be read by this repository's GitHub inspection runner")
    command = ["gh", "api", "--hostname", "github.com",
               f"repos/{repository}/actions/jobs/{job_id}/logs"]
    tail = StructuredLogTail(tests)
    total = 0
    status = "unavailable"
    deadline = time.monotonic() + MAX_LOG_SECONDS
    # Drop stderr rather than retaining or echoing headers, redirect URLs or API errors.
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as process:
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                while True:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        status = "time_limit"
                        break
                    if not selector.select(remaining):
                        status = "time_limit"
                        break
                    data = os.read(process.stdout.fileno(), min(8192, MAX_LOG_BYTES - total))
                    if not data:
                        try:
                            result = process.wait(timeout=max(0, deadline - time.monotonic()))
                        except subprocess.TimeoutExpired:
                            status = "time_limit"
                        else:
                            status = "complete" if result == 0 else "unavailable"
                            if status == "complete":
                                tail.finish()
                        break
                    total += len(data)
                    tail.feed(data)
                    if total >= MAX_LOG_BYTES:
                        status = "byte_limit"
                        break
        finally:
            if process.poll() is None:
                process.kill()
            process.wait()
    return tail, status, total


def inspect_completed(run, jobs, artifacts, repository, label, directory):
    artifact_id, _ = select_report(run, jobs, artifacts, repository, label)
    job = select_completed_job(run, jobs, repository, label)
    if artifact_id is not None:
        if directory is None:
            raise ValueError("The verified report directory is required")
        try:
            inspect_reports(directory)
            return
        except NoJUnitReports:
            pass
    tail, status, total = fetch_structured_log(repository, job["id"], known_instrumentation_tests())
    print("JUnit unavailable. Structured log progress only; this cannot establish that tests passed.")
    print(f"Source run={run['id']}; attempt={run['run_attempt']}; API={label}; "
          f"job={job['id']}; job conclusion={job['conclusion']}.")
    print(f"Log read status={status}; bytes read={total}; "
          f"older structured entries omitted={str(tail.trimmed).lower()}.")
    if status != "complete":
        print("The log read is incomplete; entries below may not include the last test activity.")
    if tail.lines:
        print("Last recognized structured progress (not a diagnosis of where execution hung):")
        print("".join(tail.lines), end="")
    else:
        print("No recognized structured test progress was available.")


def ime_summary(reports):
    """Keep trailing IME state, never arbitrary log lines, environment or editor values."""
    output = []
    remaining = MAX_IME_CHARS
    for path in reports:
        try:
            root = ET.parse(path).getroot()
        except (OSError, ET.ParseError):
            continue
        for testcase in root.iter("testcase"):
            for failure in testcase:
                if failure.tag not in ("failure", "error"):
                    continue
                fields = []
                for line in "".join(failure.itertext()).splitlines():
                    scalars = [f"{key}={value}" for key, value in IME_SCALARS.findall(line)]
                    scalars += [f"reason={reason}" for reason in IME_REASONS.findall(line)]
                    if scalars:
                        fields.append("  | " + ", ".join(scalars))
                if not fields or remaining <= 0:
                    continue
                # Names are controlled by the test source; bound and prefix every line.
                name = f"{testcase.get('classname', '')}.{testcase.get('name', '')}"
                name = " ".join(name.split())[:240]
                header = f"  | Trailing IME state: {name}\n"
                budget = min(MAX_IME_FAILURE_CHARS, remaining) - len(header)
                selected = []
                for line in reversed(fields[-64:]):
                    if len(line) + 1 > budget:
                        break
                    selected.append(line)
                    budget -= len(line) + 1
                value = header + "\n".join(reversed(selected)) + "\n"
                if len(value) <= remaining:
                    output.append(value)
                    remaining -= len(value)
    return "".join(output)


def inspect_reports(directory):
    directory = directory.resolve(strict=True)
    seen = 0
    xml_count = 0
    with tempfile.TemporaryDirectory(prefix="agent-os-junit-") as temporary:
        safe = Path(temporary)
        for path in directory.rglob("*"):
            seen += 1
            if seen > MAX_FILES:
                raise ValueError("Too many report entries")
            if path.is_symlink() or not path.resolve().is_relative_to(directory):
                raise ValueError("Report paths must stay within the downloaded directory")
            if path.is_dir() or path.suffix.lower() != ".xml":
                continue
            if not path.is_file() or path.stat().st_size > MAX_XML_BYTES:
                raise ValueError("XML report is not a bounded regular file")
            xml_count += 1
            if xml_count > MAX_XML_FILES:
                raise ValueError("Too many XML reports")
            data = path.read_bytes()
            upper = data.upper()
            if b"<!DOCTYPE" in upper or b"<!ENTITY" in upper or b"\x00" in data:
                raise ValueError("XML declarations with entities or unsupported encodings are rejected")
            try:
                ET.fromstring(data)
            except ET.ParseError:
                raise ValueError("Malformed JUnit XML report") from None
            (safe / f"report-{xml_count:02}.xml").write_bytes(data)
        if not xml_count:
            raise NoJUnitReports("No JUnit XML reports were produced by this API job")
        # Only this repository's checked-in printer runs, never downloaded code or HTML.
        printer = Path(__file__).with_name("print_android_test_failures.py")
        summary = ime_summary(sorted(safe.glob("*.xml")))
        result = subprocess.run([sys.executable, str(printer), str(safe)], check=True,
                                capture_output=True, text=True)
        # Preserve an overall cap, reserving room for states buried after long histories.
        print(result.stdout[:MAX_PRINT_CHARS - len(summary) - 1], end="")
        if summary:
            print("\n" + summary, end="")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("verify-run", "select", "print", "inspect"))
    parser.add_argument("--run", type=Path)
    parser.add_argument("--jobs", type=Path)
    parser.add_argument("--artifacts", type=Path)
    parser.add_argument("--repository")
    parser.add_argument("--api-label", choices=LABELS)
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--directory", type=Path)
    args = parser.parse_args()
    if args.mode == "print":
        inspect_reports(args.directory)
        return
    run = read_json(args.run)
    run_id, attempt = verify_run(run, args.repository)
    if args.mode == "inspect":
        inspect_completed(run, read_json(args.jobs), read_json(args.artifacts),
                          args.repository, args.api_label, args.directory)
        return
    values = {"run_id": run_id, "attempt": attempt}
    if args.mode == "select":
        artifact_id, conclusion = select_report(run, read_json(args.jobs),
            read_json(args.artifacts), args.repository, args.api_label)
        values["artifact_id"] = artifact_id or ""
        values["report_available"] = str(artifact_id is not None).lower()
        values["job_id"] = select_completed_job(run, read_json(args.jobs),
                                               args.repository, args.api_label)["id"]
        print(f"Verified API {args.api_label} job: {conclusion}; run {run_id}, attempt {attempt}")
    if args.github_output:
        with args.github_output.open("a") as output:
            for key, value in values.items():
                output.write(f"{key}={value}\n")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(f"Inspection refused: {type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
