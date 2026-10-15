#!/usr/bin/env python3
"""Generate and verify the test-harness catalogue (mandate B1.3/B1.4/B1.5).

Why this exists
---------------
The harness governs tests through several authorities that each own one question:
`plan-affected-tests.py` answers "what does this change affect", `justfile` holds
the gate ladder and its budgets, `fitness-harness-coverage.py` holds the
tool <-> suite registry, `run-integration-gate.py` emits the typed receipt. Those
answers were only discoverable by reading each artifact, and nothing noticed when
one of them drifted from the others (a module without an impact mapping, a gate
whose budget moved, the UAT frontier list changing in the justfile only).

This generator writes ONE catalogue of those answers to `v2/test-harness/` and
fails closed on the drifts it can see. It does not implement selection: it calls
the existing authorities through their CLI (the planner) and through `just`
(introspection), so there is no second engine to keep in sync.

Rules (fail-closed; each is a violation, not a warning)
------------------------------------------------------
  V1  every module with production code under `src/main` has a non-empty impact
      plan (the criterion that decides what runs when it changes).
  V2  every role resolves to exactly one existing file, and no two roles share a
      file; a new file that claims a role name outside the declared authority is
      a second engine and fails.
  V3  every declared gate exists as a `just` recipe, and the catalogue's budget
      equals the recipe's default budget.
  V4  the UAT frontier declared in the catalogue equals `just --evaluate
      uat_excludes` (the justfile is the single source).
  V5  every python suite under `scripts/` exists and declares at least one test.
  V6  no gate recipe nests Gradle: at most one `gradlew` invocation per recipe
      (one graph per phase), which `just --show` makes checkable.
  V7  a module with a non-default fork policy (forkEvery / maxParallelForks)
      declares it in JUSTIFIED_FORK_POLICY with the reason it is needed.

Usage:
    python3 scripts/gen-test-harness-catalogue.py            # write the catalogue
    python3 scripts/gen-test-harness-catalogue.py --check     # verify freshness

Exit codes:
    0  written, or identical (--check)
    1  --check found a drift (stale catalogue)
    2  a rule was violated, or the tool could not introspect the repository
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CATALOGUE_DIR = ROOT / "v2" / "test-harness"
JSON_OUT = CATALOGUE_DIR / "catalogue.json"
MD_OUT = CATALOGUE_DIR / "CATALOGUE.md"

SCHEMA = "pipelinek-test-harness-catalogue-1"

# --- declared role table (V2) ----------------------------------------------
# name -> (authority path, what it owns). The proposal's component names map to
# the implementation that actually holds them today; a second implementation is
# a second engine and this table is what refuses it.
ROLES = (
    ("ContractCatalogue", "scripts/gen-test-harness-catalogue.py",
     "the catalogue itself: which modules, gates, suites and roles exist"),
    ("ImpactResolver", "scripts/plan-affected-tests.py",
     "file -> test plan (the only impact mapping)"),
    ("SuitePolicy", "scripts/fitness-harness-coverage.py",
     "tool <-> suite registry: what must have a suite and what a suite needs"),
    ("Dispatch", "justfile",
     "the gate ladder and the only place that spawns Gradle"),
    ("Gate", "scripts/run-integration-gate.py",
     "typed PASS|FAIL|BLOCKED|STALE receipt for a gate run"),
    ("EvidenceReader", "scripts/run-scoped-junit-report.py",
     "fresh-vs-carried-over JUnit adjudication (skips, zero tests, head)"),
    ("BaselineMeter", "scripts/measure-harness-baseline.py",
     "reproducible wall/Gradle/profile samples and freshness of the XML"),
    ("CandidateReceipt", "scripts/emit-candidate-receipt.py",
     "candidate identity: bytes, digests, product-identity law"),
    ("VerdictConsumer", "scripts/consult-harness-verdict.py",
     "external harness verdict consumption under a committed publisher authority"),
)

# Role names that must not appear in a file name outside their authority (V2).
# Separators are stripped before matching, so `impact-resolver-v2.py` and
# `impactResolver2.kt` are both caught.
ROLE_NAME_RE = re.compile(
    r"(contractcatalogue|impactresolver|suitepolicy|dispatch|evidencereader|"
    r"candidatereceipt|verdictconsumer)",
    re.IGNORECASE,
)


def normalised_stem(path: pathlib.Path) -> str:
    return re.sub(r"[-_]", "", path.stem).lower()

# --- declared gate table (V3) ----------------------------------------------
GATES = (
    ("L-DEV", "dev", 900, "affected module tests + affected python suites, one "
                          "incremental Gradle graph, UAT excluded"),
    ("L-MERGE", "integration", 1800, "fitness + affected suites + lint/ABI + one "
                                     "Gradle graph, typed verdict"),
    ("L-ROUND", "gate", 1270, "full `check` with the UAT battery excluded"),
    ("L-ROUND-ESCALATED", "gate-escalate", 1270,
     "`check --rerun-tasks`: escalation after a killed run"),
    ("HARNESS", "harness", 900, "every hermetic python suite under scripts/"),
    ("L-RC", "rc", 2400, "releaseGate: distZip + SBOM + candidateAdmission + "
                          "architecture/application/release suites, UAT excluded; "
                          "declared budget pending the first complete run"),
)

# --- declared fork policy (V7) ---------------------------------------------
# Any module whose build sets forkEvery / maxParallelForks must be here with the
# reason. "The proposal says do not apply forkEvery=1 indiscriminately": this is
# how the repository can tell a justified fork from an inherited one.
JUSTIFIED_FORK_POLICY = {
    "pipeline-application": (
        "forkEvery = 1 is deliberate while the installed-CLI UAT wedge has no "
        "structural fix (PK-0-57-RC1, recorded in .agent/TESTING-STATE.md); the "
        "module's UAT classes are excluded from local gates via shard.excludes, "
        "so the fork cost is paid only by the harness"),
}

POLICIES = (
    ("test authoring, hang protocol, budgets",
     "AGENTS.md §V2 TESTING RULES"),
    ("gate ladder and what each level certifies",
     "docs/v2/07-uat/CERTIFICATION_PROTOCOL.md §4"),
    ("step certification states",
     "docs/v2/03-specifications/STEP_PLUGIN_CERTIFICATION.md"),
    ("harness fidelity levels (HF0..HF6), proposed",
     "docs/v2/04-adrs/ADR-0072-layered-test-harness-fidelity.md"),
    ("candidate/release boundary with the external harness",
     "docs/v2/07-uat/HARNESS_INVENTORY_HANDOVER.md §5.4"),
    ("responsibility migration catalogue (UAT exodus)",
     "docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md"),
)


def _just(*args: str) -> str:
    """Run `just` for introspection. Missing just is a hard failure (V3/V4)."""
    try:
        proc = subprocess.run(["just", *args], cwd=ROOT, capture_output=True,
                              text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError(f"cannot introspect `just {' '.join(args)}`: {exc}") from exc
    if proc.returncode != 0:
        raise RuntimeError(
            f"`just {' '.join(args)}` failed: {proc.stderr.strip() or proc.stdout.strip()}")
    return proc.stdout


def production_modules(root: pathlib.Path) -> list[str]:
    """Modules with production code, as repo-relative `v2/<module>` paths."""
    modules = []
    for build in sorted(root.glob("v2/*/build.gradle.kts")):
        modules.append(build.parent.relative_to(root).as_posix())
    for build in sorted(root.glob("v2/pipeline-step-sdk/*/build.gradle.kts")):
        modules.append(build.parent.relative_to(root).as_posix())
    return [m for m in modules if (root / m / "src" / "main").is_dir()]


def impact_plan(module: str) -> dict:
    """Ask the ImpactResolver (its CLI) what this module's change plans."""
    probe = f"{module}/src/main/kotlin/Probe.kt"
    proc = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "plan-affected-tests.py"),
         "--files", probe, "--format", "json", "--root", str(ROOT)],
        cwd=ROOT, capture_output=True, text=True, timeout=120)
    if proc.returncode != 0:
        raise RuntimeError(f"planner failed for {module}: {proc.stderr.strip()}")
    return json.loads(proc.stdout)


