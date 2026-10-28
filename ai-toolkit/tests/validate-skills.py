#!/usr/bin/env python3
"""Validate skill metadata, local links, sibling references, scripts and canaries."""

import json
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]
SKILLS = ROOT / "skills"
errors = []
artifacts = {"jimmer-apt", "jimmer-ksp", "jimmer-ddl-compiler", "jimmer-bom",
             "jimmer-spring-boot-starter", "jimmer-sql", "jimmer-sql-kotlin"}

skills = sorted(SKILLS.glob("*/SKILL.md"))
names = {path.parent.name for path in skills}
assert names, f"No skills in {SKILLS}"
for path in skills:
    text = path.read_text()
    match = re.match(r"\A---\n(.*?)\n---\n", text, re.S)
    if not match:
        errors.append(f"{path}: missing frontmatter")
        continue
    fields = dict(re.findall(r"^([a-z-]+): (.+)$", match[1], re.M))
    if fields.get("name") != path.parent.name:
        errors.append(f"{path}: name does not match directory")
    description = fields.get("description", "")
    if not 1 <= len(description) <= 1024 or ": " in description:
        errors.append(f"{path}: invalid unquoted description")
    if len(text.splitlines()) > 500:
        errors.append(f"{path}: entry point exceeds 500 lines")

for path in sorted(SKILLS.rglob("*.md")):
    text = path.read_text()
    for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", text):
        if "://" in target or target.startswith("#"):
            continue
        resolved = (path.parent / target.split("#")[0]).resolve()
        if not resolved.exists() or not resolved.is_relative_to(SKILLS.resolve()):
            errors.append(f"{path}: broken or nonportable local link {target}")
    for name in re.findall(r"`(jimmer-[a-z-]+)`", text):
        if name not in names | artifacts:
            errors.append(f"{path}: unknown sibling skill {name}")

scripts = sorted(SKILLS.rglob("*.sh"))
for path in scripts:
    result = subprocess.run(["bash", "-n", str(path)], capture_output=True, text=True)
    if result.returncode:
        errors.append(f"{path}: {result.stderr.strip()}")

case_count = 0
for suite in sorted((ROOT / "tests/skill-evals").glob("*cases.json")):
    cases = json.loads(suite.read_text())["cases"]
    assert len({case["id"] for case in cases}) == len(cases), f"duplicate IDs in {suite}"
    for case in cases:
        assert case["prompt"] and case["assertions"], f"empty case {case['id']}"
        assert set(case["skills"]) <= names, f"unknown skill in {case['id']}"
    case_count += len(cases)

if errors:
    raise SystemExit("\n".join(errors))
print(f"Validated {len(skills)} skills, local links, {case_count} canaries and {len(scripts)} shell helpers")
