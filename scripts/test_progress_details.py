"""Checkpoint descriptions and maintenance timestamps remain scoped and truthful."""
from contextlib import redirect_stderr, redirect_stdout
from copy import deepcopy
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import update_progress


OLD = "2026-10-27T01:02:03Z"
NOW = "2026-10-29T12:34:56Z"


class ProgressDetailsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        paths = patch.multiple(update_progress, ROOT=self.root,
                               DATA=self.root / "docs/progress.json",
                               README=self.root / "README.md",
                               SVG=self.root / "docs/progress.svg")
        paths.start()
        self.addCleanup(paths.stop)
        self.data = {
            "schema_version": 1, "note": "Original note", "updated_at": OLD,
            "checkpoints": [
                {"id": "first", "name": "第一项", "status": "pending", "details": "旧说明", "updated_at": OLD},
                {"id": "second", "name": "第二项", "status": "in_progress", "details": "待继续", "updated_at": OLD},
                {"id": "legacy", "name": "旧条目", "status": "blocked", "reason": "需要参考设备"},
            ],
        }
        self.write_fixture()

    def write_fixture(self):
        update_progress.DATA.parent.mkdir(parents=True, exist_ok=True)
        update_progress.DATA.write_text(json.dumps(self.data, ensure_ascii=False), encoding="utf-8")
        section, svg = update_progress.render(self.data)
        update_progress.README.write_text("# Project\n\n" + section + "\n", encoding="utf-8")
        update_progress.SVG.write_text(svg, encoding="utf-8")

    def invoke(self, *args):
        with patch.object(sys, "argv", ["update_progress.py", *args]), \
                patch.object(update_progress, "datetime", wraps=datetime) as clock, \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            clock.now.return_value = datetime(2026, 10, 29, 12, 34, 56, tzinfo=timezone.utc)
            update_progress.main()

    def saved(self):
        return json.loads(update_progress.DATA.read_text(encoding="utf-8"))

    def snapshot(self):
        return tuple(path.read_bytes() for path in (update_progress.DATA, update_progress.README, update_progress.SVG))

    def test_four_columns_and_legacy_fallback_do_not_invent_row_time(self):
        self.data["checkpoints"].append({"id": "empty", "name": "无说明", "status": "pending"})
        section, _ = update_progress.render(self.data)
        self.assertIn("<tr><th nowrap>检查点</th><th nowrap>状态</th><th>详细进度</th><th nowrap>最近更新时间（UTC）</th></tr>", section)
        self.assertIn("<tr><td nowrap>第一项</td><td nowrap>待实现</td><td>旧说明</td><td nowrap>2026-10-27 01:02:03</td></tr>", section)
        self.assertIn("<tr><td nowrap>旧条目</td><td nowrap>待外部条件</td><td>需要参考设备</td><td nowrap>未记录</td></tr>", section)
        self.assertIn("<tr><td nowrap>无说明</td><td nowrap>待实现</td><td>尚未记录详细进度</td><td nowrap>未记录</td></tr>", section)
        self.assertIn("测试发生时间以对应证据为准", section)

    def test_table_text_is_plain_text_and_does_not_split_rows_or_columns(self):
        self.data["checkpoints"][0].update({
            "name": "名|称\n下一行", "details": "<tag> & `code` [link](x) \\| *bold* _under_\n下一步",
            "evidence": "docs/DESIGN_RULES.md",
        })
        section, _ = update_progress.render(self.data)
        expected = ('<tr><td nowrap>名|称 下一行</td><td nowrap>待实现</td><td>&lt;tag&gt; &amp; `code` [link](x) '
                    '\\| *bold* _under_ 下一步 <a href="docs/DESIGN_RULES.md">依据</a></td><td nowrap>2026-10-27 01:02:03</td></tr>')
        self.assertIn(expected, section.splitlines())
        self.assertNotIn("<tag>", section)

    def test_evidence_links_accept_only_safe_repository_paths_or_urls(self):
        for evidence in (".github/workflows/android.yml", "core/src/test/java", "https://github.com/ecojust/Agent-OS/actions/runs/123"):
            with self.subTest(evidence=evidence):
                self.assertEqual(f'<a href="{evidence}">依据</a>', update_progress.checkpoint_evidence(evidence))
        for evidence in ("../README.md", "/etc/passwd", "docs/../README.md", "docs//rules.md",
                         "https://other.example/test", "https://github.com/ecojust/Agent-OS/../other",
                         "docs/x) [bad](https://example.com)", "docs/x\nnext", "docs/x%29", 42):
            with self.subTest(evidence=evidence), self.assertRaises(ValueError):
                update_progress.checkpoint_evidence(evidence)

    def test_timestamp_rejects_noncanonical_utc_and_impossible_dates(self):
        for timestamp in (None, 42, "", "2026-10-29", "2026-10-29T12:34:56+00:00",
                          "2026-10-29T12:34:56.000Z", "2026-1-29T12:34:56Z",
                          "2026-10-29t12:34:56z", "2026-02-29T12:34:56Z", "2026-10-29T24:00:00Z"):
            with self.subTest(timestamp=timestamp):
                data = deepcopy(self.data)
                data["checkpoints"][0]["updated_at"] = timestamp
                with self.assertRaises(ValueError):
                    update_progress.render(data)
        update_progress.validate_timestamp("2024-02-29T23:59:59Z")

    def test_cli_updates_only_rows_with_changed_status_or_detail(self):
        self.invoke("--status", "first=in_progress", "--detail", "second=已验证；未完成；下一步=继续")
        result = self.saved()
        first, second, legacy = result["checkpoints"]
        self.assertEqual(("in_progress", NOW), (first["status"], first["updated_at"]))
        self.assertEqual(("已验证；未完成；下一步=继续", NOW), (second["details"], second["updated_at"]))
        self.assertEqual(self.data["checkpoints"][2], legacy)
        self.assertEqual(NOW, result["updated_at"])

    def test_noop_status_and_detail_do_not_refresh_any_row_time(self):
        self.invoke("--status", "first=pending", "--status", "legacy=blocked", "--detail", "second=待继续")
        self.assertEqual(self.data["checkpoints"], self.saved()["checkpoints"])

    def test_repeated_edits_compare_final_values_and_do_not_mutate_input(self):
        original = deepcopy(self.data["checkpoints"])
        result = update_progress.update_checkpoints(original,
            ["first=complete", "first=pending"], ["second=改变", "second=待继续"], NOW)
        self.assertEqual(self.data["checkpoints"], result)
        self.assertEqual(self.data["checkpoints"], original)

    def test_note_and_evidence_changes_do_not_refresh_checkpoint_times(self):
        manifest = self.root / "screenshots/123-1/manifest.json"
        manifest.parent.mkdir(parents=True)
        manifest.write_text("{}", encoding="utf-8")
        for args in (("--note", "Updated note"),
                     ("--evidence-path", "screenshots/123-1", "--run-url", "https://github.com/ecojust/Agent-OS/actions/runs/123")):
            with self.subTest(args=args):
                self.write_fixture()
                self.invoke(*args)
                result = self.saved()
                self.assertEqual(self.data["checkpoints"], result["checkpoints"])
                self.assertEqual(NOW, result["updated_at"])

    def test_render_only_run_preserves_legacy_data_and_global_time(self):
        self.invoke()
        self.assertEqual(self.data, self.saved())

    def test_check_is_read_only_and_detects_stale_new_columns(self):
        before = self.snapshot()
        self.invoke("--check")
        self.assertEqual(before, self.snapshot())
        current = update_progress.README.read_text(encoding="utf-8")
        update_progress.README.write_text(current.replace("详细进度", "旧列名"), encoding="utf-8")
        before = self.snapshot()
        with self.assertRaises(SystemExit) as failure:
            self.invoke("--check")
        self.assertEqual(2, failure.exception.code)
        self.assertEqual(before, self.snapshot())

    def test_invalid_existing_timestamp_is_rejected_before_edit_and_never_written(self):
        self.data["checkpoints"][0]["updated_at"] = "2026-02-30T12:00:00Z"
        update_progress.DATA.write_text(json.dumps(self.data), encoding="utf-8")
        before = self.snapshot()
        for args in (("--check",), ("--status", "first=complete")):
            with self.subTest(args=args), self.assertRaises(SystemExit):
                self.invoke(*args)
            self.assertEqual(before, self.snapshot())

    def test_check_rejects_all_edit_options_without_writing(self):
        before = self.snapshot()
        for args in (("--detail", "first=Changed"), ("--status", "first=complete"), ("--note", ""),
                     ("--evidence-path", ""), ("--run-url", "")):
            with self.subTest(args=args), self.assertRaises(SystemExit):
                self.invoke("--check", *args)
            self.assertEqual(before, self.snapshot())

    def test_invalid_detail_and_unknown_status_do_not_write_partial_edits(self):
        before = self.snapshot()
        for args in (("--detail", "first="), ("--detail", "first=  \n"), ("--detail", "missing=Text"),
                     ("--detail", "first"), ("--status", "second=unknown")):
            with self.subTest(args=args), self.assertRaises(SystemExit):
                self.invoke("--status", "first=complete", *args)
            self.assertEqual(before, self.snapshot())


if __name__ == "__main__":
    unittest.main()
