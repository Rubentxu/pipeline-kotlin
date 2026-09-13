#!/usr/bin/env python3
"""Mechanical verifier for the Lane R receipt.

Two independent jobs:

  1. ASSERT the repository state the receipt describes. These are not "does the
     string appear" checks: each one is a property that would silently regress.

  2. RESOLVE every sha256 citation in the receipt against the archived raw
     evidence, and re-derive the base-vs-head non-regression table from the
     archived JUnit XML rather than trusting the table as written.

Exit 0 iff both hold. Every check prints on failure, so a negative control is
just a mutation of the receipt or the tree.

    python3 verify-lane-r-receipt.py
"""

from __future__ import annotations

import glob
import hashlib
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", ".."))
RECEIPT = os.path.join(REPO, "docs/v2/07-uat/LANE_R_CLEAN_BUILD_REPRODUCIBILITY_RECEIPT.md")

failures: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        failures.append(message)


def read(path: str) -> str:
    with open(os.path.join(REPO, path), encoding="utf-8") as fh:
        return fh.read()


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


# --------------------------------------------------------------------------
# 1. Repository assertions
# --------------------------------------------------------------------------

# Defect C: no test may name an absolute path into a checkout.
absolute = []
for path in glob.glob(os.path.join(REPO, "v2/*/src/test/**/*.kt"), recursive=True):
    for lineno, line in enumerate(open(path, encoding="utf-8"), 1):
        if re.search(r'"(/var/home/|/Users/|/home/[^/]+/Proyectos)', line):
            absolute.append(f"{os.path.relpath(path, REPO)}:{lineno}")
check(not absolute, f"absolute checkout paths in test sources: {absolute}")

# Defect B: the committed SDK snapshots must be gone.
for stray in glob.glob(os.path.join(REPO, "examples/example-uppercase-plugin/libs/*.jar")):
    check(False, f"committed SDK snapshot still present: {os.path.relpath(stray, REPO)}")

plugin_build = read("examples/example-uppercase-plugin/build.gradle.kts")
check(
    'compileOnly("dev.rubentxu.pipeline.v2:pipeline-domain:' in plugin_build
    and 'compileOnly("dev.rubentxu.pipeline.v2:pipeline-scripting-api:' in plugin_build,
    "plugin does not declare the SDK as module coordinates",
)
check(
    'files("libs/' not in plugin_build,
    "plugin still depends on a committed libs/ jar",
)
check(
    "cacheChangingModulesFor(0" in plugin_build,
    "plugin does not force SNAPSHOT re-resolution (stale SDK could be reused)",
)
check(
    'project(":' not in plugin_build,
    "plugin must not depend on a v2 project (it must stay an external frontier)",
)

# R5: the producer chain must be wired, not merely documented.
app_build = read("v2/pipeline-application/build.gradle.kts")
check(
    re.search(r'tasks\.named\("compileTestKotlin"\)\s*\{\s*dependsOn\(":buildExamplePlugin"\)', app_build)
    is not None,
    "compileTestKotlin does not depend on :buildExamplePlugin (a clean checkout would not compile)",
)

root_build = read("v2/build.gradle.kts")
check(
    'dependsOn(\n        ":pipeline-domain:publishSdkPublicationToSdkRepository"' in root_build,
    "publishSdkForExternalPlugin does not publish pipeline-domain",
)
check(
    'dependsOn(publishSdkForExternalPlugin)' in root_build,
    "buildExamplePlugin does not depend on publishSdkForExternalPlugin",
)
check(
    'systemProperty("pipeline.repoRoot"' in root_build,
    "pipeline.repoRoot is not exposed to Test tasks",
)

for module in ("pipeline-domain", "pipeline-scripting-api"):
    module_build = read(f"v2/{module}/build.gradle.kts")
    check("`maven-publish`" in module_build, f"{module} does not apply maven-publish")
    check(
        'layout.buildDirectory.dir("sdk-repo")' in module_build,
        f"{module} does not publish into the build-local sdk-repo",
    )

# The scripting-api jar locator must not have been reduced to a literal again.
script_definition = read(
    "v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/ScriptDefinition.kt"
)
check("fun classpathJar(artifact: String)" in script_definition, "classpathJar helper missing")
check("fun domainJar(): String? = classpathJar(\"pipeline-domain\")" in script_definition,
      "domainJar does not delegate to classpathJar")

# --------------------------------------------------------------------------
# 2. Re-derive the non-regression table from archived XML
# --------------------------------------------------------------------------


def counts(path: str) -> tuple[int, int, int]:
    root = ET.parse(path).getroot()
    return int(root.get("tests")), int(root.get("failures")), int(root.get("errors"))


xml_dir = os.path.join(HERE, "raw/xml")
head_files = {
    os.path.basename(p): p for p in glob.glob(os.path.join(xml_dir, "head/*.xml"))
}
base_files = {
    os.path.basename(p): p for p in glob.glob(os.path.join(xml_dir, "base/*.xml"))
}

check(len(head_files) == 7, f"expected 7 head XML, found {len(head_files)}")
check(len(base_files) == 7, f"expected 7 base XML, found {len(base_files)}")

drift = []
for name, head_path in sorted(head_files.items()):
    base_path = base_files.get(name)
    if base_path is None:
        drift.append(f"{name}: no base XML")
        continue
    if counts(base_path) != counts(head_path):
        drift.append(f"{name}: base {counts(base_path)} != head {counts(head_path)}")
check(not drift, f"base-vs-head counts differ: {drift}")

r2 = os.path.join(xml_dir, "TEST-dev.rubentxu.pipeline.v2.application.UppercaseStepContractSuiteTest.xml")
check(counts(r2) == (14, 0, 0), f"R2 clean-room suite is {counts(r2)}, expected (14, 0, 0)")

# --------------------------------------------------------------------------
# 3. Resolve every sha256 citation in the receipt
# --------------------------------------------------------------------------

receipt_text = open(RECEIPT, encoding="utf-8").read()
archived = {
    sha256(p): os.path.relpath(p, REPO)
    for p in glob.glob(os.path.join(HERE, "raw/**/*"), recursive=True)
    if os.path.isfile(p)
}

# Classes of citation that are provably not re-derivable from archived evidence.
# Empty here: every digest this receipt cites is the digest of an archived file.
NON_RE_DERIVABLE: dict[str, str] = {}

cited = set(re.findall(r"\b[0-9a-f]{64}\b", receipt_text))
unresolved = [
    c for c in sorted(cited) if c not in archived and c not in NON_RE_DERIVABLE
]
check(
    not unresolved,
    "cited sha256 does not resolve to archived evidence: "
    + ", ".join(f"{c[:12]}…" for c in unresolved),
)

# --------------------------------------------------------------------------

print(f"mechanical assertions: {len(archived)} archived files, {len(cited)} cited digests")
if failures:
    print("FAIL:")
    for f in failures:
        print(f"  {f}")
    sys.exit(1)

print("OK: no absolute checkout paths, no committed SDK snapshots, plugin wired to live SDK")
print("OK: archived XML reproduce the base-vs-head table and the R2 result")
print("OK: every cited sha256 resolves to an archived file")
