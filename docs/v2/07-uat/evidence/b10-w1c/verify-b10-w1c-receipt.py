#!/usr/bin/env python3
"""Verify the B10/W1c receipt (docs/v2/07-uat/B10_W1C_BODY_EXECUTION_ROUTING_RECEIPT.md).

HISTORICAL, not working tree. Every code claim is read from a git commit via `git show`
(`W1C_CODE`, default the commit that introduced the receipt), so the verifier keeps
asserting what W1c shipped after later slices edit these files.

INDEPENDENT, not transcribed. The declared body policies, the declared ownership, the
derived canonical body set and the coordinator's remaining concrete routing debt are
re-derived in Python from the Kotlin sources and compared against expectation tables
stated HERE. Nothing is read from the pinned ledger, so a pin edited to match a drifted
source is caught by the re-scan (control K7).

DIFFERENTIAL. W1c claims BEHAVIOUR PRESERVATION: the six body families the coordinator
routed by name must route identically by policy. That claim is checked against the OLD
coordinator read at `W1C_BASE` (the slice parent), not against a restatement of the new
code, so a policy silently swapped during the migration fails (control K6).

Usage:
  python3 verify-b10-w1c-receipt.py                    # static + evidence checks
  W1C_CODE=WORKTREE python3 verify-b10-w1c-receipt.py  # read the working tree (controls)
  python3 verify-b10-w1c-receipt.py --controls         # run the negative controls

Env: W1C_CODE, W1C_BASE, W1C_XML.
"""

from __future__ import annotations

import os
import json
import re
import subprocess
import sys
import tarfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[4]

# The slice parent: `main` at the time W1c was cut. The differential check reads the
# pre-W1c coordinator from this commit.
BASE = os.environ.get("W1C_BASE", "bd82e1eb")
RECEIPT = "docs/v2/07-uat/B10_W1C_BODY_EXECUTION_ROUTING_RECEIPT.md"


def _resolve_slice() -> str:
    """Resolved, never hard-coded: a constant would have to be edited (and re-committed)
    every time the slice commit is amended, which is the circularity this avoids. Still
    historical: later slices edit other files and cannot move this anchor."""
    override = os.environ.get("W1C_SLICE")
    if override:
        return override
    if (os.environ.get("W1C_CODE") or "").upper() == "WORKTREE":
        # Debug/control mode: the receipt is uncommitted, so there is no anchor to resolve.
        return os.environ.get("W1C_CODE")
    proc = subprocess.run(
        ["git", "-C", str(REPO), "log", "-1", "--format=%H", "--", RECEIPT],
        capture_output=True, text=True, check=False,
    )
    sha = proc.stdout.strip()
    if not sha:
        raise SystemExit(f"cannot resolve the W1c slice commit from {RECEIPT}")
    return sha


SLICE_SHA = _resolve_slice()
CODE = os.environ.get("W1C_CODE") or SLICE_SHA
WORKTREE = CODE.upper() == "WORKTREE"

DOMAIN = "v2/pipeline-domain"
ARCH = "v2/pipeline-architecture-tests"
APP = "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application"

COORD = f"{APP}/durable/CanonicalDurableRunCoordinator.kt"
POLICY = f"{DOMAIN}/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyExecutionPolicy.kt"
DESCRIPTOR = f"{DOMAIN}/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/StepDescriptor.kt"
DESC_REGISTRY = f"{DOMAIN}/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/StepDescriptorRegistry.kt"
LEDGER = f"{ARCH}/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/ConcreteBodyRoutingDebt.kt"
GUARD = f"{ARCH}/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/Lfc2ConcreteBodyRoutingDebtFitnessTest.kt"
FITNESS = f"{ARCH}/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/Lfc2BodyExecutionPolicyFitnessTest.kt"
DOMAIN_TEST = f"{DOMAIN}/src/test/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyExecutionPolicyTest.kt"
EVIDENCE_PREFIX = "docs/v2/07-uat/evidence/b10-w1c/"
TESTING_STATE = ".agent/TESTING-STATE.md"

XML = Path(os.environ.get("W1C_XML", HERE / "raw/xml/module-suites-xml.tar.gz"))
BASE_XML = Path(os.environ.get("W1C_BASE_XML", HERE / "raw/xml/base-failing-classes-xml.tar.gz"))

# ===== expectations, stated here, independent of the implementation =====

# Every body-bearing descriptor row: (owner, policy). W1c declares ownership explicitly.
EXPECTED_BODY_ROWS: dict[str, tuple[str, str]] = {
    "core.catchError": ("LEGACY_LINEAR", "Sequential"),
    "core.warnError": ("LEGACY_LINEAR", "Sequential"),
    "core.withEnv": ("CANONICAL_ENGINE", "Scoped(Environment)"),
    "core.dir": ("CANONICAL_ENGINE", "Scoped(WorkingDirectory)"),
    "core.withCredentials": ("CANONICAL_ENGINE", "Scoped(CredentialLease)"),
    "core.timeout": ("CANONICAL_ENGINE", "Scoped(Deadline)"),
    "core.timestamps": ("CANONICAL_ENGINE", "Scoped(Timestamps)"),
    "core.retry": ("CANONICAL_ENGINE", "Retrying"),
}

