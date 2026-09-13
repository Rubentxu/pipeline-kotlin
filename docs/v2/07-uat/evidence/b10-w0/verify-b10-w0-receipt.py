#!/usr/bin/env python3
"""Mechanical verifier for the B10 / W0 receipt.

The receipt is a statement about a **moment** (`fbcbc51f..a9aca7aa`), not about the
current working tree. Every check therefore reads that commit range with git
rather than reading files off disk. The first version read the tree, and it
expired the moment the ADR it guards was legitimately accepted and the moment the
branch advanced past W0. A verifier that dies when the decision it protects is
actually taken is worse than no verifier, because it trains you to delete it.

Three independent jobs:

  1. ASSERT the properties the receipt claims about the W0 slice: the port stayed
     inside the domain module, the algebra stayed closed, no production wiring
     existed yet, the slice stayed additive, and the ADR was still `proposed`
     *at W0*.
  2. Re-derive the module totals from the archived tarball instead of trusting
     the numbers written in the receipt.
  3. Check that the cited pre-existing failure was still legitimately reusable at
     W0, so a green-looking substitute XML cannot be swapped in.

Exit 0 iff all hold. Every failure prints, so a negative control is a mutation of
the tree, the receipt, or the archived evidence.

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

# Overridable so the history-based checks can be controlled: a control points
# W0_CODE at a commit that deliberately violates one property and requires the
# corresponding check to fire. Restoring the default restores the real receipt.
BASE = os.environ.get("B10_W0_BASE", "fbcbc51f")       # origin/main after Lane R
W0_CODE = os.environ.get("B10_W0_CODE", "a9aca7aa")    # the W0 code commit described
ORIGIN_OF_REUSED = os.environ.get("B10_W0_REUSED_FROM", "2b391e76")

REL_CONTRACT = "v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyInvoker.kt"
REL_CONTRACT_TEST = "v2/pipeline-domain/src/test/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyInvokerSeamTest.kt"
REL_ADR = "docs/v2/04-adrs/ADR-0081-body-invoker-continuation-model.md"
REL_CAPABILITIES = "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Capabilities.kt"

REUSED = os.path.join(
    REPO,
    "docs/v2/07-uat/evidence/lane-r/raw/xml/module-suites/base-module-suites/"
    "TEST-dev.rubentxu.pipeline.v2.architecture.Lfc0GlobalStateFitnessTest.xml",
)

failures: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        failures.append(message)


def git(*args: str) -> str:
    return subprocess.run(
        ["git", "-C", REPO, *args], capture_output=True, text=True, check=True
    ).stdout


def show(commit: str, path: str) -> str:
    """Content of path at commit, or '' when absent there."""
    try:
        return git("show", f"{commit}:{path}")
    except subprocess.CalledProcessError:
        return ""


def files_matching(commit: str, pattern: str) -> list[str]:
    """Paths under the commit whose content matches the regex.

    git grep exits 1 when there is no match, which is the expected answer for a
    forbidden pattern and must not be raised as an error.
    """
    result = subprocess.run(
        ["git", "-C", REPO, "grep", "-l", "-E", pattern, commit, "--", "*.kt"],
        capture_output=True, text=True,
    )
    if result.returncode not in (0, 1):
        raise subprocess.CalledProcessError(result.returncode, result.args, result.stdout)
    return sorted(
        line.split(":", 1)[1] for line in result.stdout.splitlines() if ":" in line
    )


# --------------------------------------------------------------------------
# 1. Slice shape, evaluated at W0
# --------------------------------------------------------------------------

contract = show(W0_CODE, REL_CONTRACT)
check(contract != "", "BodyInvoker.kt is not in pipeline-domain at W0")

# Hexagonal direction: a port in the domain module may not import adapters, the
# coordinator, the journal, the dispatcher, or the compiler.
ALLOWED_IMPORT_PREFIXES = ("dev.rubentxu.pipeline.v2.domain.", "kotlinx.serialization")
outward = [
    line.strip()
    for line in contract.splitlines()
    if line.startswith("import ")
    and not line.strip()[len("import ") :].startswith(ALLOWED_IMPORT_PREFIXES)
]
check(not outward, f"domain port imports an outer layer: {outward}")

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
check("require(attempt?.index?.let { it >= 1 } ?: true)" in contract,
      "the attempt-index invariant is not enforced")
check("fun interface BodyInvoker" in contract, "BodyInvoker is not a single typed seam")
check("suspend fun invoke(body: BodyRef, context: BodyInvocationContext): BodyOutcome" in contract,
      "the seam signature is not the promised one")

for factory in ("fun childBody(", "fun branchBody(", "fun namedBody("):
    check(factory in contract, f"BodyRefs.{factory.strip()} missing: refs are not derivable")

# Zero production wiring at W0: only the contract and its own test may name the key.
holders = files_matching(W0_CODE, "BODY_INVOKER_CAPABILITY")
check(holders == sorted([REL_CONTRACT, REL_CONTRACT_TEST]),
      f"BODY_INVOKER_CAPABILITY was wired somewhere new at W0: {holders}")

# The forbidden collection must not have existed at W0, in this slice or anywhere.
forbidden = files_matching(W0_CODE, r"dispatch(Retry|Timeout|Parallel|Dir)Block")
check(not forbidden, f"a dispatch*Block collection existed at W0: {forbidden}")

# The slice is additive: adding the port must not have edited the engine.
status = [
    line.split("\t")
    for line in git("diff", "--name-status", f"{BASE}..{W0_CODE}").strip().splitlines()
]
check(len(status) == 4, f"the W0 slice touches {len(status)} files, expected 4")
check(all(row[0] == "A" for row in status),
      f"the W0 slice is not additive: {[r for r in status if r[0] != 'A']}")

# The ADR was `proposed` at W0. Asserted at W0 rather than in the tree:
# W0 landed it proposed and a later commit may legitimately accept it.
adr_at_w0 = show(W0_CODE, REL_ADR)
check(re.search(r"^status:\s*proposed\s*$", adr_at_w0, re.M) is not None,
      f"ADR-0081 was not `proposed` at {W0_CODE}")

receipt = open(RECEIPT, encoding="utf-8").read()
check("ADR-0081 status: **proposed**" in receipt,
      "the receipt does not declare the ADR as proposed at W0")

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
# 3. The reused pre-existing failure was still legitimately reusable at W0
# --------------------------------------------------------------------------

diff = git("diff", "--stat", ORIGIN_OF_REUSED, W0_CODE, "--", REL_CAPABILITIES)
check(
    diff.strip() == "",
    f"{REL_CAPABILITIES} changed between {ORIGIN_OF_REUSED} and {W0_CODE}, so the "
    "pre-existing failure had to be re-run rather than cited",
)

check(os.path.isfile(REUSED), "the reused base XML for the pre-existing failure is missing")
if os.path.isfile(REUSED):
    root = ET.parse(REUSED).getroot()
    check(int(root.get("failures")) == 1,
          "the reused base XML does not actually record the failure it is cited for")

# --------------------------------------------------------------------------

print(f"W0 slice files: {len(status)} | archived evidence: "
      f"{len(glob.glob(os.path.join(HERE, 'raw/**/*'), recursive=True))}")
if failures:
    print("FAIL:")
    for f in failures:
        print(f"  {f}")
    sys.exit(1)

print("OK: port was in the domain module, closed algebra intact, zero production wiring at W0")
print("OK: slice was additive, no dispatch*Block collection, ADR was still proposed at W0")
print("OK: module totals re-derived from the archived tarball")
print("OK: the cited pre-existing failure was still valid to reuse at W0")
