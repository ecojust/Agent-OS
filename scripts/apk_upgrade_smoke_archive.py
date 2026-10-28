#!/usr/bin/env python3
"""Archive one source-bound, eight-lane development APK upgrade experiment.

Two instrumentation phases are one continuity case per API. This command does
not download artifacts, install packages, run tests, or confer release approval.
"""

import argparse
import base64
import binascii
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import tempfile

import third_party_smoke_archive as evidence

REPOSITORY = "ecojust/Agent-OS"
WORKFLOW = ".github/workflows/apk-upgrade-smoke.yml"
BASELINE_SOURCE = "b1fd337262775a505327088975a3320fe323f034"
LABELS = ("30", "31", "32", "33", "34", "35", "36", "37.0")
CASE_ID = "same-signer-in-place-upgrade-v1"
MAX_FILE_BYTES = 2 * 1024 * 1024
# Eight lanes, each with at most 80 command records and 16 MiB of raw command
# output. Base64 and fixed JSON metadata remain within this aggregate ceiling.
MAX_TOTAL_BYTES = 192 * 1024 * 1024
MAX_FILES = 704
MAX_ENTRIES = 512
FIXED_FILES = frozenset(("report.json", "build-manifest.json", "preflight.json",
                         "signing-certificate.der", "seed-receipt.json", "verify-receipt.json"))
COMMAND_ORDER = ("devices", *("property-" + str(index) for index in range(6)),
    "current-user", "users", "fresh-packages", "install-baseline", "install-fixture", "install-probe",
    "baseline-package", "baseline-path", "baseline-pull", "accessibility-list-before", "accessibility-flag-before",
    "wake-screen", "dismiss-keyguard", "accessibility-list-enable", "accessibility-flag-enable",
    "accessibility-list-after", "accessibility-flag-after", "reverse-add", "seed-instrumentation",
    "baseline-launch", "baseline-pid", "replace-candidate", "candidate-package", "candidate-path",
    "candidate-pull", "verify-instrumentation", "reverse-remove", "accessibility-list-restore",
    "accessibility-flag-restore", "accessibility-list-restored", "accessibility-flag-restored")
COMMAND_LABELS = frozenset(COMMAND_ORDER)
MAIN_COMMANDS = COMMAND_ORDER[:-5]
RESTORE_COMMANDS = COMMAND_ORDER[-4:]
require = evidence.require