# The canonical body set the durable engine may execute. Stated as a literal here: this is
# production routing, so it is pinned independently of the ledger and of the tests.
EXPECTED_CANONICAL_BODY_SET = {
    "core.dir", "core.timestamps", "core.withEnv", "core.timeout",
    "core.withCredentials", "core.retry",
}
EXPECTED_LEGACY_BODY_SET = {"core.catchError", "core.warnError"}

# Families the policy model can express but that declare NO descriptor row (declared gap).
EXPECTED_ROWS_ABSENT = {"core.parallel"}

# The W1c engine support: the shapes the coordinator interprets. PARALLEL is absent.
EXPECTED_SUPPORT_SHAPES = {"SEQUENTIAL", "SCOPED", "RETRYING"}

# Pre-W1c routing, measured on the parent commit: the arm of `projectShellScope` per key.
# The policy table above is correct only if it matches this mapping.
EXPECTED_PRE_W1C_ARMS: dict[str, str] = {
    "core.dir": "Scoped(WorkingDirectory)",
    "core.timestamps": "Scoped(Timestamps)",
    "core.withEnv": "Scoped(Environment)",
    "core.timeout": "Scoped(Deadline)",
    "core.retry": "Retrying",
    # `core.withCredentials` never had an arm: it was a name-keyed bypass before the switch.
    "core.withCredentials": "Scoped(CredentialLease)",
}
PRE_W1C_BYPASS_KEY = "core.withCredentials"

# The coordinator's remaining concrete routing debt after W1c, re-derived in Python.
EXPECTED_CONCRETE_STEP_NAMES = {"core.parallel", "core.retry"}
EXPECTED_BODY_STEP_IDS: set[str] = set()
EXPECTED_SITES = {"DISPATCH_WITH_CREDENTIALS_BLOCK"}
EXPECTED_BLOCK_BYPASSES = {"dispatchWithCredentialsBlock"}
EXPECTED_STEP_ID_SWITCHES = 0
EXPECTED_TOTAL = 4
HISTORICAL_CEILING = 18

# Suites re-run for this slice, with the measured (tests, errors) counts.
EXPECTED_APP_CLASS = ("CanonicalDurableRunCoordinatorTest", 26, 11)
EXPECTED_FITNESS_ROWS = 9

# Suite totals measured for this slice (tests, failures, errors), stated here rather than
# read back from the archive: an evidence archive that agrees with itself proves nothing.
EXPECTED_DOMAIN_TOTALS = (395, 0, 0)
EXPECTED_ARCH_TOTALS = (262, 1, 0)
EXPECTED_APP_TOTALS = (1392, 36, 0)
EXPECTED_ARCH_RED = {"dev.rubentxu.pipeline.v2.architecture.Lfc0GlobalStateFitnessTest"}
EXPECTED_APP_RED_CLASSES = 14

# Test-count growth this slice INTENDS, per module. Every other module must be identical
# between the slice parent and the slice, tests and reds alike.
EXPECTED_TEST_DELTAS = {"pipeline-domain": 7, "pipeline-architecture-tests": 1}
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
    """Comment-blind view: prose may name steps, code may not."""
    return "\n".join(
        ln for ln in text.splitlines()
        if not ln.lstrip().startswith("//") and not ln.lstrip().startswith("*")
    )


# ===== independent re-derivations =====

CORE_LITERAL = re.compile(r'"(?:core|example)\.[A-Za-z0-9_.]+"')
STEP_KEY_SWITCH = re.compile(r"when\s*\(\s*[A-Za-z0-9_.]*[Ss]tepId[A-Za-z0-9_.]*\s*\)")
BLOCK_DISPATCH = re.compile(r"\bdispatch[A-Za-z0-9_]*Block\b")
BODY_IDS_ALLOWLIST = re.compile(r"canonicalBodyStepIds\s*:\s*Set<String>\s*=\s*setOf\(([^)]*)\)")
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
    """(key -> {takesBody, owner, policy, introducesContext}) re-derived from Kotlin."""
    rows: dict[str, dict] = {}
    for key, body in PUT_ROW.findall(source):
        def field(pattern: str, default: str = "") -> str:
            m = re.search(pattern, body)
            return m.group(1) if m else default

        rows[key] = {
            "takesBody": field(r"takesBody\s*=\s*(true|false)", "false") == "true",
            # The descriptor default is CANONICAL_ENGINE: an undeclared owner is canonical.
            "owner": field(r"bodyExecutionOwner\s*=\s*BodyExecutionOwner\.(\w+)", "CANONICAL_ENGINE"),
            "policy": parse_policy(field(r"bodyExecutionPolicy\s*=\s*([^\n,]+)", "BodyExecutionPolicy.Sequential")),
            "introducesContext": "null" if re.search(r"introducesContext\s*=\s*null", body)
            else field(r"introducesContext\s*=\s*ContextKind\.(\w+)", "NONE"),
        }
    return rows


def derived_body_set(rows: dict[str, dict], owner: str) -> set[str]:
    return {key for key, row in rows.items() if row["takesBody"] and row["owner"] == owner}


def rescan_debt(source: str) -> dict:
    """Python implementation of the W1a scanner rules, over the coordinator source."""
    block = BODY_IDS_ALLOWLIST.search(source)
    body_ids = {m.group(0).strip('"') for m in CORE_LITERAL.finditer(block.group(1))} if block else set()
    sites: set[str] = set()
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
        "stepIdSwitches": len(STEP_KEY_SWITCH.findall(source)),
        "total": 0,
    }


