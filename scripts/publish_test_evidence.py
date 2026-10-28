#!/usr/bin/env python3
"""Validate a trusted workflow run and prepare bounded, non-executable test evidence."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil

import collect_performance_evidence as performance
import collect_android_evidence as android_evidence

EXPECTED_MATRIX = [str(api) for api in range(30, 37)] + ["37.0"]
PINNED_WORKFLOW = ".github/workflows/pinned-calculator-page.yml"
DISCLOSURE_WORKFLOW = ".github/workflows/pinned-window-disclosure.yml"
PINNED_MATRIX = ["34", "35", "36", "37.0"]
MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024
MAX_FILES = 8000
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
DIAGNOSTIC_TITLE = "Android checks [owned-control-diagnostic]"
QUALIFICATION_TITLE = "Android checks [task-qualification-diagnostic]"
CONTROL_STATUS_TITLE = "Android checks [control-status-diagnostic]"
SCREEN_OFF_TITLE = "Android checks [screen-off-diagnostic]"
TASK_REPORT_FOCUS_TITLE = "Android checks [task-report-focus-diagnostic]"
DIAGNOSTIC_TITLES = {DIAGNOSTIC_TITLE: android_evidence.DIAGNOSTIC_SCOPE,
                     QUALIFICATION_TITLE: android_evidence.QUALIFICATION_SCOPE,
                     CONTROL_STATUS_TITLE: android_evidence.CONTROL_STATUS_SCOPE,
                     SCREEN_OFF_TITLE: android_evidence.SCREEN_OFF_SCOPE,
                     TASK_REPORT_FOCUS_TITLE: android_evidence.TASK_REPORT_FOCUS_SCOPE}
DIAGNOSTIC_NAMES = {android_evidence.DIAGNOSTIC_SCOPE: "Owned Control",
                    android_evidence.QUALIFICATION_SCOPE: "Task Qualification",
                    android_evidence.CONTROL_STATUS_SCOPE: "Control Status",
                    android_evidence.SCREEN_OFF_SCOPE: "Screen Off",
                    android_evidence.TASK_REPORT_FOCUS_SCOPE: "Task Report Focus"}


def android_execution_scope(run: dict) -> str:
    # This is trusted workflow metadata, not an inference from a partial test count.
    # The fixed run-name is emitted only by the explicit manual choice. Legacy
    # runs and unchanged push/PR/full titles keep their existing full-suite scope.
    if run.get("event") == "workflow_dispatch":
        title = run.get("display_title", "")
        if title in DIAGNOSTIC_TITLES:
            return DIAGNOSTIC_TITLES[title]
        if title.startswith("Android checks ["):
            raise ValueError("unsupported Android diagnostic scope")
    return "full"


def diagnostic_result(capture: dict, tests: list, job: dict | None,
                      scope: str = android_evidence.DIAGNOSTIC_SCOPE) -> str:
    """Only original exact-case evidence can pass the selected fixed diagnostic."""
    if scope not in android_evidence.DIAGNOSTIC_SELECTIONS:
        raise ValueError("unsupported diagnostic scope")
    selection = android_evidence.test_selection(scope)
    junit = capture.get("junit", {})
    fields = ("reports", "tests", "failures", "errors", "skipped", "unreadableReports",
              "selectedTests", "unexpectedTests")
    if (not isinstance(junit, dict)
            or any(type(junit.get(field)) is not int or junit[field] < 0 for field in fields)
            or junit.get("selection") != selection
            or junit["tests"] > 1 or junit["selectedTests"] != junit["tests"]
            or junit["unexpectedTests"] != 0
            or any(junit[field] > junit["tests"] for field in ("failures", "errors", "skipped"))
            or not isinstance(tests, list) or len(tests) > 1):
        raise ValueError("diagnostic evidence does not match the fixed one-case selection")
    for test in tests:
        if (not isinstance(test, dict)
                or str(test.get("testClass", "")) + "#" + str(test.get("testName", "")) != selection
                or test.get("testResult") not in ("pass", "fail")):
            raise ValueError("unexpected test in fixed diagnostic evidence")
    if junit["failures"] or junit["errors"] or any(test["testResult"] == "fail" for test in tests):
        return "failed"
    if (junit["tests"] == 1 and junit["reports"] > 0 and junit["skipped"] == 0
            and junit["unreadableReports"] == 0 and len(tests) == 1
            and type(capture.get("gradleExitCode")) is int and capture["gradleExitCode"] == 0
            and job is not None and job.get("conclusion") == "success"):
        return "passed"
    return "unverified"


def read_json(path: Path, maximum: int = 2 * 1024 * 1024):
    if path.is_symlink() or path.stat().st_size > maximum:
        raise ValueError("invalid JSON evidence file")
    return json.loads(path.read_text())


def verify_source(run: dict, repository: str, review_branch: str | None = None,
                  expected_source_sha: str | None = None) -> tuple[int, int]:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("invalid repository")
    if review_branch is not None:
        if (not re.fullmatch(r"agent/[A-Za-z0-9_-]+(?:[./][A-Za-z0-9_-]+)*", review_branch)
                or not re.fullmatch(r"[0-9a-f]{40}", str(expected_source_sha or ""))
                or run.get("head_branch") != review_branch
                or run.get("head_sha") != expected_source_sha
                or run.get("event") != "workflow_dispatch"
                or run.get("path") != ".github/workflows/android.yml"):
            raise ValueError("review evidence requires the same agent/ workflow branch, explicit source SHA and manual Android run")
    elif expected_source_sha is not None or run.get("head_branch") != "master":
        raise ValueError("non-master evidence requires explicit review-branch opt-in and source SHA")
    if (run.get("repository", {}).get("full_name") != repository
            or run.get("head_repository", {}).get("full_name") != repository
            or not performance.positive_int(run.get("repository", {}).get("id"))
            or not performance.positive_int(run.get("head_repository", {}).get("id"))
            or run.get("repository", {}).get("id") != run.get("head_repository", {}).get("id")
            or run.get("event") not in ("push", "workflow_dispatch")
            or run.get("path") not in (".github/workflows/android.yml", PINNED_WORKFLOW, DISCLOSURE_WORKFLOW)
            or (run.get("path") in (PINNED_WORKFLOW, DISCLOSURE_WORKFLOW) and run.get("event") != "workflow_dispatch")
            or run.get("status") != "completed"
            or not re.fullmatch(r"[0-9a-f]{40}", str(run.get("head_sha", "")))):
        raise ValueError("only completed approved Android workflows from this repository's verified source are eligible")
    run_id, attempt = run.get("id"), run.get("run_attempt")
    if type(run_id) is not int or type(attempt) is not int or run_id <= 0 or attempt <= 0:
        raise ValueError("invalid source run identity")
    if review_branch is not None:
        android_execution_scope(run)  # Unknown diagnostic titles never enter the review archive.
    return run_id, attempt


def page_entries(value, field: str) -> list:
    pages = value if isinstance(value, list) else [value]
    return [entry for page in pages for entry in page.get(field, [])]


def eligible_artifacts(value, attempt: int) -> list:
    pattern = re.compile(r"screenshots-api-(3[0-6]|37\.0)-attempt-" + str(attempt))
    return [item for item in page_entries(value, "artifacts")
            if pattern.fullmatch(item.get("name", "")) and not item.get("expired", True)]


def screenshot_artifacts(value, run: dict) -> list:
    selected = eligible_artifacts(value, run["run_attempt"])
    if run["head_branch"] == "master":
        return selected  # Preserve the existing master archive format and eligibility.
    names, ids = set(), set()
    for artifact in selected:
        origin = artifact.get("workflow_run", {})
        if (artifact["name"] in names or artifact.get("id") in ids
                or artifact.get("expired") is not False
                or not performance.positive_int(artifact.get("id"))
                or not performance.positive_int(artifact.get("size_in_bytes"))
                or artifact["size_in_bytes"] > MAX_TOTAL_BYTES
                or origin.get("id") != run["id"] or origin.get("head_sha") != run["head_sha"]
                or origin.get("head_branch") != run["head_branch"]
                or origin.get("repository_id") != run["repository"]["id"]
                or origin.get("head_repository_id") != run["repository"]["id"]):
            raise ValueError("review screenshot artifact origin, attempt or size failed verification")
        names.add(artifact["name"])
        ids.add(artifact["id"])
    return selected


def verify_review_jobs(jobs: list, artifacts: list, run: dict) -> None:
    names = set()
    for job in jobs:
        if (job.get("name") in names or job.get("status") != "completed"
                or job.get("run_id") != run["id"] or job.get("run_attempt") != run["run_attempt"]
                or job.get("head_sha") != run["head_sha"]):
            raise ValueError("review job does not match source attempt")
        names.add(job.get("name"))
    for artifact in artifacts:
        label = artifact["name"].removeprefix("screenshots-api-").split("-attempt-", 1)[0]
        job = next((item for item in jobs if item.get("name") == f"emulator-tests ({label})"), None)
        if job is None:
            raise ValueError("review screenshot artifact has no matching job")
        try:
            start, finish, uploaded = (datetime.fromisoformat(value.replace("Z", "+00:00")) for value in
                                      (job["started_at"], job["completed_at"], artifact["created_at"]))
            if not start <= uploaded <= finish:
                raise ValueError("review screenshot upload is outside its source job")
        except (KeyError, TypeError, AttributeError) as unavailable:
            raise ValueError("review screenshot job timestamps are unavailable") from unavailable


def performance_artifacts(value, run: dict) -> list:
    if run.get("path") in (PINNED_WORKFLOW, DISCLOSURE_WORKFLOW):
        return []  # These fixed profiles do not run performance diagnostics.
    pattern = re.compile(r"performance-reports-api-(3[0-6]|37\.0)-attempt-" + str(run["run_attempt"]))
    selected = []
    names = set()
    for artifact in page_entries(value, "artifacts"):
        if not pattern.fullmatch(artifact.get("name", "")) or artifact.get("expired") is True:
            continue
        origin = artifact.get("workflow_run", {})
        if (artifact["name"] in names or artifact.get("expired") is not False
                or not performance.positive_int(artifact.get("id"))
                or not performance.positive_int(artifact.get("size_in_bytes"))
                or artifact["size_in_bytes"] > performance.MAX_ARTIFACT_BYTES
                or origin.get("id") != run["id"] or origin.get("head_sha") != run["head_sha"]
                or origin.get("head_branch") != run["head_branch"]
                or origin.get("repository_id") != run["repository"]["id"]
                or origin.get("head_repository_id") != run["repository"]["id"]):
            raise ValueError("performance artifact origin, attempt or size failed verification")
        names.add(artifact["name"])
        selected.append(artifact)
    return selected


def archive_performance(args, run, jobs, output, budget):
    artifacts = {item["name"]: item for item in performance_artifacts(read_json(args.artifacts), run)}
    source_identity = {"repository": args.repository, "runId": run["id"],
                       "attempt": run["run_attempt"], "commit": run["head_sha"]}
    records = []
    for label in EXPECTED_MATRIX:
        matches = [job for job in jobs if job.get("name") == f"emulator-tests ({label})"]
        job = matches[0] if len(matches) == 1 else {}
        record = {"matrixLabel": label, "apiLevel": int(label.split(".")[0]),
                  "jobConclusion": job.get("conclusion", "not_scheduled"),
                  "captureStatus": "artifact_unavailable", "captureError": "no_current_performance_artifact",
                  "diagnosticStatus": None, "files": []}
        name = f"performance-reports-api-{label}-attempt-{run['run_attempt']}"
        artifact = artifacts.get(name)
        if artifact is not None:
            if (len(matches) != 1 or job.get("status") != "completed" or job.get("run_id") != run["id"]
                    or job.get("run_attempt") != run["run_attempt"] or job.get("head_sha") != run["head_sha"]):
                raise ValueError("performance job does not match source attempt")
            start, finish, uploaded = (datetime.fromisoformat(value.replace("Z", "+00:00")) for value in
                                      (job["started_at"], job["completed_at"], artifact["created_at"]))
            if not start <= uploaded <= finish:
                raise ValueError("performance artifact upload is outside its source job")
            source = args.incoming / name
            if source.is_symlink() or not source.is_dir():
                raise ValueError("expected performance artifact was not downloaded")
            entries = list(source.iterdir())
            if (len(entries) > 2 or any(path.is_symlink() or not path.is_file()
                    or path.name not in ("capture.json", performance.REPORT_NAME) for path in entries)):
                raise ValueError("unexpected performance artifact files")
            capture = performance.validate_capture(
                performance.read_json(source / "capture.json", performance.MAX_CAPTURE_BYTES), label, source_identity)
            report_path = source / performance.REPORT_NAME
            if capture["captureStatus"] == "collected":
                raw = performance.read_json(report_path)
                performance.validate_report(raw, record["apiLevel"])
                data = report_path.read_bytes()
                if (capture["report"]["bytes"] != len(data)
                        or capture["report"]["sha256"] != hashlib.sha256(data).hexdigest()
                        or capture["report"]["status"] != raw["status"]):
                    raise ValueError("raw performance report differs from its capture record")
                record["diagnosticStatus"] = raw["status"]
            elif report_path.exists():
                raise ValueError("uncollected diagnostics must not carry an unverified report")
            record.update({"captureStatus": capture["captureStatus"], "captureError": capture["captureError"],
                           "actualSdkInt": capture["actualSdkInt"], "gradleExitCode": capture["gradleExitCode"],
                           "declaredCiProfile": capture["declaredCiProfile"],
                           "artifactId": artifact["id"], "files": copy_files(
                               source, output / "performance" / f"api-{label}", budget)})
        records.append(record)
    return {"kind": "performance-diagnostic-attachments", "diagnosticOnly": True,
            "budgetAcceptance": False,
            "scope": "Raw instrumented emulator CPU/PSS and stage events; excludes fixture-process CPU/PSS. Not screenshots or device performance acceptance.",
            "apis": records}


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
    if run.get("path") in (PINNED_WORKFLOW, DISCLOSURE_WORKFLOW):
        if run["path"] == DISCLOSURE_WORKFLOW:
            import pinned_window_disclosure as profile
        else:
            import pinned_calculator_page as profile
        archive_pinned_page(args, run, run_id, attempt, profile)
        return
    execution_scope = android_execution_scope(run)
    diagnostic = execution_scope in android_evidence.DIAGNOSTIC_SELECTIONS
    diagnostic_name = DIAGNOSTIC_NAMES.get(execution_scope)
    output = Path("screenshots") / f"{run_id}-{attempt}"
    if output.exists():
        raise ValueError("this run's evidence directory already exists")
    artifacts = screenshot_artifacts(read_json(args.artifacts), run)
    expected_names = {entry["name"] for entry in artifacts}
    incoming = args.incoming
    jobs = page_entries(read_json(args.jobs), "jobs")
    review = run["head_branch"] != "master"
    if review:
        verify_review_jobs(jobs, artifacts, run)
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
        if diagnostic:
            record["diagnosticResult"] = "unverified"
        if name in expected_names:
            if not source.is_dir() or source.is_symlink():
                raise ValueError("an expected screenshot artifact was not downloaded")
            capture = read_json(source / "capture.json")
            if (capture.get("schemaVersion") != 1 or capture.get("apiLevel") != api
                    or capture.get("matrixLabel") != matrix_label):
                raise ValueError("capture identity does not match its artifact")
            android_evidence.verify_capture_scope(capture, execution_scope)
            copied = copy_files(source, output / f"api-{matrix_label}", budget)
            png_files = [item for item in copied if item["path"].endswith(".png")]
            record.update({"captureStatus": capture.get("captureStatus", "unknown"),
                           "gradleExitCode": capture.get("gradleExitCode"),
                           "actualSdkInt": capture.get("actualSdkInt"),
                           "junit": capture.get("junit", {}), "screenshotCount": len(png_files),
                           "files": copied})
            test_manifest = source / "screenshots" / "manifest.json"
            tests = read_json(test_manifest).get("tests", []) if test_manifest.exists() else []
            if diagnostic:
                record["diagnosticResult"] = diagnostic_result(capture, tests, job, execution_scope)
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
    performance_records = archive_performance(args, run, jobs, output, budget)
    manifest = {
        "schemaVersion": 1, "kind": "android-emulator-test-evidence",
        "archivedAt": datetime.now(timezone.utc).isoformat(),
        "sourceRun": {"repository": args.repository, "runId": run_id, "attempt": attempt,
                      "commit": run["head_sha"], "event": run["event"], "branch": run["head_branch"],
                      "conclusion": run.get("conclusion"), "finishedAt": run.get("updated_at"),
                      "url": f"https://github.com/{args.repository}/actions/runs/{run_id}"},
        "expectedMatrixLabels": EXPECTED_MATRIX, "apis": records,
        "executionScope": execution_scope, "testSelection": android_evidence.test_selection(execution_scope),
        "performanceDiagnostics": performance_records,
        "jvm": {"screenshotStatus": "not_applicable", "reason": "JVM tests have no device UI",
                "buildJobConclusion": build_job.get("conclusion", "not_scheduled")},
        "scope": "Synthetic fixture and Agent OS test UI; emulator results are not real-device certification",
        "fileCount": budget["files"], "totalBytes": budget["bytes"],
    }
    if diagnostic:
        manifest.update({"expectedTestsPerApi": 1, "expectedTests": len(EXPECTED_MATRIX),
                         "diagnosticCaseCounts": {status: sum(record["diagnosticResult"] == status for record in records)
                                                  for status in ("passed", "failed", "unverified")},
                         "fullSuiteAcceptance": False,
                         "scope": "Fixed " + diagnostic_name.lower().replace(" ", "-") + " diagnostic only: one selected test per API. Other suite tests were not selected and receive no passing status; this cannot replace a full regression."})
    if review:
        manifest.update({"archiveMode": "review_branch", "mainlineAcceptance": False,
                         "scope": "Development branch evidence awaiting review; not mainline acceptance. " + manifest["scope"]})
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    lines = ["# " + (diagnostic_name + " Diagnostic Evidence" if diagnostic else "Android Test Evidence"), "", f"Source run: {manifest['sourceRun']['url']}", "",
             f"Source commit: `{run['head_sha']}` · Attempt: {attempt}", "",
             "These screenshots show synthetic test scenarios. Missing screenshots and failed tests remain visible in the manifest.", ""]
    if review:
        lines += [f"Source branch: `{run['head_branch']}`. Development branch evidence awaiting review; not mainline acceptance.", ""]
    if diagnostic:
        lines += [f"Execution scope: `{execution_scope}`. Only `{android_evidence.test_selection(execution_scope)}` was selected.", "",
                  "The diagnostic denominator is 8 cases (8 APIs × 1 test); missing, skipped and unexecuted cases remain unverified. "
                  "The rest of the normal suite was not run by this scope and is not counted as passed. A complete default matrix is still required for full regression acceptance.", "",
                  "| API | Diagnostic case result |", "| --- | --- |"]
        lines += [f"| {record['matrixLabel']} | {record['diagnosticResult']} |" for record in records]
        lines.append("")
    lines += [
             "| API | Job result | Screenshots | Recorded tests | Tests without screenshots |",
             "| --- | --- | --- | --- | --- |"]
    for record in records:
        lines.append(f"| {record['matrixLabel']} | {record['jobConclusion']} | {record['screenshotCount']} | "
                     f"{record.get('recordedTests', 0)} | {record.get('testsWithoutScreenshots', 0)} |")
    lines += ["", "[Full evidence manifest](manifest.json)", ""]
    collected_performance = [item for item in performance_records["apis"] if item["files"]]
    if collected_performance:
        lines += ["## Performance Diagnostics", "",
                  "Numeric diagnostic attachments are separate from screenshots and test counts. They do not establish performance-budget acceptance.", "",
                  "| API | Collection | Diagnostic result | Raw report |", "| --- | --- | --- | --- |"]
        for item in collected_performance:
            link = (f"[JSON](performance/api-{item['matrixLabel']}/{performance.REPORT_NAME})"
                    if item["captureStatus"] == "collected" else item["captureError"])
            lines.append(f"| {item['matrixLabel']} | {item['captureStatus']} | {item['diagnosticStatus']} | {link} |")
        lines.append("")
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


def pinned_page_artifacts(value, run: dict) -> list:
    """Only the four fixed lanes from this run/attempt, never a configurable profile."""
    pattern = re.compile(r"screenshots-api-(34|35|36|37\.0)-attempt-" + str(run["run_attempt"]))
    selected, names = [], set()
    for item in page_entries(value, "artifacts"):
        if not pattern.fullmatch(item.get("name", "")) or item.get("expired") is True:
            continue
        origin = item.get("workflow_run", {})
        if (item["name"] in names or item.get("expired") is not False
                or not performance.positive_int(item.get("id"))
                or not performance.positive_int(item.get("size_in_bytes"))
                or item["size_in_bytes"] > MAX_TOTAL_BYTES
                or origin.get("id") != run["id"] or origin.get("head_sha") != run["head_sha"]
                or origin.get("head_branch") != "master"
                or origin.get("repository_id") != run["repository"]["id"]
                or origin.get("head_repository_id") != run["repository"]["id"]):
            raise ValueError("pinned page artifact origin, attempt or size failed verification")
        names.add(item["name"]); selected.append(item)
    return selected


def pinned_page_report(source: Path, run: dict, label: str, pinned=None) -> dict:
    # Only the two source-selected fixed modules supply contracts; no CLI/runtime profile registry.
    if pinned is None:
        import pinned_calculator_page as pinned
    import third_party_smoke as base
    require = base.require
    binding = {"repository": run["repository"]["full_name"], "runId": run["id"],
               "attempt": run["run_attempt"], "commit": run["head_sha"]}
    report = base.read_json(source / "report.json")
    api = int(label.split(".")[0])
    require(report.get("schemaVersion") == 1 and report.get("kind") == pinned.KIND
            and report.get("source") == binding and report.get("matrixLabel") == label
            and type(report.get("expectedTests")) is int and report["expectedTests"] == len(pinned.CASES)
            and report.get("status") in ("passed", "failed", "environment_failed"), "Foreign pinned page report")
    if pinned.BUILD_VARIANT is not None:
        require(report.get("buildVariant") == pinned.BUILD_VARIANT, "Wrong internal acceptance build variant")
        pinned.validate_report_extra(report)
    declared = report.get("cases")
    require(isinstance(declared, list) and len(declared) == len(pinned.CASES) and all(isinstance(item, dict) for item in declared)
            and [item.get("name") for item in declared] == list(pinned.CASES)
            and all(item.get("status") in ("passed", "failed", "unverified") for item in declared),
            "Pinned report must preserve its exact fixed-case denominator")
    device = report.get("device", {})
    if device:
        require(type(device.get("api")) is int and device["api"] == api
                and re.fullmatch(r"emulator-[0-9]{4,5}", str(device.get("serial", ""))) is not None,
                "Pinned page device identity mismatch")
        base.validate_device(device["serial"], device.get("properties", {}), api)
    download = report.get("verifiedDownload")
    if download is not None:
        require(download == {"package": base.PACKAGE, "versionCode": "10", "versionName": "1.4.0",
                "minSdk": 26, "targetSdk": 36, "abis": ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"],
                "certificateSha256": base.PINNED_CERT, "sha256": base.PINNED_SHA, "sizeBytes": base.PINNED_SIZE},
                "Pinned page APK differs from the reviewed fixed release")
    capture = read_json(source / "capture.json")
    require(capture.get("schemaVersion") == 1 and capture.get("apiLevel") == api
            and capture.get("matrixLabel") == label and capture.get("actualSdkInt") in (None, api)
            and capture.get("runnerKind") == "direct_adb_instrumentation"
            and capture.get("gradleExitCode") is None and capture.get("junitStatus") == "not_produced_direct_adb"
            and "junit" not in capture, "Direct ADB capture must not claim a Gradle or JUnit run")
    if "instrumentation" in report:
        result = report["instrumentation"]
        require(isinstance(result, dict) and result.get("selectedClass") == pinned.TEST_CLASS
                and type(result.get("expectedTests")) is int and result["expectedTests"] == len(pinned.CASES)
                and type(result.get("passed")) is bool and type(result.get("exitCode")) is int
                and result.get("log") == "instrumentation.json", "Invalid selected instrumentation record")
        path = source / "instrumentation.json"
        require(not path.is_symlink() and path.stat().st_size <= 2 * 1024 * 1024, "Oversized instrumentation log")
        raw = path.read_bytes()
        require(len(raw) == result.get("bytes") and hashlib.sha256(raw).hexdigest() == result.get("sha256"),
                "Instrumentation log differs from its bound report")
        value = read_json(path)
        require(value.get("runner") == "direct_adb_instrumentation" and type(value.get("exitCode")) is int
                and value["exitCode"] == result["exitCode"] and type(value.get("passed")) is bool
                and value["passed"] == result["passed"] and isinstance(value.get("stdout"), str)
                and result["passed"] == pinned.instrument_result(value["stdout"], result["exitCode"]),
                "Direct ADB completion does not match its original output")
        require(capture.get("instrumentationExitCode") == result["exitCode"], "Capture command result differs from original execution")
    files = {}
    folder = source / pinned.EVIDENCE_DIR
    if folder.exists():
        require(folder.is_dir() and not folder.is_symlink(), "Invalid pinned page directory")
        paths = list(folder.iterdir()); require(len(paths) <= pinned.MAX_CASE_FILES, "Too many pinned page files")
        total = 0
        for path in paths:
            require(path.is_file() and not path.is_symlink() and re.fullmatch(
                pinned.CASE_FILE_PATTERN, path.name) is not None
                and path.name.split("-", 1)[0].removesuffix(".case.json") in pinned.CASES,
                "Unexpected pinned page evidence path")
            require(path.stat().st_size <= (96 * 1024 if path.suffix == ".json" else pinned.MAX_IMAGE_BYTES), "Oversized pinned page file")
            total += path.stat().st_size
            require(total <= MAX_TOTAL_BYTES, "Pinned page payload exceeds archive budget")
            files[path.name] = path.read_bytes()
            if path.suffix == ".json": base.parse_json(files[path.name])
    standard_path = source / "screenshots" / "manifest.json"
    standards = read_json(standard_path).get("tests", []) if standard_path.exists() else []
    require(isinstance(standards, list) and len(standards) <= len(pinned.CASES), "Unexpected standard test evidence count")
    by_name = {}
    for item in standards:
        require(isinstance(item, dict) and item.get("testClass") == pinned.TEST_CLASS
                and item.get("testName") in pinned.CASES and item["testName"] not in by_name
                and item.get("api") == api and item.get("testResult") in ("pass", "fail"),
                "Standard evidence belongs to another test or API")
        require(all(c.get("secureWindowTemporarilyCleared") is not True for c in item.get("captures", [])),
                "Pinned evidence must not use the empty-main secure-window exception")
        by_name[item["testName"]] = item
    cases, images = [], set()
    for item in declared:
        name = item["name"]; filename = name + ".case.json"
        record = None
        if filename in files:
            require(device and download, "Actual page records require verified device and pinned APK")
            if pinned.BUILD_VARIANT is not None: pinned.validate_report_extra(report, require_actual=True)
            record = pinned.validate_case(base.parse_json(files[filename]), binding, api, device["serial"], name)
            pinned.validate_case_files(record, files)
            if "record" in item:
                require(item["record"] == pinned.EVIDENCE_DIR + "/" + filename, "Report links another case file")
            # A collection failure may leave actual case records that the driver did not finish indexing.
            if item["status"] != "unverified":
                require(item["status"] == record["status"], "Driver summary disagrees with the original case")
            images.update(pinned.EVIDENCE_DIR + "/" + path for path in pinned.image_paths(record))
        else:
            require(item["status"] == "unverified" and "record" not in item, "Missing case cannot be claimed as executed")
        standard = by_name.get(name)
        raw_status = record["status"] if record else "unverified"
        status = ("failed" if raw_status == "failed" or (standard and standard["testResult"] == "fail")
                  else "passed" if raw_status == "passed" and standard and standard["testResult"] == "pass"
                  else "unverified")
        cases.append({"name": name, "status": status, pinned.CASE_STATUS_KEY: raw_status,
                      "standardTestResult": standard["testResult"] if standard else "not_recorded",
                      "record": pinned.EVIDENCE_DIR + "/" + filename if record else None})
    if report["status"] == "passed":
        require(device and download and report.get("instrumentation", {}).get("passed") is True
                and all(item[pinned.CASE_STATUS_KEY] == "passed" for item in cases), "Passed driver lacks its actual fixed case outcomes")
    # Missing standard evidence is an unverified sample, not a fabricated pass or a
    # reason to drop this lane from the archive. Keep the original driver claim too.
    status = ("failed" if any(item["status"] == "failed" for item in cases) else "passed"
              if report["status"] == "passed" and all(item["status"] == "passed" for item in cases)
              else "unverified")
    return {"status": status, "driverStatus": report["status"], "cases": cases, pinned.IMAGE_LIST_KEY: sorted(images),
            "captureStatus": capture.get("captureStatus", "unknown"), "actualSdkInt": capture.get("actualSdkInt"),
            "standardRecordedTests": len(standards), "verifiedDownload": download}


def archive_pinned_page(args, run: dict, run_id: int, attempt: int, pinned=None) -> None:
    if pinned is None:
        import pinned_calculator_page as pinned
    output = Path("screenshots") / f"{run_id}-{attempt}"
    if output.exists(): raise ValueError("this run's evidence directory already exists")
    artifacts = {item["name"]: item for item in pinned_page_artifacts(read_json(args.artifacts), run)}
    jobs = page_entries(read_json(args.jobs), "jobs")
    budget = {"bytes": 0, "files": 0}; records = []
    output.mkdir(parents=True)
    for label in PINNED_MATRIX:
        matches = [job for job in jobs if job.get("name") == f"{pinned.JOB_PREFIX} ({label})"]
        if len(matches) > 1: raise ValueError("duplicate pinned page job")
        job = matches[0] if matches else {}
        record = {"apiLevel": int(label.split(".")[0]), "matrixLabel": label,
                  "jobConclusion": job.get("conclusion", "not_scheduled"), "jobUrl": job.get("html_url"),
                  "captureStatus": "artifact_unavailable", "status": "unverified", "files": [],
                  pinned.IMAGE_LIST_KEY: [], "cases": [{"name": name, "status": "unverified"} for name in pinned.CASES]}
        artifact = artifacts.get(f"screenshots-api-{label}-attempt-{attempt}")
        if artifact is not None:
            if (not job or job.get("status") != "completed" or job.get("run_id") != run_id
                    or job.get("run_attempt") != attempt or job.get("head_sha") != run["head_sha"]):
                raise ValueError("pinned page job does not match its source attempt")
            times = [datetime.fromisoformat(value.replace("Z", "+00:00")) for value in
                     (job["started_at"], artifact["created_at"], job["completed_at"])]
            if not times[0] <= times[1] <= times[2]: raise ValueError("pinned page upload outside source job")
            source = args.incoming / artifact["name"]
            if not source.is_dir() or source.is_symlink(): raise ValueError("expected pinned artifact was not downloaded")
            copied = copy_files(source, output / f"api-{label}", budget)
            record.update(pinned_page_report(source, run, label, pinned))
            record.update(artifactId=artifact["id"], files=copied)
        record["caseCounts"] = {status: sum(item["status"] == status for item in record["cases"])
                                for status in ("passed", "failed", "unverified")}
        records.append(record)
    totals = {status: sum(item["caseCounts"][status] for item in records) for status in ("passed", "failed", "unverified")}
    scope = pinned.ARCHIVE_SCOPE
    manifest = {"schemaVersion": 1, "kind": pinned.ARCHIVE_KIND,
                "archivedAt": datetime.now(timezone.utc).isoformat(),
                "sourceRun": {"repository": args.repository, "runId": run_id, "attempt": attempt,
                              "commit": run["head_sha"], "event": run["event"], "branch": "master",
                              "workflow": pinned.WORKFLOW, "conclusion": run.get("conclusion"),
                              "url": f"https://github.com/{args.repository}/actions/runs/{run_id}"},
                "expectedMatrixLabels": PINNED_MATRIX, "expectedCases": list(pinned.CASES),
                "expectedTestsPerApi": len(pinned.CASES), "expectedTests": len(PINNED_MATRIX) * len(pinned.CASES), "caseCounts": totals, "apis": records,
                "junit": {"status": "not_produced", "reason": "Dedicated direct ADB class execution; original per-case records are retained"},
                "performanceDiagnostics": {"status": "not_run", "budgetAcceptance": False},
                "scope": scope, "fileCount": budget["files"], "totalBytes": budget["bytes"]}
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    lines = ["# " + pinned.ARCHIVE_TITLE, "", "Source run: " + manifest["sourceRun"]["url"], "",
             f"Source commit: `{run['head_sha']}` · Attempt: {attempt}", "", scope, "",
             f"The fixed denominator is {len(PINNED_MATRIX) * len(pinned.CASES)} cases (4 APIs × {len(pinned.CASES)} cases). Failed, missing and unexecuted cases stay in this denominator.", "",
             "| API | Job | Passed | Failed | Unverified | Verified image payloads |", "| --- | --- | --- | --- | --- | --- |"]
    for record in records:
        counts = record["caseCounts"]
        lines.append(f"| {record['matrixLabel']} | {record['jobConclusion']} | {counts['passed']} | {counts['failed']} | {counts['unverified']} | {len(record[pinned.IMAGE_LIST_KEY])} |")
    lines += ["", "[Full source, case and capture manifest](manifest.json)", ""]
    for record in records:
        if record["files"]:
            lines += [f"## API {record['matrixLabel']}", "", f"[Original driver report](api-{record['matrixLabel']}/report.json)", ""]
            for path in record[pinned.IMAGE_LIST_KEY][:2]:
                lines += [f"![{pinned.IMAGE_CAPTION} on API {record['matrixLabel']}](api-{record['matrixLabel']}/{path})", ""]
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
    parser.add_argument("--review-branch", help="Explicit opt-in: current archive workflow agent/ branch, not a source-selected ref")
    parser.add_argument("--expected-source-sha", help="Explicit exact 40-hex source commit required with --review-branch")
    args = parser.parse_args()
    run = read_json(args.run)
    run_id, attempt = verify_source(run, args.repository, args.review_branch, args.expected_source_sha)
    if args.mode == "verify":
        has_artifacts = bool(pinned_page_artifacts(read_json(args.artifacts), run) if run.get("path") in (PINNED_WORKFLOW, DISCLOSURE_WORKFLOW)
                             else screenshot_artifacts(read_json(args.artifacts), run))
        screenshots = screenshot_artifacts(read_json(args.artifacts), run) if args.review_branch else []
        perf_artifacts = performance_artifacts(read_json(args.artifacts), run)
        if args.github_output:
            with args.github_output.open("a") as output:
                output.write(f"run_id={run_id}\nattempt={attempt}\n"
                             f"has_artifacts={'true' if has_artifacts else 'false'}\n"
                             f"screenshot_artifact_ids={','.join(str(item['id']) for item in screenshots)}\n"
                             f"performance_artifact_ids={','.join(str(item['id']) for item in perf_artifacts)}\n"
                             f"evidence_path=screenshots/{run_id}-{attempt}\n")
        print(f"Verified trusted source run {run_id}, attempt {attempt}")
    else:
        if args.jobs is None or args.incoming is None:
            parser.error("archive requires --jobs and --incoming")
        archive(args, run, run_id, attempt)


if __name__ == "__main__":
    main()
