#!/usr/bin/env python3
"""S2-B10 / G8 — assembles the gate's derived evidence from the real JUnit XMLs.

Every number the G8 receipt asserts about tests is produced here, from the XML that
the test JVM actually wrote, and never typed by hand. The XMLs are build outputs and
are NOT committed (they do not exist in a fresh tree); this JSON is the durable
record of what they said at gate time, and it carries each XML's sha256 so the
record is bound to specific bytes rather than to a recollection.

Result truth is the XML (V2 TESTING RULES rule 25), not the console and not the
Gradle exit code. `cleanTest` deleted these XMLs before the run, so their
regeneration is the freshness canary.

Exits non-zero if an expected class is missing or is not green, so the gate cannot
pass by omission.
"""
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", ".."))

# class simple name -> (module, expect_green)
EXPECTED = {
    "CoreArchiveArtifactsStepContractSuiteTest": ("pipeline-application", True),
    "LegacyResidualConvergenceFitnessTest": ("pipeline-architecture-tests", True),
    "Lfc2RegistryFamilyFitnessTest": ("pipeline-architecture-tests", True),
    "Lfc2DurableCoordinatorScopeFitnessTest": ("pipeline-architecture-tests", True),
    "Lfc0GlobalStateFitnessTest": ("pipeline-architecture-tests", False),  # pre-existing red
    "S3EchoLegacyRemovedFitnessTest": ("pipeline-architecture-tests", True),
    "S3EmitEventLegacyRemovedFitnessTest": ("pipeline-architecture-tests", True),
    "S3ErrorLegacyRemovedFitnessTest": ("pipeline-architecture-tests", True),
    "S3IsUnixLegacyRemovedFitnessTest": ("pipeline-architecture-tests", True),
    "S3PwdLegacyRemovedFitnessTest": ("pipeline-architecture-tests", True),
    "S3SleepLegacyRemovedFitnessTest": ("pipeline-architecture-tests", True),
    "S3WriteFileLegacyRemovedFitnessTest": ("pipeline-architecture-tests", True),
}


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def find_xml(module, simple):
    base = os.path.join(ROOT, "v2", module, "build", "test-results", "test")
    for name in os.listdir(base) if os.path.isdir(base) else []:
        if name.startswith("TEST-") and name.endswith(f".{simple}.xml"):
            return os.path.join(base, name)
    return None


def main():
    import re

    classes = {}
    problems = []
    for simple, (module, expect_green) in sorted(EXPECTED.items()):
        path = find_xml(module, simple)
        if path is None:
            problems.append(f"missing XML for {simple} ({module})")
            continue
        xml = open(path, encoding="utf-8").read()
        head = xml.split(">", 2)[1] if xml.startswith("<?xml") else xml.split(">", 1)[0]
        counts = {}
        for k in ("tests", "skipped", "failures", "errors"):
            m = re.search(rf'{k}="(\d+)"', head)
            counts[k] = int(m.group(1)) if m else None
        ts = re.search(r'timestamp="([^"]+)"', head)
        classes[simple] = {
            "module": module,
            "tests": counts["tests"],
            "skipped": counts["skipped"],
            "failures": counts["failures"],
            "errors": counts["errors"],
            "xml_sha256": sha256_file(path),
            "timestamp_utc": ts.group(1) if ts else None,
        }
        green = counts["failures"] == 0 and counts["errors"] == 0
        if expect_green and not green:
            problems.append(f"{simple} expected GREEN, got f={counts['failures']} e={counts['errors']}")
        if not expect_green and green:
            problems.append(f"{simple} expected the documented pre-existing RED, got green")

    totals = {
        "s3_legacy_removed_tests": sum(
            v["tests"] for k, v in classes.items() if k.startswith("S3") and k.endswith("LegacyRemovedFitnessTest")
        ),
        "s3_legacy_removed_failures": sum(
            v["failures"] + v["errors"] for k, v in classes.items() if k.startswith("S3") and k.endswith("LegacyRemovedFitnessTest")
        ),
        "contract_suite_tests": classes.get("CoreArchiveArtifactsStepContractSuiteTest", {}).get("tests"),
        "expected_red_classes": sorted(k for k, v in classes.items() if v["failures"] or v["errors"]),
    }

    payload = {
        "gate": "S2-B10/G8",
        "code_under_test": "e7ea54ba",
        "result_truth": "JUnit XML (build outputs, not committed); sha256 recorded below",
        "classes": classes,
        "totals": totals,
    }
    out = os.path.join(HERE, "g8-canary.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2, sort_keys=True)
        fh.write("\n")

    print(f"wrote {os.path.relpath(out, ROOT)}")
    for k, v in classes.items():
        print(f"  {k:<48} tests={v['tests']:<3} f={v['failures']} e={v['errors']}  {v['xml_sha256'][:16]}…")
    print(f"  totals: S3={totals['s3_legacy_removed_tests']}/"
          f"{totals['s3_legacy_removed_failures']}  contract={totals['contract_suite_tests']}")
    if problems:
        print("\nFAIL:")
        for p in problems:
            print("  " + p)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
