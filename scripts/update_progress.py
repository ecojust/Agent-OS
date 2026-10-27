#!/usr/bin/env python3
"""Generate the README progress section from explicit engineering checkpoints."""
import argparse
from datetime import datetime, timezone
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


def render(data):
    points = data["checkpoints"]
    ids = [item["id"] for item in points]
    if not points or len(ids) != len(set(ids)):
        raise ValueError("Checkpoints must be nonempty and have unique IDs")
    if any(item["status"] not in LABELS for item in points):
        raise ValueError("Unknown checkpoint status")
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
    lines.extend(["| 检查点 | 状态 |", "| --- | --- |"])
    for item in points:
        lines.append(f"| {single_line(item['name'])} | {LABELS[item['status']]} |")
    lines.extend(["", "依据：[进度源数据](docs/progress.json) · [实现与验证范围](IMPLEMENTATION.md) · [测试截图](screenshots/README.md)。", END])
    return "\n".join(lines), svg


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--note")
    parser.add_argument("--status", action="append", default=[], metavar="CHECKPOINT=STATUS")
    parser.add_argument("--evidence-path")
    parser.add_argument("--run-url")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check and (args.note or args.status or args.evidence_path or args.run_url):
        parser.error("--check cannot modify progress")
    data = json.loads(DATA.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        parser.error("Unsupported progress format")
    lookup = {item["id"]: item for item in data["checkpoints"]}
    for change in args.status:
        key, sep, value = change.partition("=")
        if not sep or key not in lookup or value not in LABELS:
            parser.error("Unknown checkpoint or status")
        lookup[key]["status"] = value
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
    if args.note or args.status or args.evidence_path:
        data["updated_at"] = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    section, svg = render(data)
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
