"""Fail unless the named JUnit XML results prove the required tests actually ran.

Usage:
  verify-test-results.py RESULTS_DIR [--case FQCN#method]... FQCN MIN_TESTS [FQCN MIN_TESTS ...]

Every class must show at least MIN_TESTS executed with zero failures, errors and
skips. Every --case must appear as an executed, non-skipped test case, so a run
that still passes a class count but silently drops the specific required case
fails.
"""
import pathlib
import sys
import xml.etree.ElementTree as ET

results_dir = pathlib.Path(sys.argv[1])
required_cases = []
pairs = []
arguments = sys.argv[2:]
index = 0
while index < len(arguments):
    if arguments[index] == "--case":
        if index + 1 >= len(arguments):
            raise SystemExit("--case requires FQCN#method")
        required_cases.append(arguments[index + 1])
        index += 2
    else:
        pairs.append(arguments[index])
        index += 1
if not pairs or len(pairs) % 2:
    raise SystemExit("expected FQCN MIN_TESTS pairs")

suites = {}


def suite(fqcn):
    if fqcn not in suites:
        report = results_dir / f"TEST-{fqcn}.xml"
        if not report.is_file():
            raise SystemExit(f"missing test result for {fqcn}: {report}")
        suites[fqcn] = ET.parse(report).getroot()
    return suites[fqcn]


for offset in range(0, len(pairs), 2):
    fqcn = pairs[offset]
    minimum = int(pairs[offset + 1])
    root = suite(fqcn)
    counts = {key: int(root.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
    if counts["tests"] < minimum:
        raise SystemExit(f"{fqcn}: {counts['tests']} tests ran, expected at least {minimum}")
    if any(counts[key] for key in ("failures", "errors", "skipped")):
        raise SystemExit(f"{fqcn}: required results are not clean: {counts}")
    print(f"OK {fqcn}: {counts['tests']} tests, 0 failures/errors/skips")

for qualified in required_cases:
    fqcn, _, method = qualified.partition("#")
    if not method:
        raise SystemExit(f"required case must be FQCN#method: {qualified}")
    for case in suite(fqcn).iter("testcase"):
        if case.get("name") in (method, method + "()"):
            if case.find("skipped") is not None:
                raise SystemExit(f"required case was skipped: {qualified}")
            break
    else:
        raise SystemExit(f"required case did not run: {qualified}")
    print(f"OK required case ran: {qualified}")
