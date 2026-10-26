#!/usr/bin/env python3
"""Print bounded diagnostics from synthetic Android instrumentation JUnit reports."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


MAX_FILES = 24
MAX_FILE_BYTES = 2 * 1024 * 1024
MAX_FAILURES = 24
MAX_DETAIL_CHARS = 8000
MAX_OUTPUT_CHARS = 64000


def main() -> None:
    directory = Path(sys.argv[1] if len(sys.argv) > 1 else
                     "app/build/outputs/androidTest-results")
    files = sorted(directory.rglob("*.xml")) if directory.is_dir() else []
    if not files:
        print("No Android XML reports were produced; inspect the emulator step logs.")
        return

    remaining = MAX_OUTPUT_CHARS

    def emit(value: str) -> None:
        nonlocal remaining
        if remaining <= 0:
            return
        # Prefix every line so report text cannot become a workflow command.
        lines = ["  | " + line for line in value.replace("\r", "").splitlines()]
        output = "\n".join(lines)[:remaining]
        print(output)
        remaining -= len(output) + 1

    parsed = 0
    failures = 0
    for path in files[:MAX_FILES]:
        try:
            if path.stat().st_size > MAX_FILE_BYTES:
                emit(f"Skipped oversized report: {path}")
                continue
            root = ET.parse(path).getroot()
        except (OSError, ET.ParseError) as error:
            emit(f"Could not parse {path}: {type(error).__name__}")
            continue
        parsed += 1

        def visit(node: ET.Element, suite: str = "") -> None:
            nonlocal failures
            if node.tag == "testsuite":
                suite = node.get("name", "unnamed suite")
            if node.tag == "testcase":
                for problem in node:
                    if problem.tag not in ("failure", "error"):
                        continue
                    failures += 1
                    if failures > MAX_FAILURES or remaining <= 0:
                        continue
                    emit(f"Report: {path}\nSuite: {suite}\n"
                         f"Test: {node.get('classname', '')}.{node.get('name', '')}\n"
                         f"Kind: {problem.tag} / {problem.get('type', '')}")
                    detail = "\n".join(filter(None, [problem.get("message", ""),
                                                    "".join(problem.itertext())]))
                    emit(detail[:MAX_DETAIL_CHARS])
                    if len(detail) > MAX_DETAIL_CHARS:
                        emit("[Failure detail truncated; full report remains in artifacts.]")
            for child in node:
                visit(child, suite)

        visit(root)

    print(f"Parsed {parsed} Android reports; found {failures} failure/error entries.")
    if len(files) > MAX_FILES or failures > MAX_FAILURES or remaining <= 0:
        print("Diagnostic output was capped; full XML reports remain in artifacts.")


if __name__ == "__main__":
    main()
