#!/usr/bin/env python3
"""Verify the B10/W1b receipt (docs/v2/07-uat/B10_W1B_BODY_EXECUTION_POLICY_RECEIPT.md).

HISTORICAL, not working-tree. Every code claim is read from a git commit via
`git show` (`W1B_CODE`, default the W1b slice commit), so the verifier keeps
asserting what W1b actually shipped after later slices edit these files. This is the
W0/W1a lesson applied: a verifier that reads the working tree expires the moment the
decision it guards is taken.

INDEPENDENT, not transcribed. The declared body execution policies are re-parsed from
the descriptor registry in Python and compared against an expectation table stated
here, and the coordinator's concrete routing debt is re-scanned in Python and compared
against the inventory W1a MEASURED (hard-coded, not read from the pinned ledger). A pin
edited to match a drifted source is therefore caught by the re-scan (control C11).

Usage:
  python3 verify-b10-w1b-receipt.py                # static + evidence checks
  W1B_CODE=WORKTREE python3 verify-b10-w1b-receipt.py   # read the working tree (controls)
  python3 verify-b10-w1b-receipt.py --controls     # run the negative controls

Env: W1B_CODE, W1B_BASE, W1B_TAR.
"""

from __future__ import annotations

import html
import os
import re
import subprocess
import sys
import tarfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[4]

BASE = os.environ.get("W1B_BASE", "5168a064")
RECEIPT = "docs/v2/07-uat/B10_W1B_BODY_EXECUTION_POLICY_RECEIPT.md"


def _resolve_slice() -> str:
    """The slice commit is the commit that introduced the receipt. Resolved, never
    hard-coded: a constant here would have to be edited (and re-committed) every time the
    slice commit is amended, which is the circularity this avoids. Still historical:
    later slices edit other files and cannot move this anchor."""
    proc = subprocess.run(
        ["git", "-C", str(REPO), "log", "-1", "--format=%H", "--", RECEIPT],
        capture_output=True, text=True, check=False,
    )
    sha = proc.stdout.strip()
    if not sha:
        raise SystemExit(f"cannot resolve the W1b slice commit from {RECEIPT}")
    return sha


SLICE_SHA = _resolve_slice()
CODE = os.environ.get("W1B_CODE") or SLICE_SHA
WORKTREE = CODE.upper() == "WORKTREE"

DOMAIN = "v2/pipeline-domain"
ARCH = "v2/pipeline-architecture-tests"
APP = "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application"
COORD = f"{APP}/durable/CanonicalDurableRunCoordinator.kt"
DECODER = f"{APP}/CanonicalCoreStepDecoder.kt"
POLICY = f"{DOMAIN}/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyExecutionPolicy.kt"
DESCRIPTOR = f"{DOMAIN}/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/StepDescriptor.kt"
DESC_REGISTRY = f"{DOMAIN}/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/StepDescriptorRegistry.kt"
LEDGER = f"{ARCH}/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/ConcreteBodyRoutingDebt.kt"
GUARD = f"{ARCH}/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/Lfc2ConcreteBodyRoutingDebtFitnessTest.kt"
FITNESS = f"{ARCH}/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/Lfc2BodyExecutionPolicyFitnessTest.kt"
DOMAIN_TEST = f"{DOMAIN}/src/test/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyExecutionPolicyTest.kt"
METADATA_REGRESSION_TEST = (f"{DOMAIN}/src/test/kotlin/dev/rubentxu/pipeline/v2/domain/"
                            "StepDescriptorBodyMetadataTest.kt")
EVIDENCE_PREFIX = "docs/v2/07-uat/evidence/b10-w1b/"
TESTING_STATE = ".agent/TESTING-STATE.md"

TAR = Path(os.environ.get("W1B_TAR", HERE / "raw/xml/module-suites-xml.tar.gz"))
W1A_TAR = REPO / "docs/v2/07-uat/evidence/b10-w1a/raw/xml/architecture-suite-xml.tar.gz"
LANE_R_BASE_XML = (
    REPO / "docs/v2/07-uat/evidence/lane-r/raw/xml/module-suites/base-module-suites/"
    "TEST-dev.rubentxu.pipeline.v2.architecture.Lfc0GlobalStateFitnessTest.xml"
)

# ===== expectations stated here, independently of the implementation =====

EXPECTED_CODE_FILES = {POLICY, DESCRIPTOR, DESC_REGISTRY, DOMAIN_TEST, METADATA_REGRESSION_TEST, FITNESS}