def pre_w1c_arms(source: str) -> dict[str, str]:
    """The old key-keyed arms, parsed out of the pre-W1c `projectShellScope`."""
    m = re.search(r"private fun BlockStepNode\.projectShellScope\(.*?\n\}", source, re.S)
    if not m:
        return {}
    body = m.group(0)
    arms: dict[str, str] = {}
    current: str | None = None
    for line in body.splitlines():
        key = re.match(r'\s*"((?:core|example)\.[A-Za-z0-9_.]+)"\s*->', line)
        if key:
            current = key.group(1)
            arms.setdefault(current, "Sequential")
            continue
        if current is None:
            continue
        if "BlockShellScope.Directory(" in line:
            arms[current] = "Scoped(WorkingDirectory)"
        elif "BlockShellScope.TimestampsScope(" in line:
            arms[current] = "Scoped(Timestamps)"
        elif "BlockShellScope.EnvScope(" in line:
            arms[current] = "Scoped(Environment)"
        elif "BlockShellScope.Timeout(" in line:
            arms[current] = "Scoped(Deadline)"
        elif "BlockShellScope.Retry(" in line:
            arms[current] = "Retrying"
    # The withCredentials bypass was a name-keyed early return before the switch.
    if re.search(r'pluginStepId\.value\s*==\s*"core\.withCredentials"', source):
        arms[PRE_W1C_BYPASS_KEY] = "Scoped(CredentialLease)"
    return arms


def rescan_fields(source: str) -> dict:
    """The comparable subset of `rescan_debt` (everything the ledger pins)."""
    debt = rescan_debt(source)
    return {k: debt[k] for k in ("concreteStepNames", "bodyStepIds", "sites", "blockBypasses",
                                 "stepIdSwitches")}


def parse_pinned_ledger(text: str) -> dict:
    """The pinned ledger's OWN claims, re-parsed from the ledger source.

    Compared against the coordinator scan so the pin cannot drift away from the source.
    """
    def set_block(field: str, pattern: re.Pattern[str] = CORE_LITERAL) -> set[str]:
        block = re.search(rf"{field} = setOf\((.*?)\)[,)]", text, re.S)
        return {m.group(0).strip('"') for m in pattern.finditer(block.group(1))} if block else set()

    sites_block = re.search(r"sites = setOf\((.*?)\)[,)]", text, re.S)
    switch = re.search(r"stepIdSwitches = (\d+),", text)
    return {
        "concreteStepNames": set_block("concreteStepNames"),
        "bodyStepIds": set() if "bodyStepIds = emptySet()," in text else set_block("bodyStepIds"),
        "sites": {m.group(1) for m in re.finditer(r"BodyRoutingSite\.(\w+)", sites_block.group(1))}
        if sites_block else set(),
        "blockBypasses": set_block("blockBypasses", re.compile(r'"([^"]+)"')),
        "stepIdSwitches": int(switch.group(1)) if switch else -1,
    }


def tar_inventory(tar: Path, subdir: str) -> dict[str, dict]:
    """{class -> {tests, failures, errors, failing}} for one archived module slice."""
    import xml.etree.ElementTree as ET
    inventory: dict[str, dict] = {}
    if not tar.is_file():
        return inventory
    with tarfile.open(tar) as tf:
        for member in tf.getmembers():
            if not member.isfile() or not member.name.endswith(".xml"):
                continue
            if subdir and f"/{subdir}/" not in member.name.replace("\\", "/"):
                continue
            root = ET.fromstring(tf.extractfile(member).read().decode("utf-8"))
            failing = sorted(
                tc.get("name") for tc in root.iter("testcase")
                if tc.find("failure") is not None or tc.find("error") is not None
            )
            inventory[root.get("name")] = {
                "tests": int(root.get("tests")),
                "failures": int(root.get("failures")),
                "errors": int(root.get("errors")),
                "failing": failing,
            }
    return inventory


def red_of(inventory: dict[str, dict]) -> dict[str, list[str]]:
    return {cls: row["failing"] for cls, row in inventory.items() if row["failing"]}


def totals(inventory: dict[str, dict]) -> tuple[int, int, int]:
    return (sum(r["tests"] for r in inventory.values()),
            sum(r["failures"] for r in inventory.values()),
            sum(r["errors"] for r in inventory.values()))


# ===== checks =====

