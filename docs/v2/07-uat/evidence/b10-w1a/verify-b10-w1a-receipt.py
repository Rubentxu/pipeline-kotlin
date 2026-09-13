#!/usr/bin/env python3
"""Verify the B10/W1a receipt (docs/v2/07-uat/B10_W1A_ROUTING_DEBT_RECEIPT.md).

The pinned ledger in the Kotlin model is NOT trusted as an authority. This verifier
re-scans the coordinator in Python under the same rules and compares the two, so a pin
edited to match a drifted source is caught by the re-scan rather than by two copies of the
same numbers agreeing with each other.

Reads the code claims out of the working tree on purpose: W1a's claims are about the repo
state after W1a, and any later slice that changes the debt MUST also lower the ledger, which
this verifier will then require. Set W1A_BASE/W1A_CODE to re-point the git-level claims.
"""

from __future__ import annotations

import html
import io
import os
import re
import subprocess
import sys
import tarfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[4]
BASE = os.environ.get("W1A_BASE", "da594bb6")
ARCH = "v2/pipeline-architecture-tests"
COORD = (
    "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/"
    "durable/CanonicalDurableRunCoordinator.kt"
)
MODEL = f"{ARCH}/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/ConcreteBodyRoutingDebt.kt"
GUARD = f"{ARCH}/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/Lfc2ConcreteBodyRoutingDebtFitnessTest.kt"
RECEIPT = "docs/v2/07-uat/B10_W1A_ROUTING_DEBT_RECEIPT.md"
SUITE_TAR = HERE / "raw/xml/architecture-suite-xml.tar.gz"
LANE_R_BASE_XML = (
    REPO / "docs/v2/07-uat/evidence/lane-r/raw/xml/module-suites/base-module-suites/"
    "TEST-dev.rubentxu.pipeline.v2.architecture.Lfc0GlobalStateFitnessTest.xml"
)

failures: list[str] = []
checks = 0


def check(label: str, ok: bool, detail: str = "") -> None:
    global checks
    checks += 1
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}" + (f" -- {detail}" if detail and not ok else ""))
    if not ok:
        failures.append(f"{label}: {detail}")


def git(*args: str) -> str:
    return subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, text=True, check=False).stdout


def read(path: str) -> str:
    p = REPO / path
    return p.read_text(encoding="utf-8") if p.is_file() else ""


# ---- independent re-scan (Python implementation of the documented rules) -----
CORE_LITERAL = re.compile(r'"core\.[A-Za-z0-9_]+"')
BODY_IDS_BLOCK = re.compile(r"canonicalBodyStepIds\s*:\s*Set<String>\s*=\s*setOf\(([^)]*)\)")
STEP_ID_SWITCH = re.compile(r"when\s*\(\s*[A-Za-z0-9_.]*[Ss]tepId[A-Za-z0-9_.]*\s*\)")
BLOCK_DISPATCH = re.compile(r"\bdispatch[A-Za-z0-9_]*Block\b")


def rescan(source: str) -> dict:
    block = BODY_IDS_BLOCK.search(source)
    body_ids = {m.group(0).strip('"') for m in CORE_LITERAL.finditer(block.group(1))} if block else set()
    sites = set()
    if block:
        sites.add("CANONICAL_BODY_STEP_IDS")
    if re.search(r"projectShellScope\s*\(", source):
        sites.add("PROJECT_SHELL_SCOPE")
    if re.search(r"private\s+suspend\s+fun\s+dispatchWithCredentialsBlock\(", source):
        sites.add("DISPATCH_WITH_CREDENTIALS_BLOCK")
    return {
        "concreteStepNames": {m.group(0).strip('"') for m in CORE_LITERAL.finditer(source)},
        "bodyStepIds": body_ids,
        "sites": sites,
        "blockBypasses": {m.group(0) for m in BLOCK_DISPATCH.finditer(source)},
        "stepIdSwitches": len(STEP_ID_SWITCH.findall(source)),
    }


def kotlin_string_set(model: str, field: str) -> set[str]:
    m = re.search(rf"{field}\s*=\s*setOf\(([^)]*)\)", model)
    return {x.strip().strip('"') for x in m.group(1).split(",") if x.strip()} if m else set()


def kotlin_enum_set(model: str, enum: str, field: str) -> set[str]:
    """Extract the `enum.Type` members named in `field = setOf(enum.A, enum.B, ...)`."""
    m = re.search(rf"{field}\s*=\s*setOf\(([^)]*)\)", model)
    if not m:
        return set()
    return {x.strip()[len(enum) + 1:] for x in m.group(1).split(",")
            if x.strip().startswith(enum + ".")}


