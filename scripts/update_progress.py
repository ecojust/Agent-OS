#!/usr/bin/env python3
"""Generate the README progress section from explicit engineering checkpoints."""
import argparse
from datetime import datetime, timezone
import html
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "docs/progress.json"
SVG = ROOT / "docs/diagrams/development-progress.svg"
README = ROOT / "README.md"
BEGIN = "<!-- development-progress:start -->"
END = "<!-- development-progress:end -->"
LABELS = {"complete": "已完成", "in_progress": "进行中", "pending": "待实现", "blocked": "待外部条件"}


def single_line(value):
    return " ".join(str(value).split()).replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;")


def table_text(value):
    text = html.escape(" ".join(str(value).split()), quote=False)
    return re.sub(r"([\\`*_\[\]|])", r"\\\1", text)


def validate_timestamp(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", value):
        raise ValueError("Checkpoint updated_at must use YYYY-MM-DDTHH:MM:SSZ (UTC)")
    try:
        datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as error:
        raise ValueError("Checkpoint updated_at must be a valid UTC date and time") from error


def validate_checkpoints(points):
    ids = [item["id"] for item in points]
    if not points or len(ids) != len(set(ids)):
        raise ValueError("Checkpoints must be nonempty and have unique IDs")
    if any(item["status"] not in LABELS for item in points):
        raise ValueError("Unknown checkpoint status")
    for item in points:
        if "details" in item and not isinstance(item["details"], str):
            raise ValueError("Checkpoint details must be text")
        if "updated_at" in item:
            validate_timestamp(item["updated_at"])


def update_checkpoints(points, statuses, details, updated_at):
    """Return edited rows; timestamp only their final status/details changes."""
    validate_checkpoints(points)
    validate_timestamp(updated_at)
    lookup = {item["id"]: dict(item) for item in points}
    for field, changes in (("status", statuses), ("details", details)):
        for change in changes:
            key, sep, value = change.partition("=")
            if not sep or key not in lookup:
                raise ValueError("Expected a known CHECKPOINT=VALUE")
            if field == "status" and value not in LABELS:
                raise ValueError("Unknown checkpoint status")
            if field == "details":
                value = value.strip()
                if not value:
                    raise ValueError("Checkpoint detail must not be empty")
            lookup[key][field] = value
    for original in points:
        item = lookup[original["id"]]
        if any(item.get(field) != original.get(field) for field in ("status", "details")):
            item["updated_at"] = updated_at
    return list(lookup.values())


def checkpoint_evidence(value):
    """Only link simple repository paths and this repository's HTTPS URLs."""
    if not isinstance(value, str):
        raise ValueError("Checkpoint evidence must be a repository path or HTTPS URL")
    path = value.removeprefix("https://github.com/ecojust/Agent-OS/")
    if (not re.fullmatch(r"[A-Za-z0-9._/-]+", path)
            or any(part in ("", ".", "..") for part in path.split("/"))):
        raise ValueError("Checkpoint evidence must be a safe repository path or repository HTTPS URL")
    return f"[依据]({value})"


def render(data):
    points = data["checkpoints"]
    validate_checkpoints(points)
    complete = sum(item["status"] == "complete" for item in points)
    total = len(points)
    percent = round(100 * complete / total)
    filled = round(480 * complete / total)
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="560" height="62" viewBox="0 0 560 62" role="img" aria-labelledby="title desc">
  <title id="title">Engineering checkpoints: {complete}/{total} ({percent}%)</title>
  <desc id="desc">Completed engineering checkpoints, not phone compatibility coverage or total engineering effort.</desc>
  <rect x="1" y="1" width="558" height="60" rx="10" fill="#172033" stroke="#52617c"/>
  <text x="20" y="23" fill="#f2f5fc" font-family="sans-serif" font-size="14">Engineering checkpoints · {complete}/{total} · {percent}%</text>
  <rect x="20" y="35" width="480" height="10" rx="5" fill="#44516b"/>
  <rect x="20" y="35" width="{filled}" height="10" rx="5" fill="#5eead4"/>
</svg>
'''
    lines = [BEGIN, "## Development Progress", "",
             f"![开发检查点：{complete}/{total}，{percent}%](docs/diagrams/development-progress.svg)", "",
             "进度按下列固定工程检查点计数，不代表总工作量或手机兼容率。未验收的机型、应用及刷机镜像保持未验证。", "",
             f"**本次更新：** {single_line(data['note'])}", "",
             f"**记录时间：** {single_line(data['updated_at'])}", ""]
    evidence = data.get("latest_evidence")
    if evidence:
        path = evidence["path"]
        lines.extend([f"[最近测试截图与清单]({path}/) · [对应测试运行]({evidence['run_url']})。通过、失败及未截图原因见清单。", ""])
    else:
        lines.extend(["测试截图归档正在接入，尚无本轮截图；历史未采集的截图不补画、不冒充测试记录。", ""])
    demo = data.get("latest_demo")
    if demo:
        path = demo["path"]
        source = demo["source_sha"]
        run_match = re.fullmatch(r"screenshots/demos/([1-9][0-9]*)-([1-9][0-9]*)", path)
        if (not run_match or not re.fullmatch(r"[0-9a-f]{40}", source)
                or demo.get("status") != "passed"
                or demo["run_url"] != f"https://github.com/ecojust/Agent-OS/actions/runs/{run_match.group(1)}"):
            raise ValueError("Latest demo must identify a verified walkthrough and its exact source/run")
        lines.extend([
            f"**整体操作演示：** [播放视频]({path}/demo.mp4) · [录制与验证清单]({path}/manifest.json) · [测试运行]({demo['run_url']})。",
            f"录制版本：[`{source[:9]}`](https://github.com/ecojust/Agent-OS/commit/{source})。视频来自专用 API 35 模拟器，模型响应由本地测试服务提供；不是实际大模型或真机兼容验收。单个视频小于 100 MB。", ""])
    else:
        lines.extend(["**整体操作演示：** 正在接入真实操作录制，完成后提供小于 100 MB 的视频与对应源码记录；尚未录制的过程不以示意动画代替。", ""])
    lines.extend(["最近更新时间记录该条状态或进度说明的修改时间，采用 UTC；测试发生时间以对应证据为准。", "",
                  "| 检查点 | 状态 | 详细进度 | 最近更新时间（UTC） |", "| --- | --- | --- | --- |"])
    for item in points:
        detail = table_text(item.get("details") or item.get("reason") or "尚未记录详细进度")
        if item.get("evidence"):
            detail += " " + checkpoint_evidence(item["evidence"])
        updated_at = item.get("updated_at")
        when = updated_at.replace("T", " ").removesuffix("Z") if updated_at else "未记录"
        lines.append(f"| {table_text(item['name'])} | {LABELS[item['status']]} | {detail} | {when} |")
    lines.extend(["", "依据：[进度源数据](docs/progress.json) · [实现与验证范围](IMPLEMENTATION.md) · [测试截图](screenshots/README.md) · [操作演示说明](docs/validation/operation-demo.md)。", END])
    return "\n".join(lines), svg


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--note")
    parser.add_argument("--status", action="append", default=[], metavar="CHECKPOINT=STATUS")
    parser.add_argument("--detail", action="append", default=[], metavar="CHECKPOINT=TEXT")
    parser.add_argument("--evidence-path")
    parser.add_argument("--run-url")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check and (args.note is not None or args.status or args.detail
                       or args.evidence_path is not None or args.run_url is not None):
        parser.error("--check cannot modify progress")
    data = json.loads(DATA.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        parser.error("Unsupported progress format")
    now = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    try:
        data["checkpoints"] = update_checkpoints(data["checkpoints"], args.status, args.detail, now)
    except ValueError as error:
        parser.error(str(error))
    if bool(args.evidence_path) != bool(args.run_url):
        parser.error("--evidence-path and --run-url are required together")
    if args.evidence_path:
        if not re.fullmatch(r"screenshots/[A-Za-z0-9._-]+", args.evidence_path):
            parser.error("Evidence must be a direct screenshots subdirectory")
        location = (ROOT / args.evidence_path).resolve()
        if location.parent != (ROOT / "screenshots").resolve() or not (location / "manifest.json").is_file():
            parser.error("Evidence manifest is missing or outside screenshots")
        if not re.fullmatch(r"https://github\.com/ecojust/Agent-OS/actions/runs/[0-9]+", args.run_url):
            parser.error("Unexpected evidence run URL")
        json.loads((location / "manifest.json").read_text(encoding="utf-8"))
        data["latest_evidence"] = {"path": args.evidence_path, "run_url": args.run_url}
        if not args.note:
            data["note"] = "归档本次真实模拟器测试截图与结果；证据清单保留失败、受保护及无界面用例的状态。"
    if args.note:
        data["note"] = " ".join(args.note.split())
    if args.note or args.status or args.detail or args.evidence_path:
        data["updated_at"] = now
    try:
        section, svg = render(data)
    except ValueError as error:
        parser.error(str(error))
    previous = README.read_text(encoding="utf-8")
    if BEGIN in previous and END in previous:
        start, tail = previous.split(BEGIN, 1)
        _, end = tail.split(END, 1)
        updated = start + section + end
    else:
        first, sep, rest = previous.partition("\n## System Architecture")
        if not sep:
            parser.error("README insertion point missing")
        updated = first.rstrip() + "\n\n" + section + "\n" + sep + rest
    if args.check:
        if previous != updated or not SVG.is_file() or SVG.read_text(encoding="utf-8") != svg:
            parser.error("README progress is stale; run scripts/update_progress.py")
        print("README progress matches checkpoint data")
        return
    DATA.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    README.write_text(updated, encoding="utf-8")
    SVG.parent.mkdir(parents=True, exist_ok=True)
    SVG.write_text(svg, encoding="utf-8")


if __name__ == "__main__":
    main()
