# B1 — Harness coverage fitness and one-command suite orchestration

- **Date:** 2026-10-15
- **HEAD at authorship:** `6f2ede2540627fc81b9dc271e7e7b29a2e53e412`
- **Branch:** `worktree/test-harness.brave-harbor-6920`
- **Scope:** new `scripts/fitness-harness-coverage.py` + suite, `justfile` recipes.
  Additive governance tooling; no gate replaced.

The mandate's B1 asks for a verifiable catalogue and adversarial tests that detect
missing suites and new paths. This is that registry, adapted to what exists in this
revision of the repository (there is no `v2/test-harness/` or `ImpactResolver` engine:
see the divergence note in `.agent/TESTING-STATE.md`).

---

## 1. What the fitness enforces

| Rule | Claim |
|---|---|
| R1 | every `scripts/**/*.py` tool has a colocated `test_<stem>.py`, or a declared exemption |
| R2 | every `scripts/**/test_*.py` names a subject that exists (stem match anywhere under `scripts/`, or a topic-suite declaration) |
| R3 | every suite contains at least one `def test_` (an empty suite is not a suite) |
| R4 | every `scripts/**/*.sh` entry point is mapped to a suite or declared in `EXEMPT_SHELL` |

Exemptions are never silent: every run prints both kinds. `OWNER` marks files outside
this work's authority (`gen-certification-ledger.py`: roadmap "no tocar" + hash-pinned).
`OPEN_GAP` marks a known missing suite and makes `--strict` fail, so the lever exists.

## 2. What it found on day one (the point of writing it)

- 4 tools without any contract suite: `gen-ecosystem-matrix.py`,
  `regenerate-step-inventory.py`, `release/sbom-cyclonedx.py`, `release/sdkman-uat/mirror.py`.
- 1 suite whose naming hid its subjects: `release/test_sdkman_digest.py` covers
  `sdkman-publish.sh` *and* `sdkman-install-uat.sh`; it was reported as an orphan until
  declared as a topic suite.
- A shell surface of 18 entry points, only 2 of which had an explicit suite before this
  registry: `resolve-release-digest.sh`, `sdkman-publish.sh`, `sdkman-install-uat.sh`
  and `release-pipelinek.sh` are now mapped; the rest carry reasons (sandbox tooling,
  installed-CLI UAT scripts bound for the harness migration, historical scripts).

## 3. Evidence

```text
$ just fitness
harness coverage: 14 tool(s), 12 suite(s), root=…/scripts
  [OPEN_GAP] gen-ecosystem-matrix.py: …
  [OPEN_GAP] regenerate-step-inventory.py: …
  [OPEN_GAP] release/sbom-cyclonedx.py: …
  [OPEN_GAP] release/sdkman-uat/mirror.py: …
  … [SHELL EXEMPT] × 12 (each with a reason)
OK: every tool has a suite, every suite has a subject and tests, every entry point is declared
(exit 0)

$ just fitness-strict
(exit 2 — the four OPEN_GAPs are the difference; measured)

$ python3 scripts/test_fitness_harness_coverage.py
Ran 14 tests in 0.037s
OK
```

Mutation evidence (planted, measured, restored byte-identical):

```text
M-R1  disable R1 (`if not suite.is_file() and False:`)  -> exactly
      test_tool_without_suite_is_a_violation FAILS; 14/14 green after restore.
      (A first attempt that emptied the `if` body was rejected as invalid: it
      produced a syntax error, which is a broken module, not a killed rule.)
```

One-command orchestration added the same day:

```text
$ just harness            # runs every hermetic scripts/ suite, aggregates
scripts/test_admission_check.py             Ran 48 tests in 51.297s  OK
scripts/test_gen_current_uat_status.py      Ran 40 tests in 101.405s  OK
… 11 suites total
harness: 11 suite(s), fail=0        (exit 0, 2m 42s)
```

## 4. What this does NOT claim

- The fitness is not wired into `./gradlew check`; making it a gate is a policy change
  that needs ratification, so it lives behind `just fitness` / `just fitness-strict`.
- The four `OPEN_GAP`s are real gaps, declared and strict-failable, not solved here.
- `SHELL EXEMPT` reasons are scope declarations, not proofs; they exist so the surface
  is enumerated instead of invisible.
- No new test framework was introduced (stdlib `unittest`, as all harness suites).

## End-of-work-unit closure

```text
Reference implementation consulted: none applicable; the registry formalises the
    harness conventions already used by the 12 suites in scripts/.
Behaviour adopted:                  bidirectional tool <-> suite declaration with
                                    visible exemption kinds and a strict lever.
Intentional deviations:             fitness lives in scripts/ and is invoked via just
                                    rather than being forced into the Gradle graph.
Security implications reviewed:     none; reads local files only.
Tests demonstrating the contract:    scripts/test_fitness_harness_coverage.py (14
                                    methods; M-R1 mutation measured).
```
