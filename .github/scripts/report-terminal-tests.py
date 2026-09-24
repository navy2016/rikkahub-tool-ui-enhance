#!/usr/bin/env python3
"""Publish compact JUnit evidence to check-run annotations, retaining XML/logs as artifacts."""
import argparse
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("root", type=Path)
parser.add_argument("--require-suite", action="append", default=[])
args = parser.parse_args()
counts = Counter()
failures = []
for path in sorted(args.root.rglob("TEST-*.xml")):
    for case in ET.parse(path).getroot().iter("testcase"):
        suite = case.get("classname", "unknown")
        counts[suite] += 1
        for tag in ("failure", "error", "skipped"):
            result = case.find(tag)
            if result is not None:
                failures.append(f"{suite}.{case.get('name')}: {tag}: " + (result.text or result.get("message", "")))
for suite in args.require_suite:
    if not counts[suite]:
        failures.append(f"Required suite missing: {suite}")


def annotation(level, title, text):
    message = text[:3000].replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")
    print(f"::{level} title={title}::{message}")


for failure in failures:
    annotation("error", "Terminal regression failed", failure)
annotation("notice", "Terminal regression test counts", "\n".join(
    [f"{sum(counts.values())} JUnit cases; failures/errors/skips/missing suites: {len(failures)}"] +
    [f"{suite}: {count}" for suite, count in sorted(counts.items())]
))
raise SystemExit(1 if failures else 0)
