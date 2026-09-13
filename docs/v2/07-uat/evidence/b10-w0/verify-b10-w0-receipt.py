#!/usr/bin/env python3
"""Mechanical verifier for the B10 / W0 receipt.

Three independent jobs:

  1. ASSERT the properties the receipt claims about the slice. These are chosen
     so that they would silently regress: the port staying inside the domain
     module, the closed algebra staying closed, no production wiring existing
     yet, and the ADR not having been silently promoted to `accepted`.

  2. Re-derive the module totals from the archived tarball instead of trusting
     the numbers written in the receipt.

  3. Check the reused pre-existing failure is still honestly reused. The receipt
     does not re-run `Lfc0GlobalStateFitnessTest`; it points at evidence captured
     earlier. That is only valid while the inputs which decide that failure are
     unchanged, so the verifier asserts exactly that.

Exit 0 iff all hold. Every failure prints, so a negative control is just a
mutation of the tree, the receipt or the archived evidence.

    python3 verify-b10-w0-receipt.py
"""

from __future__ import annotations

import glob
import os
import re
import subprocess
import sys
import tarfile
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", ".."))
RECEIPT = os.path.join(REPO, "docs/v2/07-uat/B10_W0_INNER_SEAM_RECEIPT.md")
CONTRACT = os.path.join(
    REPO, "v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyInvoker.kt"
)
ADR = os.path.join(REPO, "docs/v2/04-adrs/ADR-0081-body-invoker-continuation-model.md")
BASE = "fbcbc51f"  # origin/main after Lane R
ORIGIN_OF_REUSED_EVIDENCE = "2b391e76"
REUSED = os.path.join(
    REPO,
    "docs/v2/07-uat/evidence/lane-r/raw/xml/module-suites/base-module-suites/"
    "TEST-dev.rubentxu.pipeline.v2.architecture.Lfc0GlobalStateFitnessTest.xml",
)

failures: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        failures.append(message)


def read(path: str) -> str:
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def git(*args: str) -> str:
    return subprocess.run(
        ["git", "-C", REPO, *args], capture_output=True, text=True, check=True
    ).stdout


# --------------------------------------------------------------------------
# 1. Slice shape: the port lives in the domain module and nothing was wired
# --------------------------------------------------------------------------

check(os.path.isfile(CONTRACT), "BodyInvoker.kt is not in pipeline-domain")

# Hexagonal direction: a port in the domain module may not import adapters,
# the coordinator, the journal, the dispatcher, or the compiler.
ALLOWED_IMPORT_PREFIXES = ("dev.rubentxu.pipeline.v2.domain.", "kotlinx.serialization")
if os.path.isfile(CONTRACT):
    outward = [
        line.strip()
        for line in read(CONTRACT).splitlines()
        if line.startswith("import ")
        and not line.strip()[len("import ") :].startswith(ALLOWED_IMPORT_PREFIXES)
    ]
    check(not outward, f"domain port imports an outer layer: {outward}")

contract = read(CONTRACT) if os.path.isfile(CONTRACT) else ""

# The contract surface the receipt promises, as properties rather than names.
check("value class BodyRef(val encoded: String)" in contract,
      "BodyRef is not a value class over a String identity")
check("sealed interface BodyOutcome" in contract, "BodyOutcome is not a sealed closed algebra")
check("data class Completed(val outcome: StepOutcome)" in contract,
      "BodyOutcome.Completed does not wrap the closed StepOutcome")
check("data class Cancelled(val reason: CancellationReason)" in contract,
      "BodyOutcome.Cancelled does not carry a typed reason")
check("Boolean" not in contract,
      "the seam uses a Boolean where a closed algebra was promised")
check("enum class CancellationReason" in contract, "CancellationReason is not closed")
check("sealed interface ExecutionContextPatch" in contract,
      "ExecutionContextPatch is not a sealed closed algebra")
check('require(attempt?.index?.let { it >= 1 } ?: true)' in contract,
      "the attempt-index invariant is not enforced")
check("fun interface BodyInvoker" in contract, "BodyInvoker is not a single typed seam")
check("suspend fun invoke(body: BodyRef, context: BodyInvocationContext): BodyOutcome" in contract,
      "the seam signature is not the promised one")

for factory in ("fun childBody(", "fun branchBody(", "fun namedBody("):
    check(factory in contract, f"BodyRefs.{factory.strip()} missing: refs are not derivable")

# Zero production wiring: only the contract and its own test may name the key.
holder_files = sorted(
    os.path.relpath(p, REPO)
    for p in glob.glob(os.path.join(REPO, "v2/**/*.kt"), recursive=True)
    if "BODY_INVOKER_CAPABILITY" in read(p)
)
check(
    holder_files
    == [
        "v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyInvoker.kt",
        "v2/pipeline-domain/src/test/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyInvokerSeamTest.kt",
    ],
    f"BODY_INVOKER_CAPABILITY is wired somewhere new: {holder_files}",
)

