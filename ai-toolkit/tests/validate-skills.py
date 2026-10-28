#!/usr/bin/env python3
"""Validate each release's skill metadata, local links, scripts and canaries."""

import json
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]
errors = []
versions = sorted((ROOT / "versions").glob("*/skills"))
assert versions, "No release skill sets found"
skill_count = script_count = 0
all_names = []
artifacts = {"jimmer-apt", "jimmer-ksp", "jimmer-ddl-compiler", "jimmer-bom",
             "jimmer-spring-boot-starter", "jimmer-sql", "jimmer-sql-kotlin"}
for root in versions:
    version = root.parent.name
    manifest = json.loads((root.parent / "sources.json").read_text())
    assert manifest["version"] == version
    assert manifest["repository"] == "https://github.com/babyfish-ct/jimmer"
    assert manifest["tag"] == f"v{version}"
    assert re.fullmatch(r"[0-9a-f]{40}", manifest["commit"])
    skills = sorted(root.glob("*/SKILL.md"))
    names = {path.parent.name for path in skills}
    assert names, f"No skills in {root}"
    all_names.append(names)
    skill_count += len(skills)
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
    for path in sorted(root.rglob("*.md")):
        text = path.read_text()
        for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", text):
            if "://" in target or target.startswith("#"):
                continue
            resolved = (path.parent / target.split("#")[0]).resolve()
            if not resolved.exists() or not resolved.is_relative_to(root.resolve()):
                errors.append(f"{path}: broken or nonportable local link {target}")
        for name in re.findall(r"`(jimmer-[a-z-]+)`", text):
            if name not in names | artifacts:
                errors.append(f"{path}: unknown sibling skill {name}")
    scripts = sorted(root.rglob("*.sh"))
    script_count += len(scripts)
    for path in scripts:
        result = subprocess.run(["bash", "-n", str(path)], capture_output=True, text=True)
        if result.returncode:
            errors.append(f"{path}: {result.stderr.strip()}")

assert all(names == all_names[0] for names in all_names), "Release topic sets differ"
case_count = 0
case_suites = sorted((ROOT / "tests/skill-evals").glob("*cases.json"))
case_suites += sorted((ROOT / "versions").glob("*/evals/cases.json"))
for suite in case_suites:
    cases = json.loads(suite.read_text())["cases"]
    assert len({case["id"] for case in cases}) == len(cases), f"duplicate IDs in {suite}"
    for case in cases:
        assert case["prompt"] and case["assertions"], f"empty case {case['id']}"
        assert set(case["skills"]) <= all_names[0], f"unknown skill in {case['id']}"
    case_count += len(cases)

if errors:
    raise SystemExit("\n".join(errors))
print(f"Validated {len(versions)} releases, {skill_count} skills, local links, "
      f"{case_count} canaries and {script_count} shell helpers")