# Every descriptor row and the body execution policy it must declare.
EXPECTED_ROWS: dict[str, str] = {
    "core.catchError": "Sequential",
    "core.warnError": "Sequential",
    "core.withEnv": "Scoped(Environment)",
    "core.dir": "Scoped(WorkingDirectory)",
    "core.withCredentials": "Scoped(CredentialLease)",
    "core.timeout": "Scoped(Deadline)",
    "core.retry": "Retrying",
    "core.emit.event": "Sequential",
    "core.sh": "Sequential",
    "core.echo": "Sequential",
    "core.sleep": "Sequential",
    "core.file.writeFile": "Sequential",
}

# Body-bearing families the model can express but that have no descriptor row yet.
DECLARED_GAP: set[str] = {"core.timestamps", "core.parallel"}

PROJECTION_CONTEXT_KIND = {
    "WorkingDirectory": "CWD",
    "Environment": "ENVIRONMENT",
    "CredentialLease": "CREDENTIALS",
    "Deadline": "CANCELLATION",
    "Timestamps": None,  # no ContextKind enumerates a timestamp source
}

# The coordinator debt W1a MEASURED at 1afb4799. Deliberately NOT read from the ledger.
MEASURED_CONCRETE_STEP_NAMES = {
    "core.dir", "core.timeout", "core.retry", "core.withCredentials",
    "core.timestamps", "core.withEnv", "core.parallel",
}
MEASURED_BODY_STEP_IDS = {
    "core.dir", "core.timeout", "core.retry",
    "core.withCredentials", "core.timestamps", "core.withEnv",
}
MEASURED_SITES = {"CANONICAL_BODY_STEP_IDS", "PROJECT_SHELL_SCOPE", "DISPATCH_WITH_CREDENTIALS_BLOCK"}
MEASURED_BLOCK_BYPASSES = {"dispatchWithCredentialsBlock"}
MEASURED_STEP_ID_SWITCHES = 1
MEASURED_TOTAL = 18
HISTORICAL_CEILING = 18

MEASURED_DOMAIN_SUITE = (388, 0)
MEASURED_ARCH_SUITE = (261, 1)
MEASURED_FITNESS_ROWS = 9
MEASURED_DOMAIN_TEST_ROWS = 20

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
    """Read a file as of a commit (never the working tree unless CODE=WORKTREE)."""
    if WORKTREE and ref is None:
        p = REPO / path
        return p.read_text(encoding="utf-8") if p.is_file() else ""
    return git("show", f"{ref or CODE}:{path}")


def changed_files() -> set[str]:
    if WORKTREE:
        out = git("status", "--porcelain")
        return {ln[3:].strip() for ln in out.splitlines() if ln.strip()}
    out = git("diff", "--name-only", f"{BASE}..{CODE}")
    return {ln.strip() for ln in out.splitlines() if ln.strip()}


def added_files() -> set[str]:
    if WORKTREE:
        out = git("status", "--porcelain")
        return {ln[3:].strip() for ln in out.splitlines() if ln.startswith("A") or ln.startswith("??")}
    out = git("diff", "--name-status", f"{BASE}..{CODE}")
    return {ln.split("\t", 1)[1].strip() for ln in out.splitlines() if ln.startswith("A\t")}


def dirty_files() -> set[str]:
    """Files differing from the checked-out commit. Used by the control harness, which
    must measure the tree, not the committed slice diff.

    The slice's own evidence and receipt are excluded: at control time they are
    uncommitted harness artifacts, not mutations under test.
    """
    out = git("status", "--porcelain")
    files = {ln[3:].strip() for ln in out.splitlines() if ln.strip()}
    return {f for f in files if not f.startswith(EVIDENCE_PREFIX) and f != RECEIPT}


def code_only(text: str) -> str:
    """Comment-blind view: prose may name steps, code may not."""
    return "\n".join(
        ln for ln in text.splitlines()
        if not ln.lstrip().startswith("//") and not ln.lstrip().startswith("*")
    )


# ===== independent re-derivations =====

