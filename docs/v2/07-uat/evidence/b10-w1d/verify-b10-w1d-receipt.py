#!/usr/bin/env python3
"""Verify the B10/W1d receipt (docs/v2/07-uat/B10_W1D_BODY_INVOKER_SHARED_PATH_RECEIPT.md).

HISTORICAL, not working tree. Every code claim is read from a git commit via `git show`
(`W1D_CODE`, default the commit that introduced the receipt), so the verifier keeps
asserting what W1d shipped after later slices edit these files.

INDEPENDENT, not transcribed. The body declaration model, the descriptor rows, the shared
body-child loop inventory, the durable aggregate identities and the coordinator's
remaining concrete routing debt are all RE-DERIVED in Python from the Kotlin sources and
compared against expectation tables stated HERE. Nothing is read back from the pinned
ledger or from the Kotlin guard tests, so a pin edited to match a drifted source is caught
by the re-scan (controls K3, K10) and a guard weakened in the same commit as the code it
guards is caught by the re-derivation.

DIFFERENTIAL. W1d claims (a) the credential body path is preserved and (b) release now
runs on the exception path. Both are checked against the OLD coordinator read at
`W1D_BASE` (the slice parent), not against a restatement of the new code: the old shape
must have `close()` inside the `try` with an empty `finally`, the new shape must have it
inside `finally` (control K7), and the env overlay + child ShOptions construction must be
the same expression in both.

ANTI-DELETION. Reaching a zero routing ledger by deleting the two durable identities is
the false green W1d was chartered to prevent, so the identities are asserted to still
exist, to still carry their replay keys, and to be pinned by a separate guard (K4, K8).

Usage:
  python3 verify-b10-w1d-receipt.py                    # static + evidence checks
  W1D_CODE=WORKTREE python3 verify-b10-w1d-receipt.py  # read the working tree (controls)
  python3 verify-b10-w1d-receipt.py --controls         # run the negative controls

Env: W1D_CODE, W1D_BASE, W1D_XML, W1D_BASE_XML.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tarfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[4]

# The slice parent: `main` when W1d was cut. The differential checks read the pre-W1d
# coordinator from this commit.
BASE = os.environ.get("W1D_BASE", "45b26c49")
RECEIPT = "docs/v2/07-uat/B10_W1D_BODY_INVOKER_SHARED_PATH_RECEIPT.md"


def _resolve_slice() -> str:
    """Resolved, never hard-coded: a constant would have to be edited (and re-committed)
    every time the slice commit is amended. Still historical: later slices edit other
    files and cannot move this anchor."""
    override = os.environ.get("W1D_SLICE")
    if override:
        return override
    if "--controls" in sys.argv:
        # Controls mutate and read the working tree by definition; there is no anchor yet.
        return "WORKTREE"
    if (os.environ.get("W1D_CODE") or "").upper() == "WORKTREE":
        return os.environ.get("W1D_CODE")
    proc = subprocess.run(
        ["git", "-C", str(REPO), "log", "-1", "--format=%H", "--", RECEIPT],
        capture_output=True, text=True, check=False,
    )
    sha = proc.stdout.strip()
    if not sha:
        raise SystemExit(f"cannot resolve the W1d slice commit from {RECEIPT}")
    return sha


SLICE_SHA = _resolve_slice()
CODE = os.environ.get("W1D_CODE") or SLICE_SHA
WORKTREE = CODE.upper() == "WORKTREE"

DOMAIN = "v2/pipeline-domain"
ARCH = "v2/pipeline-architecture-tests"
APP = "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application"

COORD = f"{APP}/durable/CanonicalDurableRunCoordinator.kt"
STEP_BODY = f"{DOMAIN}/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/StepBody.kt"
DESCRIPTOR = f"{DOMAIN}/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/StepDescriptor.kt"
DESC_REGISTRY = f"{DOMAIN}/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/StepDescriptorRegistry.kt"
AGGREGATE = f"{DOMAIN}/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyAggregateIdentity.kt"
POLICY = f"{DOMAIN}/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyExecutionPolicy.kt"
VALIDATOR = f"{DOMAIN}/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/CompiledPipelineValidator.kt"
LEDGER = f"{ARCH}/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/ConcreteBodyRoutingDebt.kt"
GUARD = f"{ARCH}/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/Lfc2ConcreteBodyRoutingDebtFitnessTest.kt"
FITNESS = f"{ARCH}/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/Lfc2BodyExecutionPolicyFitnessTest.kt"
IDENTITY_FITNESS = f"{ARCH}/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/Lfc2DurableAggregateIdentityFitnessTest.kt"
DOMAIN_TEST = f"{DOMAIN}/src/test/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyExecutionPolicyTest.kt"
EVIDENCE_PREFIX = "docs/v2/07-uat/evidence/b10-w1d/"
TESTING_STATE = ".agent/TESTING-STATE.md"

XML = Path(os.environ.get("W1D_XML", HERE / "raw/xml/module-suites-xml.tar.gz"))
BASE_XML = Path(os.environ.get("W1D_BASE_XML", HERE / "raw/xml/base-module-suites-xml.tar.gz"))

# ===== expectations, stated here, independent of the implementation =====

# Every body-bearing descriptor row: (owner, policy, invocation, introduced kind).
# W1d makes all four explicit; none of them may be defaulted.
EXPECTED_BODY_ROWS: dict[str, tuple[str, str, str, str]] = {
    "core.catchError": ("LEGACY_LINEAR", "Sequential", "ONCE", "CANCELLATION"),
    "core.warnError": ("LEGACY_LINEAR", "Sequential", "AT_MOST_ONCE", "OUTPUT_DECORATOR"),
    "core.withEnv": ("CANONICAL_ENGINE", "Scoped(Environment)", "ONCE", "ENVIRONMENT"),
    "core.dir": ("CANONICAL_ENGINE", "Scoped(WorkingDirectory)", "ONCE", "CWD"),
    "core.withCredentials": ("CANONICAL_ENGINE", "Scoped(CredentialLease)", "ONCE", "CREDENTIALS"),
    "core.timeout": ("CANONICAL_ENGINE", "Scoped(Deadline)", "ONCE", "CANCELLATION"),
    "core.timestamps": ("CANONICAL_ENGINE", "Scoped(Timestamps)", "ONCE", "null"),
    "core.retry": ("CANONICAL_ENGINE", "Retrying", "ZERO_OR_MORE", "null"),
}
EXPECTED_TERMINAL_ROWS = {
    "core.emit.event", "core.sh", "core.echo", "core.sleep", "core.file.writeFile",
}
EXPECTED_REGISTRY_SIZE = 13

# The canonical body set the durable engine may execute (production routing, pinned here
# independently of the registry tests).
EXPECTED_CANONICAL_BODY_SET = {
    "core.dir", "core.timestamps", "core.withEnv", "core.timeout",
    "core.withCredentials", "core.retry",
}
EXPECTED_LEGACY_BODY_SET = {"core.catchError", "core.warnError"}

# The durable aggregate identities: key -> durable role enum case, with the ADR that owns
# the identity. Renaming a key is replay-breaking, deleting a case is the false green.
EXPECTED_AGGREGATE_IDS = {
    "core.retry": ("RetryControlRow", "RETRY_CONTROL_ROW", "ADR-0075"),
    "core.parallel": ("ParallelStageAggregate", "PARALLEL_STAGE_AGGREGATE", "ADR-0076"),
}

# The coordinator's remaining concrete routing debt after W1d: none. Every item is
# undeclared, so the first one to come back fails.
EXPECTED_CONCRETE_STEP_NAMES: set[str] = set()
EXPECTED_BODY_STEP_IDS: set[str] = set()
EXPECTED_SITES: set[str] = set()
EXPECTED_BLOCK_BYPASSES: set[str] = set()
EXPECTED_STEP_ID_SWITCHES = 0
EXPECTED_TOTAL = 0
HISTORICAL_CEILING = 18

# The shared body path: one loop definition, one credential acquisition site.
EXPECTED_LOOP_DEFINITIONS = 1
EXPECTED_CREDENTIAL_ACQUISITIONS = 1

# The six body fields W1d removed. `StepDescriptor` must not declare any of them, and
# `body` must be the single body value.
REMOVED_BODY_FIELDS = ("takesBody", "bodyInvocations", "introducesContext",
                       "catchesInterruptions", "bodyExecutionPolicy", "bodyExecutionOwner")

# The two functions W1d removed from the coordinator (present at BASE, absent at CODE).
REMOVED_FUNCTIONS = ("dispatchWithCredentialsBlock", "dispatchAcquiredWithCredentialsBody")

# Suites re-run for this slice. Measured, stated here rather than read back from the
# archive: an evidence archive that agrees with itself proves nothing.
EXPECTED_COORDINATOR_BASELINE = ("CanonicalDurableRunCoordinatorTest", 26, 11)
EXPECTED_COORDINATOR_HEAD = ("CanonicalDurableRunCoordinatorTest", 26, 10)
# The one base failure W1d repairs: the lease is released on the child-dispatch exception
# path now, so the scope is closed (the test's failing assertion was `closeCount == 1`).
EXPECTED_REPAIRED_COORDINATOR_TEST = "withCredentials cleanup failure folds a successful body to failure()"
EXPECTED_GUARD_CLASSES = {
    "dev.rubentxu.pipeline.v2.architecture.Lfc2ConcreteBodyRoutingDebtFitnessTest": 6,
    "dev.rubentxu.pipeline.v2.architecture.Lfc2ConcreteBodyRoutingDebtFitnessTest$ViolationFixture": 10,
    "dev.rubentxu.pipeline.v2.architecture.Lfc2BodyExecutionPolicyFitnessTest": 10,
    "dev.rubentxu.pipeline.v2.architecture.Lfc2DurableAggregateIdentityFitnessTest": 5,
}

# Per-module (tests, failures) at head, and the delta this slice INTENDS against the slice
# parent. Every module not listed must be identical between base and head.
EXPECTED_HEAD_MODULE_TOTALS = {
    "pipeline-domain": (397, 0),
    "pipeline-architecture-tests": (272, 1),
    "pipeline-application": (1392, 35),
}
EXPECTED_MODULE_DELTAS = {
    "pipeline-domain": (2, 0),
    "pipeline-architecture-tests": (10, 0),
    "pipeline-application": (0, -1),
}

HEAD_INVENTORY = HERE / "raw/head-inventory.json"
BASE_INVENTORY = HERE / "raw/base-inventory.json"

failures: list[str] = []
checks = 0


def check(label: str, ok: bool, detail: str = "") -> None:
    global checks
    checks += 1
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}" + (f" -- {detail}" if detail and not ok else ""))
    if not ok:
        failures.append(label)


def git(*args: str) -> str:
    return subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, text=True, check=False).stdout


def show(path: str, ref: str | None = None) -> str:
    if WORKTREE and ref is None:
        p = REPO / path
        return p.read_text(encoding="utf-8") if p.is_file() else ""
    return git("show", f"{ref or CODE}:{path}")


def code_only(text: str) -> str:
    """Comment-blind view: prose may name steps and old fields, code may not."""
    return "\n".join(
        ln for ln in text.splitlines()
        if not ln.lstrip().startswith("//") and not ln.lstrip().startswith("*")
    )


# ===== independent re-derivations =====

CORE_LITERAL = re.compile(r'"(?:core|example)\.[A-Za-z0-9_.]+"')
STEP_KEY_SWITCH = re.compile(r"when\s*\(\s*[A-Za-z0-9_.]*[Ss]tepId[A-Za-z0-9_.]*\s*\)")
BLOCK_DISPATCH = re.compile(r"\bdispatch[A-Za-z0-9_]*Block\b")
BODY_IDS_ALLOWLIST = re.compile(r"canonicalBodyStepIds\s*:\s*Set<String>\s*=\s*setOf\(([^)]*)\)")
BODY_CHILD_LOOP = re.compile(r"\.body\.withIndex\(\)")
CREDENTIAL_ACQUIRE = re.compile(r"credentialScopePort\.acquire\(")
PUT_ROW = re.compile(r'put\(PluginStepId\("([^"]+)"\),\s*StepDescriptor\((.*?)\n\s*\)\)', re.S)


def parse_policy(expr: str) -> str:
    expr = expr.strip()
    if expr.startswith("BodyExecutionPolicy.Sequential"):
        return "Sequential"
    m = re.match(r"BodyExecutionPolicy\.Scoped\(\s*BodyContextProjection\.(\w+)\s*\)", expr)
    if m:
        return f"Scoped({m.group(1)})"
    if expr.startswith("BodyExecutionPolicy.Retrying"):
        return "Retrying"
    if expr.startswith("BodyExecutionPolicy.Parallel"):
        return "Parallel"
    return f"UNPARSED({expr})"


def parse_registry(source: str) -> dict[str, dict]:
    """key -> {terminal, owner, policy, invocation, introduces}, re-derived from Kotlin.

    W1d: a row either declares `body = StepBody.Declared(...)` (owner/policy/invocation
    parsed from it) or it is terminal (`body = StepBody.None`). There is no third shape,
    and no owner default to fall back on.
    """
    rows: dict[str, dict] = {}
    for key, body in PUT_ROW.findall(source):
        def field(pattern: str, default: str = "") -> str:
            m = re.search(pattern, body)
            return m.group(1) if m else default

        declared = "body = StepBody.Declared(" in body
        rows[key] = {
            "terminal": not declared,
            "owner": field(r"owner\s*=\s*BodyExecutionOwner\.(\w+)") if declared else "",
            "policy": parse_policy(field(r"policy\s*=\s*([^\n,]+)")) if declared else "",
            "invocation": field(r"invocation\s*=\s*BodyInvocationPolicy\.(\w+)") if declared else "",
            "introduces": (
                "null" if not re.search(r"\bintroduces\s*=", body)
                else field(r"introduces\s*=\s*ContextKind\.(\w+)", "null") if "introduces = null" not in body
                else "null"
            ) if declared else "",
            "declares_owner": bool(re.search(r"owner\s*=\s*BodyExecutionOwner\.\w+", body)),
        }
    return rows


def derived_body_set(rows: dict[str, dict], owner: str) -> set[str]:
    return {k for k, r in rows.items() if not r["terminal"] and r["owner"] == owner}


def rescan_debt(source: str) -> dict:
    """Python implementation of the W1a scanner rules, over the coordinator source."""
    code = code_only(source)
    block = BODY_IDS_ALLOWLIST.search(code)
    body_ids = {m.group(0).strip('"') for m in CORE_LITERAL.finditer(block.group(1))} if block else set()
    sites: set[str] = set()
    if block:
        sites.add("CANONICAL_BODY_STEP_IDS")
    if re.search(r"projectShellScope\s*\(", code):
        sites.add("PROJECT_SHELL_SCOPE")
    loops = len(BODY_CHILD_LOOP.findall(code))
    acquisitions = len(CREDENTIAL_ACQUIRE.findall(code))
    if loops != EXPECTED_LOOP_DEFINITIONS or acquisitions != EXPECTED_CREDENTIAL_ACQUISITIONS:
        # The body path is not shared: the W1a-era credential bypass had exactly this shape.
        sites.add("DISPATCH_WITH_CREDENTIALS_BLOCK")
    names = {m.group(0).strip('"') for m in CORE_LITERAL.finditer(code)}
    bypasses = {m.group(0) for m in BLOCK_DISPATCH.finditer(code)}
    switches = len(STEP_KEY_SWITCH.findall(code))
    return {
        "concreteStepNames": names,
        "bodyStepIds": body_ids,
        "sites": sites,
        "blockBypasses": bypasses,
        "stepIdSwitches": switches,
        "loopDefinitions": loops,
        "credentialAcquisitions": acquisitions,
        "total": len(names) + len(body_ids) + len(sites) + len(bypasses) + switches,
    }


def parse_pinned_ledger(text: str) -> dict:
    """The pinned ledger's OWN claims, re-parsed from the ledger source."""
    def set_block(field: str, pattern: re.Pattern[str] = CORE_LITERAL) -> set[str]:
        block = re.search(rf"{field} = setOf\((.*?)\)[,)]", text, re.S)
        return {m.group(0).strip('"') for m in pattern.finditer(block.group(1))} if block else set()

    sites_block = re.search(r"sites = setOf\((.*?)\)[,)]", text, re.S)
    switch = re.search(r"stepIdSwitches = (\d+),", text)
    ceiling = re.search(r"HISTORICAL_CEILING\s*=\s*(\d+)", text)
    return {
        "concreteStepNames": set_block("concreteStepNames"),
        "bodyStepIds": set() if "bodyStepIds = emptySet()," in text else set_block("bodyStepIds"),
        "sites": {m.group(1) for m in re.finditer(r"BodyRoutingSite\.(\w+)", sites_block.group(1))}
        if sites_block else set(),
        "blockBypasses": set_block("blockBypasses", re.compile(r'"([^"]+)"')),
        "stepIdSwitches": int(switch.group(1)) if switch else -1,
        "ceiling": int(ceiling.group(1)) if ceiling else -1,
    }


