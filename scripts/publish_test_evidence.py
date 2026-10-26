#!/usr/bin/env python3
"""Validate a trusted workflow run and prepare bounded, non-executable test evidence."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil

EXPECTED_MATRIX = [str(api) for api in range(30, 37)] + ["37.0"]
MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024
MAX_FILES = 4000
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def read_json(path: Path, maximum: int = 2 * 1024 * 1024):
    if path.is_symlink() or path.stat().st_size > maximum:
        raise ValueError("invalid JSON evidence file")
    return json.loads(path.read_text())


def verify_source(run: dict, repository: str) -> tuple[int, int]:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("invalid repository")
    if (run.get("repository", {}).get("full_name") != repository
            or run.get("head_repository", {}).get("full_name") != repository
            or run.get("repository", {}).get("id") != run.get("head_repository", {}).get("id")
            or run.get("event") not in ("push", "workflow_dispatch")
            or run.get("head_branch") != "master"
            or run.get("path") != ".github/workflows/android.yml"
            or run.get("status") != "completed"
            or not re.fullmatch(r"[0-9a-f]{40}", str(run.get("head_sha", "")))):
        raise ValueError("only completed Android checks from this repository's trusted master are eligible")
    run_id, attempt = run.get("id"), run.get("run_attempt")
    if type(run_id) is not int or type(attempt) is not int or run_id <= 0 or attempt <= 0:
        raise ValueError("invalid source run identity")
    return run_id, attempt


def page_entries(value, field: str) -> list:
    pages = value if isinstance(value, list) else [value]
    return [entry for page in pages for entry in page.get(field, [])]


def eligible_artifacts(value, attempt: int) -> list:
    pattern = re.compile(r"screenshots-api-(3[0-6]|37\.0)-attempt-" + str(attempt))
    return [item for item in page_entries(value, "artifacts")
            if pattern.fullmatch(item.get("name", "")) and not item.get("expired", True)]


def copy_files(source: Path, destination: Path, budget: dict) -> list[dict]:
    copied = []
    for path in sorted(source.rglob("*")):
        if path.is_symlink():
            raise ValueError("symlinks are not evidence")
        if path.is_dir():
            continue
        relative = path.relative_to(source)
        if (not path.is_file() or path.suffix.lower() not in (".png", ".json")
                or any(not re.fullmatch(r"[A-Za-z0-9_.-]+", part) or part in (".", "..")
                       for part in relative.parts)):
            raise ValueError("unexpected evidence path or file type")
        size = path.stat().st_size
        budget["bytes"] += size
        budget["files"] += 1
        if size > MAX_FILE_BYTES or budget["bytes"] > MAX_TOTAL_BYTES or budget["files"] > MAX_FILES:
            raise ValueError("evidence exceeds repository archive budget")
        data = path.read_bytes()
        if path.suffix.lower() == ".png":
            if len(data) < 24 or not data.startswith(PNG_SIGNATURE) or data[12:16] != b"IHDR":
                raise ValueError("invalid PNG evidence")
            width, height = int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")
            if not (0 < width <= 4096 and 0 < height <= 4096):
                raise ValueError("unexpected screenshot dimensions")
        else:
            read_json(path)
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        target.chmod(0o644)
        copied.append({"path": relative.as_posix(), "bytes": size,
                       "sha256": hashlib.sha256(data).hexdigest()})
    return copied


def archive(args, run: dict, run_id: int, attempt: int) -> None:
    output = Path("screenshots") / f"{run_id}-{attempt}"
    if output.exists():
        raise ValueError("this run's evidence directory already exists")
    artifacts = eligible_artifacts(read_json(args.artifacts), attempt)
    expected_names = {entry["name"] for entry in artifacts}
    incoming = args.incoming
    jobs = page_entries(read_json(args.jobs), "jobs")
    records = []
    budget = {"bytes": 0, "files": 0}
    output.mkdir(parents=True)
    for matrix_label in EXPECTED_MATRIX:
        api = int(matrix_label.split(".")[0])
        name = f"screenshots-api-{matrix_label}-attempt-{attempt}"
        source = incoming / name
        job = next((job for job in jobs if job.get("name") == f"emulator-tests ({matrix_label})"), None)
        record = {"apiLevel": api, "matrixLabel": matrix_label,
                  "jobConclusion": job.get("conclusion") if job else "not_scheduled",
                  "jobUrl": job.get("html_url") if job else None,
                  "captureStatus": "artifact_unavailable", "screenshotCount": 0, "files": []}
        if name in expected_names:
            if not source.is_dir() or source.is_symlink():
                raise ValueError("an expected screenshot artifact was not downloaded")
            capture = read_json(source / "capture.json")
            if (capture.get("schemaVersion") != 1 or capture.get("apiLevel") != api
                    or capture.get("matrixLabel") != matrix_label):
                raise ValueError("capture identity does not match its artifact")
            copied = copy_files(source, output / f"api-{matrix_label}", budget)
            png_files = [item for item in copied if item["path"].endswith(".png")]
            record.update({"captureStatus": capture.get("captureStatus", "unknown"),
                           "gradleExitCode": capture.get("gradleExitCode"),
                           "actualSdkInt": capture.get("actualSdkInt"),
                           "junit": capture.get("junit", {}), "screenshotCount": len(png_files),
                           "files": copied})
            test_manifest = source / "screenshots" / "manifest.json"
            tests = read_json(test_manifest).get("tests", []) if test_manifest.exists() else []
            actual_png = {item["path"] for item in png_files}
            tests_with_images = sum(any(capture.get("status") == "pass"
                                       and "screenshots/" + str(capture.get("png", "")) in actual_png
                                       for capture in test.get("captures", [])) for test in tests)
            record["recordedTests"] = len(tests)
            record["testsWithScreenshots"] = tests_with_images
            record["testsWithoutScreenshots"] = len(tests) - tests_with_images
            record["unrecordedTests"] = max(0, record["junit"].get("tests", 0) - len(tests))
        records.append(record)

    build_job = next((job for job in jobs if job.get("name") == "build"), {})
    manifest = {
        "schemaVersion": 1, "kind": "android-emulator-test-evidence",
        "archivedAt": datetime.now(timezone.utc).isoformat(),
        "sourceRun": {"repository": args.repository, "runId": run_id, "attempt": attempt,
                      "commit": run["head_sha"], "event": run["event"], "branch": "master",
                      "conclusion": run.get("conclusion"), "finishedAt": run.get("updated_at"),
                      "url": f"https://github.com/{args.repository}/actions/runs/{run_id}"},
        "expectedMatrixLabels": EXPECTED_MATRIX, "apis": records,
        "jvm": {"screenshotStatus": "not_applicable", "reason": "JVM tests have no device UI",
                "buildJobConclusion": build_job.get("conclusion", "not_scheduled")},
        "scope": "Synthetic fixture and Agent OS test UI; emulator results are not real-device certification",
        "fileCount": budget["files"], "totalBytes": budget["bytes"],
    }
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    lines = ["# Android Test Evidence", "", f"Source run: {manifest['sourceRun']['url']}", "",
             f"Source commit: `{run['head_sha']}` · Attempt: {attempt}", "",
             "These screenshots show synthetic test scenarios. Missing screenshots and failed tests remain visible in the manifest.", "",
             "| API | Job result | Screenshots | Recorded tests | Tests without screenshots |",
             "| --- | --- | --- | --- | --- |"]
    for record in records:
        lines.append(f"| {record['matrixLabel']} | {record['jobConclusion']} | {record['screenshotCount']} | "
                     f"{record.get('recordedTests', 0)} | {record.get('testsWithoutScreenshots', 0)} |")
    lines += ["", "[Full evidence manifest](manifest.json)", ""]
    for record in records:
        samples = [item for item in record["files"] if item["path"].endswith(".png")][:2]
        if samples:
            lines += [f"## API {record['matrixLabel']}", ""]
            for item in samples:
                lines += [f"![API {record['matrixLabel']} test screenshot](api-{record['matrixLabel']}/{item['path']})", ""]
            if any(item["path"] == "screenshots/manifest.json" for item in record["files"]):
                lines += [f"[All files and capture outcomes](api-{record['matrixLabel']}/screenshots/manifest.json)", ""]
    (output / "README.md").write_text("\n".join(lines))
    print(output.as_posix())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("verify", "archive"))
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--jobs", type=Path)
    parser.add_argument("--incoming", type=Path)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    run = read_json(args.run)
    run_id, attempt = verify_source(run, args.repository)
    if args.mode == "verify":
        has_artifacts = bool(eligible_artifacts(read_json(args.artifacts), attempt))
        if args.github_output:
            with args.github_output.open("a") as output:
                output.write(f"run_id={run_id}\nattempt={attempt}\n"
                             f"has_artifacts={'true' if has_artifacts else 'false'}\n"
                             f"evidence_path=screenshots/{run_id}-{attempt}\n")
        print(f"Verified trusted source run {run_id}, attempt {attempt}")
    else:
        if args.jobs is None or args.incoming is None:
            parser.error("archive requires --jobs and --incoming")
        archive(args, run, run_id, attempt)


if __name__ == "__main__":
    main()