CORE_LITERAL = re.compile(r'"(?:core|example)\.[A-Za-z0-9_.]+"')
STEP_KEY_SWITCH = re.compile(r"when\s*\(\s*[A-Za-z0-9_.]*([Ss]tepId|stepName|stepKey)[A-Za-z0-9_.]*\s*\)")
ELSE_BRANCH = re.compile(r"else\s*->")
BLOCK_DISPATCH = re.compile(r"\bdispatch[A-Za-z0-9_]*Block\b")
BODY_IDS_BLOCK = re.compile(r"canonicalBodyStepIds\s*:\s*Set<String>\s*=\s*setOf\(([^)]*)\)")
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
    rows: dict[str, dict] = {}
    for key, body in PUT_ROW.findall(source):
        def field(pattern: str, default: str = "") -> str:
            m = re.search(pattern, body)
            return m.group(1) if m else default

        context = field(r"introducesContext\s*=\s*ContextKind\.(\w+)", "NONE")
        if re.search(r"introducesContext\s*=\s*null", body):
            context = "NONE"
        rows[key] = {
            "takesBody": field(r"takesBody\s*=\s*(true|false)", "false") == "true",
            "bodyInvocations": field(r"bodyInvocations\s*=\s*BodyInvocationPolicy\.(\w+)", "ONCE"),
            "introducesContext": context,
            "policy": parse_policy(field(r"bodyExecutionPolicy\s*=\s*([^\n,]+)", "BodyExecutionPolicy.Sequential")),
        }
    return rows


def rescan_debt(source: str) -> dict:
    """Python implementation of the W1a scanner rules, over the coordinator source."""
    block = BODY_IDS_BLOCK.search(source)
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
    }


def tar_rows(tar: Path) -> list[tuple[str, tuple]]:
    """(basename, (tests, skipped, failures, errors)) for every archived XML."""
    rows = []
    with tarfile.open(tar, "r:gz") as t:
        for m in t.getmembers():
            if not m.name.endswith(".xml"):
                continue
            text = t.extractfile(m).read().decode("utf-8", "replace")
            r = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', text)
            if r:
                rows.append((m.name.split("/")[-1], r.groups()))
    return rows


def suite_class_xml(tar: Path, cls: str) -> str:
    with tarfile.open(tar, "r:gz") as t:
        for m in t.getmembers():
            if cls in m.name and m.name.endswith(".xml"):
                return t.extractfile(m).read().decode("utf-8", "replace")
    return ""


def failure_message(text: str) -> str:
    m = re.search(r'<failure[^>]*message="([^"]*)"', text)
    if not m:
        return ""
    return re.sub(r"/var/home/\S*/pipeline-[A-Za-z0-9-]+/", "<ROOT>/", html.unescape(m.group(1)))