def parse_aggregate_ids(source: str) -> dict[str, dict]:
    """key -> {case, role, authority} re-derived from the identity model."""
    ids: dict[str, dict] = {}
    for match in re.finditer(r"data object (\w+)\s*:\s*BodyAggregateIdentity\s*\{(.*?)\n    \}", source, re.S):
        case, body = match.group(1), match.group(2)
        key = re.search(r'PluginStepId\("([^"]+)"\)', body)
        role = re.search(r"AggregateDurableRole\.(\w+)", body)
        ids[key.group(1) if key else f"UNKEYED({case})"] = {
            "case": case,
            "role": role.group(1) if role else "",
        }
    for match in re.finditer(r'([A-Z_]+)\("(ADR-\d+)"\)', source):
        for row in ids.values():
            if row["role"] == match.group(1):
                row["authority"] = match.group(2)
    return ids


def parse_all_list(source: str) -> set[str]:
    block = re.search(r"val ALL: List<BodyAggregateIdentity> = listOf\((.*?)\)", source, re.S)
    return {m.group(1) for m in re.finditer(r"(\w+)", block.group(1))} if block else set()


def function_body(source: str, name: str) -> str:
    """The text of one function, from its signature to the brace that closes it.

    Brace-counted, not indentation-guessed: an inner `when` closes at its own indent and a
    first-`}`-wins heuristic would silently return half a function (which is how a check
    that looks structural ends up asserting nothing).
    """
    m = re.search(rf"\n\s*(?:private\s+)?(?:suspend\s+)?fun\s+(?:[A-Za-z0-9_.<>]+\.)?{re.escape(name)}\(", source)
    if not m:
        return ""
    text = source[m.start():]
    depth = 0
    started = False
    for i, ch in enumerate(text):
        if ch == "{":
            depth += 1
            started = True
        elif ch == "}":
            depth -= 1
            if started and depth == 0:
                # `} catch (...) {` / `} else {` / `},` continue the same function.
                tail = text[i + 1:].split("\n", 1)[0]
                if re.match(r"\s*(catch\b|else\b|finally\b|while\b|[.,)])", tail):
                    continue
                return text[: i + 1]
    return text


