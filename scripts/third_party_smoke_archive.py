#!/usr/bin/env python3
"""Archive bounded evidence from the dedicated third-party emulator probe.

This validates provenance and reported results, not phone compatibility, UI pixels,
or release acceptance. It performs no download, device operation or Git mutation.
"""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile


REPOSITORY = "ecojust/Agent-OS"
WORKFLOW = ".github/workflows/third-party-smoke.yml"
LABELS = ("30", "35", "37.0")
STATES = ("off", "idle")
MAX_JSON_BYTES = 2 * 1024 * 1024
MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024
MAX_FILES = 256
MAX_ENTRIES = 512
PNG = b"\x89PNG\r\n\x1a\n"
APP_FIELDS = ("package", "versionName", "versionCode", "sha256", "certificateSha256")
CORE_STEPS = ("service_state_before", "calculator_result", "service_state_after")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def positive_int(value):
    return type(value) is int and 0 < value <= 2**63 - 1


def text(value, maximum=1024):
    return isinstance(value, str) and 0 < len(value) <= maximum and "\x00" not in value


def timestamp(value):
    require(text(value, 64), "missing source timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError("invalid source timestamp") from exc
    require(parsed.tzinfo is not None and parsed.utcoffset() is not None, "source timestamp needs timezone")
    return parsed


def no_duplicates(pairs):
    value = {}
    for key, item in pairs:
        require(key not in value, "duplicate JSON key")
        value[key] = item
    return value


def json_depth(value, level=0):
    require(level <= 32, "JSON nesting exceeds limit")
    if isinstance(value, dict):
        for item in value.values():
            json_depth(item, level + 1)
    elif isinstance(value, list):
        for item in value:
            json_depth(item, level + 1)


def json_data(data):
    require(len(data) <= MAX_JSON_BYTES, "JSON exceeds size limit")
    try:
        value = json.loads(data, object_pairs_hook=no_duplicates,
                           parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite JSON number")))
        json_depth(value)
    except (UnicodeError, RecursionError, json.JSONDecodeError) as exc:
        raise ValueError("invalid JSON evidence") from exc
    return value


def read_json(path):
    path = Path(path)
    require(not path.is_symlink() and path.is_file(), "JSON input must be a regular file")
    require(path.stat().st_size <= MAX_JSON_BYTES, "JSON exceeds size limit")
    return json_data(path.read_bytes())


def verify_source(run):
    require(isinstance(run, dict), "invalid source run")
    repository = run.get("repository", {})
    head = run.get("head_repository", {})
    require(isinstance(repository, dict) and isinstance(head, dict), "invalid source repository")
    require(repository.get("full_name") == head.get("full_name") == REPOSITORY
            and positive_int(repository.get("id")) and positive_int(head.get("id"))
            and repository.get("id") == head.get("id"),
            "source must belong to this repository, not a fork")
    require(run.get("event") == "workflow_dispatch" and run.get("head_branch") == "master"
            and run.get("path") == WORKFLOW and run.get("status") == "completed",
            "source must be a completed manual third-party smoke run on master")
    require(re.fullmatch(r"[0-9a-f]{40}", str(run.get("head_sha", ""))) is not None
            and positive_int(run.get("id")) and positive_int(run.get("run_attempt")),
            "invalid source run identity")
    require(text(run.get("conclusion"), 64), "completed source needs a conclusion")
    require(timestamp(run.get("run_started_at")) <= timestamp(run.get("updated_at")), "invalid source time interval")
    return {"repository": REPOSITORY, "runId": run["id"],
            "attempt": run["run_attempt"], "commit": run["head_sha"]}


def artifact_entries(value):
    pages = value if isinstance(value, list) else [value]
    require(len(pages) <= 20, "too many artifact pages")
    entries = []
    for page in pages:
        require(isinstance(page, dict) and isinstance(page.get("artifacts"), list), "invalid artifact index")
        entries.extend(page["artifacts"])
    require(len(entries) <= 2000, "too many artifacts")
    return entries


def eligible_artifacts(index, run, version=1):
    identity = verify_source(run)
    require(type(version) is int and version in (1, 2, 3, 4), "unknown evidence version")
    prefix = "third-party-smoke-api-" if version == 1 else f"third-party-smoke-v{version}-api-"
    labels = r"(30|35|37\.0)" if version == 1 else r"(30|31|32|33|34|35|36|37\.0)"
    pattern = re.compile(prefix + labels + "-attempt-" + str(identity["attempt"]))
    selected = {}
    for item in artifact_entries(index):
        require(isinstance(item, dict), "invalid artifact metadata")
        match = pattern.fullmatch(str(item.get("name", "")))
        if not match:
            continue
        label = match.group(1)
        require(label not in selected, "duplicate current-attempt artifact")
        origin = item.get("workflow_run", {})
        require(isinstance(origin, dict), "invalid artifact origin")
        require(positive_int(item.get("id")) and positive_int(item.get("size_in_bytes"))
                and item["size_in_bytes"] <= MAX_TOTAL_BYTES and type(item.get("expired")) is bool,
                "invalid artifact id, size or expiry")
        require(positive_int(origin.get("id")) and positive_int(origin.get("repository_id"))
                and positive_int(origin.get("head_repository_id"))
                and origin.get("id") == identity["runId"] and origin.get("head_sha") == identity["commit"]
                and origin.get("head_branch") == "master"
                and origin.get("repository_id") == run["repository"]["id"]
                and origin.get("head_repository_id") == run["repository"]["id"],
                "artifact source differs from trusted run")
        require(timestamp(run["run_started_at"]) <= timestamp(item.get("created_at")) <= timestamp(run["updated_at"]),
                "artifact was not uploaded during the current source attempt")
        selected[label] = item
    return selected


def eligible_run_artifacts(index, run):
    """Select one current-attempt evidence protocol, never mix old and new lanes."""
    protocols = [eligible_artifacts(index, run, version) for version in (1, 2, 3, 4)]
    require(sum(bool(items) for items in protocols) <= 1, "source attempt contains mixed evidence versions")
    selected = next((items for items in protocols if items), {})
    ids = [item["id"] for item in selected.values()]
    require(len(ids) == len(set(ids)), "artifact ID is reused across matrix lanes")
    return selected


def validate_sample(sample):
    require(isinstance(sample, dict) and sample.get("schemaVersion") == 1
            and type(sample.get("schemaVersion")) is int and sample.get("kind") == "third-party-smoke-lock",
            "invalid locked sample")
    app = sample.get("app")
    require(isinstance(app, dict) and all(text(app.get(field)) for field in APP_FIELDS), "locked app identity is incomplete")
    require(app["package"] == "org.fossify.math" and app["versionName"] == "1.4.0" and app["versionCode"] == "10",
            "unsupported smoke sample")
    for field in ("sha256", "certificateSha256"):
        require(re.fullmatch(r"[0-9a-f]{64}", app[field]) is not None, "invalid locked app digest")
    require(sample.get("apis") == [30, 35, 37], "locked sample must retain all three APIs")
    scope = sample.get("scope", {})
    require(isinstance(scope, dict) and scope.get("deviceKind") == "dedicated_emulator"
            and scope.get("states") == list(STATES), "locked sample scope changed")
    return app


def relative_path(value):
    require(text(value, 240) and "\\" not in value, "invalid evidence path")
    parts = value.split("/")
    require(not value.startswith("/") and len(parts) <= 6
            and all(re.fullmatch(r"[A-Za-z0-9_.-]+", part) and part not in (".", "..") for part in parts),
            "evidence path escapes or is malformed")
    return PurePosixPath(value)


def scan_files(source, budget):
    source = Path(source)
    require(source.is_dir() and not source.is_symlink(), "artifact directory is missing or linked")
    found = {}
    pending = [(source, 0)]
    entries = 0
    while pending:
        directory, depth = pending.pop()
        require(depth <= 5, "artifact nesting exceeds limit")
        for item in sorted(directory.iterdir()):
            entries += 1
            require(entries <= MAX_ENTRIES, "too many artifact entries")
            require(not item.is_symlink(), "symlinks are not evidence")
            name = item.relative_to(source).as_posix()
            relative_path(name)
            if item.is_dir():
                pending.append((item, depth + 1))
                continue
            require(item.is_file() and item.suffix.lower() in (".json", ".png"), "unexpected evidence file type")
            size = item.stat().st_size
            require(size <= MAX_FILE_BYTES, "evidence file exceeds size limit")
            budget["files"] += 1
            budget["bytes"] += size
            require(budget["files"] <= MAX_FILES and budget["bytes"] <= MAX_TOTAL_BYTES,
                    "evidence archive exceeds budget")
            data = item.read_bytes()
            require(len(data) == size, "evidence changed during reading")
            if item.suffix.lower() == ".json":
                json_data(data)
            else:
                require(len(data) >= 33 and data.startswith(PNG) and data[8:12] == b"\x00\x00\x00\r"
                        and data[12:16] == b"IHDR", "invalid PNG evidence")
                width, height = int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")
                require(0 < width <= 4096 and 0 < height <= 4096, "PNG dimensions exceed limit")
            found[name] = {"path": name, "bytes": size, "sha256": hashlib.sha256(data).hexdigest(), "data": data}
    return found


def validate_report(report, label, identity, locked_app, files):
    require(isinstance(report, dict) and type(report.get("schemaVersion")) is int and report["schemaVersion"] == 1
            and report.get("kind") == "third-party-app-smoke", "invalid probe report schema")
    source = report.get("source")
    require(isinstance(source, dict) and positive_int(source.get("runId")) and positive_int(source.get("attempt"))
            and source == identity, "probe report source does not match trusted run")
    require(report.get("status") in ("passed", "failed", "environment_failed"), "invalid probe status")
    device, app = report.get("device"), report.get("app")
    require(isinstance(device, dict) and isinstance(app, dict), "missing device or app identity")
    expected_api = int(label.split(".")[0])
    api = device.get("apiLevel")
    require(api is None or type(api) is int and api == expected_api, "probe API differs from artifact lane")
    require(api is not None or report["status"] == "environment_failed", "missing device cannot pass a probe")
    require(all(app.get(key) == locked_app[key] for key in APP_FIELDS), "probe app differs from locked identity")
    actual_identity = report.get("actualInstalledIdentityVerified")
    require(type(actual_identity) is bool, "missing installed identity verification state")
    records = report.get("states")
    require(isinstance(records, list) and len(records) <= 2, "invalid state results")
    state_map, referenced = {}, set()
    capture_counts = Counter()
    for state in records:
        require(isinstance(state, dict) and state.get("state") in STATES and state["state"] not in state_map,
                "duplicate or unknown probe state")
        require(state.get("status") in ("passed", "failed", "unverified"), "invalid state status")
        name = state["state"]
        steps, captures = state.get("steps"), state.get("captures")
        require(isinstance(steps, list) and len(steps) <= 64 and isinstance(captures, list) and len(captures) <= 12,
                "invalid state steps or captures")
        for step in steps:
            require(isinstance(step, dict) and text(step.get("name"), 96)
                    and step.get("status") in ("passed", "failed", "unverified"), "invalid step record")
        for capture in captures:
            require(isinstance(capture, dict) and text(capture.get("stage"), 96)
                    and capture.get("status") in ("captured", "blocked", "unavailable"), "invalid capture record")
            status = capture["status"]
            capture_counts[status] += 1
            if status == "captured":
                path = str(relative_path(capture.get("path")))
                require(path.endswith(".png") and path in files and path not in referenced,
                        "missing or reused screenshot file")
                require(type(capture.get("bytes")) is int and capture["bytes"] == files[path]["bytes"]
                        and capture.get("sha256") == files[path]["sha256"], "screenshot digest or size differs")
                referenced.add(path)
            else:
                require(text(capture.get("reason"), 2048) and not any(key in capture for key in ("path", "sha256", "bytes")),
                        "uncaptured screen needs a reason and no image reference")
        if state["status"] == "passed":
            expected_state = "disabled" if name == "off" else "bound_idle"
            require(actual_identity and state.get("installedIdentityVerified") is True and api == expected_api
                    and re.fullmatch(r"emulator-[0-9]{1,6}", str(device.get("serial", ""))) is not None,
                    "passed state lacks verified installed app or emulator identity")
            names = Counter(step["name"] for step in steps)
            require(all(names[key] == 1 for key in CORE_STEPS) and all(step["status"] == "passed" for step in steps),
                    "passed state lacks successful independent checks")
            require(state.get("serviceState") == expected_state, "passed state has the wrong Agent service state")
            observations = state.get("serviceObservations")
            require(isinstance(observations, list) and len(observations) == 2, "missing before/after service observations")
            for observation, stage in zip(observations, ("before", "after")):
                require(isinstance(observation, dict) and observation.get("stage") == stage
                        and observation.get("state") == expected_state and observation.get("verified") is True,
                        "unverified Agent service observation")
            result = state.get("result")
            require(isinstance(result, dict) and result.get("expected") == result.get("actual") == "5"
                    and result.get("resourceId") == "org.fossify.math:id/result",
                    "passed state lacks the independent calculator result")
            require(captures, "passed state has no screenshot attempt")
        state_map[name] = state
    require({name for name in files if name.endswith(".png")} == referenced, "unreferenced screenshot file")
    if report["status"] == "passed":
        require(set(state_map) == set(STATES) and all(state_map[name]["status"] == "passed" for name in STATES),
                "passed probe lacks both successful states")
    return state_map, dict(capture_counts)


def archive(incoming, output, run, artifact_index, sample, repo=None):
    identity = verify_source(run)
    selected = eligible_artifacts(artifact_index, run)
    locked = validate_sample(sample)
    repo = Path(repo or Path.cwd()).resolve()
    expected = f"screenshots/third-party-{identity['runId']}-{identity['attempt']}"
    require(str(output) == expected, "archive output must match the source run under screenshots")
    destination = repo / expected
    require(not (repo / "screenshots").is_symlink() and not destination.exists() and not destination.is_symlink(),
            "archive destination already exists or is linked")
    incoming = Path(incoming)
    require(not incoming.is_symlink(), "incoming directory cannot be linked")
    records, staged, budget = [], [], {"files": 0, "bytes": 0}
    for label in LABELS:
        record = {"matrixLabel": label, "apiLevel": int(label.split(".")[0]), "status": "environment_failed",
                  "reason": "current_attempt_artifact_unavailable", "states": {name: "unverified" for name in STATES},
                  "captureCounts": {}, "screenshotCount": 0, "files": []}
        artifact = selected.get(label)
        if artifact is None or artifact["expired"]:
            if artifact is not None:
                record.update({"reason": "current_attempt_artifact_expired", "artifactId": artifact["id"]})
            records.append(record)
            continue
        source = incoming / artifact["name"]
        require(source.is_dir() and not source.is_symlink(), "verified artifact was not downloaded")
        files = scan_files(source, budget)
        require("report.json" in files, "artifact has no report.json")
        report = json_data(files["report.json"]["data"])
        state_map, capture_counts = validate_report(report, label, identity, locked, files)
        record.update({"status": report["status"], "reason": report.get("reason"), "artifactId": artifact["id"],
                       "device": report["device"], "actualInstalledIdentityVerified": report["actualInstalledIdentityVerified"],
                       "states": {name: state_map.get(name, {}).get("status", "unverified") for name in STATES},
                       "captureCounts": capture_counts, "screenshotCount": capture_counts.get("captured", 0),
                       "files": [{key: value for key, value in item.items() if key != "data"} for item in files.values()]})
        staged.append((label, files))
        records.append(record)
    state_counts = Counter(status for record in records for status in record["states"].values())
    manifest = {"schemaVersion": 1, "kind": "third-party-emulator-smoke-evidence",
                "archivedAt": datetime.now(timezone.utc).isoformat(),
                "sourceRun": {**identity, "url": f"https://github.com/{REPOSITORY}/actions/runs/{identity['runId']}",
                              "conclusion": run["conclusion"], "event": "workflow_dispatch", "branch": "master"},
                "scope": {"deviceKind": "dedicated_emulator", "states": list(STATES),
                          "executingStateValidated": False, "upgradeValidated": False, "physicalDeviceValidated": False,
                          "releaseAccepted": False, "note": "Small fixed-version developer probe; not general application or phone compatibility acceptance."},
                "sampleId": sample.get("sampleId"), "app": locked, "expectedMatrixLabels": list(LABELS),
                "apis": records, "stateCounts": {name: state_counts[name] for name in ("passed", "failed", "unverified")},
                "allProbeStatesPassed": run["conclusion"] == "success" and all(record["status"] == "passed" for record in records),
                "fileCount": budget["files"], "totalBytes": budget["bytes"],
                "screenshotCount": sum(record["screenshotCount"] for record in records)}
    (repo / "screenshots").mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".third-party-archive-", dir=repo / "screenshots"))
    try:
        for label, files in staged:
            for name, item in files.items():
                target = temporary / f"api-{label}" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(item["data"])
                target.chmod(0o644)
        (temporary / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        require(not destination.exists() and not destination.is_symlink(), "archive destination appeared during validation")
        temporary.rename(destination)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate-source", "archive"))
    parser.add_argument("--source-run-json", type=Path, required=True)
    parser.add_argument("--artifact-index", type=Path, required=True)
    parser.add_argument("--incoming", type=Path)
    parser.add_argument("--sample", type=Path)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--output")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args(argv)
    try:
        run, index = read_json(args.source_run_json), read_json(args.artifact_index)
        identity = verify_source(run)
        artifacts = eligible_run_artifacts(index, run)
        path = f"screenshots/third-party-{identity['runId']}-{identity['attempt']}"
        if args.command == "validate-source":
            result = {**identity, "evidencePath": path,
                      "artifactIds": ",".join(str(item["id"]) for item in artifacts.values() if not item["expired"])}
            if args.github_output:
                require(not args.github_output.is_symlink(), "GitHub output cannot be a symlink")
                with args.github_output.open("a", encoding="utf-8") as stream:
                    for key, value in result.items():
                        stream.write(f"{key}={value}\n")
        else:
            require(args.incoming is not None, "archive requires --incoming")
            # After the workflow checks out the exact source SHA, use its lock.
            # Earlier sources retain their original scripts and v1/v2/v3 contracts.
            selected_sample = args.sample
            if selected_sample is None:
                newer = args.repo / "docs/validation/third-party-app-set-lock.json"
                selected_sample = newer if newer.exists() else args.repo / "docs/validation/third-party-calculator-lock.json"
            sample = read_json(selected_sample)
            if type(sample.get("schemaVersion")) is int and sample["schemaVersion"] == 4:
                from third_party_smoke_set_archive import archive as archive_set
                result = archive_set(args.incoming, args.output or path, run, index, sample, args.repo)
            else:
                result = archive(args.incoming, args.output or path, run, index, sample, args.repo)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (ValueError, OSError) as exc:
        print("Third-party smoke archive rejected: " + str(exc))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