def check_domain() -> None:
    print("\n== domain declarations ==")
    policy_src = show(POLICY)
    descriptor_src = show(DESCRIPTOR)
    registry_src = show(DESC_REGISTRY)

    owner_body = re.findall(r"enum class BodyExecutionOwner\s*\{(.*?)\n\}", policy_src, re.S)
    owner_cases = {c.strip().rstrip(",") for c in owner_body[0].splitlines()} if owner_body else set()
    owner_cases = {c for c in owner_cases if c and not c.startswith(("*", "/"))}
    check("BodyExecutionOwner is a closed two-case enum",
          owner_cases == {"CANONICAL_ENGINE", "LEGACY_LINEAR"}, f"cases={sorted(owner_cases)}")

    check("StepDescriptor declares the owner with the canonical default",
          "val bodyExecutionOwner: BodyExecutionOwner = BodyExecutionOwner.CANONICAL_ENGINE" in descriptor_src)

    rows = parse_registry(registry_src)
    declared = {k: (v["owner"], v["policy"]) for k, v in rows.items() if v["takesBody"]}
    check("every body-bearing row declares the expected owner and policy",
          declared == EXPECTED_BODY_ROWS,
          f"diff={ {k: (declared.get(k), EXPECTED_BODY_ROWS.get(k)) for k in set(declared) ^ set(EXPECTED_BODY_ROWS)} }")

    absent = EXPECTED_ROWS_ABSENT & set(rows)
    check("the declared gap families still have no row", absent == set(), f"present={sorted(absent)}")

    check("canonical body set derived from the registry equals the pinned set",
          derived_body_set(rows, "CANONICAL_ENGINE") == EXPECTED_CANONICAL_BODY_SET,
          f"got={sorted(derived_body_set(rows, 'CANONICAL_ENGINE'))}")
    check("legacy body set derived from the registry equals the pinned set",
          derived_body_set(rows, "LEGACY_LINEAR") == EXPECTED_LEGACY_BODY_SET,
          f"got={sorted(derived_body_set(rows, 'LEGACY_LINEAR'))}")

    check("the accessor filters on takesBody AND ownership",
          re.search(r"fun bodyStepIds\(owner: BodyExecutionOwner\): Set<PluginStepId> =\s*\n\s*descriptors\.filter "
                    r"\{ \(_, descriptor\) ->\s*\n\s*descriptor\.takesBody && descriptor\.bodyExecutionOwner == owner",
                    registry_src) is not None)

    support = set(re.findall(r"val SCOPED_SEQUENTIAL_RETRYING: BodyExecutionSupport =\s*\n\s*BodyExecutionSupport\(\s*\n\s*setOf\((.*?)\)",
                             policy_src, re.S)[0].replace("\n", " ").split())
    support_shapes = {s.replace("BodyExecutionPolicyShape.", "").strip(",") for s in support if s.strip()}
    check("the W1c engine support admits exactly the interpreted shapes",
          support_shapes == EXPECTED_SUPPORT_SHAPES, f"got={sorted(support_shapes)}")

    timestamps = rows.get("core.timestamps", {})
    check("the timestamps row declares Scoped(Timestamps) with no invented context kind",
          timestamps.get("policy") == "Scoped(Timestamps)" and timestamps.get("introducesContext") == "null",
          f"row={timestamps}")


def check_coordinator() -> None:
    print("\n== coordinator routing ==")
    src = show(COORD)
    code = code_only(src)

    check("no step-identity switch remains, not even in prose",
          len(STEP_KEY_SWITCH.findall(src)) == EXPECTED_STEP_ID_SWITCHES,
          f"found={STEP_KEY_SWITCH.findall(src)}")
    check("the hard-coded body id allowlist is gone",
          BODY_IDS_ALLOWLIST.search(src) is None)

    derived = re.search(r"private val canonicalBodyStepIds: Set<PluginStepId> =\s*\n\s*"
                        r"StepDescriptorRegistry\.standard\(\)\.bodyStepIds\(BodyExecutionOwner\.CANONICAL_ENGINE\)",
                        src)
    check("body eligibility is derived from declared ownership", derived is not None)

    check("eligibility compares the typed id, not its string form",
          "pluginStepId !in canonicalBodyStepIds" in src)

    check("the old keyed projection is gone and the policy projection is present",
          "projectShellScope" not in src and re.search(r"fun BlockStepNode\.projectBodyExecution\(", src) is not None)

    policy_shapes = set(re.findall(r"is BodyExecutionPolicy\.(\w+) ->", code))
    check("the projection is exhaustive over every policy shape",
          policy_shapes == {"Sequential", "Scoped", "Retrying", "Parallel"}, f"got={sorted(policy_shapes)}")

    projections = set(re.findall(r"is BodyContextProjection\.(\w+) ->", code))
    check("every declared context projection has an interpretation",
          projections == {"WorkingDirectory", "Environment", "Timestamps", "Deadline", "CredentialLease"},
          f"got={sorted(projections)}")

    check("the projection has no else branch hiding an unhandled case",
          all("else ->" not in region for region in (
              (re.search(r"fun BlockStepNode\.projectBodyExecution\(.*?\n\}", code, re.S) or re.match(r"", "")).group(0),
              (re.search(r"fun BlockStepNode\.projectScopedBody\(.*?\n\}", code, re.S) or re.match(r"", "")).group(0),
              (re.search(r"val scope = when \(projection\) \{.*?\n        \}", code, re.S) or re.match(r"", "")).group(0),
          )))

    check("the coordinator reads the declared policy through the port",
          "bodyPolicyResolver.resolve(block.pluginStepId)" in src)
    check("the production resolver is the registry authority bounded by the engine support",
          re.search(r"bodyPolicyResolver: BodyPolicyResolver =\s*\n\s*StepDescriptorRegistry\.standard\(\)"
                    r"\.bodyPolicyResolver\(BodyExecutionSupport\.SCOPED_SEQUENTIAL_RETRYING\)", src) is not None)

    cred = re.search(r"is BodyExecutionProjection\.CredentialLifecycle -> return dispatchWithCredentialsBlock\(", src)
    check("the credential lifecycle is reached by POLICY, not by Step name", cred is not None)
    check("the name-keyed credential bypass is gone",
          not re.search(r'pluginStepId\.value\s*==\s*"core\.withCredentials"', src))

    rejected = re.search(r"is BodyPolicyResolution\.Rejected -> return StepOutcome\.Failure\(", src)
    unimplemented = re.search(r"is BodyExecutionProjection\.Unimplemented -> return StepOutcome\.Failure\(", src)
    check("a rejected policy fails closed before any child is dispatched",
          rejected is not None and unimplemented is not None,
          "both the resolution rejection and the unimplemented shape must return a typed failure")

    literal_re = re.compile(r'"(?:core|example)\.[A-Za-z0-9_.]+"')
    names = {m.group(0).strip('"') for m in literal_re.finditer(src)}
    check("only the two non-body durable identities name a concrete Step",
          names == EXPECTED_CONCRETE_STEP_NAMES, f"got={sorted(names)}")

    debt = rescan_debt(src)
    check("concrete step names re-derive to the pinned set",
          debt["concreteStepNames"] == EXPECTED_CONCRETE_STEP_NAMES, f"got={sorted(debt['concreteStepNames'])}")
    check("body step ids re-derive to the empty set",
          debt["bodyStepIds"] == EXPECTED_BODY_STEP_IDS, f"got={sorted(debt['bodyStepIds'])}")
    check("routing sites re-derive to the credential dispatcher only",
          debt["sites"] == EXPECTED_SITES, f"got={sorted(debt['sites'])}")
    check("block bypasses re-derive to the credential dispatcher only",
          debt["blockBypasses"] == EXPECTED_BLOCK_BYPASSES, f"got={sorted(debt['blockBypasses'])}")
    total = (len(debt["concreteStepNames"]) + len(debt["bodyStepIds"]) + len(debt["sites"])
             + len(debt["blockBypasses"]) + debt["stepIdSwitches"])
    check("the re-derived debt total is the W1c measured total", total == EXPECTED_TOTAL, f"got={total}")