def tar_classes(tar: Path) -> dict[str, dict]:
    """{class -> {tests, failures, errors, failing, module}} from an archived XML tree."""
    import xml.etree.ElementTree as ET
    inventory: dict[str, dict] = {}
    if not tar.is_file():
        return inventory
    with tarfile.open(tar) as tf:
        for member in tf.getmembers():
            if not member.isfile() or not member.name.endswith(".xml"):
                continue
            module = re.sub(r"^\./", "", member.name).split("/build/")[0]
            root = ET.fromstring(tf.extractfile(member).read().decode("utf-8"))
            failing = sorted(
                tc.get("name") for tc in root.iter("testcase")
                if tc.find("failure") is not None or tc.find("error") is not None
            )
            inventory[root.get("name")] = {
                "module": module,
                "tests": int(root.get("tests")),
                "failures": int(root.get("failures")),
                "errors": int(root.get("errors")),
                "failing": failing,
            }
    return inventory


def load_json(path: Path) -> dict:
    return json.loads(path.read_text()) if path.is_file() else {}


# ===== checks =====

def check_body_declaration() -> None:
    print("\n[1] the body declaration is one coherent value with no defaults")
    body = show(STEP_BODY)
    code = code_only(body)
    check("StepBody is a sealed interface with a None case and a Declared case",
          "sealed interface StepBody" in code
          and re.search(r"data object None\s*:\s*StepBody", code) is not None
          and re.search(r"data class Declared\(", code) is not None)
    check("StepBody.None is a bare marker that carries no body metadata at all",
          re.search(r"^\s*data object None\s*:\s*StepBody\s*$", code, re.M) is not None)
    check("the execution owner and shape are required, never defaulted",
          re.search(r"val execution:\s*BodyExecution\s*,", code) is not None
          and re.search(r"val invocation:\s*BodyInvocationPolicy\s*,", code) is not None
          and re.search(r"val owner:\s*BodyExecutionOwner\s*,", code) is not None
          and re.search(r"val policy:\s*BodyExecutionPolicy\s*,", code) is not None)
    check("declared exposes the absent case as null, so callers branch on the ADT",
          re.search(r"val declared:\s*Declared\?", code) is not None)

    descriptor = code_only(show(DESCRIPTOR))
    check("StepDescriptor carries exactly one body value",
          re.search(r"val body:\s*StepBody\s*=\s*StepBody\.None", descriptor) is not None)
    leftovers = [f for f in REMOVED_BODY_FIELDS if re.search(rf"val {f}\b", descriptor)]
    check("StepDescriptor declares none of the six removed body fields",
          not leftovers, f"still declared: {leftovers}")


