#!/usr/bin/env python3
"""Freeze app samples and audit reported results; never certify device compatibility."""

import argparse
from datetime import datetime, timezone
import hashlib
import ipaddress
import json
from pathlib import Path, PurePosixPath
import re
import sys
from urllib.parse import urlsplit


STATES = ("off", "idle", "executing")
STATUSES = ("passed", "limited", "failed", "unverified")
MAX_JSON_BYTES = 8 * 1024 * 1024
MAX_JSON_DEPTH = 64
MAX_EVIDENCE_BYTES = 16 * 1024 * 1024
ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def fields(value, required, optional=()):
    require(isinstance(value, dict), "expected an object")
    require(set(required) <= set(value), "missing fields: " + ", ".join(sorted(set(required) - set(value))))
    require(set(value) <= set(required) | set(optional), "unknown fields: " + ", ".join(sorted(set(value) - set(required) - set(optional))))


def text(value, label):
    require(isinstance(value, str) and 0 < len(value.strip()) <= 4096, label + " must be nonempty text")


def timestamp(value):
    text(value, "timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError("invalid timestamp") from error
    require(parsed.tzinfo is not None, "timestamp must include timezone")
    return parsed


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON key: " + key)
        result[key] = value
    return result


def read_json(path):
    with Path(path).open("rb") as stream:
        data = stream.read(MAX_JSON_BYTES + 1)
    require(len(data) <= MAX_JSON_BYTES, "JSON exceeds size limit")
    try:
        value = json.loads(data, object_pairs_hook=unique_object,
                           parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite JSON number")))
    except RecursionError as error:
        raise ValueError("JSON exceeds nesting limit") from error
    pending = [(value, 0)]
    while pending:
        node, depth = pending.pop()
        require(depth <= MAX_JSON_DEPTH, "JSON exceeds nesting limit")
        if isinstance(node, dict):
            pending.extend((child, depth + 1) for child in node.values())
        elif isinstance(node, list):
            pending.extend((child, depth + 1) for child in node)
    return value


def validate_source_url(value):
    text(value, "source.url")
    require(not any(character.isspace() or ord(character) < 32 for character in value)
            and "\\" not in value, "source URL cannot contain whitespace, controls or backslashes")
    try:
        parsed = urlsplit(value)
        require(parsed.scheme == "https" and parsed.hostname, "source URL needs HTTPS and a hostname")
        require(parsed.username is None and parsed.password is None, "source URL cannot contain credentials")
        # Accessing port validates its numeric range and syntax.
        _ = parsed.port
        host = parsed.hostname
        try:
            ipaddress.ip_address(host)
        except ValueError:
            ascii_host = host.encode("idna").decode("ascii").removesuffix(".")
            require(len(ascii_host) <= 253 and all(re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", label)
                    for label in ascii_host.split(".")), "source URL has invalid hostname")
    except (ValueError, UnicodeError) as error:
        raise ValueError("invalid source URL: " + str(error)) from error


def write_new(path, value):
    # Existing records are immutable through this CLI. Validation happens before opening output.
    encoded = json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    with Path(path).open("x", encoding="utf-8") as stream:
        stream.write(encoded)


def validate_sample(sample):
    fields(sample, ("schema_version", "sample_id", "evidence_kind", "frozen_by", "environment", "apps"))
    require(type(sample["schema_version"]) is int and sample["schema_version"] == 1, "unsupported sample schema")
    for name in ("sample_id", "frozen_by"):
        text(sample[name], name)
    require(sample["evidence_kind"] in ("actual", "synthetic"), "invalid evidence_kind")
    env = sample["environment"]
    fields(env, ("device_model", "device_kind", "android_api", "build_fingerprint", "agent_version", "model_id",
                 "region", "services", "network", "physical_sim", "enabled_esim"))
    for name in ("device_model", "build_fingerprint", "agent_version", "model_id", "region", "services"):
        text(env[name], name)
    require(env["device_kind"] in ("physical_device", "emulator"), "invalid device_kind")
    require(type(env["android_api"]) is int and env["android_api"] >= 30, "android_api must be at least 30")
    require(env["network"] == "wifi" and env["physical_sim"] is False and env["enabled_esim"] is False,
            "first-version sample requires Wi-Fi, no physical SIM and no enabled eSIM")
    apps = sample["apps"]
    require(isinstance(apps, list) and 0 < len(apps) <= 2000, "sample needs 1..2000 apps")
    packages = set()
    for app in apps:
        fields(app, ("package", "version_name", "version_code", "certificate_sha256", "source",
                     "category", "ordinary", "critical", "dependencies", "flows"))
        package = app["package"]
        require(isinstance(package, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", package),
                "invalid Android package")
        require(package not in packages, "duplicate package: " + package)
        packages.add(package)
        for name in ("version_name", "version_code", "category"):
            text(app[name], name)
        require(re.fullmatch(r"0|[1-9][0-9]{0,18}", app["version_code"])
                and int(app["version_code"]) <= 2**63 - 1,
                "version_code must be canonical nonnegative decimal within signed long range")
        require(isinstance(app["certificate_sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", app["certificate_sha256"]),
                "certificate_sha256 must be a lowercase SHA-256")
        fields(app["source"], ("url", "channel", "authorization"))
        for name in app["source"]:
            text(app["source"][name], "source." + name)
        validate_source_url(app["source"]["url"])
        require(type(app["ordinary"]) is bool and type(app["critical"]) is bool, "groups must be explicit booleans")
        deps = app["dependencies"]
        require(isinstance(deps, list) and all(isinstance(d, str) and d.strip() for d in deps), "invalid dependencies")
        require(len(set(deps)) == len(deps), "duplicate dependencies")
        flows = app["flows"]
        require(isinstance(flows, list) and 0 < len(flows) <= 100, "app needs 1..100 flows")
        ids, dimensions = set(), set()
        for flow in flows:
            fields(flow, ("id", "description", "dimension", "states", "capture_policy"))
            text(flow["id"], "flow id")
            text(flow["description"], "flow description")
            require(flow["id"] not in ids, "duplicate flow id")
            ids.add(flow["id"])
            dimension = flow["dimension"]
            require(dimension in ("installation", "daily", "agent"), "invalid flow dimension")
            dimensions.add(dimension)
            require(flow["capture_policy"] in ("ui", "nonvisual"), "invalid frozen capture_policy")
            expected = ["executing"] if dimension == "agent" else list(STATES)
            require(flow["states"] == expected, "daily/installation require all three states; agent uses executing")
        require({"installation", "daily"} <= dimensions, "each app needs installation and daily flows")


def freeze(sample, frozen_at=None):
    validate_sample(sample)
    payload = {"schema_version": 1, "frozen_at": frozen_at or datetime.now(timezone.utc).isoformat(), "sample": sample}
    timestamp(payload["frozen_at"])
    return dict(payload, sample_sha256=digest(payload))


def validate_frozen(frozen):
    fields(frozen, ("schema_version", "frozen_at", "sample", "sample_sha256"))
    require(type(frozen["schema_version"]) is int and frozen["schema_version"] == 1, "unsupported frozen schema")
    timestamp(frozen["frozen_at"])
    validate_sample(frozen["sample"])
    payload = {key: value for key, value in frozen.items() if key != "sample_sha256"}
    require(frozen["sample_sha256"] == digest(payload), "frozen sample hash mismatch")


def evidence_file(reference, root, screenshot=False):
    fields(reference, ("path", "sha256"))
    path = reference["path"]
    require(isinstance(path, str) and "\\" not in path, "evidence path must be repository-relative POSIX path")
    relative = PurePosixPath(path)
    require(not relative.is_absolute() and len(relative.parts) >= 2 and relative.parts[0] == "screenshots"
            and not any(part in (".", "..") for part in path.split("/")), "evidence must remain under screenshots/")
    candidate = Path(root)
    for part in relative.parts:
        candidate = candidate / part
        require(not candidate.is_symlink(), "symlink evidence is not allowed")
    require(candidate.is_file(), "evidence file does not exist: " + path)
    require(candidate.resolve().is_relative_to(Path(root).resolve() / "screenshots"), "evidence escapes screenshots/")
    require(candidate.stat().st_size <= MAX_EVIDENCE_BYTES, "evidence file exceeds size limit")
    with candidate.open("rb") as stream:
        data = stream.read(MAX_EVIDENCE_BYTES + 1)
    require(len(data) <= MAX_EVIDENCE_BYTES, "evidence file exceeds size limit")
    require(hashlib.sha256(data).hexdigest() == reference["sha256"], "evidence hash mismatch: " + path)
    if screenshot:
        valid = ((candidate.suffix.lower() == ".png" and data.startswith(b"\x89PNG\r\n\x1a\n"))
                 or (candidate.suffix.lower() in (".jpg", ".jpeg") and data.startswith(b"\xff\xd8\xff")))
        require(valid, "captured screenshot must reference a PNG or JPEG")


def validate_result(result, frozen, root):
    fields(result, ("id", "package", "version_name", "version_code", "flow_id", "state", "status", "tested_at",
                    "operator", "method", "notes", "dependency_status", "evidence"), ("supersedes",))
    for name in ("id", "operator", "method", "notes"):
        text(result[name], name)
    require(timestamp(result["tested_at"]) >= timestamp(frozen["frozen_at"]), "result predates sample freeze")
    app = next((a for a in frozen["sample"]["apps"] if a["package"] == result["package"]), None)
    require(app is not None, "result package not in frozen sample")
    require((result["version_name"], result["version_code"]) == (app["version_name"], app["version_code"]),
            "result version differs from frozen app")
    flow = next((f for f in app["flows"] if f["id"] == result["flow_id"]), None)
    require(flow is not None and result["state"] in flow["states"], "result flow/state not in frozen sample")
    require(result["status"] in STATUSES, "invalid result status")
    dependencies = result["dependency_status"]
    require(isinstance(dependencies, dict) and set(dependencies) == set(app["dependencies"]),
            "result must report every frozen dependency")
    require(all(value in ("met", "missing", "unknown") for value in dependencies.values()), "invalid dependency status")
    if result["status"] == "passed":
        require(all(value == "met" for value in dependencies.values()), "missing/unknown dependencies cannot pass")
    evidence = result["evidence"]
    fields(evidence, ("capture_status", "reason", "screenshots", "reports"))
    text(evidence["reason"], "capture reason")
    capture = evidence["capture_status"]
    require(capture in ("captured", "protected", "nonvisual", "unavailable", "not_collected"), "invalid capture status")
    require(capture != "nonvisual" or flow["capture_policy"] == "nonvisual",
            "UI flow cannot change its frozen capture_policy to nonvisual")
    require(isinstance(evidence["screenshots"], list) and isinstance(evidence["reports"], list), "invalid evidence lists")
    require(len(evidence["screenshots"]) <= 20 and len(evidence["reports"]) <= 20, "too many evidence files")
    require(bool(evidence["screenshots"]) == (capture == "captured"), "capture status contradicts screenshots")
    for reference in evidence["screenshots"]:
        evidence_file(reference, root, screenshot=True)
    for reference in evidence["reports"]:
        evidence_file(reference, root)
    if result["status"] == "passed":
        require(capture in ("captured", "protected", "nonvisual"), "missing capture must remain unverified")
        require(evidence["reports"], "passed results need a report; screenshot existence is not task evidence")
    return result["package"], result["flow_id"], result["state"]


def validate_ledger(ledger, frozen, root):
    fields(ledger, ("schema_version", "sample_sha256", "evidence_kind", "environment", "results", "ledger_sha256"))
    require(type(ledger["schema_version"]) is int and ledger["schema_version"] == 1
            and ledger["sample_sha256"] == frozen["sample_sha256"], "ledger sample mismatch")
    sample = frozen["sample"]
    require(ledger["evidence_kind"] == sample["evidence_kind"] and ledger["environment"] == sample["environment"],
            "ledger evidence kind or device/build/service configuration mismatch")
    require(ledger["ledger_sha256"] == digest({k: v for k, v in ledger.items() if k != "ledger_sha256"}), "ledger hash mismatch")
    results = ledger["results"]
    require(isinstance(results, list) and len(results) <= 100000, "invalid ledger results")
    latest, ids = {}, set()
    for result in results:
        key = validate_result(result, frozen, root)
        require(result["id"] not in ids, "duplicate result id")
        ids.add(result["id"])
        previous = latest.get(key)
        if previous:
            require(result.get("supersedes") == previous["id"], "repeat result must explicitly supersede latest attempt")
            require(timestamp(result["tested_at"]) >= timestamp(previous["tested_at"]), "replacement is older than prior result")
        else:
            require("supersedes" not in result, "supersedes has no matching prior attempt")
        latest[key] = result
    return latest


def import_results(frozen, batch, ledger=None, root=ROOT):
    validate_frozen(frozen)
    fields(batch, ("schema_version", "sample_sha256", "evidence_kind", "environment", "results"))
    require(type(batch["schema_version"]) is int and batch["schema_version"] == 1
            and batch["sample_sha256"] == frozen["sample_sha256"], "batch sample mismatch")
    require(isinstance(batch["results"], list) and batch["results"], "batch must contain results")
    if ledger is not None:
        validate_ledger(ledger, frozen, root)
    combined = dict(batch, results=(ledger["results"] if ledger is not None else []) + batch["results"])
    combined["ledger_sha256"] = digest(combined)
    validate_ledger(combined, frozen, root)
    return combined


def outcome(statuses):
    if "failed" in statuses:
        return "failed"
    if statuses and all(status == "passed" for status in statuses):
        return "passed"
    if any(status in ("passed", "limited") for status in statuses):
        return "limited"
    return "unverified"


def group_summary(rows):
    counts = {status: sum(row["status"] == status for row in rows) for status in STATUSES}
    return dict(denominator=len(rows), counts=counts,
                pass_rate=(counts["passed"] / len(rows) if rows else None))


def summarize(frozen, ledger=None, root=ROOT):
    validate_frozen(frozen)
    latest = validate_ledger(ledger, frozen, root) if ledger is not None else {}
    rows, agent_rows, per_state = [], [], {state: [] for state in STATES}
    per_dimension = {name: [] for name in ("installation", "daily", "agent")}
    for app in frozen["sample"]["apps"]:
        all_statuses, agent_statuses = [], []
        states = {state: [] for state in STATES}
        absent, missing_dependencies = [], set()
        dimensions = {name: [] for name in per_dimension}
        for flow in app["flows"]:
            for state in flow["states"]:
                key = app["package"], flow["id"], state
                result = latest.get(key)
                status = result["status"] if result else "unverified"
                dimensions[flow["dimension"]].append(status)
                if result:
                    missing_dependencies.update(k for k, v in result["dependency_status"].items() if v != "met")
                else:
                    absent.append({"flow_id": flow["id"], "state": state})
                if flow["dimension"] == "agent":
                    agent_statuses.append(status)
                else:
                    all_statuses.append(status)
                    states[state].append(status)
        row = {"package": app["package"], "ordinary": app["ordinary"], "critical": app["critical"],
               "status": outcome(all_statuses), "missing_results": absent,
               "unmet_or_unknown_dependencies": sorted(missing_dependencies)}
        rows.append(row)
        for dimension, statuses in dimensions.items():
            if statuses:
                per_dimension[dimension].append({"status": outcome(statuses)})
        for state in STATES:
            per_state[state].append({"status": outcome(states[state])})
        if agent_statuses:
            agent_rows.append({"package": app["package"], "status": outcome(agent_statuses)})
    groups = {"complete": group_summary(rows),
              "ordinary": group_summary([row for row in rows if row["ordinary"]]),
              "critical": group_summary([row for row in rows if row["critical"]]),
              "special_dependencies": group_summary([row for row, app in zip(rows, frozen["sample"]["apps"]) if app["dependencies"]]),
              "agent_operation": group_summary(agent_rows)}
    def threshold(group, numerator, denominator):
        stats = groups[group]
        return stats["denominator"] > 0 and stats["counts"]["passed"] * denominator >= stats["denominator"] * numerator
    checks = {"at_least_50_apps": len(rows) >= 50,
              "complete_at_least_90_percent": threshold("complete", 90, 100),
              "ordinary_at_least_95_percent": threshold("ordinary", 95, 100),
              "critical_100_percent": threshold("critical", 1, 1),
              "actual_evidence_declared": frozen["sample"]["evidence_kind"] == "actual",
              "physical_device_declared": frozen["sample"]["environment"]["device_kind"] == "physical_device"}
    return {"schema_version": 1, "sample_sha256": frozen["sample_sha256"],
            "ledger_sha256": ledger["ledger_sha256"] if ledger is not None else None,
            "evidence_kind": frozen["sample"]["evidence_kind"], "environment": frozen["sample"]["environment"],
            "groups": groups, "by_agent_state": {state: group_summary(items) for state, items in per_state.items()},
            "by_dimension": {dimension: group_summary(items) for dimension, items in per_dimension.items()},
            "apps": rows, "agent_results": agent_rows,
            "attempts": len(ledger["results"]) if ledger is not None else 0, "current_results": len(latest),
            "gate_checks": checks, "ready_for_human_gate_review": all(checks.values()),
            "release_accepted": False,
            "notice": "Reported results only. Hashes check record integrity, not authenticity. Device, official source, operator and method are self-reported. No ROM, performance, licensing or release certification is granted."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=ROOT, help="repository root containing screenshots/")
    commands = parser.add_subparsers(dest="command", required=True)
    freezing = commands.add_parser("freeze", help="validate sample and create immutable frozen record")
    freezing.add_argument("--sample", type=Path, required=True)
    freezing.add_argument("--output", type=Path, required=True)
    importing = commands.add_parser("import", help="append reported attempts into a new ledger file")
    importing.add_argument("--frozen", type=Path, required=True)
    importing.add_argument("--batch", type=Path, required=True)
    importing.add_argument("--ledger", type=Path)
    importing.add_argument("--output", type=Path, required=True)
    reporting = commands.add_parser("report", help="summarize frozen denominators, including untested apps")
    reporting.add_argument("--frozen", type=Path, required=True)
    reporting.add_argument("--ledger", type=Path)
    reporting.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "freeze":
            result = freeze(read_json(args.sample))
        else:
            frozen = read_json(args.frozen)
            ledger = read_json(args.ledger) if args.ledger else None
            if args.ledger:
                require(isinstance(ledger, dict), "ledger must be an object")
            result = (import_results(frozen, read_json(args.batch), ledger, args.repo) if args.command == "import"
                      else summarize(frozen, ledger, args.repo))
        if args.output:
            write_new(args.output, result)
        else:
            print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (ValueError, OSError, TypeError, KeyError, AttributeError, RecursionError) as error:
        print("app compatibility: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