def check_behaviour_preservation() -> None:
    print("\n== differential: pre-W1c routing vs declared policy ==")
    old_coord = show(COORD, ref=BASE)
    arms = pre_w1c_arms(old_coord)
    check("the pre-W1c coordinator routed exactly the expected families by name",
          arms == EXPECTED_PRE_W1C_ARMS, f"got={sorted(arms.items())}")

    rows = parse_registry(show(DESC_REGISTRY))
    mismatched = {key: (policy, rows.get(key, {}).get("policy"))
                  for key, policy in arms.items() if rows.get(key, {}).get("policy") != policy}
    check("every pre-W1c family declares the policy that matches its old routing",
          mismatched == {}, f"mismatched={mismatched}")


def check_ledger_and_guard() -> None:
    print("\n== ledger and guard ==")
    ledger = show(LEDGER)
    guard = show(GUARD)
    fitness = show(FITNESS)

    check("ceiling is unchanged provenance",
          re.search(r"const val HISTORICAL_CEILING = 18", ledger) is not None)
    check("the pinned names are the two non-body identities",
          'concreteStepNames = setOf(\n            "core.parallel",\n            "core.retry",\n        )' in ledger)
    check("the pinned body step ids are empty", "bodyStepIds = emptySet()," in ledger)
    check("the pinned site set keeps only the credential dispatcher",
          "BodyRoutingSite.DISPATCH_WITH_CREDENTIALS_BLOCK,\n        )," in ledger
          and "BodyRoutingSite.PROJECT_SHELL_SCOPE,\n            BodyRoutingSite.DISPATCH" not in ledger)
    check("the pinned switch count is zero", "stepIdSwitches = 0," in ledger)
    check("retired sites keep their enum cases so re-introduction is detected",
          "CANONICAL_BODY_STEP_IDS," in ledger and "PROJECT_SHELL_SCOPE," in ledger)
    check("the scanner still detects every retired shape",
          all(p in ledger for p in (
              r'Regex("canonicalBodyStepIds\\s*:\\s*Set<String>',
              r'Regex("projectShellScope\\s*\\(',
              r'Regex("private\\s+suspend\\s+fun\\s+dispatchWithCredentialsBlock\\(',
              r'Regex("when\\s*\\(\\s*[A-Za-z0-9_.]*[Ss]tepId',
          )))

    # The guard has no literal total to pin: it pins the ledger to the scan both in total and
    # item-by-item, and the ledger is the living state. `EXPECTED_TOTAL` above is the
    # independent number, re-derived from the coordinator source.
    check("the guard rejects a total mismatch and an undeclared item",
          all(p in guard for p in ("verdict is RoutingDebtVerdict.WithinPinnedDebt",
                                   "(verdict as RoutingDebtVerdict.WithinPinnedDebt).debtTotal",
                                   "discovered - pinned",
                                   "The pinned ledger must not carry slack")))
    check("the guard keeps the ceiling as provenance",
          "18,\n            PinnedConcreteBodyRoutingDebt.HISTORICAL_CEILING," in guard)
    check("the pinned ledger agrees with the independently re-derived coordinator debt",
          parse_pinned_ledger(ledger) == rescan_fields(show(COORD)),
          f"pinned={parse_pinned_ledger(ledger)} source={rescan_fields(show(COORD))}")
    check("the W1b firewall law is replaced by its inverse",
          "does not yet resolve body policies" not in fitness
          and "the coordinator resolves body policies through the port" in fitness)
    check("the policy fitness law pins the lowered total as a literal",
          re.search(r"assertEquals\(\s*4,\s*discovered\.total,", fitness) is not None)