def check_registry_rows() -> None:
    print("\n[2] every registered body row states its owner, shape and cardinality")
    rows = parse_registry(show(DESC_REGISTRY))
    check(f"the core registry declares {EXPECTED_REGISTRY_SIZE} rows", len(rows) == EXPECTED_REGISTRY_SIZE,
          f"found {len(rows)}: {sorted(rows)}")
    body_rows = {k: r for k, r in rows.items() if not r["terminal"]}
    terminal_rows = {k: r for k, r in rows.items() if r["terminal"]}
    check("the body rows are exactly the eight declared families",
          set(body_rows) == set(EXPECTED_BODY_ROWS),
          f"found {sorted(body_rows)}")
    check("the terminal rows are exactly the five declared families",
          set(terminal_rows) == EXPECTED_TERMINAL_ROWS, f"found {sorted(terminal_rows)}")
    check("EVERY body row declares an owner explicitly",
          all(r["declares_owner"] for r in body_rows.values()),
          f"rows without an owner: {[k for k, r in body_rows.items() if not r['declares_owner']]}")
    for key, expected in EXPECTED_BODY_ROWS.items():
        got = body_rows.get(key)
        actual = (got["owner"], got["policy"], got["invocation"], got["introduces"]) if got else ("MISSING",) * 4
        check(f"{key} declares {expected}", actual == expected, f"got {actual}")
    check("the derived canonical body set is production routing, pinned here",
          derived_body_set(rows, "CANONICAL_ENGINE") == EXPECTED_CANONICAL_BODY_SET,
          f"got {sorted(derived_body_set(rows, 'CANONICAL_ENGINE'))}")
    check("containment families declare legacy ownership",
          derived_body_set(rows, "LEGACY_LINEAR") == EXPECTED_LEGACY_BODY_SET,
          f"got {sorted(derived_body_set(rows, 'LEGACY_LINEAR'))}")

    owner_doc = show(POLICY)
    check("BodyExecutionOwner no longer advertises a default",
          "This is the default because it is the target state" not in owner_doc)


