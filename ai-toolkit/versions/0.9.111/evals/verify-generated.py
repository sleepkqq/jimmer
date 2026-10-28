#!/usr/bin/env python3
"""Compile retained canary answers and execute their functions against H2."""

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def answer(directory, case):
    events = [json.loads(line) for line in (directory / f"{case}-1.jsonl").read_text().splitlines()]
    if any(event["type"] not in {"step_start", "step_finish", "text"} for event in events):
        raise ValueError(f"Failed/tool-contaminated answer: {case}")
    text = "\n".join(event["part"]["text"] for event in events if event["type"] == "text")
    if not text.strip():
        raise ValueError(f"Empty answer: {case}")
    return text


def code(text, language):
    blocks = re.findall(rf"```{language}\s*\n(.*?)```", text, re.S)
    if not blocks:
        # Tolerate a single fence with no language tag (observed model output).
        untagged = re.findall(r"```[ \t]*\n(.*?)```", text, re.S)
        if len(untagged) == 1:
            return untagged[0]
    if len(blocks) != 1:
        raise ValueError(f"Expected exactly one {language} block, got {len(blocks)}")
    return blocks[0]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runs", type=Path, required=True)
    parser.add_argument("--gradle", default="gradle")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    examples = here.parent / "examples"
    with tempfile.TemporaryDirectory(prefix="jimmer-canary-compile-") as temporary:
        project = Path(temporary)
        for name in ("settings.gradle.kts", "build.gradle.kts"):
            shutil.copy2(examples / name, project / name)
        shutil.copytree(examples / "schema", project / "schema")
        for language, case, filename in (
            ("java", "java-create-and-query", "CanaryJava.java"),
            ("kotlin", "kotlin-conditional-and-query", "CanaryKotlin.kt"),
        ):
            module = project / language
            shutil.copytree(examples / language / "src", module / "src")
            shutil.copy2(examples / language / "build.gradle.kts", module / "build.gradle.kts")
            sources = module / "src/main" / language / "example"
            (sources / filename).write_text(code(answer(args.runs, case), language))
            extension = "java" if language == "java" else "kt"
            shutil.copy2(here / "checks" / f"Examples.{extension}", sources / f"Examples.{extension}")
        dto = code(answer(args.runs, "kotlin-patch-presence"), "dto")
        for language in ("java", "kotlin"):
            stale = project / language / "src/main/dto/BookStore.dto"
            if stale.exists():
                stale.unlink()
            dto_path = project / language / "src/main/dto/example/BookStore.dto"
            dto_path.parent.mkdir(parents=True, exist_ok=True)
            dto_path.write_text(dto)
        subprocess.run([args.gradle, "-p", str(project), "--no-daemon", "--console=plain", "check"], check=True)