TARBALL_TOTALS: dict[str, tuple[int, int, int]] = {}


def check_evidence() -> None:
    print("\n== evidence ==")
    check("the receipt is present and names the slice", "W1c" in show(RECEIPT))
    check("the testing state carries a W1c section", "W1c" in show(TESTING_STATE))

    if not XML.is_file():
        check(f"the module-suite XML evidence archive exists at {XML}", False)
        return
    check("the module-suite XML evidence archive exists", True)

    domain = tar_inventory(XML, "domain")
    arch = tar_inventory(XML, "arch")
    app = tar_inventory(XML, "app")
    TARBALL_TOTALS.update({
        "pipeline-domain": totals(domain),
        "pipeline-architecture-tests": totals(arch),
        "pipeline-application": totals(app),
    })
    for module, inventory, expected in (
        ("pipeline-domain", domain, EXPECTED_DOMAIN_TOTALS),
        ("pipeline-architecture-tests", arch, EXPECTED_ARCH_TOTALS),
        ("pipeline-application", app, EXPECTED_APP_TOTALS),
    ):
        check(f"{module} archived totals (tests, failures, errors) = {expected}",
              totals(inventory) == expected, f"got={totals(inventory)}")

    check("the domain module has no red class", red_of(domain) == {}, f"got={sorted(red_of(domain))}")
    check("the architecture module's only red class is the known pre-existing one",
          set(red_of(arch)) == EXPECTED_ARCH_RED, f"got={sorted(red_of(arch))}")
    check("the application module's red classes are the measured fourteen",
          len(red_of(app)) == EXPECTED_APP_RED_CLASSES, f"got={len(red_of(app))}")

    touched = {
        "BodyExecutionPolicyTest$Ownership": 6,
        "BodyExecutionPolicyTest$Representability": 7,
        "BodyExecutionPolicyTest$SupportAdmission": 3,
        "BodyExecutionPolicyTest$FailClosedResolution": 9,
        "BodyExecutionPolicyTest$RegistryAuthority": 2,
        "Lfc2ConcreteBodyRoutingDebtFitnessTest": 4,
        "Lfc2ConcreteBodyRoutingDebtFitnessTest$ViolationFixture": 8,
        "Lfc2BodyExecutionPolicyFitnessTest": EXPECTED_FITNESS_ROWS,
        "Lfc2DurableCoordinatorScopeFitnessTest": 4,
    }
    all_rows = domain | arch
    for suffix, expected_tests in touched.items():
        # Exact class-name match: a suffix match would let a nested class stand in for its parent.
        matches = [(name, row) for name, row in all_rows.items() if name.endswith("." + suffix)]
        check(f"{suffix} archived green with {expected_tests} tests",
              len(matches) == 1 and matches[0][1]["tests"] == expected_tests
              and matches[0][1]["failing"] == [],
              f"got={[(n, r['tests'], r['failing']) for n, r in matches]}")

    coord = [row for name, row in app.items() if "CanonicalDurableRunCoordinatorTest" in name]
    check("the coordinator class archives the measured 26 tests / 11 pre-existing reds",
          len(coord) == 1 and (coord[0]["tests"], len(coord[0]["failing"])) == (26, EXPECTED_APP_CLASS[2]),
          f"got={[(r['tests'], len(r['failing'])) for r in coord]}")

    if not BASE_XML.is_file():
        check(f"the base evidence archive exists at {BASE_XML}", False)
        return
    check("the base evidence archive exists", True)
    base_app = red_of(tar_inventory(BASE_XML, "app"))
    base_arch = red_of(tar_inventory(BASE_XML, "arch"))
    head_app = red_of(app)
    head_arch = red_of(arch)

    # Zero new regressions: every class red at head is red at base with exactly the same
    # failing test names, and no base red was silently repaired or dropped from the archive.
    check("no application class regressed: head reds are base reds, name for name",
          head_app == base_app,
          f"new={sorted(set(head_app) - set(base_app))} "
          f"changed={ {c: (head_app[c], base_app.get(c)) for c in set(head_app) & set(base_app) if head_app[c] != base_app[c]} }")
    check("no architecture class regressed: head reds are base reds, name for name",
          head_arch == base_arch,
          f"head={head_arch} base={base_arch}")
    check("the base archive covers every head-red application class",
          set(base_app) == set(head_app), f"missing={sorted(set(head_app) - set(base_app))}")


