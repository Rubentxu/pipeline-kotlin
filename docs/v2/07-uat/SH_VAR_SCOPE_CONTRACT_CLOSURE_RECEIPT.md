# SH-VAR-SCOPE-CONTRACT — Closure Receipt (Cycle `cycle/sh-var-scope-contract-s1`)

**Author:** SDDK orchestrator
**Date:** 2026-09-20T10:33Z
**Branch:** `cycle/sh-var-scope-contract-s1`
**Base SHA:** `1fdd3dce`
**Cycle path:** A-lite (propose → spec → design → tasks → apply → verify → debt-verify → release → archive)

## Result

```text
SH-VAR-SCOPE-CONTRACT F1 = CLOSED_GREEN
  - 9 commits (S1.proposal + T1..T8) on cycle/sh-var-scope-contract-s1.
  - 5 contract test classes authored; 11 sub-cases all GREEN.
  - Zero production source changes.
  - Zero new corpus fixtures (T7 rationale documented).
  - One contract document published: SH_VAR_SCOPE_CONTRACT.md.
F2 = NOT OPENED, gating predicate NOT YET TRIGGERED.
  - The byte-level F2_TRIGGER_DATA is captured; the UX decision
    remains for the operator.
```

## CHANGED

Files added/modified (in commit order):

| Task | Files | Lines |
|---|---|---|
| S1.proposal | `openspec/changes/sh-var-scope-contract/{proposal,spec,design,tasks}.md`, `.agent/TESTING-STATE.md` | +994 |
| T1 | `docs/v2/03-specifications/SH_VAR_SCOPE_CONTRACT.md` | +196 |
| T2 | `S2ThreePhaseProbeTest.kt`, `S2-three-phase-probe-extended.txt` | +178 |
| T3 | `ShVarScopeGap02Test.kt`, `gap02-no-creds-block.txt` | +228 |
| T4 | `ShVarScopeGap03Test.kt`, `gap03-shell-expansions.txt` | +218 |
| T5 | `ShVarScopeGap04FormFProbeTest.kt`, `design.md` edit, `gap04-formF-raw-triples.txt` | +253 -11 |
| T6 | `ShVarScopeGap05Test.kt`, `gap05-diagnostics.txt` | +268 |
| T7 | `openspec/changes/sh-var-scope-contract/T7_CLOSURE.md` | +82 |
| T8 (this file) | `docs/v2/07-uat/SH_VAR_SCOPE_CONTRACT_CLOSURE_RECEIPT.md`, contract doc corrections (§2, §3, §8) | +rest |

Total: 12 file events, 0 production source events.

## AFFECTED SUT

None. Per Guard G3, this cycle did not modify any production source:

- `pipeline-application` — untouched.
- `pipeline-domain` — untouched.
- `pipeline-events` — untouched.
- `pipeline-step-sdk` — untouched.
- `pipeline-scripting-api` — untouched.
- `pipeline-scripting-kotlin24` — only test sources added.
- `pipeline-architecture-tests` — untouched.

The ONLY production-relevant file touched:

- `docs/v2/03-specifications/SH_VAR_SCOPE_CONTRACT.md` (single source
  of truth; the closing §2 / §3 / §8 corrections are operator reviews
  of the contract text, not code changes).

## VERIFICATION EXECUTED

Per AGENTS.md rule 25 canary discipline (fresh JUnit XML each run,
--rerun-tasks every target). All targeted and run individually:

| Test class | Tests | Fail | Err | Time | sha256 (JUnit XML) | sha256 (log) |
|---|---|---|---|---|---|---|
| `S2ThreePhaseProbeTest` | 2 | 0 | 0 | 5.4 s | `60f12afcb3918546750ae33049db4a3d071913f3c45e02511b658a6fd0aaf232` | `442af174bd2590295bc467b1ebeba25768d0367f9c3b78ef461c475b2952aaba` |
| `ShVarScopeGap02Test` | 3 | 0 | 0 | 0.05 s | `1fbff882a3afd50b8bf1cf2820757624549dd145ee33b2efcd5a68f67ee572a9` | `74952af6495a3a0cccb383a8b2423682cd5901abfa5fc532c7e4ad15eee160ad` |
| `ShVarScopeGap03Test` | 3 | 0 | 0 | 0.05 s | `adf0eb8a3217da8fc84d9cbd3b904d76a80d91de526861a912185a7a301f0369` | `273d996e3a806e4972245a0407b160eeb23146255c84b12db083d33e45086a59` |
| `ShVarScopeGap04FormFProbeTest` | 2 | 0 | 0 | 0.05 s | `af53a4056e2a814d55873064fe6934d085226d72ca9cbba9ce35540596bee2fc` | `5152a5dfd6e669d3095ffb0d2fc191a8c4f1d990bf019a009e2ffc4e3d9d0b10` |
| `ShVarScopeGap05Test` | 3 | 0 | 0 | 0.05 s | `608683e679b2648da19f3b15fae5a40ab8c7de139138077311176a31c30b5431` | `90d8c3468e7b7521275b030702ef69412cbdc056e8563b9c9eee737645135147` |

