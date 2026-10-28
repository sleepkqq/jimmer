#!/usr/bin/env python3
"""Hide trial condition/order from a separate rubric grader; keep a reversible key."""

import argparse
import json
from pathlib import Path
import random


def blind(cases_path, directories, output, seed):
    cases = {c["id"]: c for c in json.loads(cases_path.read_text())["cases"]}
    trials = []
    for condition, directory in directories.items():
        for run in json.loads((directory / "runs.json").read_text()):
            if run["id"] not in cases:
                raise ValueError(f"Unknown case: {run['id']}")
            events = [json.loads(line) for line in
                      (directory / f"{run['id']}-{run['repeat']}.jsonl").read_text().splitlines()]
            if run["exit_code"] or any(e["type"] not in {"step_start", "text", "step_finish"} for e in events):
                raise ValueError("Failed/contaminated trial")
            answer = "\n".join(e["part"]["text"] for e in events if e["type"] == "text")
            if not answer.strip():
                raise ValueError("Empty answer")
            trials.append((condition, run["id"], run["repeat"], answer))
    random.Random(seed).shuffle(trials)
    packet, key = [], {}
    for index, (condition, case_id, repeat, answer) in enumerate(trials, 1):
        identifier = f"answer-{index:03d}"
        case = cases[case_id]
        packet.append({"id": identifier, "task": case["prompt"],
                       "assertions": case["assertions"], "answer": answer})
        key[identifier] = {"condition": condition, "case": case_id, "repeat": repeat}
    output.mkdir(parents=True, exist_ok=False)
    (output / "packet.json").write_text(json.dumps(packet, ensure_ascii=False, indent=2) + "\n")
    (output / "key.json").write_text(json.dumps(key, indent=2) + "\n")
    print(f"Prepared {len(packet)} masked answers; keep key.json away from the grader")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--updated", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=7391)
    args = parser.parse_args()
    blind(args.cases, {"baseline": args.baseline, "updated": args.updated}, args.output, args.seed)