def check_coordinator() -> None:
    print("\n[3] one shared body path, and no concrete routing left")
    source = show(COORD)
    debt = rescan_debt(source)
    check("the coordinator has no concrete Step literal",
          debt["concreteStepNames"] == EXPECTED_CONCRETE_STEP_NAMES, f"got {sorted(debt['concreteStepNames'])}")
    check("the coordinator has no hard-coded body id allowlist",
          debt["bodyStepIds"] == EXPECTED_BODY_STEP_IDS, f"got {sorted(debt['bodyStepIds'])}")
    check("no routing site remains", debt["sites"] == EXPECTED_SITES, f"got {sorted(debt['sites'])}")
    check("no dispatch*Block identifier remains",
          debt["blockBypasses"] == EXPECTED_BLOCK_BYPASSES, f"got {sorted(debt['blockBypasses'])}")
    check("no step-id switch remains", debt["stepIdSwitches"] == EXPECTED_STEP_ID_SWITCHES,
          f"got {debt['stepIdSwitches']}")
    check("the re-derived debt total is zero", debt["total"] == EXPECTED_TOTAL, f"got {debt['total']}")
    check("exactly one body-child loop defines the shared path",
          debt["loopDefinitions"] == EXPECTED_LOOP_DEFINITIONS, f"got {debt['loopDefinitions']}")
    check("exactly one site acquires a credential lease",
          debt["credentialAcquisitions"] == EXPECTED_CREDENTIAL_ACQUISITIONS,
          f"got {debt['credentialAcquisitions']}")

    for name in REMOVED_FUNCTIONS:
        check(f"{name} is gone", function_body(source, name) == "")
        check(f"{name} existed at the slice parent", function_body(show(COORD, BASE), name) != "")

    loop = function_body(source, "invokeBodyChildren")
    check("the shared loop iterates the body in declaration order",
          ".body.withIndex()" in loop)
    check("the shared loop stops on the first Failure or Unstable",
          re.search(r"is StepOutcome\.Failure,\s*is StepOutcome\.Unstable\s*->\s*return childOutcome", loop) is not None)
    check("the shared loop returns Success when every child passed",
          "return StepOutcome.Success" in loop)

    lease = function_body(source, "executeCredentialLeasedBody")
    check("the credential lease re-enters the shared loop",
          "invokeBodyChildren(" in lease)
    check("the lease is released in a finally, so cleanup runs on every path",
          re.search(r"\}\s*finally\s*\{\s*\n\s*cleanup\s*=\s*leased\.close\(\)", lease) is not None)
    check("acquisition is typed: Unavailable and Invalid are returned, never thrown",
          "is CredentialScopeOutcome.Unavailable -> return StepOutcome.Failure(" in lease
          and "is CredentialScopeOutcome.Invalid -> return StepOutcome.Failure(" in lease)
    check("the body outcome is produced BEFORE the fold",
          lease.index("invokeBodyChildren(") < lease.index("mergeBodyAndCleanup("))
    check("both typed outcomes are folded by the pure function",
          "mergeBodyAndCleanup(bodyOutcome, cleanup)" in lease)

    merge = function_body(source, "mergeBodyAndCleanup")
    check("the fold is total over the cleanup ADT and has no else branch",
          "when (cleanup)" in merge and "else" not in merge
          and "CredentialScopeCleanup.Cleaned" in merge
          and "CredentialScopeCleanup.Failed" in merge)

    projection = function_body(source, "decodeCredentialBindings")
    check("the bindings are decoded once, in the pure projection",
          projection.count("CredentialBindingsPayload.decode") == 1)
    check("a malformed payload becomes a typed InvalidInput, never a throw",
          re.search(r"catch \(\w+: IllegalArgumentException\)", projection) is not None
          and "BodyExecutionProjection.InvalidInput(" in projection)
    check("the projection performs no effect of its own",
          all(call not in projection for call in
              ("credentialScopePort.acquire(", "invokeBodyChildren(", ".close()")))
    check("the projection no longer carries a bare credential marker",
          "CredentialLifecycle" not in source)
    check("the credential projection carries the decoded bindings",
          "data class CredentialLease(" in source)

    base_lease = function_body(show(COORD, BASE), "dispatchAcquiredWithCredentialsBody")
    base_finally = base_lease[base_lease.index("} finally {"):] if "} finally {" in base_lease else ""
    check("the slice parent closed the lease INSIDE the try, so a thrown child leaked it",
          "scope.close()" in base_lease
          and base_lease.index("scope.close()") < base_lease.index("} finally {")
          and "close()" not in base_finally)
    check("the slice closes the lease inside the finally instead",
          lease.index("} finally {") < lease.index("cleanup = leased.close()")
          and "leased.close()" not in lease[:lease.index("} finally {")])

    check("the identical env overlay expression is preserved",
          "executionContext.pushed(" in base_lease
          and "executionContext.pushed(" in lease
          and "stageShOptions.copy(env = stageShOptions.env +" in base_lease
          and "stageShOptions.copy(env = stageShOptions.env +" in lease)
    check("the removed dead local was never read at the slice parent",
          re.search(r"val childContext = StepLifecycleContext\(", show(COORD, BASE)) is not None
          and "val childContext = StepLifecycleContext(" not in source)


