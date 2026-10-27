#!/usr/bin/env python3
"""Pinned, disposable-emulator application probes; never phone compatibility certification."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import struct
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SAMPLE = ROOT / "docs/validation/third-party-calculator-lock.json"
REPOSITORY = "ecojust/Agent-OS"
APIS = {"30": 30, "35": 35, "37.0": 37}
PACKAGE = "org.fossify.math"
PROBE_PACKAGE = "dev.agentos.compat"
AGENT_PACKAGE = "dev.agentos.app"
PINNED_SHA = "7c5443c22d6d62c67dfe80df33ea9161ff0fa440f92590bcb45d7008b57613ed"
PINNED_CERT = "affdb124d3f4720c2f98dbca9eacba0514fba4306e20a2786c861c3c0d6ff292"
PINNED_SIZE = 7047681
RESULT_ID = PACKAGE + ":id/result"
MAX_JSON = 512 * 1024
MAX_CAPTURE = 8 * 1024 * 1024
STATES = ("off", "idle")


class ProbeError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise ProbeError(message)


def read_json(path):
    path = Path(path)
    require(not path.is_symlink() and path.is_file(), "JSON file is missing or linked")
    require(path.stat().st_size <= MAX_JSON, "JSON is too large")
    return parse_json(path.read_bytes())


def parse_json(data):
    require(len(data) <= MAX_JSON, "JSON is too large")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate JSON field")
            result[key] = value
        return result
    try:
        result = json.loads(data, object_pairs_hook=unique,
                            parse_constant=lambda _: (_ for _ in ()).throw(ProbeError("Nonfinite JSON value")))
        pending = [(result, 0)]
        while pending:
            value, depth = pending.pop()
            require(depth <= 32, "JSON nesting is too deep")
            if isinstance(value, dict):
                pending.extend((item, depth + 1) for item in value.values())
            elif isinstance(value, list):
                pending.extend((item, depth + 1) for item in value)
        return result
    except (UnicodeError, RecursionError, json.JSONDecodeError) as error:
        raise ProbeError("Invalid JSON result") from error


def load_lock(path=DEFAULT_SAMPLE):
    data = read_json(path)
    require(data.get("schemaVersion") == 1 and data.get("kind") == "third-party-smoke-lock", "Unknown lock format")
    app = data.get("app", {})
    expected = {"package": PACKAGE, "versionName": "1.4.0", "versionCode": "10",
                "sha256": PINNED_SHA, "certificateSha256": PINNED_CERT, "sizeBytes": PINNED_SIZE,
                "url": "https://github.com/FossifyOrg/Calculator/releases/download/1.4.0/calculator-10-foss-release.apk",
                "minSdk": 26, "targetSdk": 36, "abis": ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"],
                "license": "GPL-3.0"}
    require(app == expected, "This probe only accepts the reviewed Calculator 1.4.0 lock")
    require(data.get("apis") == [30, 35, 37] and data.get("scope", {}).get("states") == list(STATES),
            "The frozen API or state sample changed")
    require(data["scope"].get("deviceKind") == "dedicated_emulator"
            and data["scope"].get("releaseAcceptance") is False, "Probe scope cannot certify devices")
    return data


def source_from_environment(env=None):
    env = os.environ if env is None else env
    require(env.get("GITHUB_ACTIONS") == "true", "Only the dedicated GitHub Actions workflow may operate devices")
    require(env.get("GITHUB_REPOSITORY") == REPOSITORY and env.get("GITHUB_REF") == "refs/heads/master"
            and env.get("GITHUB_EVENT_NAME") == "workflow_dispatch", "Unexpected repository, branch, or event")
    require(env.get("GITHUB_WORKFLOW_REF") == REPOSITORY + "/.github/workflows/third-party-smoke.yml@refs/heads/master",
            "Only the dedicated third-party workflow may operate devices")
    sha = env.get("GITHUB_SHA", "")
    require(re.fullmatch(r"[0-9a-f]{40}", sha) is not None, "Invalid source commit")
    values = []
    for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
        value = env.get(key, "")
        require(re.fullmatch(r"[1-9][0-9]{0,18}", value) is not None, "Invalid run identity")
        values.append(int(value))
    return {"repository": REPOSITORY, "runId": values[0], "attempt": values[1], "commit": sha}


def empty_report(lock, source, label):
    require(label in APIS, "Unsupported matrix API")
    return {"schemaVersion": 1, "kind": "third-party-app-smoke", "source": source,
            "matrixLabel": label, "device": {"serial": "", "apiLevel": None},
            "app": copy.deepcopy(lock["app"]), "scope": copy.deepcopy(lock["scope"]),
            "status": "environment_failed", "actualInstalledIdentityVerified": False,
            "driverSteps": [], "states": [unverified_state(state, "Probe has not executed") for state in STATES]}


def unverified_state(state, reason):
    return {"state": state, "status": "unverified", "steps": [], "captures": [], "reason": reason}


def ensure_directory(path):
    path = Path(path).absolute()
    for parent in (path, *path.parents):
        require(not parent.is_symlink(), "Output directory cannot traverse a symbolic link")
    path.mkdir(parents=True, exist_ok=True)
    require(path.is_dir(), "Output is not a directory")
    return path


def write_report(directory, report):
    directory = ensure_directory(directory)
    target = directory / "report.json"
    require(not target.is_symlink(), "Report cannot be a symbolic link")
    if target.exists():
        previous = read_json(target)
        require(previous.get("source") == report["source"] and previous.get("matrixLabel") == report["matrixLabel"],
                "Cannot overwrite evidence from another run or API")
    encoded = (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode()
    require(len(encoded) <= MAX_JSON, "Report exceeded its size limit")
    with tempfile.NamedTemporaryFile(dir=directory, prefix=".report-", delete=False) as stream:
        stream.write(encoded)
        temporary = Path(stream.name)
    temporary.replace(target)


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_apk_bytes(path, app):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), "APK is missing or linked")
    require(path.stat().st_size == app["sizeBytes"], "APK size does not match the frozen release")
    require(sha256_file(path) == app["sha256"], "APK SHA-256 does not match the frozen release")


class ReleaseRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        require(parsed.scheme == "https" and parsed.hostname in {
            "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"
        } and parsed.username is None and parsed.password is None and parsed.port in (None, 443),
                "Release download redirected outside its allowed HTTPS hosts")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download_apk(app, destination, opener=None):
    destination = Path(destination)
    ensure_directory(destination.parent)
    require(not destination.exists() and not destination.is_symlink(), "Download output must not already exist")
    opener = opener or urllib.request.build_opener(ReleaseRedirects())
    request = urllib.request.Request(app["url"], headers={"User-Agent": "Agent-OS-compatibility-probe/1"})
    temporary = None
    try:
        with opener.open(request, timeout=20) as response:
            require(response.status == 200, "Official release did not return HTTP 200")
            length = response.headers.get("Content-Length")
            if length is not None:
                require(length.isdigit() and int(length) == app["sizeBytes"], "Unexpected release response length")
            with tempfile.NamedTemporaryFile(dir=destination.parent, prefix=".calculator-", delete=False) as stream:
                temporary = Path(stream.name)
                count = 0
                deadline = time.monotonic() + 120
                while True:
                    require(time.monotonic() < deadline, "Release download exceeded its total deadline")
                    chunk = response.read(65536)
                    if not chunk:
                        break
                    count += len(chunk)
                    require(count <= app["sizeBytes"], "Release response exceeded the frozen size")
                    stream.write(chunk)
        validate_apk_bytes(temporary, app)
        temporary.replace(destination)
        return destination
    except urllib.error.HTTPError as error:
        raise ProbeError("Official release download returned HTTP " + str(error.code)) from None
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def parse_signature(output):
    require(re.search(r"^Verifies\s*$", output, re.M) is not None, "APK signature was not verified")
    counts = re.findall(r"^Number of signers:\s*([0-9]+)\s*$", output, re.M)
    certs = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$", output, re.M)
    require(counts == ["1"] and len(certs) == 1, "Expected exactly one APK signer")
    return certs[0].lower()


def parse_badging(output):
    packages = re.findall(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", output, re.M)
    sdk = re.findall(r"^sdkVersion:'([0-9]+)'", output, re.M)
    target = re.findall(r"^targetSdkVersion:'([0-9]+)'", output, re.M)
    abi_lines = re.findall(r"^native-code:(.*)$", output, re.M)
    require(len(packages) == len(sdk) == len(target) == 1 and len(abi_lines) <= 1, "Ambiguous APK metadata")
    abis = re.findall(r"'([A-Za-z0-9_-]+)'", abi_lines[0]) if abi_lines else []
    require(len(set(abis)) == len(abis), "Duplicate ABI metadata")
    package, code, version = packages[0]
    return {"package": package, "versionCode": code, "versionName": version,
            "minSdk": int(sdk[0]), "targetSdk": int(target[0]), "abis": sorted(abis)}


def run_command(command, timeout=60, binary=False, limit=1024 * 1024):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, check=False)
    require(len(result.stdout) <= limit and len(result.stderr) <= limit, "Command output exceeded its bound")
    if binary:
        require(result.returncode == 0, "ADB file collection failed")
        return result.stdout
    stdout = result.stdout.decode("utf-8", "replace")
    stderr = result.stderr.decode("utf-8", "replace")
    require(result.returncode == 0, Path(command[0]).name + " failed: " + (stderr or stdout)[-1200:])
    return stdout + ("\n" + stderr if stderr else "")


def verify_apk(path, app, build_tools, command=run_command):
    validate_apk_bytes(path, app)
    signer = parse_signature(command([str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(path)]))
    require(signer == app["certificateSha256"], "APK signer does not match the frozen release")
    metadata = parse_badging(command([str(build_tools / "aapt"), "dump", "badging", str(path)]))
    for key in ("package", "versionName", "versionCode", "minSdk", "targetSdk"):
        require(metadata[key] == app[key], "APK metadata mismatch: " + key)
    require(metadata["abis"] == sorted(app["abis"]), "APK ABI set changed")
    return dict(metadata, certificateSha256=signer, sha256=app["sha256"])


def select_emulator(devices):
    lines = [line.strip().split() for line in devices.splitlines()[1:] if line.strip()]
    require(len(lines) == 1 and len(lines[0]) >= 2 and lines[0][1] == "device", "Exactly one ready emulator is required")
    serial = lines[0][0]
    require(re.fullmatch(r"emulator-[0-9]{4,5}", serial) is not None, "Physical or TCP-connected devices are not permitted")
    return serial


def validate_device(serial, properties, expected_api):
    require(re.fullmatch(r"emulator-[0-9]{4,5}", serial) is not None, "Not an emulator serial")
    require(properties.get("ro.kernel.qemu") == "1" and properties.get("ro.boot.qemu", "") in ("", "1"),
            "Device is not reporting the expected emulator environment")
    require(properties.get("ro.build.version.sdk") == str(expected_api), "Actual API does not match the matrix")
    require(properties.get("ro.product.cpu.abi") == "x86_64", "This smoke sample requires an x86_64 emulator")
    require(properties.get("sys.boot_completed") == "1", "Emulator has not completed boot")


def validate_emulator_version(properties, api):
    matches = re.findall(r"^Pkg\.Revision\s*=\s*([0-9]+)\.([0-9]+)\.([0-9]+)\s*$", properties, re.M)
    require(len(matches) == 1, "Emulator tool revision is unavailable or ambiguous")
    revision = tuple(int(part) for part in matches[0])
    require(api != 37 or revision >= (37, 2, 11), "API 37.0 requires emulator >=37.2.11")
    return ".".join(str(part) for part in revision)


def validate_relative_file(value):
    require(isinstance(value, str) and 0 < len(value) <= 160, "Invalid capture path")
    path = PurePosixPath(value)
    require(not path.is_absolute() and 1 <= len(path.parts) <= 2 and all(
        re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", part) and part not in (".", "..") for part in path.parts),
        "Capture path escaped its evidence directory")
    require(value == path.as_posix() and path.suffix.lower() == ".png", "Capture must be a normalized PNG path")
    return path


def validate_ui_results(data, source, api, serial):
    require(isinstance(data, dict) and data.get("schemaVersion") == 1, "Invalid UI result format")
    binding = data.get("source", {})
    require(binding.get("sha") == source["commit"] and str(binding.get("runId")) == str(source["runId"])
            and str(binding.get("attempt")) == str(source["attempt"])
            and str(binding.get("expectedApi")) == str(api) and binding.get("deviceSerial") == serial,
            "UI results belong to another run or device")
    states = data.get("states")
    require(isinstance(states, list) and len(states) == 2
            and {x.get("state") for x in states if isinstance(x, dict)} == set(STATES), "Missing or duplicate UI states")
    require(type(data.get("actualInstalledIdentityVerified")) is bool, "Installed identity verification is missing")
    seen_paths = set()
    for state in states:
        status = state.get("status")
        require(status in ("passed", "failed", "unverified"), "Invalid state result")
        captures = state.get("captures", [])
        require(isinstance(captures, list) and len(captures) <= 8, "Too many capture records")
        for capture in captures:
            require(capture.get("status") in ("captured", "blocked", "unavailable"), "Invalid capture status")
            require(isinstance(capture.get("stage"), str) and len(capture["stage"]) <= 120, "Invalid capture stage")
            if capture["status"] == "captured":
                path = str(validate_relative_file(capture.get("path")))
                require(path not in seen_paths, "Capture file is reused across evidence records")
                seen_paths.add(path)
                require(re.fullmatch(r"[0-9a-f]{64}", capture.get("sha256", "")) is not None
                        and type(capture.get("bytes")) is int and 8 <= capture["bytes"] <= MAX_CAPTURE, "Invalid capture identity")
            else:
                require(isinstance(capture.get("reason"), str) and bool(capture["reason"]), "Missing non-capture reason")
                require(not any(key in capture for key in ("path", "sha256", "bytes")), "Blocked capture carries an image")
        if status == "passed":
            require(data["actualInstalledIdentityVerified"] is True and state.get("installedIdentityVerified") is True,
                    "Passed UI result has no independent installed identity proof")
            require(state.get("result") == {"expected": "5", "actual": "5", "resourceId": RESULT_ID}, "Calculator result was not independently verified")
            expected_service = "disabled" if state["state"] == "off" else "bound_idle"
            require(state.get("serviceState") == expected_service, "Unexpected Agent service state")
            observations = state.get("serviceObservations", [])
            require(isinstance(observations, list) and len(observations) == 2,
                    "Exactly two main service observations are required")
            for observation, stage in zip(observations, ("before", "after")):
                require(observation.get("stage") == stage and observation.get("state") == expected_service
                        and observation.get("verified") is True, "Agent state was not verified before and after calculation")
            steps = state.get("steps", [])
            require(steps and all(step.get("status") == "passed" for step in steps), "Passed state contains incomplete steps")
            for name in ("service_state_before", "calculator_result", "service_state_after"):
                require(sum(step.get("name") == name for step in steps) == 1, "Missing core assertion: " + name)
            require(captures, "Passed UI test has no screenshot attempt record")
    return states


def collect_capture(data, directory, capture):
    relative = validate_relative_file(capture["path"])
    require(len(data) == capture["bytes"] and hashlib.sha256(data).hexdigest() == capture["sha256"],
            "Collected screenshot size or SHA-256 mismatch")
    require(data.startswith(b"\x89PNG\r\n\x1a\n") and 33 <= len(data) <= MAX_CAPTURE
            and data[8:16] == b"\x00\x00\x00\rIHDR", "Collected screenshot is not a bounded PNG")
    width, height = struct.unpack(">II", data[16:24])
    require(0 < width <= 4096 and 0 < height <= 4096, "Collected screenshot dimensions are invalid")
    target = directory.joinpath(*relative.parts)
    ensure_directory(target.parent)
    require(not target.exists() and not target.is_symlink(), "Screenshot output already exists")
    created = False
    try:
        with target.open("xb") as output:
            created = True
            output.write(data)
    except Exception:
        if created and target.is_file() and not target.is_symlink():
            target.unlink()
        raise


def collect_captures(states, directory, fetch):
    """Keep successful screenshots and record collection failures without dangling references."""
    errors = []
    for state in states:
        for capture in state.get("captures", []):
            if capture["status"] != "captured":
                continue
            original = dict(capture)
            try:
                collect_capture(fetch(capture["path"]), directory, capture)
            except Exception as error:
                message = type(error).__name__ + ": " + str(error)[:1200]
                errors.append({"state": state["state"], "stage": capture["stage"], "error": message})
                capture.clear()
                capture.update({"stage": original["stage"], "status": "unavailable",
                                "reason": "screenshot_collection_failed", "detail": message})
    return errors


def run_probe(args, lock, source):
    report = empty_report(lock, source, args.api)
    directory = ensure_directory(args.output)
    write_report(directory, report)
    api = APIS[args.api]
    stage = "device_preflight"
    try:
        sdk = Path(args.sdk_root)
        adb = str(sdk / "platform-tools/adb")
        build_tools = sdk / "build-tools/35.0.0"
        report["emulatorVersion"] = validate_emulator_version((sdk / "emulator/source.properties").read_text(), api)
        serial = select_emulator(run_command([adb, "devices"]))
        def shell(*words, **kwargs):
            return run_command([adb, "-s", serial, "shell", *words], **kwargs).strip()
        properties = {key: shell("getprop", key) for key in ("ro.kernel.qemu", "ro.boot.qemu", "ro.build.version.sdk",
                     "ro.product.cpu.abi", "sys.boot_completed", "ro.build.fingerprint")}
        validate_device(serial, properties, api)
        require(shell("am", "get-current-user") == "0", "Only the disposable emulator owner profile is supported")
        report["device"] = {"serial": serial, "apiLevel": api, "fingerprint": properties["ro.build.fingerprint"],
                            "abi": properties["ro.product.cpu.abi"], "kernelQemu": properties["ro.kernel.qemu"],
                            "bootQemu": properties["ro.boot.qemu"]}
        report["driverSteps"].append({"name": stage, "status": "passed"})
        stage = "apk_verification"
        report["verifiedDownload"] = verify_apk(Path(args.calculator_apk), lock["app"], build_tools)
        report["driverSteps"].append({"name": stage, "status": "passed"})
        stage = "fresh_install_precondition"
        installed = shell("pm", "list", "packages").splitlines()
        for package in (AGENT_PACKAGE, PACKAGE, PROBE_PACKAGE, PROBE_PACKAGE + ".test"):
            require("package:" + package not in installed, "A probe package is already installed; refusing to clear existing data")
        report["driverSteps"].append({"name": stage, "status": "passed"})
        for name, path in (("install_agent", args.agent_apk), ("install_calculator", args.calculator_apk),
                           ("install_probe", args.probe_apk), ("install_probe_tests", args.test_apk)):
            stage = name
            path = Path(path)
            require(path.is_file() and not path.is_symlink(), "Missing CI-built APK")
            test_flags = ["-t"] if name in ("install_probe", "install_probe_tests") else []
            output = run_command([adb, "-s", serial, "install", *test_flags, str(path)], timeout=120)
            require(re.search(r"^Success\s*$", output, re.M) is not None, "Installation did not report success")
            report["driverSteps"].append({"name": name, "status": "passed", "sha256": sha256_file(path)})
        stage = "instrumentation"
        parameters = {"class": "dev.agentos.compat.CalculatorCompatibilityInstrumentedTest",
                      "syntheticTestMarker": "agent-os-third-party-smoke-v1",
                      "freshInstallMarker": "agent-os-third-party-fresh-install-v1",
                      "deviceSerial": serial, "expectedApi": str(api), "sourceSha": source["commit"],
                      "runId": str(source["runId"]), "runAttempt": str(source["attempt"])}
        command = [adb, "-s", serial, "shell", "am", "instrument", "-w", "-r"]
        for key, value in parameters.items():
            command.extend(["-e", key, value])
        command.append(PROBE_PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
        try:
            output = run_command(command, timeout=360)
            command_completed = True
        except Exception as error:
            # Collection is still attempted: a failed/terminated runner may already
            # have persisted a completed state and its real screenshots.
            output = "Instrumentation command failed: " + type(error).__name__ + ": " + str(error)[:1600]
            command_completed = False
        (directory / "instrumentation.json").write_text(
            json.dumps({"commandCompleted": command_completed, "output": output}, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8")
        instrument_passed = command_completed and bool(re.search(r"\bOK \(2 tests\)", output)) and not any(
            token in output for token in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "shortMsg=", "Process crashed"))
        report["driverSteps"].append({"name": stage, "status": "passed" if instrument_passed else "failed"})
        stage = "collect_ui_results"
        raw = run_command([adb, "-s", serial, "exec-out", "run-as", PROBE_PACKAGE,
                           "cat", "files/third-party-smoke/ui-results.json"], binary=True, limit=MAX_JSON)
        ui = parse_json(raw)
        states = validate_ui_results(ui, source, api, serial)
        report["states"] = states
        report["actualInstalledIdentityVerified"] = ui["actualInstalledIdentityVerified"]
        def fetch_capture(path):
            return run_command([adb, "-s", serial, "exec-out", "run-as", PROBE_PACKAGE, "cat",
                                "files/third-party-smoke/" + path], binary=True, limit=MAX_CAPTURE)
        capture_errors = collect_captures(states, directory, fetch_capture)
        (directory / "ui-results.json").write_bytes(raw)
        report["driverSteps"].append({"name": stage, "status": "failed" if capture_errors else "passed"})
        if capture_errors:
            report["captureCollectionErrors"] = capture_errors
        report["status"] = "passed" if instrument_passed and not capture_errors and all(x["status"] == "passed" for x in states) else "failed"
        return report["status"] == "passed"
    except Exception as error:
        report["status"] = "failed" if stage in ("instrumentation", "collect_ui_results") else "environment_failed"
        report["driverSteps"].append({"name": stage, "status": "failed"})
        report["error"] = {"stage": stage, "type": type(error).__name__, "message": str(error)[:1600]}
        return False
    finally:
        write_report(directory, report)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sample", type=Path, default=DEFAULT_SAMPLE)
    commands = parser.add_subparsers(dest="command", required=True)
    download = commands.add_parser("download")
    download.add_argument("--output", type=Path, required=True)
    for name in ("prepare", "run", "finalize"):
        command = commands.add_parser(name)
        command.add_argument("--api", choices=APIS, required=True)
        command.add_argument("--output", type=Path, required=True)
        if name == "run":
            command.add_argument("--sdk-root", required=True)
            for flag in ("agent-apk", "probe-apk", "test-apk", "calculator-apk"):
                command.add_argument("--" + flag, type=Path, required=True)
        if name == "finalize":
            command.add_argument("--outcome", choices=("success", "failure", "cancelled", "skipped", "unknown"), required=True)
    args = parser.parse_args()
    try:
        lock = load_lock(args.sample)
        if args.command == "download":
            download_apk(lock["app"], args.output)
            print("Official Calculator APK size and SHA-256 verified")
            return 0
        source = source_from_environment()
        if args.command == "run":
            return 0 if run_probe(args, lock, source) else 1
        if args.command == "prepare":
            report = empty_report(lock, source, args.api)
        else:
            path = args.output / "report.json"
            report = read_json(path) if path.exists() else empty_report(lock, source, args.api)
            require(report.get("source") == source and report.get("matrixLabel") == args.api, "Cannot finalize evidence from another run")
            report["emulatorStepOutcome"] = args.outcome
            if args.outcome != "success" and report["status"] == "passed":
                report["status"] = "environment_failed"
                report["error"] = {"stage": "emulator_runner", "message": "Emulator step did not complete successfully"}
        write_report(args.output, report)
        return 0
    except (ProbeError, OSError, ValueError, urllib.error.URLError) as error:
        print("Third-party probe: " + str(error))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
