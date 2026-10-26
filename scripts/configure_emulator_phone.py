#!/usr/bin/env python3
"""Set and verify the CI phone baseline before its emulator is launched."""

import argparse
import os
from pathlib import Path
import re
import sys


def config_path(avd_home, avd_name):
    if not avd_home or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{0,63}", avd_name):
        raise ValueError("Require the runner's ANDROID_AVD_HOME and a valid AVD name")
    root = Path(avd_home).resolve(strict=True)
    directory = root / f"{avd_name}.avd"
    path = directory / "config.ini"
    if directory.is_symlink() or path.is_symlink() or not path.is_file():
        raise ValueError("Expected a regular config.ini in the named runner AVD")
    if path.stat().st_size > 1024 * 1024:
        raise ValueError("AVD config exceeds the expected size")
    return path


def keyboard_values(lines):
    return [line.split("=", 1)[1].strip() for line in lines
            if "=" in line and line.split("=", 1)[0].strip() == "hw.keyboard"]


def configure(avd_home, avd_name, check=False):
    path = config_path(avd_home, avd_name)
    lines = path.read_text().splitlines()
    if not check:
        # Remove inherited or duplicate values; leave every unrelated device setting intact.
        lines = [line for line in lines if not
                 ("=" in line and line.split("=", 1)[0].strip() == "hw.keyboard")]
        lines.append("hw.keyboard=no")
        path.write_text("\n".join(lines) + "\n")
    if keyboard_values(path.read_text().splitlines()) != ["no"]:
        raise ValueError("Phone baseline requires exactly one hw.keyboard=no setting")
    print(f"Verified AVD {avd_name}: hw.keyboard=no (touchscreen phone baseline)")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--avd-name", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    configure(os.environ.get("ANDROID_AVD_HOME"), args.avd_name, args.check)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError) as error:
        print(f"Phone baseline verification failed: {error}", file=sys.stderr)
        sys.exit(1)