def check_whole_repo_inventory() -> None:
    """The zero-new-regressions claim over EVERY module, not just the touched ones."""
    print("\n== whole-repo base-vs-head inventory ==")
    if not HEAD_INVENTORY.is_file() or not BASE_INVENTORY.is_file():
        check(f"both suite inventories are archived ({HEAD_INVENTORY.name}, {BASE_INVENTORY.name})", False)
        return
    check("both suite inventories are archived", True)
    head = json.loads(HEAD_INVENTORY.read_text())
    base = json.loads(BASE_INVENTORY.read_text())
    if TARBALL_TOTALS:
        mismatched = {m: (TARBALL_TOTALS[m], (head[m]["tests"], head[m]["failures"], head[m]["errors"]))
                      for m in TARBALL_TOTALS
                      if m in head and TARBALL_TOTALS[m] != (head[m]["tests"], head[m]["failures"], head[m]["errors"])}
        check("the archived inventory agrees with the archived XML for the touched modules",
              mismatched == {}, f"got={mismatched}")
    check("the same modules ran at base and at head", set(head) == set(base),
          f"only_head={sorted(set(head) - set(base))} only_base={sorted(set(base) - set(head))}")

    regressions: dict[str, object] = {}
    delta_offenders: dict[str, tuple[int, int]] = {}
    intended: dict[str, int] = {}
    for module in sorted(set(head) & set(base)):
        h, b = head[module], base[module]
        if h["red"] != b["red"]:
            regressions[module] = {
                "new": sorted(set(h["red"]) - set(b["red"])),
                "repaired": sorted(set(b["red"]) - set(h["red"])),
                "changed": {c: (b["red"][c], h["red"][c]) for c in set(h["red"]) & set(b["red"])
                            if h["red"][c] != b["red"][c]},
            }
        grew = h["tests"] - b["tests"]
        if grew:
            intended[module] = grew
        if grew and EXPECTED_TEST_DELTAS.get(module) != grew:
            delta_offenders[module] = (b["tests"], h["tests"])

    check("no red class appears, disappears or changes failing set in any module",
          regressions == {}, f"got={regressions}")
    check("the only test-count growth is the growth this slice intends",
          intended == EXPECTED_TEST_DELTAS and delta_offenders == {},
          f"intended={intended} offenders={delta_offenders}")
    check("the modules this slice does not touch are identical test-for-test",
          all(head[m]["tests"] == base[m]["tests"] for m in set(head) & set(base)
              if m not in EXPECTED_TEST_DELTAS),
          f"drifted={sorted(m for m in set(head) & set(base) if m not in EXPECTED_TEST_DELTAS and head[m]['tests'] != base[m]['tests'])}")


def run_static_checks() -> None:
    print(f"W1c slice commit: {SLICE_SHA}")
    print(f"Code under verification: {'WORKTREE' if WORKTREE else CODE}")
    print(f"Slice parent (differential): {BASE}")
    check_domain()
    check_coordinator()
    check_behaviour_preservation()
    check_ledger_and_guard()
    check_evidence()
    check_whole_repo_inventory()


# ===== negative controls =====

MUTATIONS: list[tuple[str, str, str, str]] = [
    # (id, file, description, expected failing check fragment)
    (
        "K1", DESC_REGISTRY,
        "catchError declares canonical ownership",
        "legacy body set",
    ),
    (
        "K2", DESC_REGISTRY,
        "the timestamps row is deleted",
        "canonical body set",
    ),
    (
        "K3", DESC_REGISTRY,
        "core.parallel gains a descriptor row",
        "gap families",
    ),
    (
        "K4", COORD,
        "a hard-coded body id allowlist is re-introduced",
        "allowlist is gone",
    ),
    (
        "K5", COORD,
        "the projection switches on the Step identity again",
        "step-identity switch",
    ),
    (
        "K6", DESC_REGISTRY,
        "withCredentials declares Sequential instead of the credential lease",
        "matches its old routing",
    ),
    (
        "K7", LEDGER,
        "source drift AND the pinned ledger edited to match it",
        "re-derived debt total",
    ),
    (
        "K8", POLICY,
        "the engine support admits PARALLEL without implementing it",
        "engine support admits exactly",
    ),
    (
        "K9", DESCRIPTOR,
        "the descriptor default owner becomes legacy",
        "canonical default",
    ),
    (
        "K10", COORD,
        "eligibility compares string names again",
        "typed id",
    ),
]


