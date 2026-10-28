#!/usr/bin/env python3
"""Combine inspected answer grades with CLI evidence; never infer quality from exit status."""

import argparse
import hashlib
import json
from pathlib import Path
import statistics


def summarize(directory, condition, cases, grades):
    records = [record for record in json.loads((directory / "runs.json").read_text())
               if record["id"] in cases]
    answers = []
    seen = set()
    for record in records:
        key = (record["id"], record["repeat"])
        if key in seen:
            raise ValueError(f"Duplicate run: {key}")
        seen.add(key)
        path = directory / f"{key[0]}-{key[1]}.jsonl"
        events = [json.loads(line) for line in path.read_text().splitlines()]
        if record["exit_code"] or any(e["type"] not in {"step_start", "text", "step_finish"} for e in events):
            raise ValueError(f"Failed/contaminated run: {path}")
        text = "\n".join(e["part"]["text"] for e in events if e["type"] == "text")
        if not text.strip():
            raise ValueError(f"No answer: {path}")
        if "task_sha256" in record:
            expected = hashlib.sha256(cases[key[0]]["prompt"].encode()).hexdigest()
            if record["task_sha256"] != expected:
                raise ValueError(f"Task changed since run: {key}")
        flags = grades[key[0]][condition][key[1] - 1]
        if len(flags) != len(cases[key[0]]["assertions"]) or not all(type(v) is bool for v in flags):
            raise ValueError(f"Invalid grade vector: {key}")
        answers.append({**record, "passed": sum(flags), "total": len(flags),
                        "assertion_passes": flags, "answer_sha256": hashlib.sha256(text.encode()).hexdigest(),
                        "answer": text})
    expected = {(name, repeat) for name in cases
                for repeat in range(1, len(grades[name][condition]) + 1)}
    if seen != expected:
        raise ValueError(f"Incomplete run set: missing={expected - seen}, extra={seen - expected}")
    passed = sum(a["passed"] for a in answers)
    total = sum(a["total"] for a in answers)
    return {"passed": passed, "total": total, "pass_rate": passed / total,
            "median_seconds": statistics.median(a["seconds"] for a in answers),
            "mean_context_bytes": statistics.mean(a["context_bytes"] for a in answers),
            "runs": answers}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--updated", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--grading", type=Path, default=Path(__file__).with_name("grading.json"))
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("cases.json"))
    args = parser.parse_args()
    cases = {case["id"]: case for case in json.loads(args.cases.read_text())["cases"]}
    grading = json.loads(args.grading.read_text())
    cases = {name: cases[name] for name in grading["cases"]}
    result = {"grading_method": grading["method"]}
    for condition in ("baseline", "updated"):
        result[condition] = summarize(getattr(args, condition), condition, cases, grading["cases"])
    if {r["model"] for r in result["baseline"]["runs"]} != {r["model"] for r in result["updated"]["runs"]}:
        raise SystemExit("Model mismatch")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x") as stream:
        json.dump(result, stream, indent=2)
        stream.write("\n")
    print(json.dumps({key: {k: v for k, v in result[key].items() if k != "runs"}
                      for key in ("baseline", "updated")}, indent=2))
