#!/usr/bin/env python3
"""Verify the B10/W1 G0 pre-flight claims (docs/v2/07-uat/B10_W1_PREFLIGHT.md).

This verifier guards a HISTORICAL claim: the state of the tree at the pre-flight
base commit. It therefore reads every source claim out of the base commit via
`git show` / `git grep`, never off the working tree. Otherwise it would expire the
moment the first W1 slice lands -- the defect that had to be corrected in the W0
verifier (see B10_W0_INNER_SEAM_RECEIPT.md).

Result truth for the baseline counts is the archived JUnit XML tarball plus a fresh
re-derivation, not the pre-flight prose.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import tarfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[4]
BASE = os.environ.get("W1_G0_BASE", "1afb4799")
# The pre-flight doc is committed ON TOP of the base it describes, so it is read
# from DOC (default HEAD), unlike the code claims which are read from BASE.
DOC = os.environ.get("W1_G0_DOC", "HEAD")
EV = REPO / "docs/v2/07-uat/evidence/b10-w1-g0"
TAR = Path(os.environ.get("W1_G0_TAR", EV / "raw/xml/g0-baseline-xml.tar.gz"))
CANARY = Path(os.environ.get("W1_G0_CANARY", EV / "raw/xml/g0-canary-xml.tar.gz"))
COORD = (
    "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/"
    "durable/CanonicalDurableRunCoordinator.kt"
)

failures: list[str] = []
checks = 0


def check(label: str, ok: bool, detail: str = "") -> None:
    global checks
    checks += 1
    status = "PASS" if ok else "FAIL"
    print(f"  [{status}] {label}" + (f" -- {detail}" if detail and not ok else ""))
    if not ok:
        failures.append(f"{label}: {detail}")


def git(*args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(REPO), *args], capture_output=True, text=True, check=False
    ).stdout


def base_file(path: str) -> str:
    return git("show", f"{BASE}:{path}")


def doc_file(path: str) -> str:
    return git("show", f"{DOC}:{path}")


def base_grep(pattern: str, pathspec: str = "v2") -> list[str]:
    # git grep exits 1 when there are no matches; that is a legitimate empty result.
    out = subprocess.run(
        ["git", "-C", str(REPO), "grep", "-l", "-E", pattern, BASE, "--", pathspec],
        capture_output=True,
        text=True,
        check=False,
    ).stdout
    return [ln for ln in out.splitlines() if ln.strip()]


def base_grep_lines(pattern: str, pathspec: str) -> list[str]:
    """Line-level git grep: returns `path:lineno:text` (never file names alone)."""
    out = subprocess.run(
        ["git", "-C", str(REPO), "grep", "-n", "-E", pattern, BASE, "--", pathspec],
        capture_output=True,
        text=True,
        check=False,
    ).stdout
    return [ln for ln in out.splitlines() if ln.strip()]


def xml_rows(name: str, tar: Path | None = None) -> list[tuple[str, str, str, str]]:
    """Yield (class-name, tests, failures, errors) for archived XML matching name."""
    rows = []
    with tarfile.open(tar or TAR, "r:gz") as t:
        for m in t.getmembers():
            if name not in m.name or not m.name.endswith(".xml"):
                continue
            text = t.extractfile(m).read().decode("utf-8", "replace")
            r = re.search(
                r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', text
            )
            if r:
                rows.append((m.name.split("/")[-1][5:-4], *r.groups()))
    return rows


def xml_timestamps(tar: Path) -> list[str]:
    out = []
    with tarfile.open(tar, "r:gz") as t:
        for m in t.getmembers():
            if not m.name.endswith(".xml"):
                continue
            text = t.extractfile(m).read().decode("utf-8", "replace")
            ts = re.search(r'<testsuite[^>]*timestamp="([^"]+)"', text)
            if ts:
                out.append(ts.group(1))
    return out


print(f"B10/W1 G0 pre-flight verifier -- base {BASE}")
print()

coord = base_file(COORD)
check("coordinator source is readable at base", bool(coord), COORD)

# ---- C1: the forbidden concrete routing is present and counted ---------------
lits = re.findall(r'"core\.[a-zA-Z]+"', coord)
distinct = sorted(set(lits))
check(
    "C1 coordinator contains 15 concrete core.* step literals",
    len(lits) == 15,
    f"found {len(lits)}",
)
check(
    "C1 those literals name 7 distinct block steps",
    len(distinct) == 7,
    f"found {len(distinct)}: {distinct}",
)

# ---- C2/C3/C4: the three structural sites ------------------------------------
check(
    "C2 projectShellScope routes on the concrete pluginStepId",
    "private fun BlockStepNode.projectShellScope" in coord
    and re.search(r"projectShellScope\(options: ShOptions\): BlockShellScope = when \(pluginStepId\.value\)", coord) is not None,
)
check(
    "C2 projectShellScope has 5 concrete routing arms plus else",
    all(
        f'"{n}" ->' in coord
        for n in ("core.dir", "core.timestamps", "core.withEnv", "core.timeout", "core.retry")
    )
    and "else -> BlockShellScope.None" in coord,
)
body_ids = re.search(r"canonicalBodyStepIds: Set<String> = setOf\(([^)]*)\)", coord)
check(
    "C3 canonicalBodyStepIds hardcodes 6 block step ids",
    body_ids is not None
    and len(re.findall(r'"core\.[a-zA-Z]+"', body_ids.group(1))) == 6,
    "set not found or wrong size",
)
check(
    "C4 dispatchBody special-cases core.withCredentials",
    'block.pluginStepId.value == "core.withCredentials"' in coord,
)

# ---- C5: the guard names the principle but enumerates two examples ----------
guard_path = base_grep(
    r"class Lfc2DurableCoordinatorScopeFitnessTest", "v2/pipeline-architecture-tests"
)
check("C5 the scope fitness guard exists", len(guard_path) >= 1, f"{guard_path}")
guard = base_file(guard_path[0].split(":", 1)[1]) if guard_path else ""
# The guard asserts on source TEXT, e.g. assertFalse(source.contains("\"core.sh\"")),
# so the tokens appear escaped in the file. Match the bare token, not a quoted literal.
check(
    "C5 guard's concrete-step assertions are core.sh and core.echo",
    all(
        f'assertFalse(source.contains("\\"{t}\\"' in guard
        for t in ("core.sh", "core.echo")
    ),
    "expected assertFalse(source.contains(\"...\")) rows for both tokens",
)
check(
    "C5 guard asserts isShellPlugin is not read by the durable protocol",
    re.search(r'assertFalse\(\s*source\.contains\(\s*"isShellPlugin"', guard) is not None,
)
block_names = ["core.dir", "core.timeout", "core.retry", "core.withCredentials",
               "core.timestamps", "core.withEnv"]
check(
    "C5 guard does NOT assert any of the six block steps",
    guard != "" and not any(b in guard for b in block_names),
    f"guard mentions {[b for b in block_names if b in guard]}",
)

# ---- C6: nothing else scans for the block machinery --------------------------
for token in ("projectShellScope", "dispatchBody", "dispatchWithCredentialsBlock", "BlockShellScope"):
    hits = base_grep(token, "v2/pipeline-architecture-tests")
    check(f"C6 no architecture test mentions {token}", hits == [], f"{hits}")

# ---- C7: the property W1 must preserve, and one it must also remove ----------
# AGENTS.md forbids a `dispatch*Block` COLLECTION. At base there is no collection,
# but there IS a single dispatchWithCredentialsBlock seam -- the shape the
# constitution names -- sitting beside dispatchBody. Assert both facts precisely.
ident_out = subprocess.run(
    ["git", "-C", str(REPO), "grep", "-o", "-h", "-E",
     r"\bdispatch[A-Za-z]*Block\b", BASE, "--", "v2/**/src/main/**/*.kt"],
    capture_output=True, text=True, check=False,
).stdout
idents = sorted({ln.strip() for ln in ident_out.splitlines() if ln.strip()})
check(
    "C7 no dispatch*Block collection exists in production at base",
    base_grep_lines(r"(val|var)\s+dispatch[A-Za-z]*Block\b", "v2/**/src/main/**/*.kt") == [],
)
check(
    "C7 the only dispatch*Block identifier at base is dispatchWithCredentialsBlock",
    idents == ["dispatchWithCredentialsBlock"],
    f"found {idents}",
)
check(
    "C7 dispatchWithCredentialsBlock is declared in the coordinator",
    len(base_grep_lines(r"private suspend fun dispatchWithCredentialsBlock\(", COORD)) == 1,
)

# ---- C8: baseline counts re-derived from archived XML ------------------------
expected = {
    "CanonicalDurableRunCoordinatorTest": ("26", "11", "0"),
    "CompatibilityCorpusTest": ("20", "2", "0"),
    "UatTimeoutBlockDurableTest": ("3", "0", "0"),
    "OpIdBodyPathTest": ("8", "0", "0"),
    "FileBasedRetryControlJournalTest": ("11", "0", "0"),
    "WindowCRetryRecoveryProductionWiringTest": ("1", "0", "0"),
    "Lfc2DurableCoordinatorScopeFitnessTest": ("4", "0", "0"),
}
for cls, want in expected.items():
    rows = xml_rows(cls)
    got = rows[0][1:] if rows else None
    check(f"C8 baseline {cls} = {want[0]}/{want[1]}/{want[2]}", got == want, f"got {got}")

check(
    "C8 tarball archives every pinned retry suite",
    all(xml_rows(c) for c in (
        "RetryAcceptanceMatrixTest", "RetryReconciliationDriverTest",
        "ProductionRetryChildRowReaderTest", "CliNonCanonicalInMemoryExitsTwoTest",
    )),
)

# ---- C9: the red baseline is pre-existing, per three closure receipts --------
receipts = {
    "docs/v2/07-uat/CTX_P_CLOSURE_RECEIPT.md": "12/26",
    "docs/v2/07-uat/E_EM_11_CLOSURE_RECEIPT.md": "12/26",
    "docs/v2/07-uat/LB02_A5_45_RECOVERY_AND_UNREACHABLE.md": "24/14",
}
for path, token in receipts.items():
    body = base_file(path)
    check(f"C9 {Path(path).name} records baseline {token}", token in body)

# ---- C10: today's count does not exceed the documented baseline --------------
rows = xml_rows("CanonicalDurableRunCoordinatorTest")
now = int(rows[0][2]) if rows else -1
check("C10 coordinator failures <= documented baseline of 12", 0 <= now <= 12, f"{now}")

# ---- C11: the pre-flight prose agrees with the XML (derive, not transcribe) --
doc = doc_file("docs/v2/07-uat/B10_W1_PREFLIGHT.md")
check("C11 pre-flight doc is committed", bool(doc), f"git show {DOC}:...")
check(
    "C11 doc states the re-derived coordinator baseline",
    re.search(rf"CanonicalDurableRunCoordinatorTest\s+{rows[0][1]}\s+{now}\s+0", doc) is not None
    if rows
    else False,
    "doc row does not match archived XML",
)
check(
    "C11 doc states the re-derived corpus baseline",
    re.search(r"CompatibilityCorpusTest\s+20\s+2\s+0", doc) is not None,
)
check("C11 doc states the 15-literal count", "15 concrete Step-name literals" in doc)
check(
    "C11 doc records the 11 pre-existing failures as characterised",
    "12/26" in doc and "24/14" in doc,
)

# ---- C12: the pre-flight declares implementation not started ----------------
check("C12 doc marks implementation as not started", "implementation NOT started" in doc)

# ---- C13: the canary run regenerated the same baseline ----------------------
# The pinned XMLs were DELETED before the canary run (deleting a task output
# invalidates Gradle's up-to-date check, so the canary is also what forced
# re-execution). Two independent universes: the archived run and the canary.
check("C13 canary archive exists", CANARY.is_file(), str(CANARY))
if CANARY.is_file():
    for cls, want in expected.items():
        rows = xml_rows(cls, CANARY)
        got = rows[0][1:] if rows else None
        check(f"C13 canary {cls} reproduces {want[0]}/{want[1]}/{want[2]}", got == want, f"got {got}")
    base_ts, can_ts = xml_timestamps(TAR), xml_timestamps(CANARY)
    check(
        "C13 canary timestamps are strictly newer than the archived run",
        bool(base_ts) and bool(can_ts) and min(can_ts) > max(base_ts),
        f"base max {max(base_ts) if base_ts else '-'} vs canary min {min(can_ts) if can_ts else '-'}",
    )
    check(
        "C13 archived and canary archives cover the same classes",
        sorted(r[0] for r in xml_rows("Test", TAR)) == sorted(r[0] for r in xml_rows("Test", CANARY)),
    )

    # And the reverse direction: the canary must reproduce the pinned classes that
    # are NOT the baseline subject, i.e. the green suites stay green.
    for cls in ("UatTimeoutBlockDurableTest", "OpIdBodyPathTest",
                "FileBasedRetryControlJournalTest", "Lfc2DurableCoordinatorScopeFitnessTest"):
        check(f"C13 canary keeps {cls} green",
              xml_rows(cls, CANARY) and xml_rows(cls, CANARY)[0][2:] == ("0", "0"))

print()
if failures:
    print(f"RESULT: FAIL ({len(failures)}/{checks} checks failed)")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print(f"RESULT: PASS ({checks}/{checks} checks)")