def digest(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def json_data(data):
    require(len(data) <= MAX_FILE_BYTES, "JSON evidence exceeds its byte bound")
    try:
        text = data.decode("utf-8", "strict")
        require(not text.startswith("\ufeff"), "JSON evidence must not contain a byte-order mark")
        return evidence.json_data(data)
    except UnicodeError:
        raise ValueError("JSON evidence must be UTF-8") from None


def read_bytes(path, maximum=MAX_FILE_BYTES):
    """Read a bounded regular file without following its final symlink."""
    no_link_parents(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as stream:
        before = os.fstat(stream.fileno())
        require(stat.S_ISREG(before.st_mode) and before.st_nlink == 1,
                "Evidence must be a single-link regular file")
        require(0 < before.st_size <= maximum, "Evidence file size is invalid")
        data = stream.read(maximum + 1)
        after = os.fstat(stream.fileno())
        require(len(data) == before.st_size and len(data) <= maximum
                and (before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns)
                == (after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns),
                "Evidence changed while reading")
        return data


def no_link_parents(path):
    path = Path(path).absolute()
    require(not any(item.is_symlink() for item in (path, *path.parents)), "Evidence path traverses a symbolic link")


def read_json(path):
    return json_data(read_bytes(path))


def verify_source(run):
    require(isinstance(run, dict), "Invalid source run")
    repository, head = run.get("repository"), run.get("head_repository")
    require(isinstance(repository, dict) and isinstance(head, dict)
            and repository.get("full_name") == head.get("full_name") == REPOSITORY
            and evidence.positive_int(repository.get("id"))
            and type(head.get("id")) is int and repository["id"] == head["id"],
            "Source must belong to this repository, not a fork")
    require(run.get("path") == WORKFLOW and run.get("event") == "workflow_dispatch"
            and run.get("head_branch") == "master" and run.get("status") == "completed"
            and run.get("display_title") == "APK upgrade smoke (test)",
            "Source must be a completed manual upgrade run on master")
    require(re.fullmatch(r"[0-9a-f]{40}", str(run.get("head_sha", ""))) is not None
            and evidence.positive_int(run.get("id")) and evidence.positive_int(run.get("run_attempt")),
            "Invalid source run identity")
    require(run.get("conclusion") in ("success", "failure", "cancelled", "timed_out",
                                      "action_required", "neutral", "skipped", "startup_failure", "stale"),
            "Invalid completed source conclusion")
    require(evidence.timestamp(run.get("run_started_at")) <= evidence.timestamp(run.get("updated_at")),
            "Invalid source time interval")
    return {"repository": REPOSITORY, "runId": run["id"], "attempt": run["run_attempt"],
            "commit": run["head_sha"]}


def eligible_artifacts(index, run):
    source = verify_source(run)
    prefix = "apk-upgrade-smoke-api-"
    pattern = re.compile(prefix + r"(30|31|32|33|34|35|36|37\.0)-attempt-" + str(source["attempt"]))
    selected, ids = {}, set()
    for item in evidence.artifact_entries(index):
        require(isinstance(item, dict), "Invalid artifact entry")
        name = item.get("name", "")
        require(isinstance(name, str), "Invalid artifact name")
        match = pattern.fullmatch(name)
        if match is None:
            require(not (name.startswith(prefix) and name.endswith("-attempt-" + str(source["attempt"]))),
                    "Unknown current-attempt API artifact")
            continue
        label = match.group(1)
        require(label not in selected and item.get("id") not in ids, "Duplicate artifact lane or identity")
        require(evidence.positive_int(item.get("id")) and evidence.positive_int(item.get("size_in_bytes"))
                and item["size_in_bytes"] <= MAX_TOTAL_BYTES and type(item.get("expired")) is bool,
                "Invalid artifact id, size or expiry")
        origin = item.get("workflow_run")
        require(isinstance(origin, dict) and type(origin.get("id")) is int
                and origin["id"] == source["runId"] and origin.get("head_sha") == source["commit"]
                and origin.get("head_branch") == "master"
                and type(origin.get("repository_id")) is int and type(origin.get("head_repository_id")) is int
                and origin["repository_id"] == origin["head_repository_id"] == run["repository"]["id"],
                "Artifact source differs from the trusted run")
        require(isinstance(item.get("digest"), str) and re.fullmatch(r"sha256:[0-9a-f]{64}", item["digest"]),
                "Artifact digest is missing or malformed")
        require(evidence.timestamp(run["run_started_at"]) <= evidence.timestamp(item.get("created_at"))
                <= evidence.timestamp(run["updated_at"]), "Artifact belongs to another attempt interval")
        selected[label] = item
        ids.add(item["id"])
    return selected


def scan_files(directory, budget):
    directory = Path(directory)
    require(directory.is_dir() and not directory.is_symlink(), "Artifact directory is missing or linked")
    files, pending, entries = {}, [(directory, 0)], 0
    while pending:
        current, depth = pending.pop()
        for path in sorted(current.iterdir()):
            entries += 1
            require(entries <= MAX_ENTRIES and not path.is_symlink(), "Artifact entries exceed bounds or contain a link")
            relative = path.relative_to(directory).as_posix()
            evidence.relative_path(relative)
            if path.is_dir():
                require(depth == 0 and relative == "commands", "Unexpected evidence directory")
                pending.append((path, 1))
                continue
            require(relative in FIXED_FILES or re.fullmatch(r"commands/[0-9]{2}-[a-z0-9-]{1,64}\.json", relative)
                    or re.fullmatch(r"phase-(seed|verify)\.json", relative), "Unexpected evidence file")
            data = read_bytes(path, 16 * 1024 if relative.endswith(".der") else MAX_FILE_BYTES)
            if relative.endswith(".json"):
                json_data(data)
            budget["files"] += 1
            budget["bytes"] += len(data)
            require(budget["files"] <= MAX_FILES and budget["bytes"] <= MAX_TOTAL_BYTES,
                    "Evidence archive exceeds its byte or file bound")
            files[relative] = {"path": relative, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "data": data}
    require("report.json" in files, "Artifact has no report.json")
    return files


def _validate_report(report, label, source, files):
    # The host's protocol validator also checks complete, source-bound phase
    # receipts. Files and provenance are independently checked by this archive.
    require(isinstance(report, dict), "Invalid upgrade report")
    import apk_upgrade_smoke as probe
    require(type(report.get("schemaVersion")) is int and report["schemaVersion"] == 1
            and report.get("kind") == "agentos-apk-upgrade-smoke", "Invalid upgrade report schema")
    reported_source = report.get("source")
    require(isinstance(reported_source, dict) and reported_source == source
            and all(type(reported_source.get(key)) is int for key in ("runId", "attempt"))
            and report.get("matrixLabel") == label
            and report.get("baselineSource") == BASELINE_SOURCE
            and report.get("caseId") == CASE_ID, "Upgrade case provenance differs")
    require(report.get("status") in ("passed", "failed", "unverified"), "Invalid upgrade case outcome")
    captures = report.get("captures")
    require(isinstance(captures, list) and len(captures) == 2, "Invalid protected capture policy")
    for phase, capture in zip(("seedBaseline", "verifyCandidate"), captures):
        require(isinstance(capture, dict) and set(capture) == {"phase", "status", "reason", "secureFlagsCleared"}
                and capture["phase"] == phase and capture["status"] == "blocked"
                and capture["reason"] == "secure_apk_upgrade_capture_not_attempted"
                and capture["secureFlagsCleared"] is False, "Protected capture policy changed")
    device = report.get("device")
    if device is not None:
        require(isinstance(device, dict) and set(device) == {"serial", "apiLevel", "abi", "fingerprint",
                "kernelQemu", "bootQemu", "emulatorVersion", "ownerUser"}, "Invalid device record")
        require(type(device["apiLevel"]) is int and device["apiLevel"] == int(label.split(".")[0])
                and isinstance(device["serial"], str) and re.fullmatch(r"emulator-[0-9]{4,5}", device["serial"])
                and device["abi"] == "x86_64" and device["kernelQemu"] == "1" and device["bootQemu"] in ("", "1")
                and type(device["ownerUser"]) is int and device["ownerUser"] == 0
                and evidence.text(device["fingerprint"], 1024), "Device does not match the dedicated API lane")
        require(isinstance(device["emulatorVersion"], str), "Missing emulator revision")
        probe.device_rules.validate_emulator_version("Pkg.Revision=" + device["emulatorVersion"], device["apiLevel"])
    references = report.get("artifactFiles")
    require(isinstance(references, list) and len(references) < MAX_FILES, "Invalid artifact file references")
    referenced = set()
    for item in references:
        require(isinstance(item, dict) and set(item) == {"path", "bytes", "sha256"}, "Invalid file reference")
        name = item["path"]
        require(isinstance(name, str) and name != "report.json" and name in files and name not in referenced,
                "Referenced file is absent, duplicated or self-referential")
        require(type(item["bytes"]) is int and item["bytes"] == files[name]["bytes"]
                and digest(item["sha256"]) and item["sha256"] == files[name]["sha256"],
                "Referenced file bytes or hash differ")
        referenced.add(name)
    require(referenced == set(files) - {"report.json"}, "Artifact contains unreferenced files")
    command_names = sorted(name for name in files if name.startswith("commands/"))
    require(len(command_names) <= 80, "Too many collected commands")
    seen_labels, ordered_labels, output_bytes = set(), [], 0
    for index, name in enumerate(command_names, 1):
        command = json_data(files[name]["data"])
        require(isinstance(command, dict) and set(command) == {"schemaVersion", "label", "exitCode",
                "timeoutSeconds", "elapsedMillis", "reason", "stdoutBase64", "stderrBase64"},
                "Invalid command record fields")
        cmd_label = command["label"]
        require(isinstance(cmd_label, str) and cmd_label in COMMAND_LABELS and cmd_label not in seen_labels
                and name == f"commands/{index:02d}-{cmd_label}.json", "Command identity or order is invalid")
        seen_labels.add(cmd_label)
        ordered_labels.append(cmd_label)
        require(type(command["schemaVersion"]) is int and command["schemaVersion"] == 1
                and type(command["timeoutSeconds"]) is int and 0 < command["timeoutSeconds"] <= 180
                and type(command["elapsedMillis"]) is int and 0 <= command["elapsedMillis"] <= 1200000
                and (command["exitCode"] is None or type(command["exitCode"]) is int and -255 <= command["exitCode"] <= 255)
                and command["reason"] in (None, "COMMAND_TIMEOUT", "COMMAND_OUTPUT_LIMIT", "COMMAND_FAILED",
                                          "COMMAND_UNAVAILABLE", "COMMAND_CLEANUP_FAILED"),
                "Invalid command result types or bounds")
        size = 0
        for stream in ("stdoutBase64", "stderrBase64"):
            value = command[stream]
            require(isinstance(value, str) and len(value) <= 1400000, "Command stream exceeds its encoded bound")
            try:
                data = base64.b64decode(value, validate=True)
            except (ValueError, binascii.Error):
                raise ValueError("Invalid command stream encoding") from None
            require(base64.b64encode(data).decode("ascii") == value, "Noncanonical command stream encoding")
            size += len(data)
        output_bytes += size
        require(size <= 1024 * 1024 and output_bytes <= 16 * 1024 * 1024,
                "Command output exceeds its recorded budget")
    cleanup_at = next((index for index, name in enumerate(ordered_labels)
                       if name == "reverse-remove" or name in RESTORE_COMMANDS), len(ordered_labels))
    main_commands, cleanup_commands = ordered_labels[:cleanup_at], ordered_labels[cleanup_at:]
    require(main_commands == list(MAIN_COMMANDS[:len(main_commands)]), "Command phases were reordered or replayed")
    if cleanup_commands and cleanup_commands[0] == "reverse-remove":
        require("reverse-add" in main_commands, "Reverse cleanup has no preceding setup")
        cleanup_commands = cleanup_commands[1:]
    require(cleanup_commands == list(RESTORE_COMMANDS[:len(cleanup_commands)])
            and (not cleanup_commands or "accessibility-list-enable" in main_commands),
            "Service cleanup was reordered or has no preceding setup")
    build = json_data(files["build-manifest.json"]["data"]) if "build-manifest.json" in files else None
    preflight = json_data(files["preflight.json"]["data"]) if "preflight.json" in files else None
    if build is not None:
        require(isinstance(build, dict) and type(build.get("schemaVersion")) is int, "Build schema must be an integer")
        require(build.get("source") == source and all(type(build["source"].get(key)) is int
                for key in ("runId", "attempt")), "Build source differs")
        probe.validate_build_manifest(build, source)
        for name in ("preflight.json", "signing-certificate.der"):
            if name in files:
                require(build["files"][name] == {key: files[name][key] for key in ("bytes", "sha256")},
                        "Collected build file differs from the frozen bundle")
    if preflight is not None:
        require(isinstance(preflight, dict) and set(preflight) == {
            "schemaVersion", "kind", "status", "signingPolicy", "lineageEvaluated", "installationPerformed",
            "deviceCompatibilityEvaluated", "configurationMigrationEvaluated", "trustedCertificateSha256",
            "tools", "baseline", "candidate"}, "Invalid original preflight fields")
        require(type(preflight["schemaVersion"]) is int and preflight["schemaVersion"] == 1
                and all(preflight[key] is False for key in ("lineageEvaluated", "installationPerformed",
                        "deviceCompatibilityEvaluated", "configurationMigrationEvaluated")),
                "Preflight scope or schema changed")
        require(digest(preflight["trustedCertificateSha256"]), "Invalid preflight certificate pin")
        tools = preflight["tools"]
        require(isinstance(tools, dict) and set(tools) == {"apksigner", "aapt2"}
                and isinstance(tools["apksigner"], str) and re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,3}", tools["apksigner"])
                and isinstance(tools["aapt2"], str) and re.fullmatch(r"Android Asset Packaging Tool \(aapt\) [0-9A-Za-z.\-]+", tools["aapt2"]),
                "Invalid preflight tool versions")
        for role, version in (("baseline", 1), ("candidate", 2)):
            value = preflight[role]
            require(isinstance(value, dict) and set(value) == {"bytes", "sha256", "package", "versionCode",
                    "minSdk", "targetSdk", "currentSignerCount", "currentSignerSha256"}, "Invalid preflight APK fields")
            require(all(type(value[key]) is int for key in ("bytes", "versionCode", "minSdk", "targetSdk", "currentSignerCount"))
                    and value["versionCode"] == version and value["currentSignerCount"] == 1,
                    "Invalid preflight APK numeric identity")
        require(build is not None, "Collected preflight has no source-bound build manifest")
        probe.validate_preflight(preflight, build)
    if "signing-certificate.der" in files:
        require(build is not None and files["signing-certificate.der"]["sha256"] == build["certificateSha256"],
                "Exported certificate differs from the pinned identity")
    for name in ("seed-receipt.json", "verify-receipt.json"):
        if name in files:
            receipt = json_data(files[name]["data"])
            require(isinstance(receipt, dict) and type(receipt.get("schemaVersion")) is int
                    and type(receipt.get("runId")) is str and receipt["runId"] == str(source["runId"])
                    and type(receipt.get("runAttempt")) is str and receipt["runAttempt"] == str(source["attempt"]),
                    "Receipt source scalar types differ from the instrumentation protocol")
    if report["status"] == "passed":
        require(all(name in files for name in ("build-manifest.json", "preflight.json", "signing-certificate.der",
                                               "seed-receipt.json", "verify-receipt.json")),
                "Passed upgrade lacks original build or phase evidence")
    probe.validate_report(report, source, label, files)
    return report["status"]


def validate_report(report, label, source, files):
    try:
        return _validate_report(report, label, source, files)
    except (KeyError, TypeError, AttributeError, IndexError):
        raise ValueError("Malformed upgrade protocol structure") from None


def archive(incoming, output, run, artifact_index, repo=None):
    source = verify_source(run)
    selected = eligible_artifacts(artifact_index, run)
    expected = f"screenshots/apk-upgrade-{source['runId']}-{source['attempt']}"
    require(str(output) == expected, "Archive output must match the source run")
    no_link_parents(repo or Path.cwd())
    repo = Path(repo or Path.cwd()).resolve()
    destination = repo / expected
    require(not (repo / "screenshots").is_symlink() and not destination.exists()
            and not destination.is_symlink(), "Archive output exists or is linked")
    incoming = Path(incoming)
    no_link_parents(incoming)
    records, staged, build_identity = [], [], {}
    budget = {"files": 0, "bytes": 0}
    for label in LABELS:
        artifact = selected.get(label)
        record = {"matrixLabel": label, "apiLevel": int(label.split(".")[0]), "caseId": CASE_ID,
                  "status": "unverified", "reason": "current_attempt_artifact_unavailable",
                  "captureCounts": {}, "screenshotCount": 0, "files": []}
        if artifact is None or artifact["expired"]:
            if artifact is not None:
                record.update(reason="current_attempt_artifact_expired", artifactId=artifact["id"])
            records.append(record)
            continue
        files = scan_files(incoming / artifact["name"], budget)
        report = json_data(files["report.json"]["data"])
        status = validate_report(report, label, source, files)
        for name in ("build-manifest.json", "preflight.json", "signing-certificate.der"):
            if name in files:
                require(name not in build_identity or build_identity[name] == files[name]["sha256"],
                        "APK build identity differs between API lanes")
                build_identity[name] = files[name]["sha256"]
        record.update(status=status, reason=report.get("reason"), artifactId=artifact["id"],
                      artifactDigest=artifact["digest"], device=report.get("device"),
                      phases=report["phases"], executionAttempted=report["executionAttempted"],
                      workflowOutcome=report.get("workflowOutcome"),
                      captureCounts=dict(Counter(capture["status"] for capture in report.get("captures", []))),
                      files=[{k: v for k, v in item.items() if k != "data"} for item in files.values()])
        records.append(record)
        staged.append((label, files))
    counts = Counter(record["status"] for record in records)
    require(sum(counts.values()) == 8, "Upgrade denominator changed")
    manifest = {"schemaVersion": 1, "kind": "agentos-apk-upgrade-smoke-evidence",
                "archivedAt": datetime.now(timezone.utc).isoformat(),
                "sourceRun": {**source, "url": f"https://github.com/{REPOSITORY}/actions/runs/{source['runId']}",
                              "conclusion": run["conclusion"], "event": "workflow_dispatch", "branch": "master"},
                "baselineSource": BASELINE_SOURCE, "expectedMatrixLabels": list(LABELS),
                "caseId": CASE_ID, "expectedCombinationCount": 8, "apis": records,
                "stateCounts": {key: counts[key] for key in ("passed", "failed", "unverified")},
                "allUpgradeCasesPassed": run["conclusion"] == "success" and counts["passed"] == 8,
                "scope": {"deviceKind": "dedicated_emulator", "phasesPerCombination": 2,
                          "signingPolicy": "same-current-certificate-pinned", "temporaryDevelopmentCertificate": True,
                          "productionSigningValidated": False, "signerRotationValidated": False,
                          "physicalDeviceValidated": False, "releaseAccepted": False},
                "buildIdentityFileHashes": build_identity, "fileCount": budget["files"],
                "totalBytes": budget["bytes"], "screenshotCount": 0}
    readme = ("# Development APK in-place upgrade evidence\n\n"
              f"Source: `{source['commit']}`; [run {source['runId']}](https://github.com/{REPOSITORY}/actions/runs/{source['runId']})"
              f", attempt {source['attempt']}. Baseline: `{BASELINE_SOURCE}`.\n\n"
              "Each API contributes one continuity case across seed and verification phases. "
              "Missing and failed lanes remain in the eight-case denominator.\n\n"
              f"Passed: {counts['passed']}; failed: {counts['failed']}; unverified: {counts['unverified']}.\n\n"
              "Protected configuration is recorded as blocked capture; no PNG is generated. "
              "Blocked capture entries describe the no-capture policy; only phase results establish which steps ran. "
              "This exact development certificate experiment does not validate production signing, "
              "signer rotation, physical devices or release readiness.\n")
    (repo / "screenshots").mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".apk-upgrade-archive-", dir=repo / "screenshots"))
    try:
        for label, files in staged:
            for name, item in files.items():
                target = temporary / ("api-" + label) / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(item["data"])
                target.chmod(0o644)
        (temporary / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (temporary / "README.md").write_text(readme, encoding="utf-8")
        require(not destination.exists() and not destination.is_symlink(), "Archive output appeared during validation")
        temporary.rename(destination)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate-source", "archive"))
    parser.add_argument("--source-run-json", required=True, type=Path)
    parser.add_argument("--artifact-index", required=True, type=Path)
    parser.add_argument("--incoming", type=Path)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--output")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args(argv)
    try:
        run, index = read_json(args.source_run_json), read_json(args.artifact_index)
        source = verify_source(run)
        artifacts = eligible_artifacts(index, run)
        path = f"screenshots/apk-upgrade-{source['runId']}-{source['attempt']}"
        if args.command == "validate-source":
            result = {**source, "evidencePath": path,
                      "artifactIds": ",".join(str(artifacts[label]["id"]) for label in LABELS
                                              if label in artifacts and not artifacts[label]["expired"])}
            if args.github_output:
                require(not args.github_output.is_symlink(), "GitHub output cannot be linked")
                with args.github_output.open("a", encoding="utf-8") as stream:
                    for key, value in result.items():
                        stream.write(f"{key}={value}\n")
        else:
            require(args.incoming is not None, "Archive requires --incoming")
            result = archive(args.incoming, args.output or path, run, index, args.repo)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (ValueError, OSError) as failure:
        print("APK upgrade archive rejected: " + str(failure))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
