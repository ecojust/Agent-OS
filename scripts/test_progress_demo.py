"""README links retain the identity and limitations of an archived real recording."""
from copy import deepcopy
import unittest

import update_progress


class DemoProgressTest(unittest.TestCase):
    def data(self):
        return {"checkpoints": [{"id": "demo", "name": "Demo", "status": "in_progress"}],
                "note": "Recording pending", "updated_at": "2026-10-27T00:00:00Z"}

    def demo(self):
        return {"path": "screenshots/demos/123-1", "source_sha": "a" * 40,
                "run_url": "https://github.com/ecojust/Agent-OS/actions/runs/123", "status": "passed"}

    def test_pending_has_no_play_link(self):
        section, _ = update_progress.render(self.data())
        self.assertIn("尚未录制", section)
        self.assertNotIn("demo.mp4", section)

    def test_success_links_exact_source_and_records_test_model(self):
        data = self.data()
        data["latest_demo"] = self.demo()
        section, _ = update_progress.render(data)
        self.assertIn("screenshots/demos/123-1/demo.mp4", section)
        self.assertIn("/commit/" + "a" * 40, section)
        self.assertIn("本地测试服务", section)
        self.assertIn("不是实际大模型或真机兼容验收", section)

    def test_failure_or_mismatched_identity_cannot_be_successful_play_link(self):
        for key, value in [("status", "failed"), ("path", "screenshots/demos/../other"),
                           ("path", "screenshots/demos/123-0"), ("source_sha", "master"),
                           ("run_url", "https://github.com/ecojust/Agent-OS/actions/runs/124")]:
            with self.subTest(key=key, value=value):
                data = self.data()
                data["latest_demo"] = deepcopy(self.demo())
                data["latest_demo"][key] = value
                with self.assertRaises(ValueError):
                    update_progress.render(data)


if __name__ == "__main__":
    unittest.main()