def verify() -> None:
    print(f"B10/W1b receipt verifier -- code {CODE} (base {BASE})")

    # ---- scope ---------------------------------------------------------
    print("\n[scope]")
    changed = changed_files()
    code_and_tests = {f for f in changed if not f.startswith("docs/") and f != TESTING_STATE}
    check("the slice changed exactly the expected code and test files",
          code_and_tests == EXPECTED_CODE_FILES,
          f"unexpected={sorted(code_and_tests - EXPECTED_CODE_FILES)} missing={sorted(EXPECTED_CODE_FILES - code_and_tests)}")
    check("the coordinator is untouched by W1b", COORD not in changed)
    check("the legacy plugin-id table is untouched", DECODER not in changed)
    check("no build file changed", not [f for f in changed if f.endswith(".gradle.kts") or "/gradle/" in f])
    check("the W1a ledger and guard are untouched", LEDGER not in changed and GUARD not in changed)
    docs_changes = {f for f in changed if f.startswith("docs/")}
    check("the slice records its testing state", TESTING_STATE in changed)
    check("every docs change belongs to this slice's evidence or receipt",
          all(f == RECEIPT or f.startswith(EVIDENCE_PREFIX) for f in docs_changes), f"{sorted(docs_changes)}")
    check("exactly one production file is added: the policy module",
          {f for f in added_files() if "/src/main/" in f} == {POLICY},
          f"added={sorted(added_files())}")

    # ---- policy vocabulary ---------------------------------------------
    print("\n[vocabulary]")
    policy_src = show(POLICY)
    policy_code = code_only(policy_src)
    check("the policy module exists at CODE", policy_src.strip() != "")

    leaked = CORE_LITERAL.findall(policy_code)
    check("the policy vocabulary contains no step-name literal", leaked == [], f"{leaked}")

    switch = STEP_KEY_SWITCH.search(policy_code)
    check("the policy vocabulary contains no step-key switch", switch is None, f"{switch.group(0) if switch else ''}")

    check("the policy vocabulary has no else branch", not ELSE_BRANCH.search(policy_code))

    forbidden_imports = ["dev.rubentxu.pipeline.v2.application", "kotlinx.coroutines", "java.io.",
                         "java.nio.", "java.util.concurrent"]
    imports = [ln for ln in policy_code.splitlines() if ln.strip().startswith("import ")]
    offenders = [ln for ln in imports if any(f in ln for f in forbidden_imports)]
    check("the decision layer imports nothing outward and no coroutine machinery", offenders == [], f"{offenders}")

    shapes = set(re.findall(r"^\s{4}([A-Z][A-Z_]+),\s*$", policy_src, re.M))
    check("the closed shape family has exactly four members",
          shapes == {"SEQUENTIAL", "SCOPED", "RETRYING", "PARALLEL"}, f"{sorted(shapes)}")

    projections = set(re.findall(r"data object (\w+) : BodyContextProjection", policy_src))
    check("the projection family has exactly five members",
          projections == {"WorkingDirectory", "Environment", "Timestamps", "Deadline", "CredentialLease"},
          f"{sorted(projections)}")

    rejection = re.search(r"sealed interface BodyPolicyRejection\b(.*?)^\}", policy_src, re.S | re.M)
    flat = set(re.findall(r"data (?:class|object) (\w+)\s*[(:]", rejection.group(1) if rejection else ""))
    check("the rejection algebra has exactly four cases",
          flat == {"UnknownStep", "NotABodyStep", "IncoherentMetadata", "UnsupportedByEngine"}, f"{sorted(flat)}")

    check("RetryPolicy's default key is a segment key, not a step name",
          'PluginStepId("retry-attempt")' in policy_src and '"core.retry"' not in policy_code)
    check("ParallelPolicy's default key is a segment key, not a step name",
          'PluginStepId("parallel-branch")' in policy_src)
    check("resolution reads the registry contract descriptor, not a name table",
          "registry.definition(key)?.contract?.descriptor" in policy_src)
    check("the resolution result is a closed two-case algebra",
          "sealed interface BodyPolicyResolution" in policy_src
          and "data class Resolved(" in policy_src and "data class Rejected(" in policy_src)
    check("engine admission is over the closed shape family",
          "fun supports(policy: BodyExecutionPolicy): Boolean = policy.shape in shapes" in policy_src)

    # ---- descriptor declarations ---------------------------------------
    print("\n[declaration]")
    registry_src = show(DESC_REGISTRY)
    rows = parse_registry(registry_src)
    check("every expected descriptor row is present", set(rows) == set(EXPECTED_ROWS),
          f"missing={sorted(set(EXPECTED_ROWS) - set(rows))} extra={sorted(set(rows) - set(EXPECTED_ROWS))}")

    mismatched = {k: (rows[k]["policy"], v) for k, v in EXPECTED_ROWS.items() if k in rows and rows[k]["policy"] != v}
    check("every descriptor row declares the expected body execution policy", mismatched == {}, f"{mismatched}")

    check("the two unregistered body families are still the declared gap",
          not (DECLARED_GAP & set(rows)), f"{sorted(DECLARED_GAP & set(rows))}")

    incoherent = []
    for key, row in rows.items():
        policy, invocations, context = row["policy"], row["bodyInvocations"], row["introducesContext"]
        if policy == "Retrying" and invocations != "ZERO_OR_MORE":
            incoherent.append(f"{key}: Retrying with {invocations}")
        if invocations == "ZERO_OR_MORE" and policy not in ("Retrying", "Parallel"):
            incoherent.append(f"{key}: ZERO_OR_MORE with {policy}")
        m = re.match(r"Scoped\((\w+)\)", policy)
        if m:
            required = PROJECTION_CONTEXT_KIND[m.group(1)]
            if required is not None and context != required:
                incoherent.append(f"{key}: {policy} with introducesContext={context}")
    check("every declaration is coherent with its own metadata", incoherent == [], f"{incoherent}")

    non_body = [k for k, r in rows.items() if not r["takesBody"] and r["policy"] != "Sequential"]
    check("no body-less row declares an execution reshape", non_body == [], f"{non_body}")
    check("the descriptor default is the sequential policy",
          "val bodyExecutionPolicy: BodyExecutionPolicy = BodyExecutionPolicy.DEFAULT," in show(DESCRIPTOR))
    check("the registry exposes no step-name-keyed policy authority",
          "fun policyFor" not in registry_src and "Map<String, BodyExecutionPolicy>" not in registry_src)

    # ---- debt neutrality ------------------------------------------------
    print("\n[debt]")
    debt = rescan_debt(show(COORD))
    check("the coordinator still carries exactly the W1a-measured debt",
          debt["concreteStepNames"] == MEASURED_CONCRETE_STEP_NAMES, f"{sorted(debt['concreteStepNames'])}")
    check("the body step-id inventory is unchanged", debt["bodyStepIds"] == MEASURED_BODY_STEP_IDS,
          f"{sorted(debt['bodyStepIds'])}")
    check("the routing site inventory is unchanged", debt["sites"] == MEASURED_SITES, f"{sorted(debt['sites'])}")
    check("the dispatch bypass inventory is unchanged", debt["blockBypasses"] == MEASURED_BLOCK_BYPASSES,
          f"{sorted(debt['blockBypasses'])}")
    check("the step-id switch count is unchanged", debt["stepIdSwitches"] == MEASURED_STEP_ID_SWITCHES,
          f"{debt['stepIdSwitches']}")
    total = (len(debt["concreteStepNames"]) + len(debt["bodyStepIds"]) + len(debt["sites"])
             + len(debt["blockBypasses"]) + debt["stepIdSwitches"])
    check("the re-scanned debt total equals the W1a high-water mark", total == MEASURED_TOTAL, f"{total}")

    check("the pinned ledger still records the historical ceiling",
          re.search(rf"HISTORICAL_CEILING\s*=\s*{HISTORICAL_CEILING}\b", show(LEDGER)) is not None,
          "ceiling drifted")
    check("the coordinator does not yet resolve body policies",
          not any(t in code_only(show(COORD))
                  for t in ("BodyExecutionPolicy", "BodyPolicyResolver", "resolveBodyExecutionPolicy")))

    # ---- test evidence --------------------------------------------------
    print("\n[evidence]")
    if not TAR.is_file():
        check("the module suite XML archive exists", False, str(TAR))
        return

    rows_xml = tar_rows(TAR)
    domain_rows = [r for r in rows_xml if r[0].startswith("TEST-dev.rubentxu.pipeline.v2.domain.")]
    arch_rows = [r for r in rows_xml if r[0].startswith("TEST-dev.rubentxu.pipeline.v2.architecture.")]
    check("both module suites are archived", len(domain_rows) > 0 and len(arch_rows) > 0,
          f"domain={len(domain_rows)} arch={len(arch_rows)}")

    d = (sum(int(r[1][0]) for r in domain_rows), sum(int(r[1][2]) + int(r[1][3]) for r in domain_rows))
    check(f"the pipeline-domain suite is {MEASURED_DOMAIN_SUITE[0]} tests / {MEASURED_DOMAIN_SUITE[1]} failures",
          d == MEASURED_DOMAIN_SUITE, f"{d}")

    a = (sum(int(r[1][0]) for r in arch_rows), sum(int(r[1][2]) + int(r[1][3]) for r in arch_rows))
    check(f"the architecture suite is {MEASURED_ARCH_SUITE[0]} tests / {MEASURED_ARCH_SUITE[1]} failure",
          a == MEASURED_ARCH_SUITE, f"{a}")

    reds = [n for n, g in arch_rows if int(g[2]) + int(g[3]) > 0]
    check("the single red is the known Lfc0GlobalStateFitnessTest",
          reds == ["TEST-dev.rubentxu.pipeline.v2.architecture.Lfc0GlobalStateFitnessTest.xml"], f"{reds}")

    if LANE_R_BASE_XML.is_file():
        base_msg = failure_message(LANE_R_BASE_XML.read_text(encoding="utf-8"))
        check("the pre-existing failure is byte-identical to the Lane R base after checkout-path normalisation",
              base_msg != "" and base_msg == failure_message(suite_class_xml(TAR, "Lfc0GlobalStateFitnessTest")))

    if W1A_TAR.is_file():
        w1a = tar_rows(W1A_TAR)
        w1a_tests = sum(int(r[1][0]) for r in w1a)
        w1a_bad = sum(int(r[1][2]) + int(r[1][3]) for r in w1a)
        check("the architecture delta over the W1a baseline is exactly +9 (the new laws)",
              a[0] - w1a_tests == MEASURED_FITNESS_ROWS, f"{w1a_tests} -> {a[0]}")
        check("the pre-existing red count did not change", (w1a_bad, a[1]) == (1, 1), f"{w1a_bad} -> {a[1]}")

    new_fitness = [r for r in arch_rows if "Lfc2BodyExecutionPolicyFitnessTest" in r[0]]
    check(f"the W1b fitness laws ran {MEASURED_FITNESS_ROWS} rows green",
          len(new_fitness) == 1 and int(new_fitness[0][1][0]) == MEASURED_FITNESS_ROWS
          and new_fitness[0][1][2:] == ("0", "0"), f"{new_fitness}")

    new_domain = [r for r in domain_rows if "BodyExecutionPolicyTest" in r[0]]
    check(f"the W1b domain tests ran {MEASURED_DOMAIN_TEST_ROWS} rows green",
          sum(int(r[1][0]) for r in new_domain) == MEASURED_DOMAIN_TEST_ROWS
          and all(r[1][2:] == ("0", "0") for r in new_domain), f"{[(r[0], r[1]) for r in new_domain]}")

    # ---- receipt --------------------------------------------------------
    print("\n[receipt]")
    receipt = show(RECEIPT)
    check("the receipt exists at CODE", receipt.strip() != "")
    check("the receipt names the code-under-test commit", BASE in receipt, BASE)
    check("the receipt records the debt total and the ceiling", "18" in receipt and "ceiling" in receipt.lower())