def python_suites(root: pathlib.Path) -> list[dict]:
    suites = []
    for path in sorted(root.glob("scripts/**/test_*.py")):
        text = path.read_text(encoding="utf-8", errors="ignore")
        suites.append({
            "path": path.relative_to(root).as_posix(),
            "tests": text.count("def test_"),
        })
    return suites


def count_gradle_invocations(body: str) -> int:
    """Executed Gradle invocations in a recipe body (V6: one graph per phase).

    `just --show` prints the recipe SOURCE, so `echo "dev> v2/gradlew ..."`
    mentions gradlew without running it, and an interpolated timeout arrives as
    `timeout {{ budget }} v2/gradlew ...` (with spaces around the name). Both
    shapes are pinned by tests: counting the echo fails a correct recipe, and
    missing the interpolated form silently blessed every gate as "0 calls".
    """
    invocation = re.compile(
        r"(?:^|[;&|]\s*)(?:timeout\s+(?:\{\{[^}]*\}\}|\S+)\s+)?(?:v2/|\./)?gradlew\b")
    reporter = re.compile(r"^(?:echo|printf|cat|ls|test|\[|:)\b")
    count = 0
    for raw in body.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("#!/"):
            continue
        if reporter.match(line):
            continue
        if invocation.search(line):
            count += 1
    return count