def mutate(path: str, mutation_id: str) -> str | None:
    """Apply one mutation to one file. Returns the original content, or None if the
    mutation no longer matches the source (a stale fixture must fail, not silently pass)."""
    target = REPO / path
    original = target.read_text(encoding="utf-8")
    text = original
    if mutation_id == "K7":
        # The drift this control exists for is COORDINATED: the source gains a hard-coded
        # allowlist and the ledger is edited to describe it. The verifier's expectations are
        # stated in Python, so the edited pin cannot hide the drift.
        ledger_text = re.sub(r"bodyStepIds = emptySet\(\),", 'bodyStepIds = setOf("core.dir"),', original)
        ledger_text = ledger_text.replace(
            'concreteStepNames = setOf(\n            "core.parallel",\n            "core.retry",\n        ),',
            'concreteStepNames = setOf(\n            "core.parallel",\n            "core.retry",\n            "core.dir",\n        ),',
        )
        ledger_text = ledger_text.replace(
            "        sites = setOf(\n            BodyRoutingSite.DISPATCH_WITH_CREDENTIALS_BLOCK,\n        ),",
            "        sites = setOf(\n            BodyRoutingSite.CANONICAL_BODY_STEP_IDS,\n"
            "            BodyRoutingSite.DISPATCH_WITH_CREDENTIALS_BLOCK,\n        ),",
        )
        target.write_text(ledger_text, encoding="utf-8")
        coord = REPO / COORD
        coord_original = coord.read_text(encoding="utf-8")
        coord.write_text(
            coord_original.replace(
                "private val canonicalBodyStepIds: Set<PluginStepId> =",
                "private val canonicalBodyStepIds: Set<String> = setOf(\"core.dir\")\n"
                "private val unusedBodyStepIds: Set<PluginStepId> =",
            ),
            encoding="utf-8",
        )
        return json.dumps({LEDGER: original, COORD: coord_original})
    if mutation_id == "K1":
        text = text.replace(
            "bodyExecutionOwner = BodyExecutionOwner.LEGACY_LINEAR,\n                ))\n                put(PluginStepId(\"core.warnError\")",
            ")\n                put(PluginStepId(\"core.warnError\")",
        )
    elif mutation_id == "K2":
        text = re.sub(r'put\(PluginStepId\("core\.timestamps"\), StepDescriptor\(.*?\n\s*\)\)\n', "", text, flags=re.S)
    elif mutation_id == "K3":
        marker = '                // Terminal steps (no body)\n'
        row = ('                put(PluginStepId("core.parallel"), StepDescriptor(\n'
               '                    stepId = "core.parallel",\n'
               '                    name = "parallel",\n'
               '                    configRef = "",\n'
               '                    takesBody = true,\n'
               '                    bodyInvocations = BodyInvocationPolicy.ZERO_OR_MORE,\n'
               '                    introducesContext = null,\n'
               '                    bodyExecutionPolicy = BodyExecutionPolicy.Parallel(ParallelPolicy()),\n'
               '                ))\n')
        text = text.replace(marker, row + marker)
    elif mutation_id == "K4":
        text = text.replace(
            "private val canonicalBodyStepIds: Set<PluginStepId> =",
            "private val canonicalBodyStepIds: Set<String> = setOf(\"core.dir\", \"core.retry\")\n"
            "private val unusedBodyStepIds: Set<PluginStepId> =",
        )
    elif mutation_id == "K5":
        text = text.replace(
            "): BodyExecutionProjection = when (policy) {",
            "): BodyExecutionProjection = when (pluginStepId.value) {",
        )
    elif mutation_id == "K6":
        text = text.replace(
            'bodyExecutionPolicy = BodyExecutionPolicy.Scoped(BodyContextProjection.CredentialLease),\n'
            '                ))\n                put(PluginStepId("core.timeout")',
            'bodyExecutionPolicy = BodyExecutionPolicy.Sequential,\n'
            '                ))\n                put(PluginStepId("core.timeout")',
        )
    elif mutation_id == "K7":
        text = text.replace("bodyStepIds = emptySet(),", 'bodyStepIds = setOf("core.withCredentials"),')
    elif mutation_id == "K8":
        text = text.replace(
            "                    BodyExecutionPolicyShape.RETRYING,\n                ),",
            "                    BodyExecutionPolicyShape.RETRYING,\n"
            "                    BodyExecutionPolicyShape.PARALLEL,\n                ),",
        )
    elif mutation_id == "K9":
        text = text.replace(
            "val bodyExecutionOwner: BodyExecutionOwner = BodyExecutionOwner.CANONICAL_ENGINE",
            "val bodyExecutionOwner: BodyExecutionOwner = BodyExecutionOwner.LEGACY_LINEAR",
        )
    elif mutation_id == "K10":
        text = text.replace("pluginStepId !in canonicalBodyStepIds", "pluginStepId.value !in canonicalBodyStepIds")
    target.write_text(text, encoding="utf-8")
    return json.dumps({path: original}) if text != original else None


def run_controls() -> None:
    global CODE, WORKTREE
    print("== negative controls (must fail for the intended law) ==")
    dirty = [ln for ln in git("status", "--porcelain").splitlines()
             if ln.strip() and EVIDENCE_PREFIX not in ln and RECEIPT not in ln]
    if dirty:
        print(f"  refusing to run: the tree is not pristine:\n    " + "\n    ".join(dirty))
        sys.exit(2)

    CODE = "WORKTREE"
    WORKTREE = True
    results: list[tuple[str, str, bool, str]] = []
    for mutation_id, path, description, expected in MUTATIONS:
        original = mutate(path, mutation_id)
        if original is None:
            results.append((mutation_id, description, False, "MUTATION NOT APPLIED (stale fixture)"))
            continue
        try:
            failures.clear()
            run_static_checks_quiet()
            hit = any(expected in f for f in failures)
            results.append((mutation_id, description, hit, "; ".join(failures[:3]) or "no failure"))
        finally:
            for mutated_path, content in json.loads(original).items():
                (REPO / mutated_path).write_text(content, encoding="utf-8")
    print()
    red = 0
    for mutation_id, description, hit, detail in results:
        print(f"  [{'RED' if hit else 'GREEN'}] {mutation_id} {description}")
        if not hit:
            print(f"        expected a failure mentioning: {detail}")
        red += 1 if hit else 0
    print(f"\n{red}/{len(MUTATIONS)} controls red for the intended law")
    if red != len(MUTATIONS):
        sys.exit(1)


def run_static_checks_quiet() -> None:
    """The static checks without the banner, for control runs."""
    import io
    from contextlib import redirect_stdout
    buf = io.StringIO()
    with redirect_stdout(buf):
        check_domain()
        check_coordinator()
        check_behaviour_preservation()
        check_ledger_and_guard()


def main() -> None:
    if "--controls" in sys.argv:
        run_controls()
        return
    run_static_checks()
    print(f"\n{checks - len(failures)}/{checks} checks passed")
    if failures:
        print("FAILURES:")
        for f in failures:
            print(f"  - {f}")
        sys.exit(1)
    print("W1c receipt verified")


if __name__ == "__main__":
    main()