def check_ledger() -> None:
    print("\n[4] the pinned ledger is empty and the ceiling did not move")
    text = show(LEDGER)
    pinned = parse_pinned_ledger(text)
    check("the pinned ledger enumerates no concrete Step name",
          pinned["concreteStepNames"] == EXPECTED_CONCRETE_STEP_NAMES, f"got {sorted(pinned['concreteStepNames'])}")
    check("the pinned ledger enumerates no body Step id",
          pinned["bodyStepIds"] == EXPECTED_BODY_STEP_IDS, f"got {sorted(pinned['bodyStepIds'])}")
    check("the pinned ledger enumerates no routing site",
          pinned["sites"] == EXPECTED_SITES, f"got {sorted(pinned['sites'])}")
    check("the pinned ledger enumerates no dispatch bypass",
          pinned["blockBypasses"] == EXPECTED_BLOCK_BYPASSES, f"got {sorted(pinned['blockBypasses'])}")
    check("the pinned step-id switch count is zero",
          pinned["stepIdSwitches"] == EXPECTED_STEP_ID_SWITCHES, f"got {pinned['stepIdSwitches']}")
    check(f"HISTORICAL_CEILING is still {HISTORICAL_CEILING}, not re-pinned",
          pinned["ceiling"] == HISTORICAL_CEILING, f"got {pinned['ceiling']}")
    check("the ledger is a measurement, not a relaxation: it declares empty sets explicitly",
          "concreteStepNames = emptySet()," in text and "sites = emptySet()," in text)
    check("the body-child loop inventory is declared with its expected value",
          "val EXPECTED = BodyChildLoopInventory(loopDefinitions = 1, credentialAcquisitions = 1)" in text)
    check("the verdict requires the loop inventory, so it cannot be skipped by omission",
          re.search(r"fun decide\(\s*\n\s*discovered: ConcreteBodyRoutingDebt,\s*\n\s*discoveredLoops: BodyChildLoopInventory,", text) is not None)
    check("the guard scans the real coordinator and pins the literal zero",
          "ConcreteBodyRoutingScanner.scan(coordinatorText())" in show(GUARD)
          and "0," in show(GUARD))
    check("the guard's negative controls cover a duplicated body path without any literal",
          "duplicating the body child loop is rejected even without any literal" in show(GUARD))
    check("the guard's negative controls cover a second lease acquisition",
          "a second credential acquisition site is rejected" in show(GUARD))
    check("the guard's negative controls cover a re-introduced allowlist",
          "re-introducing a hard-coded body allowlist is rejected" in show(GUARD))
    check("the policy fitness re-scans the coordinator and asserts zero",
          "W1d lowers the pinned concrete routing debt to zero" in show(FITNESS)
          and "BodyChildLoopInventory.EXPECTED" in show(FITNESS))


def check_aggregate_identities() -> None:
    print("\n[5] the two durable identities are pinned, not deleted")
    source = show(AGGREGATE)
    ids = parse_aggregate_ids(source)
    check("exactly the two declared durable aggregate identities exist",
          set(ids) == set(EXPECTED_AGGREGATE_IDS), f"got {sorted(ids)}")
    for key, (case, role, authority) in EXPECTED_AGGREGATE_IDS.items():
        got = ids.get(key, {})
        check(f"{key} is the {case} identity with role {role}",
              got.get("case") == case and got.get("role") == role, f"got {got}")
        check(f"{key} cites {authority}", got.get("authority") == authority, f"got {got.get('authority')}")
    check("the pinned ALL list contains both identities",
          parse_all_list(source) == {case for case, _, _ in EXPECTED_AGGREGATE_IDS.values()},
          f"got {sorted(parse_all_list(source))}")
    check("the identity model states the keys are a replay contract, not a routing branch",
          "durable KEYS" in source and "ReplayPolicy" not in source)
    check("the parallel fingerprint key is built in the identity model, not in the coordinator",
          "fun fingerprintKey(" in source
          and "fingerprintKey(stageIndex" in show(COORD))
    coordinator = code_only(show(COORD))
    check("the coordinator reaches both identities by type",
          all(f"BodyAggregateIdentity.{case}" in coordinator for case, _, _ in EXPECTED_AGGREGATE_IDS.values()))
    check("the coordinator never spells an aggregate key literal",
          all(f'"{key}"' not in coordinator for key in EXPECTED_AGGREGATE_IDS))
    check("the identity fitness guards the anti-deletion law",
          "the two durable aggregate identities are pinned, not deleted" in show(IDENTITY_FITNESS)
          and "declared exactly once" in show(IDENTITY_FITNESS))


def check_docs() -> None:
    print("\n[6] the receipt and the state file describe what shipped")
    receipt = REPO / RECEIPT
    text = receipt.read_text() if receipt.is_file() else ""
    if WORKTREE:
        check("the receipt exists", bool(text))
    else:
        check("the receipt is committed in the slice", bool(git("show", f"{CODE}:{RECEIPT}")))
    check("the receipt records the immutable ceiling", "18" in text and "INMUTABLE" in text)
    check("the receipt records the reclassification, not a deletion",
          "reclassif" in text.lower() or "RECLASSIF" in text)
    check("the receipt states the W1d exit criteria", "exit criteria" in text.lower())
    state = REPO / TESTING_STATE
    state_text = state.read_text() if state.is_file() else ""
    check("the state file records the W1d slice", "W1d" in state_text)
    check("the body spec points at the implemented model",
          "StepBody" in (REPO / "docs/v2/03-specifications/BLOCK_STEP_EXECUTION.md").read_text())