def fork_policy(module: str) -> str | None:
    build = ROOT / module / "build.gradle.kts"
    if not build.is_file():
        return None
    text = build.read_text(encoding="utf-8", errors="ignore")
    found = []
    for key in ("forkEvery", "maxParallelForks"):
        m = re.search(rf"{key}\s*=\s*([^\n]+)", text)
        if m:
            found.append(f"{key}={m.group(1).strip()}")
    return ", ".join(found) if found else None


def collect() -> tuple[dict, list[str]]:
    """Return (catalogue, violations)."""
    violations: list[str] = []

    # V2 — roles resolve to one existing file each; names outside authority.
    role_rows = []
    seen_authorities: dict[str, str] = {}
    for name, rel, owns in ROLES:
        path = ROOT / rel
        if not path.is_file():
            violations.append(f"V2 role {name} authority does not exist: {rel}")
        if rel in seen_authorities:
            violations.append(
                f"V2 roles {seen_authorities[rel]} and {name} share one authority: {rel}")
        seen_authorities[rel] = name
        role_rows.append({"name": name, "authority": rel, "owns": owns})
    authority_paths = set(seen_authorities)
    # The suite of a declared authority legitimately carries its name
    # (`test_emit_candidate_receipt.py`); a SECOND implementation does not.
    authority_suites = {
        (pathlib.Path(rel).parent /
         f"test_{pathlib.Path(rel).stem.replace('-', '_')}.py").as_posix()
        for rel in authority_paths
    }
    for candidate in sorted(list((ROOT / "scripts").rglob("*.py"))
                            + list((ROOT / "v2" / "test-harness").rglob("*.py"))):
        rel = candidate.relative_to(ROOT).as_posix()
        if rel in authority_paths or rel in authority_suites:
            continue
        if ROLE_NAME_RE.search(normalised_stem(candidate)):
            violations.append(
                f"V2 {rel}: its name claims a harness role outside the declared "
                "authority (second engine); rename it or declare the role")

    # V1 — every production module has an impact criterion.
    module_rows = []
    for module in production_modules(ROOT):
        plan = impact_plan(module)
        if plan.get("unclassified"):
            violations.append(
                f"V1 {module}: the planner leaves it UNCLASSIFIED "
                f"({plan['unclassified']}); no impact criterion exists")
        if not plan.get("gradle_tasks") and not plan.get("python_suites"):
            violations.append(
                f"V1 {module}: empty impact plan ({plan}); a change here plans nothing")
        fork = fork_policy(module)
        if fork and module.removeprefix("v2/") not in JUSTIFIED_FORK_POLICY:
            violations.append(
                f"V7 {module}: fork policy {fork!r} without a declared reason in "
                "JUSTIFIED_FORK_POLICY")
        module_rows.append({
            "module": module,
            "owner": "pipeline-kotlin maintainers",
            "impact": {
                "gradle_tasks": plan.get("gradle_tasks", []),
                "python_suites": plan.get("python_suites", []),
            },
            "fork_policy": fork,
            "fork_justification": JUSTIFIED_FORK_POLICY.get(
                module.removeprefix("v2/")),
        })

    # V3/V6 — gates exist, budgets match, one Gradle graph per recipe.
    gate_rows = []
    for level, recipe, budget, runs in GATES:
        try:
            body = _just("--show", recipe)
        except RuntimeError as exc:
            violations.append(f"V3 gate {level}: recipe `{recipe}` missing ({exc})")
            continue
        m = re.search(r'budget="(\d+)"', body)
        recipe_budget = int(m.group(1)) if m else None
        if recipe_budget != budget:
            violations.append(
                f"V3 gate {level} (`{recipe}`): catalogue budget {budget} != "
                f"recipe default {recipe_budget}")
        gradle_calls = count_gradle_invocations(body)
        if gradle_calls > 1:
            violations.append(
                f"V6 gate {level} (`{recipe}`): {gradle_calls} gradlew invocations; "
                "one graph per phase")
        # A recipe may delegate the graph to a tool (the integration gate does).
        # Recording it keeps "how many graphs does this phase run" answerable
        # from the catalogue instead of from the recipe body. A recipe that
        # invokes gradlew itself is direct, even when it also runs a planning
        # script (dev does: the planner decides, gradlew executes).
        delegates = sorted({m for m in re.findall(r"scripts/([\w.-]+\.py)", body)})
        if gradle_calls:
            via = "direct"
        else:
            via = delegates or "none"
        gate_rows.append({
            "level": level, "recipe": recipe, "budget_s": budget, "runs": runs,
            "gradle_invocations": gradle_calls,
            "gradle_via": via,
        })

    # V4 — the UAT frontier is bound to the justfile variable, never a copy.
    excludes = _just("--evaluate", "uat_excludes").strip()
    if not excludes:
        violations.append("V4 `just --evaluate uat_excludes` is empty")
    placeholder = re.compile(r"\{\{\s*uat_excludes\s*\}\}")
    for level, recipe, _b, _r in GATES:
        if level not in ("L-ROUND", "L-ROUND-ESCALATED", "L-DEV", "L-MERGE", "L-RC"):
            continue
        body = _just("--show", recipe)
        if "shard.excludes" in body and not placeholder.search(body):
            violations.append(
                f"V4 gate {level} (`{recipe}`): shard.excludes is not bound to "
                "{{ uat_excludes }}; the justfile variable must stay the single "
                "source of the UAT frontier")

    # V5 — suites exist and carry tests.
    suites = python_suites(ROOT)
    for suite in suites:
        if suite["tests"] < 1:
            violations.append(f"V5 {suite['path']}: no `def test_`")
    kotlin_test_modules = sorted(
        p.parent.relative_to(ROOT).as_posix()
        for p in ROOT.glob("v2/*/src/test")
    ) + sorted(p.parent.relative_to(ROOT).as_posix()
               for p in ROOT.glob("v2/pipeline-step-sdk/*/src/test"))

    catalogue = {
        "schema": SCHEMA,
        "home": "v2/test-harness/",
        "roles": role_rows,
        "gates": gate_rows,
        "modules": module_rows,
        "suites": {
            "python": suites,
            "python_total": len(suites),
            "python_tests": sum(s["tests"] for s in suites),
            "kotlin_modules_with_tests": kotlin_test_modules,
        },
        "uat_frontier": {
            "excludes": [x for x in excludes.split(",") if x],
            "declared_in": "justfile:uat_excludes",
        },
        "policies": [{"topic": t, "authority": a} for t, a in POLICIES],
        "receipts": {"root": "docs/v2/07-uat/", "candidate_evidence":
                     "docs/v2/07-uat/evidence/"},
    }
    return catalogue, violations