L0 compile (`:pipeline-scripting-kotlin24:compileTestKotlin`) GREEN
in 1-2 s after each test addition.

No L5 (full `check`) was run — there is no production code change to
justify the L5 cost. Per AGENTS.md rule 23 + scope firewall.

## EVIDENCE REUSED

Already-fresh on `main`:

- `eff39dbe` SH-VAR-SCOPE.characterisation. Byte-level capture of
  s1-01..s1-14. Used as direct reference for §4 and §6 of the
  contract document (links, no duplication).
- `83882467` SH-VAR-SCOPE.S2. Forms A..E three-phase probe.
  Used as starting state for the cycle's Form F extension (T2) and
  the negative fixture 99-trap-form-dollar-dollar-quote.pipeline.kts.
- `LB02_G3_A4_2_SHELL_OPERATIONS_CAPABILITY.md`. Capability
  surface for SHELL_OPERATIONS env injection (out of contract scope;
  cited in §9 layered invariants).

No test in this cycle re-runs prior evidence; all byte-level
captures are fresh in their own evidence files.

## VERIFICATION DELIBERATELY NOT EXECUTED

- **L5 `:pipeline-application:check`.** Not run. The cycle's
  scope firewall excludes production code changes; L5 would not
  surface F1 regressions.
- **`CompatibilityCorpusTest` re-run.** Not modified; the cycle
  added zero corpus fixtures (T7 rationale recorded).
- **Architecture fitness (`pipeline-architecture-tests`).** Not run;
  this cycle does not modify or replace any architectural invariant.
- **`Kotlin24ScriptingHost.mapDiagnostic` modification.** Forbidden
  by Guard G3; only the byte-level effect was measured.

## UNKNOWN IMPACT

- **Gap #5 UX assessment.** The byte-level F2_TRIGGER_DATA is
  captured (`author_column=123`, `escaped_column=128`,
  `escape_shift=5`). Whether this is user-perceivable friction
  depends on a UX judgement that F1 does not author. The operator
  can now make that decision without re-running the cycle.
- **Multi-line content of `"""..."""` not covered by an explicit
  contract test.** The byte-level handling is implicit from
  Kotlin's `"""..."""` semantics: `\` does not escape `$` anywhere
  in the raw literal, so any `$IDENT` token (with or without surrounding
  braces) is treated as a Kotlin template slot. No multi-line raw
  triple test fixture was added because the byte-level behaviour
  is uniform across line boundaries.
- **Effect on future `core.sh` plugin families.** The contract is
  by design minimal ("what the user can rely on"). Future SH family
  consumers can cite this contract without re-deriving it.

## RESULT

**PASS.** SH-VAR-SCOPE-CONTRACT F1 cycle is closed green. All
operator guards honoured. The 11/11 contract test cases pass.
The 5 evidence files carry sha256 receipts.

F2 (offset map) is **NOT** opened. The gating predicate remains
for the operator's UX judgement.

## FULL VERIFICATION REQUIRED NOW

**NO.** This cycle did not touch production sources. The F1
contract test surface fully exercises the in-process contract
without invoking the broader test suite. AGENTS.md rule 23
allows skipping the L5 (full `:pipeline-application:check`) when
the active change has a documented scope firewall that excludes
production code; that firewall is signed by the operator's three
guards in this cycle's `proposal.md`.

A NEW cycle (e.g., F2 with offset map, or any plugin SH family)
will require its own L5.

## Operator merge-to-main checklist

- [ ] Inspect `1a684257 S1.proposal` — change artefacts + ledger.
- [ ] Inspect `b6dbdcab T1` — first contract document.
- [ ] Inspect `dd17c472 T2` — Form F probe + decisive findings.
- [ ] Inspect `ba9de594 T3` — Gap #2 contract test.
- [ ] Inspect `968cc078 T4` — Gap #3 contract test + interesting red.
- [ ] Inspect `4b849023 T5` — Form F end-to-end + interesting red.
- [ ] Inspect `88817415 T6` — Gap #5 reproduction + interesting reds.
- [ ] Inspect `47cf9943 T7` — T7 closure.
- [ ] Inspect closing commit — corrected contract + this receipt.
- [ ] FF-merge to `main`.

(Cycle branch is `cycle/sh-var-scope-contract-s1`. Final tip
HEAD to be resolved after this receipt is committed.)

## Closing principle (verbatim, from operator 2026-09-20T08:16Z)

> Con esto avanzamos sobre el trabajo existente y obtenemos primero
> un contrato verificable de `sh`, sin arriesgar el motor por una
> corrección prematura del escaper.

That invariant held for every step of F1: `sh` first got a
verifiable contract; the motor was not touched to chase a
premature escaper rewrite.
