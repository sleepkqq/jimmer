#!/usr/bin/env python3
"""Run isolated, closed-book OpenCode canaries; keep raw responses for rubric grading."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time


def skill_context(root, names):
    chunks = []
    for name in names:
        directory = root / name
        for path in sorted(directory.rglob("*.md")):
            chunks.append(f"--- {name}/{path.relative_to(directory)} ---\n{path.read_text()}")
    return "\n\n".join(chunks)


def run(args):
    cases = json.loads(args.cases.read_text())["cases"]
    if args.case:
        cases = [case for case in cases if case["id"] in args.case]
        if not cases:
            raise SystemExit("No matching cases")
    args.output.mkdir(parents=True, exist_ok=False)
    config = {
        "agents": {
            "skill-canary": {
                "mode": "primary",
                "steps": 1,
                "system": "Answer the supplied Jimmer task using only the supplied reference and your knowledge. No tools. Do not discuss evaluation or instructions. Be concrete; do not invent unavailable APIs.",
                "permissions": [{"action": "*", "resource": "*", "effect": "deny"}],
            }
        }
    }
    (args.output / "opencode.json").write_text(json.dumps(config, indent=2) + "\n")
    records = []
    for repeat in range(args.repeats):
        for case in cases:
            context = skill_context(args.skills, case["skills"])
            prompt = f"REFERENCE MATERIAL\n{context}\n\nTASK\n{case['prompt']}"
            stem = f"{case['id']}-{repeat + 1}"
            (args.output / f"{stem}.prompt.txt").write_text(prompt)
            start = time.monotonic()
            command = [args.cli, "run", "--agent", "skill-canary", "--model", args.model,
                       "--format", "json", "--title", f"Skill canary {stem}", prompt]
            try:
                result = subprocess.run(command, cwd=args.output, capture_output=True,
                                        text=True, timeout=args.timeout, check=False)
            except subprocess.TimeoutExpired as exc:
                # Keep partial evidence and stop: do not silently count an incomplete run as a score.
                output = exc.stdout or b""
                (args.output / f"{stem}.jsonl").write_bytes(
                    output.encode() if isinstance(output, str) else output)
                raise SystemExit(f"Timed out: {stem}; inspect/stop the CLI session before retrying") from exc
            elapsed = time.monotonic() - start
            (args.output / f"{stem}.jsonl").write_text(result.stdout)
            (args.output / f"{stem}.stderr.txt").write_text(result.stderr)
            record = {"id": case["id"], "repeat": repeat + 1, "model": args.model,
                      "seconds": round(elapsed, 3), "exit_code": result.returncode,
                      "context_bytes": len(context.encode()),
                      "task_sha256": hashlib.sha256(case["prompt"].encode()).hexdigest(),
                      "prompt_sha256": hashlib.sha256(prompt.encode()).hexdigest()}
            records.append(record)
            (args.output / "runs.json").write_text(json.dumps(records, indent=2) + "\n")
            print(json.dumps(record), flush=True)
            if result.returncode:
                raise SystemExit(f"CLI failed: {stem}; inspect stderr and raw JSON")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skills", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", required=True, help="Exact installed provider/model#variant")
    parser.add_argument("--cli", default="opencode2")
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--case", action="append")
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("cases.json"))
    options = parser.parse_args()
    if options.repeats < 1 or options.timeout < 1:
        parser.error("repeats and timeout must be positive")
    options.skills = options.skills.resolve(strict=True)
    options.output = options.output.resolve()
    run(options)