def check_evidence() -> None:
    print("\n[7] evidence: guard suites green, zero new regressions")
    head = tar_classes(XML)
    base = tar_classes(BASE_XML)
    check("the head XML archive is present", bool(head))
    check("the base XML archive is present", bool(base))

    for cls, expected_tests in EXPECTED_GUARD_CLASSES.items():
        row = head.get(cls)
        check(f"{cls.split('.')[-1]} ran {expected_tests} tests, all green",
              row is not None and row["tests"] == expected_tests
              and row["failures"] == 0 and row["errors"] == 0,
              f"got {row}")

    name, base_tests, base_failures = EXPECTED_COORDINATOR_BASELINE
    head_name, head_tests, head_failures = EXPECTED_COORDINATOR_HEAD
    head_row = head.get(f"dev.rubentxu.pipeline.v2.application.durable.{head_name}")
    base_row = base.get(f"dev.rubentxu.pipeline.v2.application.durable.{name}")
    check("the coordinator class baseline is measured, not assumed",
          base_row is not None and base_row["tests"] == base_tests and base_row["failures"] == base_failures,
          f"got {base_row}")
    check("the coordinator class is not widened",
          head_row is not None and head_row["tests"] == head_tests, f"got {head_row}")
    check("the head repair is a repaired failure, not a moved one: the class lost exactly one name",
          head_row is not None and base_row is not None
          and set(base_row["failing"]) - set(head_row["failing"]) == {EXPECTED_REPAIRED_COORDINATOR_TEST}
          and set(head_row["failing"]) - set(base_row["failing"]) == set(),
          f"got {sorted(set(base_row['failing']) - set(head_row['failing'])) if head_row and base_row else 'n/a'}")
    check("every coordinator failure at head already failed at the slice parent",
          head_row is not None and base_row is not None
          and set(head_row["failing"]) - set(base_row["failing"]) == set(),
          f"new failing names: {sorted(set(head_row['failing']) - set(base_row['failing'])) if head_row and base_row else 'n/a'}")

    head_inv = load_json(HEAD_INVENTORY)
    base_inv = load_json(BASE_INVENTORY)
    check("both module inventories are present", bool(head_inv) and bool(base_inv))
    if head_inv and base_inv:
        for module, (tests, failures) in sorted(EXPECTED_HEAD_MODULE_TOTALS.items()):
            row = head_inv.get(module)
            check(f"{module} totals are {tests} / {failures}",
                  row is not None and (row["tests"], row["failures"]) == (tests, failures),
                  f"got {row and (row['tests'], row['failures'])}")
        deltas: list[str] = []
        for module in sorted(set(base_inv) | set(head_inv)):
            b = base_inv.get(module, {"tests": 0, "failures": 0})
            h = head_inv.get(module, {"tests": 0, "failures": 0})
            got = (h["tests"] - b["tests"], h["failures"] - b["failures"])
            want = EXPECTED_MODULE_DELTAS.get(module, (0, 0))
            if got != want:
                deltas.append(f"{module}: got {got}, expected {want}")
        check("only the three intended modules move, and only by the intended delta",
              not deltas, "; ".join(deltas))
        regressions: list[str] = []
        for module in sorted(set(base_inv) | set(head_inv)):
            b = base_inv.get(module, {})
            h = head_inv.get(module, {})
            b_red = b.get("red", {})
            h_red = h.get("red", {})
            for cls in sorted(set(h_red) - set(b_red)):
                regressions.append(f"{module}: new red class {cls}")
            for cls in sorted(set(h_red) & set(b_red)):
                new_names = sorted(set(h_red[cls]) - set(b_red[cls]))
                if new_names:
                    regressions.append(f"{module}: {cls} new failing names {new_names}")
        check("zero modules regress between the slice parent and the slice",
              not regressions, "; ".join(regressions[:5]))


def check_area() -> None:
    print("\n[8] validator and tests agree with the ADT")
    validator = show(VALIDATOR)
    check("the validator reports a missing declaration, not a removed flag",
          "declares no body" in validator and "takesBody=false" not in validator)
    test = show(DOMAIN_TEST)
    check("the policy test reads the declaration instead of a descriptor field",
          "declaredBodyOf(" in test and "policyOf(" in test)
    test_code = code_only(test)
    check("the policy test no longer constructs the contradictory terminal+policy shape",
          "takesBody = false" not in test_code and "bodyExecutionPolicy" not in test_code)
    check("the policy test pins the terminal case as StepBody.None",
          "StepBody.None" in test_code)


def main() -> None:
    print(f"B10/W1d verifier\n  slice: {SLICE_SHA}\n  code:  {CODE}"
          f"{' (WORKTREE)' if WORKTREE else ''}\n  base:  {BASE}")
    check_body_declaration()
    check_registry_rows()
    check_coordinator()
    check_ledger()
    check_aggregate_identities()
    check_docs()
    check_evidence()
    check_area()
    print(f"\n{checks - len(failures)}/{checks} checks passed")
    if failures:
        print("FAILED:")
        for f in failures:
            print(f"  - {f}")
        sys.exit(1)


# ===== negative controls =====
#
# A law that cannot fail is not a law. Each control applies ONE mutation to the working
# tree, runs this verifier in WORKTREE mode, and requires the check that owns the mutated
# law to go red. The tree must be clean before and after: a control that "passes" because
# of a stale worktree state is not evidence (AGENTS.md 14-16, 21).

DUPLICATED_BODY_LOOP = """
    /**
     * CONTROL K1: a second body-child loop, with no literal and no dispatch*Block name.
     * This is the shape the W1a-era credential bypass had, and the shape the W1a scanner
     * (which only looks for names) could not see.
     */
    private suspend fun controlDuplicatedBodyLoop(block: BlockStepNode) {
        for ((childIndex, child) in block.body.withIndex()) {
            dispatch(child, childIndex)
        }
    }

""" + "    private suspend fun invokeBodyChildren("


def _coord(*pairs: tuple[str, str]) -> None:
    path = REPO / COORD
    text = path.read_text()
    for old, new in pairs:
        if old not in text:
            raise SystemExit(f"control anchor not found in {COORD}: {old[:60]!r}")
        text = text.replace(old, new, 1)
    path.write_text(text)