# ===== controls =====

RESULTS: list[tuple[str, str, str, str, str]] = []
LOG = Path(os.environ.get("W1B_CONTROL_LOG", "/tmp/w1b-control-gradle.log"))


def _run_kotlin(module: str, pattern: str) -> tuple[str, str]:
    project = module.split("/")[-1]
    results = REPO / f"v2/{project}/build/test-results/test"
    if results.is_dir():
        for f in results.glob("TEST-*.xml"):
            f.unlink()
    proc = subprocess.run(
        ["./v2/gradlew", "-p", "v2", "--no-build-cache", f":{project}:test", "--tests", pattern],
        cwd=str(REPO), capture_output=True, text=True, check=False, timeout=1800,
    )
    combined = (proc.stdout or "") + (proc.stderr or "")
    LOG.write_text(combined, encoding="utf-8")
    xmls = sorted(results.glob("TEST-*.xml")) if results.is_dir() else []
    if not xmls and (re.search(r"^e: ", combined, re.M) or "Compilation error" in combined):
        return "COMPILE_ERROR", "kotlin compilation failed (not a valid RED)"
    if not xmls and "FAILURE: Build failed" in combined:
        return "INFRA_ERROR", combined.strip().splitlines()[-1][:200]
    if not xmls:
        return "INFRA_ERROR", "no XML regenerated (canary)"
    messages, total = [], 0
    for f in xmls:
        text = f.read_text(encoding="utf-8", errors="replace")
        r = re.search(r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', text)
        if not r:
            continue
        total += int(r.group(1))
        if int(r.group(2)) + int(r.group(3)) > 0:
            messages.append(failure_message(text))
    if total == 0:
        return "INFRA_ERROR", combined[-400:]
    return ("RED", " | ".join(messages)) if messages else ("GREEN", "")


def _run_verifier() -> tuple[str, str]:
    env = dict(os.environ, W1B_CODE="WORKTREE")
    proc = subprocess.run([sys.executable, str(Path(__file__).resolve())],
                          capture_output=True, text=True, check=False, env=env)
    bad = [ln.strip() for ln in proc.stdout.splitlines() if "[FAIL]" in ln]
    return ("RED", " | ".join(bad)) if bad else ("GREEN", "")


def controls() -> None:
    """Each control runs independently from the pristine slice commit."""
    pristine = SLICE_SHA

    def reset() -> None:
        subprocess.run(["git", "-C", str(REPO), "reset", "--hard", pristine],
                       capture_output=True, text=True, check=False)

    def append(path: str, text: str) -> None:
        p = REPO / path
        p.write_text(p.read_text(encoding="utf-8") + "\n" + text + "\n", encoding="utf-8")

    def patch(path: str, old: str, new: str, count: int = 1) -> None:
        p = REPO / path
        text = p.read_text(encoding="utf-8")
        if old not in text:
            raise SystemExit(f"anchor not found in {path}: {old[:70]!r}")
        p.write_text(text.replace(old, new, count if count >= 0 else -1), encoding="utf-8")

    def run(name: str, target: str, expected: str, mutate, kotlin=None, verifier=False) -> None:
        print(f"\n[{name}] target={target}")
        reset()
        mutate()
        touched = dirty_files()
        print(f"  touched: {sorted(touched)}")
        if verifier:
            observed, detail = _run_verifier()
        else:
            observed, detail = _run_kotlin(*kotlin)
        reset()
        clean = dirty_files() == set()
        print(f"  reset clean: {clean}")
        print(f"  observed: {observed}")
        print(f"  reason: {detail[:400]}")
        matched = expected in detail and observed == "RED"
        print(f"  reason matches the intended law: {matched}")
        RESULTS.append((name, target, expected, observed, f"{'OK' if matched else 'UNMATCHED'} {detail[:180]}"))
        assert clean, f"{name} left the tree dirty"

    # C1 — a step-name literal in the policy vocabulary.
    run("C1", "policy vocabulary", "must name execution shapes, not Steps",
        lambda: append(POLICY, 'internal val LEAKED_STEP: String = "core.dir"'),
        kotlin=(ARCH, "Lfc2BodyExecutionPolicyFitnessTest*"))

    # C2 — a step-key switch. Compiles and is exhaustive, so the only law it can trip is
    # the switch detector itself; C3 isolates the else-branch law separately.
    run("C2", "policy vocabulary", "not a switch over a StepKey or step name",
        lambda: append(POLICY, "internal fun leak(stepName: Boolean): Int = when (stepName) {\n"
                               "    true -> 1\n"
                               "    false -> 0\n"
                               "}"),
        kotlin=(ARCH, "Lfc2BodyExecutionPolicyFitnessTest*"))

    # C3 — an else branch hiding an unhandled case.
    run("C3", "policy vocabulary", "else branch would silently absorb",
        lambda: append(POLICY, 'internal fun leakElse(x: Int): String = when { x > 0 -> "a" else -> "b" }'),
        kotlin=(ARCH, "Lfc2BodyExecutionPolicyFitnessTest*"))

    # C4 — an outward/JDK import in the decision layer.
    def c4() -> None:
        patch(POLICY, "import kotlinx.serialization.Serializable",
              "import kotlinx.serialization.Serializable\nimport java.nio.file.Path")
        append(POLICY, "internal val LEAKED_ROOT: Path? = null")

    run("C4", "policy vocabulary", "no application, coroutines, or I/O",
        c4, kotlin=(ARCH, "Lfc2BodyExecutionPolicyFitnessTest*"))

    # C5 — a descriptor declaration that disagrees with the live routing semantics.
    run("C5", "descriptor declaration", "disagrees with its live routing",
        lambda: patch(DESC_REGISTRY,
                      "bodyExecutionPolicy = BodyExecutionPolicy.Scoped(BodyContextProjection.WorkingDirectory),",
                      "bodyExecutionPolicy = BodyExecutionPolicy.Sequential,"),
        kotlin=(DOMAIN, "BodyExecutionPolicyTest*"))

    # C6 — Retrying without repeat cardinality.
    run("C6", "descriptor coherence", "must declare a policy that is coherent",
        lambda: patch(DESC_REGISTRY,
                      "bodyInvocations = BodyInvocationPolicy.ZERO_OR_MORE,\n                    introducesContext = null,",
                      "bodyInvocations = BodyInvocationPolicy.ONCE,\n                    introducesContext = null,"),
        kotlin=(ARCH, "Lfc2BodyExecutionPolicyFitnessTest*"))

    # C7 — a projected scope that contradicts the declared context kind.
    run("C7", "descriptor coherence", "must declare a policy that is coherent",
        lambda: patch(DESC_REGISTRY,
                      "bodyExecutionPolicy = BodyExecutionPolicy.Scoped(BodyContextProjection.WorkingDirectory),",
                      "bodyExecutionPolicy = BodyExecutionPolicy.Scoped(BodyContextProjection.Environment),"),
        kotlin=(ARCH, "Lfc2BodyExecutionPolicyFitnessTest*"))

    # C8 — a body-less row declaring an execution reshape.
    run("C8", "descriptor coherence", "must not declare a body execution shape",
        lambda: patch(DESC_REGISTRY,
                      'stepId = "core.sh",\n                    name = "sh",\n                    configRef = "",\n                    takesBody = false,',
                      'stepId = "core.sh",\n                    name = "sh",\n                    configRef = "",\n'
                      '                    takesBody = false,\n'
                      '                    bodyExecutionPolicy = BodyExecutionPolicy.Scoped(BodyContextProjection.Environment),'),
        kotlin=(ARCH, "Lfc2BodyExecutionPolicyFitnessTest*"))

    # C9 — the descriptor default stops being sequential.
    run("C9", "descriptor default", "must preserve existing behaviour",
        lambda: patch(DESCRIPTOR,
                      "val bodyExecutionPolicy: BodyExecutionPolicy = BodyExecutionPolicy.DEFAULT,",
                      "val bodyExecutionPolicy: BodyExecutionPolicy =\n        dev.rubentxu.pipeline.v2.domain.step.BodyContextProjection.Deadline.let {\n            BodyExecutionPolicy.Scoped(it)\n        },"),
        kotlin=(ARCH, "Lfc2BodyExecutionPolicyFitnessTest*"))

    # C10 — the coordinator starts resolving policies, i.e. W1c work in a W1b slice.
    run("C10", "scope firewall", "routing bodies through it is W1c",
        lambda: patch(COORD, "private fun BlockStepNode.projectShellScope(",
                      "private val policyProbe: dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolver? = null\n\n"
                      "private fun BlockStepNode.projectShellScope("),
        kotlin=(ARCH, "Lfc2BodyExecutionPolicyFitnessTest*"))

    # C11 — ISOLATING CONTROL for the independent re-scan: rename a concrete step
    # literal in the coordinator AND in the pinned ledger, so source and pin agree
    # with each other (every Kotlin law stays green) while the historical inventory
    # W1a measured no longer matches. Only the Python re-scan can catch this.
    def c11() -> None:
        patch(COORD, '"core.timestamps"', '"core.xtra"', count=-1)
        patch(LEDGER, '"core.timestamps"', '"core.xtra"', count=-1)

    print("\n[C11] isolating control: source and pin agree, historical measurement does not")
    reset()
    c11()
    print(f"  touched: {sorted(dirty_files())}")
    kotlin_state, kotlin_detail = _run_kotlin(ARCH, "Lfc2ConcreteBodyRoutingDebtFitnessTest*")
    print(f"  W1a ledger laws: {kotlin_state} {kotlin_detail[:200]}")
    verify_state, verify_detail = _run_verifier()
    print(f"  verifier: {verify_state}")
    print(f"  reason: {verify_detail[:600]}")
    reset()
    print(f"  reset clean: {dirty_files() == set()}")
    RESULTS.append(("C11", "independent re-scan", "W1a-measured debt",
                    verify_state, f"{'OK' if 'W1a-measured debt' in verify_detail else 'UNMATCHED'} {verify_detail[:180]}"))
    print(f"  reason matches the intended law: {'W1a-measured debt' in verify_detail}")

    print("\n=== control summary ===")
    unmatched = [r for r in RESULTS if not r[4].startswith("OK")]
    for name, target, expected, observed, verdict in RESULTS:
        print(f"{name:>4}  {observed:>13}  {'OK ' if verdict.startswith('OK') else 'MISS'}  expected={expected}")
    print(f"\ncontrols that fail for the intended law: {len(RESULTS) - len(unmatched)}/{len(RESULTS)}")
    if unmatched:
        for r in unmatched:
            print(f"  MISS {r[0]}: expected {r[1]!r}; got {r[4][:120]}")
        raise SystemExit(1)

    verifier_reds = [r for r in RESULTS if r[0] == "C11" and r[3] == "RED"]
    print(f"\ncontrols that make the verifier itself fail: {len(verifier_reds)}/1 (C11)")
    print("C11 is the isolating control: the Kotlin ledger is satisfied because source and pin")
    print("agree, and only the re-scan against W1a's measured inventory fails. That is what makes")
    print("the re-scan an authority rather than a mirror of the pin.")


if __name__ == "__main__":
    if "--controls" in sys.argv:
        controls()
    else:
        verify()
        print()
        if failures:
            print(f"RESULT: FAIL ({len(failures)}/{checks} checks failed)")
            for f in failures:
                print(f"  - {f}")
            sys.exit(1)
        print(f"RESULT: PASS ({checks}/{checks} checks)")