def kotlin_int(model: str, name: str) -> int | None:
    m = re.search(rf"{name}\s*=\s*(\d+)", model)
    return int(m.group(1)) if m else None


def xml_total(tar: Path, cls: str) -> tuple[int, int, int] | None:
    """Aggregate every archived XML whose class name contains `cls`.

    A class with @Nested inner classes produces several XML files; taking the first
    match would report only one of them.
    """
    tests = fails = errs = 0
    found = False
    with tarfile.open(tar, "r:gz") as t:
        for m in t.getmembers():
            if cls not in m.name or not m.name.endswith(".xml"):
                continue
            text = t.extractfile(m).read().decode("utf-8", "replace")
            r = re.search(r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', text)
            if r:
                found = True
                tests += int(r.group(1)); fails += int(r.group(2)); errs += int(r.group(3))
    return (tests, fails, errs) if found else None


def failure_message(text: str) -> str:
    m = re.search(r'<failure[^>]*message="([^"]*)"', text)
    if not m:
        return ""
    return re.sub(r"/var/home/\S*/pipeline-[A-Za-z0-9-]+/", "<ROOT>/", html.unescape(m.group(1)))


print(f"B10/W1a receipt verifier -- base {BASE}")
print()

coord = read(COORD)
model = read(MODEL)
guard = read(GUARD)
receipt = read(RECEIPT)

check("coordinator source is readable", bool(coord), COORD)
check("ledger model is present", bool(model), MODEL)
check("guard test is present", bool(guard), GUARD)
check("receipt is present", bool(receipt), RECEIPT)

scan = rescan(coord)

# ---- 1: the pin equals the source, re-derived --------------------------------
for field in ("concreteStepNames", "bodyStepIds", "blockBypasses"):
    pinned = kotlin_string_set(model, field)
    check(
        f"pinned {field} equals the independently re-scanned value",
        pinned == scan[field],
        f"pinned {sorted(pinned)} vs scanned {sorted(scan[field])}",
    )
pinned_sites = kotlin_enum_set(model, "BodyRoutingSite", "sites")
check(
    "pinned sites equals the independently re-scanned value",
    pinned_sites == scan["sites"],
    f"pinned {sorted(pinned_sites)} vs scanned {sorted(scan['sites'])}",
)
# Positional, not a substring search: the model's own comment explains why
# entries.toSet() is NOT used, so a `not in model` check would trip on the prose.
check(
    "the site ledger is named explicitly, not assigned from the enum entries",
    re.search(r"sites\s*=\s*BodyRoutingSite\.entries\.toSet\(\)", model) is None
    and re.search(r"sites\s*=\s*setOf\(", model) is not None,
    "sites must be assigned from an explicit setOf(...) of named members",
)
pinned_switches = kotlin_int(model, "stepIdSwitches")
check(
    "pinned stepIdSwitches equals the independently re-scanned count",
    pinned_switches == scan["stepIdSwitches"],
    f"pinned {pinned_switches} vs scanned {scan['stepIdSwitches']}",
)

total = (
    len(scan["concreteStepNames"]) + len(scan["bodyStepIds"]) + len(scan["sites"])
    + len(scan["blockBypasses"]) + scan["stepIdSwitches"]
)
ceiling = kotlin_int(model, "HISTORICAL_CEILING")
check("debts total is 18 as the receipt states", total == 18, f"{total}")
check("HISTORICAL_CEILING equals the measured total", ceiling == total, f"ceiling {ceiling} vs total {total}")
check("receipt states the same total", "total = 18 == HISTORICAL_CEILING" in receipt)

# ---- 2: the ceiling cannot be re-pinned (the sum is the ceiling) -------------
check(
    "the receipt forbids raising the ceiling",
    "never raised" in receipt.lower(),
)
check(
    "the model documents the ceiling as a high-water mark",
    "never raised" in model.lower() or "High-water mark" in model,
)

# ---- 3: every law has a fixture ---------------------------------------------
laws = {
    "new concrete name": ('"core.echo"', "ConcreteStepName"),
    "new dispatch*Block bypass": ("dispatchTimeoutBlock", "BlockDispatchBypass"),
    "second step-id switch": ("otherStepId", "StepIdSwitch"),
    "widened body step allowlist": ("core.echo", "BodyStepId"),
    "removed site without ledger": ("SiteInventoryDrift", "removedScope"),
    "raised ledger": ("LedgerRaisedBeyondCeiling", "LedgerRaisedBeyondCeiling"),
}
for law, (needle, expect) in laws.items():
    check(
        f"law has a fixture: {law}",
        needle in guard and expect in guard,
        f"expected {needle!r} and {expect!r} in the guard test",
    )
check(
    "the guard asserts the real source is within the pinned debt",
    "WithinPinnedDebt" in guard and "canonical durable coordinator carries only the pinned" in guard,
)
check(
    "the guard asserts the ledger carries no slack",
    "must not carry slack" in guard or "enumerates every discovered debt item" in guard,
)
check(
    "the model documents the coverage boundary",
    "does NOT see" in model and "over-report" in model,
)

# ---- 4: W1a touched no production file ---------------------------------------
changed = [ln for ln in git("diff", "--name-only", f"{BASE}..HEAD").splitlines() if ln.strip()]
check("W1a changed no production or build file",
      not any(re.search(r"/src/main/|\.gradle(\.kts)?$|gradle\.properties$", c) for c in changed),
      f"changed: {changed}")
check("W1a changed no pre-existing test",
      not any(c.endswith("Lfc2DurableCoordinatorScopeFitnessTest.kt") for c in changed),
      f"changed: {changed}")
check(
    "the two new guard files are the only code added",
    sorted(c for c in changed if c.endswith(".kt")) == sorted([MODEL, GUARD]),
    f"kotlin changes: {sorted(c for c in changed if c.endswith('.kt'))}",
)

# ---- 5: no semantic migration happened (the debt is still exactly as pinned) --
check(
    "the only dispatch*Block identifier is still the withCredentials bypass",
    scan["blockBypasses"] == {"dispatchWithCredentialsBlock"},
    f"{sorted(scan['blockBypasses'])}",
)
check(
    "the concrete routing sites are still the three pinned ones",
    scan["sites"] == {"CANONICAL_BODY_STEP_IDS", "PROJECT_SHELL_SCOPE", "DISPATCH_WITH_CREDENTIALS_BLOCK"},
    f"{sorted(scan['sites'])}",
)

# ---- 6: module suite totals, re-derived --------------------------------------
if SUITE_TAR.is_file():
    guard_rows = xml_total(SUITE_TAR, "Lfc2ConcreteBodyRoutingDebtFitnessTest")
    check(
        "the guard suite ran 11 tests with no failures",
        guard_rows == (11, 0, 0),
        f"{guard_rows}",
    )
    totals = []
    with tarfile.open(SUITE_TAR, "r:gz") as t:
        for m in t.getmembers():
            if not m.name.endswith(".xml"):
                continue
            text = t.extractfile(m).read().decode("utf-8", "replace")
            r = re.search(r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', text)
            if r:
                totals.append((m.name.split("/")[-1], r.groups()))
    n_tests = sum(int(x[1][0]) for x in totals)
    n_bad = sum(int(x[1][1]) + int(x[1][2]) for x in totals)
    check("the architecture suite is 252 tests / 1 failure", (n_tests, n_bad) == (252, 1), f"{n_tests}/{n_bad}")
    reds = [name for name, g in totals if int(g[1]) + int(g[2]) > 0]
    check(
        "the single red is the known Lfc0GlobalStateFitnessTest",
        reds == ["TEST-dev.rubentxu.pipeline.v2.architecture.Lfc0GlobalStateFitnessTest.xml"],
        f"{reds}",
    )
    if LANE_R_BASE_XML.is_file():
        base_msg = failure_message(LANE_R_BASE_XML.read_text(encoding="utf-8"))
        with tarfile.open(SUITE_TAR, "r:gz") as t:
            head_text = next(
                t.extractfile(m).read().decode("utf-8", "replace")
                for m in t.getmembers() if "Lfc0GlobalStateFitnessTest" in m.name
            )
        check(
            "the pre-existing failure text is identical to the Lane R base after normalisation",
            base_msg != "" and base_msg == failure_message(head_text),
            "failure messages differ",
        )
    check("the +11 are exactly the new guard", guard_rows == (11, 0, 0), f"guard rows {guard_rows}")
else:
    check("the architecture suite XML is archived", False, str(SUITE_TAR))

print()
if failures:
    print(f"RESULT: FAIL ({len(failures)}/{checks} checks failed)")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print(f"RESULT: PASS ({checks}/{checks} checks)")