def render_markdown(cat: dict) -> str:
    lines = [
        "# Test-harness catalogue (GENERATED — do not hand-edit)\n",
        "\n",
        "Regenerate with `python3 scripts/gen-test-harness-catalogue.py`; "
        "`just fitness` fails when it drifts.\n",
        f"\nSchema: `{cat['schema']}` · home: `{cat['home']}`\n",
        "\n## Roles (one authority each: this is what refuses a second engine)\n",
        "\n| Role | Authority | Owns |\n|---|---|---|\n",
    ]
    for role in cat["roles"]:
        lines.append(f"| {role['name']} | `{role['authority']}` | {role['owns']} |\n")
    lines.append("\n## Gates\n\n| Level | Recipe | Budget (s) | Gradle | Graph via | "
                 "Runs |\n")
    lines.append("|---|---|---|---|---|---|\n")
    for gate in cat["gates"]:
        via = gate["gradle_via"]
        if isinstance(via, list):
            via = ", ".join(f"`{v}`" for v in via) or "none"
        lines.append(f"| {gate['level']} | `just {gate['recipe']}` | "
                     f"{gate['budget_s']} | {gate['gradle_invocations']} | {via} | "
                     f"{gate['runs']} |\n")
    lines.append("\n## Modules with production code\n\n")
    lines.append("| Module | Impact (Gradle) | Fork policy | Justification |\n|---"
                 "|---|---|---|\n")
    for module in cat["modules"]:
        tasks = ", ".join(f"`{t}`" for t in module["impact"]["gradle_tasks"]) or "—"
        fork = module["fork_policy"] or "default"
        reason = (module["fork_justification"] or "—").split("\n")[0]
        lines.append(f"| `{module['module']}` | {tasks} | {fork} | {reason} |\n")
    lines.append(f"\n## Suites\n\n- python suites: **{cat['suites']['python_total']}** "
                 f"({cat['suites']['python_tests']} `def test_` bodies)\n")
    lines.append(f"- Kotlin modules with `src/test`: "
                 f"**{len(cat['suites']['kotlin_modules_with_tests'])}**\n")
    lines.append(f"\n## UAT frontier\n\n- excludes: "
                 f"`{', '.join(cat['uat_frontier']['excludes'])}`\n")
    lines.append(f"- declared in: `{cat['uat_frontier']['declared_in']}`\n")
    lines.append("\n## Policies (index, not a copy)\n\n| Topic | Authority |\n|---|---|\n")
    for policy in cat["policies"]:
        lines.append(f"| {policy['topic']} | `{policy['authority']}` |\n")
    lines.append(f"\n## Receipts\n\n- root: `{cat['receipts']['root']}`\n")
    lines.append(f"- candidate evidence: `{cat['receipts']['candidate_evidence']}`\n")
    return "".join(lines)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--check", action="store_true",
                    help="verify the committed catalogue is fresh (exit 1 on drift)")
    args = ap.parse_args(argv)

    try:
        catalogue, violations = collect()
    except RuntimeError as exc:
        print(f"REFUSED: {exc}", file=sys.stderr)
        return 2

    if violations:
        print(f"REFUSED: {len(violations)} violation(s); nothing written.",
              file=sys.stderr)
        for v in violations:
            print(f"  - {v}", file=sys.stderr)
        return 2

    payload = json.dumps(catalogue, indent=2, sort_keys=True) + "\n"
    markdown = render_markdown(catalogue)

    if args.check:
        stale = []
        for path, expected in ((JSON_OUT, payload), (MD_OUT, markdown)):
            existing = path.read_text(encoding="utf-8") if path.exists() else ""
            if existing != expected:
                stale.append(path.relative_to(ROOT).as_posix())
        if stale:
            print("STALE (regenerate): " + ", ".join(stale), file=sys.stderr)
            return 1
        print(f"OK-IDENTICAL ({len(catalogue['modules'])} modules, "
              f"{len(catalogue['gates'])} gates, "
              f"{catalogue['suites']['python_total']} python suites)")
        return 0

    CATALOGUE_DIR.mkdir(parents=True, exist_ok=True)
    JSON_OUT.write_text(payload, encoding="utf-8")
    MD_OUT.write_text(markdown, encoding="utf-8")
    print(f"WRITTEN {JSON_OUT.relative_to(ROOT)} and {MD_OUT.relative_to(ROOT)} "
          f"({len(catalogue['modules'])} modules, {len(catalogue['gates'])} gates, "
          f"{catalogue['suites']['python_total']} python suites)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