# The forbidden collection must not exist, in this slice or anywhere else.
forbidden = [
    os.path.relpath(p, REPO)
    for p in glob.glob(os.path.join(REPO, "v2/**/*.kt"), recursive=True)
    if re.search(r"dispatch(Retry|Timeout|Parallel|Dir)Block", read(p))
]
check(not forbidden, f"a dispatch*Block collection exists: {forbidden}")

# The slice is additive: adding the port must not have edited the engine.
status = [
    line.split("\t")
    for line in git("diff", "--name-status", f"{BASE}..HEAD").strip().splitlines()
]
check(len(status) == 4, f"the slice touches {len(status)} files, expected 4")
check(all(row[0] == "A" for row in status),
      f"the slice is not additive: {[r for r in status if r[0] != 'A']}")

# The ADR is proposed, and the receipt says so. Accepting it is not part of a merge.
if os.path.isfile(ADR):
    check(re.search(r"^status:\s*proposed\s*$", read(ADR), re.M) is not None,
          "ADR-0081 is no longer `proposed` (acceptance is a design gate, not this PR)")
else:
    failures.append("ADR-0081 is missing")

receipt = read(RECEIPT)
check("ADR-0081 status: **proposed**" in receipt,
      "the receipt does not declare the ADR as proposed")

# --------------------------------------------------------------------------
# 2. Module totals, re-derived from the archived tarball
# --------------------------------------------------------------------------

TARBALL = os.path.join(HERE, "raw/xml/module-suites/head-module-suites-xml.tar.gz")
SEAM = os.path.join(
    HERE, "raw/xml/bodyinvoker-seam/"
    "TEST-dev.rubentxu.pipeline.v2.domain.step.BodyInvokerSeamTest.xml"
)

EXPECTED_TOTALS = {
    "pipeline-domain": (368, 0, 0),
    "pipeline-architecture-tests": (241, 1, 0),
}
EXPECTED_FAILING = {
    "pipeline-domain": [],
    "pipeline-architecture-tests": ["Lfc0GlobalStateFitnessTest"],
}

check(os.path.isfile(TARBALL), "module-suite tarball missing")

totals: dict[str, tuple[int, int, int, list[str]]] = {}
if os.path.isfile(TARBALL):
    with tarfile.open(TARBALL) as tar:
        for member in tar.getmembers():
            if not member.name.endswith(".xml"):
                continue
            module = member.name.split("/")[0]
            root = ET.fromstring(tar.extractfile(member).read())
            t, f, e = (
                int(root.get("tests")),
                int(root.get("failures")),
                int(root.get("errors")),
            )
            seen_t, seen_f, seen_e, bad = totals.get(module, (0, 0, 0, []))
            bad = list(bad)
            if f + e:
                bad.append(os.path.basename(member.name)[5:-4].split(".")[-1])
            totals[module] = (seen_t + t, seen_f + f, seen_e + e, bad)

for module, expected in EXPECTED_TOTALS.items():
    got = totals.get(module)
    check(got is not None, f"{module}: absent from the archived module-suite XML")
    if got is not None:
        check(got[:3] == expected, f"{module}: totals {got[:3]} != {expected}")

for module, expected in EXPECTED_FAILING.items():
    got = totals.get(module)
    if got is not None:
        check(
            sorted(got[3]) == sorted(expected),
            f"{module}: failing classes {sorted(got[3])} != {sorted(expected)}",
        )

check(os.path.isfile(SEAM), "the HF0 contract-suite XML is not archived")
if os.path.isfile(SEAM):
    root = ET.parse(SEAM).getroot()
    got = (int(root.get("tests")), int(root.get("failures")), int(root.get("errors")))
    check(got == (8, 0, 0), f"BodyInvokerSeamTest is {got}, expected (8, 0, 0)")

# --------------------------------------------------------------------------
# 3. The reused pre-existing failure is still legitimately reusable
# --------------------------------------------------------------------------

CAPABILITIES = "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Capabilities.kt"
diff = git("diff", "--stat", ORIGIN_OF_REUSED_EVIDENCE, "HEAD", "--", CAPABILITIES)
check(
    diff.strip() == "",
    f"{CAPABILITIES} changed since the reused evidence was captured, so the "
    "pre-existing failure must be re-run rather than cited",
)

check(os.path.isfile(REUSED), "the reused base XML for the pre-existing failure is missing")
if os.path.isfile(REUSED):
    root = ET.parse(REUSED).getroot()
    check(int(root.get("failures")) == 1,
          "the reused base XML does not actually record the failure it is cited for")

# --------------------------------------------------------------------------

print(f"slice files: {len(status)} | archived evidence: "
      f"{len(glob.glob(os.path.join(HERE, 'raw/**/*'), recursive=True))}")
if failures:
    print("FAIL:")
    for f in failures:
        print(f"  {f}")
    sys.exit(1)

print("OK: port is in the domain module, closed algebra intact, zero production wiring")
print("OK: slice is additive, no dispatch*Block collection, ADR still proposed")
print("OK: module totals re-derived from the archived tarball")
print("OK: the cited pre-existing failure is still valid to reuse")
