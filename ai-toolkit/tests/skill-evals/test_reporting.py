"""Regression: evaluation output must fail closed on incomplete/contaminated CLI runs."""

import json
from pathlib import Path
import tempfile
import unittest

from report import summarize
from run import skill_context
from blind import blind


class ReportingTest(unittest.TestCase):
    def test_retains_reference_and_rejects_invalid_evidence(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            skill = root / "sample"
            (skill / "references").mkdir(parents=True)
            (skill / "SKILL.md").write_text("entry")
            (skill / "references/detail.md").write_text("needed detail")
            self.assertIn("needed detail", skill_context(root, ["sample"]))

            record = {"id": "one", "repeat": 1, "exit_code": 0, "seconds": 1,
                      "context_bytes": 10, "model": "test/model"}
            (root / "runs.json").write_text(json.dumps([record]))
            output = root / "one-1.jsonl"
            output.write_text(json.dumps({"type": "text", "part": {"text": "answer"}}))
            cases = {"one": {"prompt": "task", "assertions": ["correct behavior"]}}
            grades = {"one": {"updated": [[True]]}}
            self.assertEqual(summarize(root, "updated", cases, grades)["passed"], 1)

            suite = root / "cases.json"
            suite.write_text(json.dumps({"cases": [{"id": "one", **cases["one"]}]}))
            blind(suite, {"baseline": root, "updated": root}, root / "blind", 1)
            packet = json.loads((root / "blind/packet.json").read_text())
            key = json.loads((root / "blind/key.json").read_text())
            self.assertEqual(len(packet), 2)
            self.assertTrue(all(set(answer) == {"id", "task", "assertions", "answer"} for answer in packet))
            self.assertEqual({entry["condition"] for entry in key.values()}, {"baseline", "updated"})

            record["task_sha256"] = "changed-task"
            (root / "runs.json").write_text(json.dumps([record]))
            with self.assertRaisesRegex(ValueError, "Task changed"):
                summarize(root, "updated", cases, grades)
            del record["task_sha256"]
            (root / "runs.json").write_text(json.dumps([record]))

            output.write_text(json.dumps({"type": "tool_use", "part": {}}))
            with self.assertRaisesRegex(ValueError, "contaminated"):
                summarize(root, "updated", cases, grades)
            output.write_text(json.dumps({"type": "step_start", "part": {}}))
            with self.assertRaisesRegex(ValueError, "No answer"):
                summarize(root, "updated", cases, grades)
            (root / "runs.json").write_text("[]")
            with self.assertRaisesRegex(ValueError, "Incomplete"):
                summarize(root, "updated", cases, grades)


if __name__ == "__main__":
    unittest.main()