CONTROLS: list[tuple[str, str, str, callable]] = [
    ("K1", "a second body-child loop with no literal at all is rejected",
     f"{COORD} (duplicated loop)",
     lambda: _coord(("    private suspend fun invokeBodyChildren(", DUPLICATED_BODY_LOOP)),
     "exactly one body-child loop defines the shared path"),
    ("K2", "a concrete Step literal is rejected",
     f"{COORD} (core.echo literal)",
     lambda: _coord(("    private suspend fun invokeBodyChildren(",
                     "    private val controlStepName = \"core.echo\"\n\n    private suspend fun invokeBodyChildren(")),
     "the coordinator has no concrete Step literal"),
    ("K3", "a re-introduced body allowlist is rejected",
     f"{COORD} (canonicalBodyStepIds)",
     lambda: _coord(("    private suspend fun invokeBodyChildren(",
                     "    private val canonicalBodyStepIds: Set<String> = setOf(\"core.dir\")\n\n    private suspend fun invokeBodyChildren(")),
     "the coordinator has no hard-coded body id allowlist"),
    ("K4", "a re-introduced dispatch*Block is rejected",
     f"{COORD} (dispatchWithCredentialsBlock)",
     lambda: _coord(("    private suspend fun invokeBodyChildren(",
                     "    private suspend fun dispatchWithCredentialsBlock() { }\n\n    private suspend fun invokeBodyChildren(")),
     "no dispatch*Block identifier remains"),
    ("K5", "a second credential acquisition site is rejected",
     f"{COORD} (second acquire)",
     lambda: _coord(("    private suspend fun invokeBodyChildren(",
                     "    private suspend fun controlSecondAcquisition() = credentialScopePort.acquire(emptyList(), RunId(\"x\"))\n\n    private suspend fun invokeBodyChildren(")),
     "exactly one site acquires a credential lease"),
    ("K6", "moving the release out of the finally is rejected",
     f"{COORD} (close outside finally)",
     lambda: _coord(("        } finally {\n            cleanup = leased.close()\n        }",
                     "        }\n        cleanup = leased.close()")),
     "the lease is released in a finally"),
    ("K7", "raising the pinned ledger above zero is rejected",
     f"{LEDGER} (pin raised)",
     lambda: _ledger(),
     "the pinned ledger enumerates no concrete Step name"),
    ("K8", "lowering the historical ceiling is rejected",
     f"{LEDGER} (ceiling lowered)",
     lambda: _text(LEDGER, "const val HISTORICAL_CEILING = 18", "const val HISTORICAL_CEILING = 0"),
     "HISTORICAL_CEILING is still 18"),
    ("K9", "deleting a durable aggregate identity is rejected",
     f"{AGGREGATE} (key renamed)",
     lambda: _text(AGGREGATE, 'PluginStepId("core.retry")', 'PluginStepId("core.retry-row")'),
     "core.retry is the RetryControlRow identity"),
    ("K10", "dropping an identity from the pinned ALL list is rejected",
     f"{AGGREGATE} (identity unpinned)",
     lambda: _text(AGGREGATE, "listOf(RetryControlRow, ParallelStageAggregate)", "listOf(RetryControlRow)"),
     "the pinned ALL list contains both identities"),
    ("K11", "defaulting the execution owner is rejected",
     f"{STEP_BODY} (owner defaulted)",
     lambda: _text(STEP_BODY, "val execution: BodyExecution,",
                   "val execution: BodyExecution = BodyExecution(BodyExecutionOwner.CANONICAL_ENGINE, BodyExecutionPolicy.Sequential),"),
     "the execution owner and shape are required, never defaulted"),
    ("K12", "re-introducing a removed StepDescriptor body field is rejected",
     f"{DESCRIPTOR} (takesBody returns)",
     lambda: _text(DESCRIPTOR, "val body: StepBody = StepBody.None",
                   "val takesBody: Boolean = false\n    val body: StepBody = StepBody.None"),
     "StepDescriptor declares none of the six removed body fields"),
]


def _text(path: str, old: str, new: str) -> None:
    p = REPO / path
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"control anchor not found in {path}: {old[:60]!r}")
    p.write_text(text.replace(old, new, 1))


def _ledger() -> None:
    _text(LEDGER, "concreteStepNames = emptySet(),", 'concreteStepNames = setOf("core.echo"),')


def _read_worktree() -> None:
    """Controls mutate the working tree, so they must read the working tree. The historical
    slice anchor is irrelevant to them (and does not exist until the receipt is committed)."""
    global CODE, WORKTREE, SLICE_SHA
    os.environ["W1D_CODE"] = "WORKTREE"
    CODE = SLICE_SHA = "WORKTREE"
    WORKTREE = True


def run_controls() -> None:
    import subprocess as sp
    _read_worktree()
    touched = sorted({path for _, _, path, _, _ in CONTROLS})
    dirty = git("status", "--porcelain", *[p.split(" (")[0] for p in touched]).strip()
    if dirty:
        raise SystemExit(f"refusing to run controls on a dirty tree:\n{dirty}")
    print(f"running {len(CONTROLS)} negative controls against the WORKTREE")
    results = []
    for cid, description, path, mutate, expected in CONTROLS:
        target = path.split(" (")[0]
        try:
            mutate()
            env = dict(os.environ, W1D_CODE="WORKTREE")
            proc = sp.run([sys.executable, str(HERE / "verify-b10-w1d-receipt.py")],
                          capture_output=True, text=True, env=env)
            caught = expected in proc.stdout and proc.returncode != 0
            detail = "" if caught else "verifier did not report the expected law"
        finally:
            subprocess.run(["git", "-C", str(REPO), "checkout", "--", target], check=False)
        results.append((cid, caught, description, detail))
    print()
    for cid, ok, description, detail in results:
        print(f"  [{'PASS' if ok else 'FAIL'}] {cid}: {description}" + (f" -- {detail}" if detail else ""))
    bad = [c for c, ok, _, _ in results if not ok]
    print(f"\n{len(results) - len(bad)}/{len(results)} controls behaved as required")
    if bad:
        sys.exit(1)


if __name__ == "__main__":
    if "--controls" in sys.argv:
        run_controls()
    else:
        main()
