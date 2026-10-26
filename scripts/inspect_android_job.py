#!/usr/bin/env python3
"""Validate a completed trusted Android job and inspect its XML without running it."""

import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile


LABELS = tuple(str(api) for api in range(30, 37)) + ("37.0",)
MAX_ARTIFACT_BYTES = 64 * 1024 * 1024
MAX_FILES = 2000
MAX_XML_FILES = 24
MAX_XML_BYTES = 2 * 1024 * 1024


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
        return pages.get(name, [])
    return [item for page in pages for item in page.get(name, [])]


def timestamp(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def select_report(run, jobs, artifacts, repository, label):
    run_id, attempt = verify_run(run, repository)
    if label not in LABELS:
        raise ValueError("Unsupported API label")
    matches = [job for job in entries(jobs, "jobs")
               if job.get("name") == f"emulator-tests ({label})"]
    if len(matches) != 1:
        raise ValueError("Expected exactly one matching API job in this attempt")
    job = matches[0]
    if (job.get("status") != "completed" or not job.get("conclusion")
            or job.get("run_id") != run_id or job.get("run_attempt") != attempt
            or job.get("head_sha") != run["head_sha"]):
        raise ValueError("Selected API job must be completed in the matching run attempt")
    start, finish = timestamp(job["started_at"]), timestamp(job["completed_at"])
    matches = []
    for artifact in entries(artifacts, "artifacts"):
        origin = artifact.get("workflow_run", {})
        if (artifact.get("name") != f"emulator-test-reports-api-{label}"
                or artifact.get("expired") is not False):
            continue
        if (origin.get("id") != run_id or origin.get("head_sha") != run["head_sha"]
                or origin.get("head_branch") != "master"
                or origin.get("repository_id") != run["repository"]["id"]
                or origin.get("head_repository_id") != run["repository"]["id"]
                or not positive_int(artifact.get("id"))
                or not positive_int(artifact.get("size_in_bytes"))
                or artifact["size_in_bytes"] > MAX_ARTIFACT_BYTES):
            raise ValueError("Report artifact metadata failed verification")
        # Existing report names lack an attempt suffix. Reject old attempts by upload time.
        if start <= timestamp(artifact["created_at"]) <= finish:
            matches.append(artifact)
    if len(matches) != 1:
        raise ValueError("No unique current-attempt report artifact is available")
    return matches[0]["id"], job["conclusion"]


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
            (safe / f"report-{xml_count:02}.xml").write_bytes(data)
        if not xml_count:
            raise ValueError("No JUnit XML reports were produced by this API job")
        # Only this repository's checked-in printer runs, never downloaded code or HTML.
        printer = Path(__file__).with_name("print_android_test_failures.py")
        subprocess.run([sys.executable, str(printer), str(safe)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("verify-run", "select", "print"))
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
    values = {"run_id": run_id, "attempt": attempt}
    if args.mode == "select":
        artifact_id, conclusion = select_report(run, read_json(args.jobs),
            read_json(args.artifacts), args.repository, args.api_label)
        values["artifact_id"] = artifact_id
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
